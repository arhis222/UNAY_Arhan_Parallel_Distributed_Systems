package edu.polytech.verifier;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import edu.polytech.verifier.VerificationResult.Category;
import edu.polytech.verifier.VerificationResult.CloseReason;
import edu.polytech.verifier.VerificationResult.Code;
import edu.polytech.verifier.VerificationResult.Diagnostic;
import edu.polytech.verifier.VerificationResult.MissingDelivery;
import edu.polytech.verifier.VerificationResult.Phase;
import edu.polytech.verifier.VerificationResult.Verdict;

/** Fixed-group checker. Call sequentially; no runtime, TOM, timers or callbacks. */
public final class GroupVerifier {
    public enum SubmissionIssue { NOT_INVOKED, REJECTED, UNCERTAIN }

    private static final class Slot {
        MessageId id;
        SubmissionIssue issue;
        boolean confirmed;
        String describe() { return (id == null ? "UNCONFIRMED" : (confirmed ? "CONFIRMED " : "PROVISIONAL ") + id)
                + (issue == null ? "" : " / " + issue); }
    }
    private static final class RegisteredMessage {
        final byte[] original;
        final int slot;
        final Set<ActorId> outstanding;
        RegisteredMessage(byte[] original, int slot, Set<ActorId> members) {
            this.original = original.clone(); this.slot = slot;
            outstanding = new LinkedHashSet<>(members);
        }
    }
    private static final class History {
        final List<MessageId> ids = new ArrayList<>();
        final Map<MessageId, Integer> firstPosition = new LinkedHashMap<>();
    }

    private final BaselineScenario scenario;
    private final Map<ActorId, Map<Integer, Slot>> slots = new LinkedHashMap<>();
    private final Map<ActorId, History> histories = new LinkedHashMap<>();
    private final Map<MessageId, RegisteredMessage> registered = new LinkedHashMap<>();
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private Phase phase = Phase.OPEN;
    private CloseReason closeReason;
    private boolean workloadClosed, violation, reportingGap;
    private long ordinal, confirmed, validDeliveries;

    public GroupVerifier(BaselineScenario scenario) {
        if (scenario == null) throw new IllegalArgumentException("Scenario required");
        this.scenario = scenario;
        for (ActorId member : scenario.plannedSends().keySet()) {
            slots.put(member, new LinkedHashMap<Integer, Slot>());
            histories.put(member, new History());
        }
    }

    private void diagnostic(Code code, Category category, ActorId receiver, MessageId message, String detail) {
        diagnostics.add(new Diagnostic(code, category, ordinal, receiver, message, detail, null, null));
        if (category != Category.INCOMPLETE) violation = true;
    }

    private boolean event() {
        if (ordinal == Long.MAX_VALUE) {
            diagnostic(Code.COUNTER_OVERFLOW, Category.OBSERVATION, null, null, "Report counter exhausted");
            return false;
        }
        ordinal++;
        return true;
    }

    private boolean canAccount() {
        if (phase == Phase.CLOSED) {
            diagnostic(Code.LATE_REPORT, Category.OBSERVATION, null, null, "Accounting after observation closure");
            return false;
        }
        if (workloadClosed) {
            diagnostic(Code.WORKLOAD_CLOSED, Category.OBSERVATION, null, null, "Accounting after workload closure");
            return false;
        }
        return true;
    }

    private boolean receiver(ActorId receiver, MessageId message) {
        if (!histories.containsKey(receiver)) {
            diagnostic(Code.UNKNOWN_ACTOR, Category.OBSERVATION, receiver, message,
                    "Receiver " + receiver + " is not a declared actor/incarnation");
            return false;
        }
        return true;
    }

    private boolean sender(ActorId sender, ActorId receiver, MessageId message) {
        if (!histories.containsKey(sender)) {
            diagnostic(Code.UNKNOWN_ACTOR, Category.OBSERVATION, receiver, message,
                    "Sender " + sender + " is not a declared actor/incarnation");
            return false;
        }
        return true;
    }

    private boolean slot(ActorId sender, int index, MessageId message) {
        if (!sender(sender, null, message)) return false;
        if (index < 0 || index >= scenario.plannedSends().get(sender)) {
            diagnostic(Code.INVALID_SLOT, Category.OBSERVATION, null, message,
                    "Sender " + sender + ": invalid planned slot " + index);
            return false;
        }
        return true;
    }

    private boolean context(MessageId id, ActorId receiver) {
        if (id == null) {
            diagnostic(Code.INVALID_INPUT, Category.OBSERVATION, receiver, null, "Message identity required");
            return false;
        }
        if (!scenario.executionId().equals(id.executionId()) || !scenario.groupId().equals(id.groupId())) {
            diagnostic(Code.WRONG_CONTEXT, Category.OBSERVATION, receiver, id, "Wrong execution/group");
            return false;
        }
        return sender(id.sender(), receiver, id);
    }

    public void registerMulticast(int plannedIndex, MessageId id, byte[] original) {
        register(plannedIndex, id, original, true);
    }

    /** Retains independent evidence before invocation; delivery never confirms it. */
    public boolean beginMulticast(int plannedIndex, MessageId id, byte[] original) {
        return register(plannedIndex, id, original, false);
    }

    private boolean register(int plannedIndex, MessageId id, byte[] original, boolean atomic) {
        if (!event() || !canAccount() || !context(id, null)) return false;
        if (original == null) {
            diagnostic(Code.INVALID_INPUT, Category.OBSERVATION, null, id, "Original bytes required");
            return false;
        }
        if (!slot(id.sender(), plannedIndex, id)) return false;
        if (registered.containsKey(id)) {
            diagnostic(Code.REUSED_MESSAGE, Category.OBSERVATION, null, id, "Identity already registered");
            return false;
        }
        Map<Integer, Slot> senderSlots = slots.get(id.sender());
        if (senderSlots.containsKey(plannedIndex)) {
            diagnostic(Code.REUSED_SLOT, Category.OBSERVATION, null, id, "Slot already accounted: " + plannedIndex);
            return false;
        }
        RegisteredMessage record = new RegisteredMessage(original, plannedIndex, histories.keySet());
        Slot entry = new Slot(); entry.id = id; entry.confirmed = atomic;
        registered.put(id, record);
        senderSlots.put(plannedIndex, entry);
        if (atomic) confirmed = Math.addExact(confirmed, 1);
        return true;
    }

    /** Confirms exactly one issue-free provisional invocation while workload is open. */
    public boolean confirmMulticast(MessageId id) {
        if (!event()) return false;
        if (phase == Phase.CLOSED) {
            diagnostic(Code.LATE_REPORT, Category.OBSERVATION, null, id, "Confirmation after observation closure");
            return false;
        }
        if (!canAccount() || !context(id, null)) return false;
        RegisteredMessage record = registered.get(id);
        if (record == null) {
            diagnostic(Code.UNKNOWN_MESSAGE, Category.OBSERVATION, null, id, "Confirmation without registration");
            return false;
        }
        Slot entry = slots.get(id.sender()).get(record.slot);
        if (entry.confirmed || entry.issue != null) {
            diagnostic(Code.INVALID_CONFIRMATION, Category.OBSERVATION, null, id,
                    "Slot " + record.slot + " cannot be confirmed: " + entry.describe());
            return false;
        }
        entry.confirmed = true;
        confirmed = Math.addExact(confirmed, 1);
        return true;
    }

    public void recordSubmissionIssue(ActorId sender, int plannedIndex, SubmissionIssue issue, String detail) {
        if (!event() || !canAccount() || !slot(sender, plannedIndex, null)) return;
        if (issue == null || detail == null || detail.trim().isEmpty()) {
            diagnostic(Code.INVALID_INPUT, Category.OBSERVATION, null, null,
                    "Sender " + sender + ": issue and explanation required");
            return;
        }
        Map<Integer, Slot> senderSlots = slots.get(sender);
        Slot entry = senderSlots.get(plannedIndex);
        if (entry != null && entry.issue != null) {
            diagnostic(Code.REUSED_SLOT, Category.OBSERVATION, null, entry.id,
                    "Sender " + sender + ": issue already recorded for slot " + plannedIndex);
            return;
        }
        if (entry == null) { entry = new Slot(); senderSlots.put(plannedIndex, entry); }
        entry.issue = issue;
        Code code = issue == SubmissionIssue.UNCERTAIN ? Code.SUBMISSION_UNCERTAIN
                : issue == SubmissionIssue.REJECTED ? Code.SUBMISSION_REJECTED : Code.NOT_INVOKED;
        diagnostic(code, issue == SubmissionIssue.UNCERTAIN ? Category.INCOMPLETE : Category.OBSERVATION,
                null, entry.id, "Sender " + sender + ", slot " + plannedIndex + ": " + detail);
    }

    public void delivered(ActorId receiver, MessageId id, byte[] observed) {
        if (!event()) return;
        if (phase == Phase.CLOSED) {
            diagnostic(Code.LATE_REPORT, Category.OBSERVATION, receiver, id, "Delivery after observation closure");
            return;
        }
        if (!receiver(receiver, id) || !context(id, receiver)) return;
        if (observed == null) {
            diagnostic(Code.INVALID_INPUT, Category.OBSERVATION, receiver, id, "Delivered bytes required");
            return;
        }
        byte[] bytes = observed.clone();
        History history = histories.get(receiver);
        int position = history.ids.size();
        if (position == Integer.MAX_VALUE) {
            diagnostic(Code.COUNTER_OVERFLOW, Category.OBSERVATION, receiver, id, "History index capacity exhausted");
            return;
        }
        history.ids.add(id);
        Integer first = history.firstPosition.get(id);
        if (first != null)
            diagnostic(Code.DUPLICATE, Category.PROTOCOL, receiver, id,
                    "First position " + first + ", repeated position " + position);
        else history.firstPosition.put(id, position);

        for (Map.Entry<ActorId, History> entry : histories.entrySet()) {
            if (entry.getKey().equals(receiver) || entry.getValue().ids.size() <= position) continue;
            MessageId other = entry.getValue().ids.get(position);
            if (!id.equals(other))
                diagnostic(Code.ORDER_DIVERGENCE, Category.PROTOCOL, receiver, id,
                        "Position " + position + ": " + receiver + " reports " + id
                        + "; " + entry.getKey() + " reports " + other);
        }
        RegisteredMessage record = registered.get(id);
        if (record == null) {
            diagnostic(Code.UNKNOWN_MESSAGE, Category.OBSERVATION, receiver, id, "No independent multicast registration");
            return;
        }
        boolean contentOK = Arrays.equals(record.original, bytes);
        if (!contentOK) {
            int difference = 0;
            while (difference < Math.min(record.original.length, bytes.length)
                    && record.original[difference] == bytes[difference]) difference++;
            diagnostics.add(new Diagnostic(Code.CONTENT_MISMATCH, Category.PROTOCOL, ordinal, receiver, id,
                    "Expected length " + record.original.length + ", actual length " + bytes.length
                    + ", first difference " + difference, record.original, bytes));
            violation = true;
        }
        if (first == null && contentOK && record.outstanding.remove(receiver))
            validDeliveries = Math.addExact(validDeliveries, 1);
    }

    public void recordObservationGap(String detail) {
        if (!event()) return;
        if (detail == null || detail.trim().isEmpty()) {
            diagnostic(Code.INVALID_INPUT, Category.OBSERVATION, null, null, "Gap explanation required");
            return;
        }
        reportingGap = true;
        diagnostic(Code.REPORTING_GAP, Category.INCOMPLETE, null, null, detail);
    }

    public void closeWorkload() {
        if (!event() || !canAccount()) return;
        workloadClosed = true;
        phase = Phase.DRAINING;
    }

    public VerificationResult closeObservation(CloseReason reason) {
        if (reason == null) {
            if (event()) diagnostic(Code.INVALID_INPUT, Category.OBSERVATION, null, null, "Close reason required");
        } else if (phase == Phase.CLOSED) {
            if (reason != closeReason && event())
                diagnostic(Code.CONFLICTING_CLOSE, Category.OBSERVATION, null, null,
                        "Already closed with " + closeReason + ", requested " + reason);
        } else if (event()) {
            phase = Phase.CLOSED;
            closeReason = reason;
        }
        return snapshot();
    }

    public VerificationResult snapshot() {
        List<Diagnostic> evidence = new ArrayList<>(diagnostics);
        List<MissingDelivery> missing = new ArrayList<>();
        Map<ActorId, List<MessageId>> historyView = new LinkedHashMap<>();
        Map<ActorId, Map<Integer, String>> accounting = new LinkedHashMap<>();
        long accounted = 0, provisional = 0;
        boolean problemSlot = false;
        for (Map.Entry<ActorId, Map<Integer, Slot>> actor : slots.entrySet()) {
            Map<Integer, String> view = new LinkedHashMap<>();
            for (Map.Entry<Integer, Slot> entry : actor.getValue().entrySet()) {
                view.put(entry.getKey(), entry.getValue().describe());
                Slot state = entry.getValue();
                problemSlot |= state.issue != null;
                if (state.id != null && !state.confirmed) {
                    provisional = Math.addExact(provisional, 1);
                    evidence.add(new Diagnostic(Code.PROVISIONAL_SUBMISSION, Category.INCOMPLETE, ordinal,
                            null, state.id, "Unconfirmed submission in slot " + entry.getKey()
                            + (state.issue == null ? "" : "; " + state.issue), null, null));
                }
            }
            accounted = Math.addExact(accounted, actor.getValue().size());
            accounting.put(actor.getKey(), view);
            historyView.put(actor.getKey(), histories.get(actor.getKey()).ids);
        }
        for (Map.Entry<MessageId, RegisteredMessage> entry : registered.entrySet()) {
            RegisteredMessage record = entry.getValue();
            for (ActorId receiver : record.outstanding)
                missing.add(new MissingDelivery(entry.getKey(), receiver, record.slot));
            if (!record.outstanding.isEmpty()) {
                boolean absentEverywhere = true;
                for (History history : histories.values()) absentEverywhere &= !history.firstPosition.containsKey(entry.getKey());
                evidence.add(new Diagnostic(Code.MISSING_DELIVERIES, Category.INCOMPLETE, ordinal, null, entry.getKey(),
                        "Registered slot " + record.slot + "; owed to " + record.outstanding
                        + (absentEverywhere ? "; absent everywhere" : ""), null, null));
            }
        }
        long unaccounted = scenario.plannedMessages() - accounted;
        boolean coverage = confirmed >= scenario.minimumConfirmedMessages()
                && validDeliveries >= scenario.minimumValidDeliveries();
        if (!workloadClosed) evidence.add(incomplete(Code.WORKLOAD_OPEN, "Workload has not closed"));
        if (unaccounted != 0) evidence.add(incomplete(Code.UNACCOUNTED_SENDS,
                unaccounted + " planned sends unaccounted; absent sparse slot entries remain PLANNED"));
        if (!coverage) evidence.add(incomplete(Code.UNMET_COVERAGE,
                "Confirmed " + confirmed + "/" + scenario.minimumConfirmedMessages()
                + ", valid deliveries " + validDeliveries + "/" + scenario.minimumValidDeliveries()));
        if (closeReason == CloseReason.TIMEOUT) evidence.add(incomplete(Code.TIMEOUT, "Execution budget expired; no death inferred"));
        if (closeReason == CloseReason.INTERRUPTED) evidence.add(incomplete(Code.INTERRUPTED, "Observation interrupted"));

        Verdict verdict;
        if (violation) verdict = Verdict.UNSUCCESSFUL;
        else if (phase != Phase.CLOSED) verdict = Verdict.IN_PROGRESS;
        else if (closeReason != CloseReason.NORMAL || !workloadClosed || reportingGap || problemSlot
                || provisional != 0 || unaccounted != 0 || !missing.isEmpty() || !coverage) verdict = Verdict.INCOMPLETE;
        else verdict = Verdict.SUCCESSFUL;
        return new VerificationResult(scenario, phase, verdict, closeReason, coverage, confirmed, validDeliveries,
                provisional, unaccounted, evidence, missing, historyView, accounting);
    }

    private Diagnostic incomplete(Code code, String detail) {
        return new Diagnostic(code, Category.INCOMPLETE, ordinal, null, null, detail, null, null);
    }
}
