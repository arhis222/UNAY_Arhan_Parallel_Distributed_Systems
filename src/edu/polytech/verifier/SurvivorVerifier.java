package edu.polytech.verifier;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import edu.polytech.verifier.VerificationResult.Verdict;

/** Sequential final application-delivery checks for exactly one death, no reinsertion.
 * Completion/report completeness are explicit caller evidence, never inferred here.
 */
public final class SurvivorVerifier {
    private static final class Original {
        final byte[] bytes;
        boolean confirmed, uncertain;
        Original(byte[] bytes) { this.bytes = bytes.clone(); }
    }

    public static final class Result {
        private final Verdict verdict;
        private final List<String> diagnostics;
        private final Map<ActorId, List<MessageId>> histories;
        private Result(Verdict verdict, List<String> diagnostics, Map<ActorId, List<MessageId>> histories) {
            this.verdict = verdict;
            this.diagnostics = Collections.unmodifiableList(new ArrayList<>(diagnostics));
            Map<ActorId, List<MessageId>> copy = new LinkedHashMap<>();
            for (Map.Entry<ActorId, List<MessageId>> e : histories.entrySet())
                copy.put(e.getKey(), Collections.unmodifiableList(new ArrayList<>(e.getValue())));
            this.histories = Collections.unmodifiableMap(copy);
        }
        public Verdict verdict() { return verdict; }
        public List<String> diagnostics() { return diagnostics; }
        public Map<ActorId, List<MessageId>> histories() { return histories; }
        @Override public String toString() { return verdict + " " + diagnostics; }
    }

    private final String execution, group;
    private final ActorId dead;
    private final Set<ActorId> members, survivors, removals = new LinkedHashSet<>();
    private final Map<MessageId, Original> originals = new LinkedHashMap<>();
    private final Map<ActorId, List<MessageId>> histories = new LinkedHashMap<>();
    private final List<String> violations = new ArrayList<>();
    private boolean deathObserved, reportingGap, closed;

    public SurvivorVerifier(String execution, String group, Set<ActorId> members, ActorId dead) {
        this.execution = MessageId.requireName(execution);
        this.group = MessageId.requireName(group);
        if (members == null || members.contains(null) || members.size() < 2 || !members.contains(dead))
            throw new IllegalArgumentException("Declare a dead member and nonempty survivors");
        this.dead = dead;
        this.members = new LinkedHashSet<>(members);
        survivors = new LinkedHashSet<>(members);
        survivors.remove(dead);
        for (ActorId actor : survivors) histories.put(actor, new ArrayList<MessageId>());
    }

    private boolean open() {
        if (!closed) return true;
        violations.add("LATE_REPORT: observation already closed");
        return false;
    }

    public void beginMulticast(MessageId id, byte[] bytes) {
        if (!open()) return;
        if (!valid(id) || bytes == null || originals.containsKey(id)) {
            violations.add("INVALID_REGISTRATION: " + id); return;
        }
        originals.put(id, new Original(bytes));
    }

    private boolean valid(MessageId id) {
        return id != null && execution.equals(id.executionId()) && group.equals(id.groupId())
                && members.contains(id.sender());
    }

    public void confirmMulticast(MessageId id) {
        if (!open()) return;
        Original original = originals.get(id);
        if (original == null || original.confirmed || original.uncertain) {
            violations.add("INVALID_CONFIRMATION: " + id); return;
        }
        original.confirmed = true;
    }

    public void uncertainMulticast(MessageId id) {
        if (!open()) return;
        Original original = originals.get(id);
        if (original == null || original.confirmed || original.uncertain) {
            violations.add("INVALID_UNCERTAINTY: " + id); return;
        }
        original.uncertain = true;
    }

    public void delivered(ActorId receiver, MessageId id, byte[] bytes) {
        if (!open()) return;
        if (!members.contains(receiver) || !valid(id) || bytes == null || !originals.containsKey(id)) {
            violations.add("INVALID_DELIVERY: receiver=" + receiver + " message=" + id); return;
        }
        // Dead actor histories have no bearing on non-uniform survivor agreement.
        if (receiver.equals(dead)) return;
        List<MessageId> history = histories.get(receiver);
        if (history.contains(id)) violations.add("DUPLICATE: receiver=" + receiver + " message=" + id);
        if (!Arrays.equals(originals.get(id).bytes, bytes))
            violations.add("CONTENT_MISMATCH: receiver=" + receiver + " message=" + id);
        history.add(id);
    }

    public void observedDeath(ActorId actor) {
        if (!open()) return;
        if (!dead.equals(actor)) violations.add("UNEXPECTED_DEATH: " + actor);
        else deathObserved = true;
    }

    /** Caller validates the actual installed view, not merely a failure request. */
    public void observedRemoval(ActorId observer) {
        if (!open()) return;
        if (!survivors.contains(observer)) violations.add("INVALID_REMOVAL_OBSERVER: " + observer);
        else removals.add(observer);
    }

    public void observationGap() { if (open()) reportingGap = true; }

    /** A non-closing readiness check, not a verdict or proof of traffic exhaustion. */
    public boolean requiredDelivered() {
        for (Map.Entry<MessageId, Original> e : originals.entrySet())
            if (e.getValue().confirmed && survivors.contains(e.getKey().sender()))
                for (List<MessageId> history : histories.values())
                    if (!history.contains(e.getKey())) return false;
        return true;
    }

    public Result assess(boolean workloadAccounted, boolean noTrafficEvidence, boolean timedOut) {
        closed = true;
        List<String> evidence = new ArrayList<>(violations);
        boolean complete = workloadAccounted && noTrafficEvidence && !timedOut && !reportingGap
                && deathObserved && removals.containsAll(survivors);
        if (!deathObserved) evidence.add("MISSING_DEATH");
        if (!removals.containsAll(survivors)) evidence.add("MISSING_REMOVAL: observed=" + removals);
        if (!complete) evidence.add("INCOMPLETE_EVIDENCE: workload=" + workloadAccounted
                + " drained=" + noTrafficEvidence + " timeout=" + timedOut + " reportingGap=" + reportingGap);
        boolean finalEvidence = complete;
        boolean concrete = !violations.isEmpty();
        long mandatory = 0;
        for (Map.Entry<MessageId, Original> e : originals.entrySet()) {
            MessageId id = e.getKey();
            Original original = e.getValue();
            if (!original.confirmed && !original.uncertain) {
                complete = false; evidence.add("PROVISIONAL_SUBMISSION: " + id);
            }
            if (original.uncertain) evidence.add("UNCERTAIN_SUBMISSION: original retained for " + id);
            boolean required = original.confirmed && survivors.contains(id.sender());
            if (required) mandatory++;
            int delivered = 0;
            for (Map.Entry<ActorId, List<MessageId>> h : histories.entrySet()) {
                if (h.getValue().contains(id)) delivered++;
                else if (required) evidence.add("MISSING_REQUIRED: receiver=" + h.getKey() + " message=" + id);
            }
            if (required && delivered != survivors.size()) complete = false;
            if (!required && delivered != 0 && delivered != survivors.size()) {
                evidence.add("PARTIAL_ALL_OR_NONE: " + id);
                if (finalEvidence) concrete = true;
                complete = false;
            }
        }
        if (mandatory == 0) { complete = false; evidence.add("UNMET_SURVIVOR_TRAFFIC"); }
        List<MessageId> reference = histories.values().iterator().next();
        for (Map.Entry<ActorId, List<MessageId>> h : histories.entrySet()) {
            if (!reference.equals(h.getValue())) {
                evidence.add("SURVIVOR_DISAGREEMENT: " + h.getKey());
                if (finalEvidence) concrete = true;
                complete = false;
            }
        }
        return new Result(concrete ? Verdict.UNSUCCESSFUL : complete ? Verdict.SUCCESSFUL : Verdict.INCOMPLETE,
                evidence, histories);
    }
}
