package edu.polytech.verifier;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable historical assessment; later reports produce new assessments. */
public final class VerificationResult {
    public enum Phase { OPEN, DRAINING, CLOSED }
    public enum Verdict { IN_PROGRESS, SUCCESSFUL, UNSUCCESSFUL, INCOMPLETE }
    public enum CloseReason { NORMAL, TIMEOUT, INTERRUPTED }
    public enum Category { PROTOCOL, OBSERVATION, INCOMPLETE }
    public enum Code {
        INVALID_INPUT, WRONG_CONTEXT, UNKNOWN_ACTOR, INVALID_SLOT, REUSED_SLOT, REUSED_MESSAGE,
        UNKNOWN_MESSAGE, DUPLICATE, CONTENT_MISMATCH, ORDER_DIVERGENCE, LATE_REPORT,
        WORKLOAD_CLOSED, CONFLICTING_CLOSE, SUBMISSION_REJECTED, NOT_INVOKED,
        SUBMISSION_UNCERTAIN, PROVISIONAL_SUBMISSION, INVALID_CONFIRMATION, REPORTING_GAP, WORKLOAD_OPEN, UNACCOUNTED_SENDS,
        MISSING_DELIVERIES, UNMET_COVERAGE, TIMEOUT, INTERRUPTED, COUNTER_OVERFLOW
    }

    public static final class Diagnostic {
        private final Code code;
        private final Category category;
        private final long ordinal;
        private final ActorId receiver;
        private final MessageId message;
        private final String detail;
        private final byte[] expected, actual;

        Diagnostic(Code code, Category category, long ordinal, ActorId receiver, MessageId message,
                String detail, byte[] expected, byte[] actual) {
            this.code = code; this.category = category; this.ordinal = ordinal;
            this.receiver = receiver; this.message = message; this.detail = detail;
            this.expected = excerpt(expected); this.actual = excerpt(actual);
        }
        private static byte[] excerpt(byte[] bytes) {
            return bytes == null ? null : Arrays.copyOf(bytes, Math.min(32, bytes.length));
        }
        public Code code() { return code; }
        public Category category() { return category; }
        public long ordinal() { return ordinal; }
        public ActorId receiver() { return receiver; }
        public MessageId message() { return message; }
        public String detail() { return detail; }
        public byte[] expectedExcerpt() { return expected == null ? null : expected.clone(); }
        public byte[] actualExcerpt() { return actual == null ? null : actual.clone(); }
        @Override public String toString() {
            return category + "/" + code + " event=" + ordinal + " receiver=" + receiver
                    + " message=" + message + ": " + detail;
        }
    }

    public static final class MissingDelivery {
        private final MessageId message;
        private final ActorId receiver;
        private final int plannedIndex;
        MissingDelivery(MessageId message, ActorId receiver, int plannedIndex) {
            this.message = message; this.receiver = receiver; this.plannedIndex = plannedIndex;
        }
        public MessageId message() { return message; }
        public ActorId receiver() { return receiver; }
        public int plannedIndex() { return plannedIndex; }
        @Override public String toString() { return message + " -> " + receiver + " (slot " + plannedIndex + ")"; }
    }

    private final BaselineScenario scenario;
    private final Phase phase;
    private final Verdict verdict;
    private final CloseReason closeReason;
    private final boolean coverageSatisfied;
    private final long confirmedMessages, validDeliveries, provisionalMessages, unaccountedSends;
    private final List<Diagnostic> diagnostics;
    private final List<MissingDelivery> missingDeliveries;
    private final Map<ActorId, List<MessageId>> histories;
    private final Map<ActorId, Map<Integer, String>> accountedSlots;

    VerificationResult(BaselineScenario scenario, Phase phase, Verdict verdict, CloseReason closeReason,
            boolean coverageSatisfied, long confirmedMessages, long validDeliveries, long provisionalMessages, long unaccountedSends,
            List<Diagnostic> diagnostics, List<MissingDelivery> missing,
            Map<ActorId, List<MessageId>> histories, Map<ActorId, Map<Integer, String>> slots) {
        this.scenario = scenario; this.phase = phase; this.verdict = verdict; this.closeReason = closeReason;
        this.coverageSatisfied = coverageSatisfied; this.confirmedMessages = confirmedMessages;
        this.validDeliveries = validDeliveries; this.provisionalMessages = provisionalMessages; this.unaccountedSends = unaccountedSends;
        this.diagnostics = Collections.unmodifiableList(new ArrayList<>(diagnostics));
        missingDeliveries = Collections.unmodifiableList(new ArrayList<>(missing));
        Map<ActorId, List<MessageId>> historyCopy = new LinkedHashMap<>();
        for (Map.Entry<ActorId, List<MessageId>> entry : histories.entrySet())
            historyCopy.put(entry.getKey(), Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
        this.histories = Collections.unmodifiableMap(historyCopy);
        Map<ActorId, Map<Integer, String>> slotCopy = new LinkedHashMap<>();
        for (Map.Entry<ActorId, Map<Integer, String>> entry : slots.entrySet())
            slotCopy.put(entry.getKey(), Collections.unmodifiableMap(new LinkedHashMap<>(entry.getValue())));
        accountedSlots = Collections.unmodifiableMap(slotCopy);
    }

    public BaselineScenario scenario() { return scenario; }
    public Phase phase() { return phase; }
    public Verdict verdict() { return verdict; }
    public CloseReason closeReason() { return closeReason; }
    public boolean coverageSatisfied() { return coverageSatisfied; }
    public long confirmedMessages() { return confirmedMessages; }
    public long validDeliveries() { return validDeliveries; }
    /** Registered originals not yet confirmed, including uncertain provisional records. */
    public long provisionalMessages() { return provisionalMessages; }
    public long unaccountedSends() { return unaccountedSends; }
    public List<Diagnostic> diagnostics() { return diagnostics; }
    public List<MissingDelivery> missingDeliveries() { return missingDeliveries; }
    public Map<ActorId, List<MessageId>> histories() { return histories; }
    /** Sparse slots only; absent entries are still PLANNED. */
    public Map<ActorId, Map<Integer, String>> accountedSlots() { return accountedSlots; }
    public boolean hasDiagnostic(Code code) {
        for (Diagnostic diagnostic : diagnostics) if (diagnostic.code() == code) return true;
        return false;
    }
    @Override public String toString() {
        return scenario.executionId() + "/" + scenario.groupId() + " " + phase + " " + verdict
                + " confirmed=" + confirmedMessages + " validDeliveries=" + validDeliveries
                + " missing=" + missingDeliveries.size() + " unaccounted=" + unaccountedSends;
    }
}
