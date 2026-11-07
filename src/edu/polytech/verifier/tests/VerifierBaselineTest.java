package edu.polytech.verifier.tests;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import edu.polytech.verifier.ActorId;
import edu.polytech.verifier.BaselineScenario;
import edu.polytech.verifier.GroupVerifier.SubmissionIssue;
import edu.polytech.verifier.MessageId;
import edu.polytech.verifier.VerificationResult;
import edu.polytech.verifier.VerificationResult.CloseReason;
import edu.polytech.verifier.VerificationResult.Category;
import edu.polytech.verifier.VerificationResult.Code;
import edu.polytech.verifier.VerificationResult.Diagnostic;
import edu.polytech.verifier.VerificationResult.MissingDelivery;
import edu.polytech.verifier.VerificationResult.Phase;
import edu.polytech.verifier.VerificationResult.Verdict;

/** Simulated reports only. Invalid traces pass when their expected errors are detected. */
public final class VerifierBaselineTest {
    private static final ActorId A = new ActorId(0, 1), B = new ActorId(1, 1), C = new ActorId(2, 1);
    private static final ActorId[] MEMBERS = { A, B, C };
    private static int passed;

    private static void check(boolean condition, String detail) {
        if (!condition) throw new AssertionError(detail);
    }
    private static void test(String name, Runnable body) {
        body.run();
        passed++;
        System.out.println("PASS: " + name);
    }
    private static void invalid(Runnable body) {
        try { body.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected invalid configuration/identity");
    }
    private static void immutable(Runnable body) {
        try { body.run(); } catch (UnsupportedOperationException expected) { return; }
        throw new AssertionError("Mutable result exposed");
    }
    private static Map<ActorId, Integer> plan(int a, int b, int c) {
        Map<ActorId, Integer> map = new LinkedHashMap<>();
        map.put(A, a); map.put(B, b); map.put(C, c);
        return map;
    }
    private static BaselineScenario scenario(Map<ActorId, Integer> map) {
        long count = 0;
        for (int value : map.values()) count += value;
        return new BaselineScenario("run", "group", map, count == 0, count == 0 ? 0 : 1,
                count == 0 ? 0 : 1, 0, 1000);
    }
    private static VerifierHarness harness(int a, int b, int c) {
        return new VerifierHarness(scenario(plan(a, b, c)));
    }
    private static MessageId id(ActorId actor, long sequence) { return new MessageId("run", "group", actor, sequence); }
    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int i = 0; i < values.length; i++) result[i] = (byte) values[i];
        return result;
    }
    private static void all(VerifierHarness h, MessageId id, byte[] payload) {
        for (ActorId member : MEMBERS) h.deliver(member, id, payload);
    }
    private static void expect(VerificationResult result, Verdict verdict, Code... codes) {
        check(result.verdict() == verdict, "Expected " + verdict + ", got " + result + " " + result.diagnostics());
        for (Code code : codes) check(result.hasDiagnostic(code), "Missing " + code + ": " + result.diagnostics());
    }
    private static Diagnostic diagnostic(VerificationResult result, Code code) {
        for (Diagnostic value : result.diagnostics()) if (value.code() == code) return value;
        throw new AssertionError("Missing diagnostic " + code);
    }
    private static boolean missing(VerificationResult result, MessageId id, ActorId receiver) {
        for (MissingDelivery value : result.missingDeliveries())
            if (value.message().equals(id) && value.receiver().equals(receiver)) return true;
        return false;
    }
    private static VerificationResult finish(VerifierHarness h) {
        h.closeWorkload(); return h.finish(CloseReason.NORMAL);
    }

    private static void observation(VerificationResult result, Code code, ActorId receiver, MessageId message) {
        expect(result, Verdict.UNSUCCESSFUL, code);
        Diagnostic d = diagnostic(result, code);
        check(d.category() == Category.OBSERVATION, "Wrong category: " + d);
        check(Objects.equals(d.receiver(), receiver) && Objects.equals(d.message(), message),
                "Wrong report attribution: " + d);
        long concrete = result.diagnostics().stream().filter(value -> value.category() != Category.INCOMPLETE).count();
        check(concrete == 1, "Expected one isolated violation: " + result.diagnostics());
    }

    private static void unchanged(VerificationResult before, VerificationResult after) {
        check(before.histories().equals(after.histories()), "Rejected input changed histories");
        check(before.accountedSlots().equals(after.accountedSlots()), "Rejected input changed slots");
        check(before.confirmedMessages() == after.confirmedMessages()
                && before.validDeliveries() == after.validDeliveries()
                && before.unaccountedSends() == after.unaccountedSends(), "Rejected input changed accounting");
        check(before.missingDeliveries().size() == after.missingDeliveries().size(), "Obligation count changed");
        for (MissingDelivery pair : before.missingDeliveries())
            check(after.missingDeliveries().stream().anyMatch(value -> value.message().equals(pair.message())
                    && value.receiver().equals(pair.receiver()) && value.plannedIndex() == pair.plannedIndex()),
                    "Obligation changed: " + pair);
    }

    private static void rejectedMutation(VerifierHarness h, Consumer<VerifierHarness> action,
            Code code, ActorId receiver, MessageId message) {
        VerificationResult before = h.snapshot();
        action.accept(h);
        VerificationResult after = h.snapshot();
        observation(after, code, receiver, message);
        unchanged(before, after);
        if (after.phase() == Phase.OPEN) h.closeWorkload();
        observation(h.finish(CloseReason.NORMAL), code, receiver, message);
    }

    private static void identities() {
        ActorId same = new ActorId(0, 1);
        check(A.equals(same) && A.hashCode() == same.hashCode(), "Actor value equality");
        check(!A.equals(new ActorId(0, 2)) && !A.equals(B) && !A.equals(null), "Actor distinctions");
        MessageId m = id(A, Long.MAX_VALUE), copy = id(same, Long.MAX_VALUE);
        check(m.equals(copy) && m.hashCode() == copy.hashCode(), "Message value equality");
        check(!m.equals(id(A, 0)) && !m.equals(id(B, Long.MAX_VALUE)), "Sender/sequence distinct");
        check(!m.equals(new MessageId("other", "group", A, Long.MAX_VALUE)), "Run distinct");
        check(!m.equals(new MessageId("run", "other", A, Long.MAX_VALUE)), "Group distinct");
        check(!m.equals(id(new ActorId(0, 2), Long.MAX_VALUE)), "Incarnation distinct");
        invalid(() -> new ActorId(-1, 0)); invalid(() -> new ActorId(0, -1));
        invalid(() -> id(A, -1)); invalid(() -> id(null, 0));
        invalid(() -> new MessageId(null, "group", A, 0));
        invalid(() -> new MessageId("run", " ", A, 0));
    }

    private static void configuration() {
        Map<ActorId, Integer> map = plan(1, 0, 0);
        BaselineScenario scenario = scenario(map);
        map.clear();
        check(scenario.plannedMessages() == 1 && scenario.plannedSends().size() == 3, "Configuration copy");
        immutable(() -> scenario.plannedSends().clear());
        invalid(() -> new BaselineScenario("run", "group", null, false, 1, 1, 0, 1));
        invalid(() -> scenario(Collections.<ActorId, Integer>emptyMap()));
        invalid(() -> new BaselineScenario("run", "group", plan(-1, 0, 0), false, 1, 1, 0, 1));
        Map<ActorId, Integer> bad = plan(1, 0, 0); bad.put(null, 0);
        invalid(() -> scenario(bad));
        Map<ActorId, Integer> nullCount = plan(1, 0, 0); nullCount.put(A, null);
        invalid(() -> new BaselineScenario("run", "group", nullCount, false, 1, 1, 0, 1));
        invalid(() -> new BaselineScenario("", "group", plan(1, 0, 0), false, 1, 1, 0, 1));
        invalid(() -> new BaselineScenario("run", "group", plan(1, 0, 0), false, 1, 1, -1, 1));
        invalid(() -> new BaselineScenario("run", "group", plan(1, 0, 0), false, 1, 1, 0, 0));
        invalid(() -> new BaselineScenario("run", "group", plan(1, 0, 0), false, 2, 1, 0, 1));
        invalid(() -> new BaselineScenario("run", "group", plan(1, 0, 0), false, 1, 4, 0, 1));
        invalid(() -> new BaselineScenario("run", "group", plan(1, 0, 0), false, -1, 1, 0, 1));
        invalid(() -> new BaselineScenario("run", "group", plan(1, 0, 0), true, 0, 0, 0, 1));
        invalid(() -> new BaselineScenario("run", "group", plan(0, 0, 0), false, 0, 0, 0, 1));
    }

    private static void completeAndLag() {
        VerifierHarness h = harness(1, 1, 0);
        MessageId a = id(A, 10), b = id(B, 5);
        h.register(0, a, bytes(7)); h.register(0, b, bytes(7));
        VerificationResult before = h.snapshot();
        h.deliver(A, b, bytes(7)); h.deliver(A, a, bytes(7));
        h.deliver(B, b, bytes(7));
        expect(h.snapshot(), Verdict.IN_PROGRESS);
        check(!h.snapshot().hasDiagnostic(Code.ORDER_DIVERGENCE), "Compatible lag rejected");
        h.closeWorkload();
        check(h.snapshot().phase() == Phase.DRAINING, "Not draining");
        h.deliver(B, a, bytes(7)); h.deliver(C, b, bytes(7)); h.deliver(C, a, bytes(7));
        expect(h.snapshot(), Verdict.IN_PROGRESS);
        check(h.snapshot().missingDeliveries().isEmpty(), "Obligations not cleared");
        VerificationResult done = h.finish(CloseReason.NORMAL);
        expect(done, Verdict.SUCCESSFUL);
        check(done.confirmedMessages() == 2 && done.validDeliveries() == 6, "Counts include self");
        check(done.histories().get(A).equals(Arrays.asList(b, a)), "Submission order imposed");
        check(before.histories().get(A).isEmpty() && before.missingDeliveries().size() == 6, "Snapshot changed");
    }

    private static void defensiveCopies() {
        VerifierHarness h = harness(1, 0, 0); MessageId m = id(A, 0);
        byte[] original = bytes(1, 2); h.register(0, m, original); original[0] = 9;
        byte[] observed = bytes(1, 2); h.deliver(A, m, observed); observed[0] = 8;
        h.deliver(B, m, bytes(1, 2)); h.deliver(C, m, bytes(1, 2));
        VerificationResult result = finish(h); expect(result, Verdict.SUCCESSFUL);
        immutable(() -> result.histories().clear());
        immutable(() -> result.histories().get(A).clear());
        immutable(() -> result.diagnostics().clear());
        immutable(() -> result.missingDeliveries().clear());
        immutable(() -> result.accountedSlots().clear());
        immutable(() -> result.accountedSlots().get(A).clear());

        VerifierHarness bad = harness(1, 0, 0); bad.register(0, m, bytes(1, 2));
        byte[] wrong = bytes(3, 4); bad.deliver(A, m, wrong); wrong[0] = 6;
        VerificationResult snapshot = bad.snapshot();
        Diagnostic d = diagnostic(snapshot, Code.CONTENT_MISMATCH);
        byte[] expected = d.expectedExcerpt(), actual = d.actualExcerpt();
        expected[0] = 90; actual[0] = 91;
        check(d.expectedExcerpt()[0] == 1 && d.actualExcerpt()[0] == 3, "Diagnostic bytes leaked");
        check(d.detail().contains("first difference 0"), "Mismatch location missing");
        check(missing(snapshot, m, A), "Corruption cleared obligation");
        bad.deliver(A, m, bytes(1, 2));
        expect(bad.snapshot(), Verdict.UNSUCCESSFUL, Code.DUPLICATE, Code.CONTENT_MISMATCH);
        check(missing(bad.snapshot(), m, A) && bad.snapshot().validDeliveries() == 0, "Duplicate repaired corruption");
        check(snapshot.histories().get(A).size() == 1, "Earlier bad snapshot changed");
    }

    private static void divergence(boolean later) {
        VerifierHarness h = harness(3, 0, 0);
        MessageId a = id(A, 0), b = id(A, 1), c = id(A, 2);
        h.register(0, a, bytes()); h.register(1, b, bytes()); h.register(2, c, bytes());
        if (later) { h.deliver(A, a, bytes()); h.deliver(B, a, bytes()); }
        h.deliver(A, b, bytes()); h.deliver(B, c, bytes());
        VerificationResult result = finish(h); expect(result, Verdict.UNSUCCESSFUL, Code.ORDER_DIVERGENCE);
        String detail = diagnostic(result, Code.ORDER_DIVERGENCE).detail();
        check(detail.contains("Position " + (later ? 1 : 0)) && detail.contains(A.toString())
                && detail.contains(B.toString()) && detail.contains(b.toString()), "Divergence evidence missing");
    }

    private static void duplicatesAndLength() {
        VerifierHarness h = harness(1, 0, 0); MessageId m = id(A, 0);
        h.register(0, m, bytes(1)); all(h, m, bytes(1)); h.deliver(B, m, bytes(1));
        VerificationResult result = finish(h); expect(result, Verdict.UNSUCCESSFUL, Code.DUPLICATE);
        check(result.validDeliveries() == 3 && result.histories().get(B).size() == 2, "Duplicate accounting");
        check(diagnostic(result, Code.DUPLICATE).detail().contains("repeated position 1"), "Duplicate positions");
        VerifierHarness length = harness(1, 0, 0); length.register(0, m, bytes(1, 2));
        length.deliver(A, m, bytes(1));
        expect(finish(length), Verdict.UNSUCCESSFUL, Code.CONTENT_MISMATCH);
    }

    private static void unknownOriginal() {
        MessageId m = id(A, 0);
        VerifierHarness h = harness(1, 0, 0);
        h.deliver(A, m, bytes(1));
        observation(h.snapshot(), Code.UNKNOWN_MESSAGE, A, m);
        check(h.snapshot().confirmedMessages() == 0, "Invented original");
        h.register(0, m, bytes(1)); all(h, m, bytes(1));
        expect(finish(h), Verdict.UNSUCCESSFUL, Code.UNKNOWN_MESSAGE);
    }

    private static void invalidDelivery(ActorId receiver, MessageId reported, byte[] payload,
            Code code, String invalidRole) {
        VerifierHarness h = harness(1, 0, 0); h.register(0, id(A, 0), bytes(1));
        rejectedMutation(h, value -> value.deliver(receiver, reported, payload), code, receiver, reported);
        if (invalidRole != null) {
            Diagnostic d = diagnostic(h.snapshot(), code);
            ActorId invalidActor = invalidRole.equals("Sender") ? reported.sender() : receiver;
            check(d.detail().contains(invalidRole + " " + invalidActor), "Invalid actor role not identified: " + d);
        }
    }

    private static void invalidRegistration(int slot, MessageId reported, byte[] payload, Code code) {
        VerifierHarness h = harness(2, 0, 0); MessageId m = id(A, 0);
        h.register(0, m, bytes(1));
        VerificationResult before = h.snapshot();
        h.register(slot, reported, payload);
        observation(h.snapshot(), code, null, reported);
        unchanged(before, h.snapshot());
        all(h, m, bytes(1));
        VerificationResult result = finish(h);
        observation(result, code, null, reported);
        check(result.missingDeliveries().isEmpty() && !result.hasDiagnostic(Code.CONTENT_MISMATCH),
                "First independent original overwritten");
    }

    private static void invalidInputScenarios() {
        MessageId m = id(A, 0);
        ActorId undeclared = new ActorId(9, 1), stale = new ActorId(0, 2);
        test("delivery: unknown receiver", () -> invalidDelivery(undeclared, m, bytes(1), Code.UNKNOWN_ACTOR, "Receiver"));
        test("delivery: wrong receiver incarnation", () -> invalidDelivery(stale, m, bytes(1), Code.UNKNOWN_ACTOR, "Receiver"));
        test("delivery: null receiver", () -> invalidDelivery(null, m, bytes(1), Code.UNKNOWN_ACTOR, "Receiver"));
        test("delivery: unknown sender", () -> invalidDelivery(A, id(undeclared, 0), bytes(1), Code.UNKNOWN_ACTOR, "Sender"));
        test("delivery: wrong sender incarnation", () -> invalidDelivery(A, id(stale, 0), bytes(1), Code.UNKNOWN_ACTOR, "Sender"));
        test("delivery: wrong execution", () -> invalidDelivery(A, new MessageId("wrong", "group", A, 0), bytes(1), Code.WRONG_CONTEXT, null));
        test("delivery: wrong group", () -> invalidDelivery(A, new MessageId("run", "wrong", A, 0), bytes(1), Code.WRONG_CONTEXT, null));
        test("delivery: null identity", () -> invalidDelivery(A, null, bytes(1), Code.INVALID_INPUT, null));
        test("delivery: null payload", () -> invalidDelivery(A, m, null, Code.INVALID_INPUT, null));
        test("registration: reused identity", () -> invalidRegistration(1, m, bytes(9), Code.REUSED_MESSAGE));
        test("registration: reused slot", () -> invalidRegistration(0, id(A, 1), bytes(1), Code.REUSED_SLOT));
        test("registration: negative slot", () -> invalidRegistration(-1, id(A, 1), bytes(), Code.INVALID_SLOT));
        test("registration: past last slot", () -> invalidRegistration(2, id(A, 1), bytes(), Code.INVALID_SLOT));
        test("registration: null payload", () -> invalidRegistration(1, id(A, 1), null, Code.INVALID_INPUT));
        test("registration: null identity", () -> invalidRegistration(1, null, bytes(), Code.INVALID_INPUT));
        test("registration: wrong execution", () -> invalidRegistration(1, new MessageId("wrong", "group", A, 1), bytes(), Code.WRONG_CONTEXT));
        test("registration: wrong group", () -> invalidRegistration(1, new MessageId("run", "wrong", A, 1), bytes(), Code.WRONG_CONTEXT));
        test("registration: unknown sender", () -> invalidRegistration(1, id(undeclared, 1), bytes(), Code.UNKNOWN_ACTOR));
    }

    private static void singleMember() {
        VerifierHarness h = new VerifierHarness(scenario(Collections.singletonMap(A, 1)));
        MessageId m = id(A, 0); h.register(0, m, bytes(4));
        VerificationResult pending = h.snapshot();
        check(pending.missingDeliveries().size() == 1 && missing(pending, m, A), "Self obligation absent");
        h.closeWorkload(); expect(h.snapshot(), Verdict.IN_PROGRESS, Code.MISSING_DELIVERIES);
        h.deliver(A, m, bytes(4));
        VerificationResult result = h.finish(CloseReason.NORMAL);
        expect(result, Verdict.SUCCESSFUL);
        check(result.validDeliveries() == 1 && result.confirmedMessages() == 1
                && result.histories().get(A).equals(Collections.singletonList(m))
                && result.missingDeliveries().isEmpty() && result.diagnostics().isEmpty(), "Self delivery accounting");
    }

    private static void nonemptyLateDelivery() {
        VerifierHarness h = harness(1, 0, 0); MessageId m = id(A, 0);
        h.register(0, m, bytes(4)); all(h, m, bytes(4));
        VerificationResult old = finish(h); expect(old, Verdict.SUCCESSFUL);
        h.deliver(B, m, bytes(4));
        VerificationResult result = h.snapshot();
        observation(result, Code.LATE_REPORT, B, m);
        unchanged(old, result);
        check(old.verdict() == Verdict.SUCCESSFUL && old.diagnostics().isEmpty()
                && old.histories().get(B).equals(Collections.singletonList(m)), "Earlier success changed");
    }

    private static void omissions(boolean everyone) {
        VerifierHarness h = harness(1, 0, 0); MessageId m = id(A, 0); h.register(0, m, bytes());
        if (!everyone) { h.deliver(A, m, bytes()); h.deliver(B, m, bytes()); }
        VerificationResult result = finish(h);
        expect(result, Verdict.INCOMPLETE, Code.MISSING_DELIVERIES);
        check(result.missingDeliveries().size() == (everyone ? 3 : 1) && missing(result, m, C), "Missing pairs wrong");
        if (everyone) {
            check(missing(result, m, A) && missing(result, m, B), "Absent everywhere obligations lost");
            check(diagnostic(result, Code.MISSING_DELIVERIES).detail().contains("absent everywhere"), "No absence evidence");
        }
    }

    private static void issues(SubmissionIssue issue, boolean afterRegistration) {
        VerifierHarness h = harness(1, 0, 0); MessageId m = id(A, 0);
        if (afterRegistration) h.register(0, m, bytes(1));
        h.verifier().recordSubmissionIssue(A, 0, issue, "simulated submission outcome");
        Code code = issue == SubmissionIssue.UNCERTAIN ? Code.SUBMISSION_UNCERTAIN
                : issue == SubmissionIssue.REJECTED ? Code.SUBMISSION_REJECTED : Code.NOT_INVOKED;
        VerificationResult result = finish(h);
        expect(result, issue == SubmissionIssue.UNCERTAIN ? Verdict.INCOMPLETE : Verdict.UNSUCCESSFUL, code);
        check(result.unaccountedSends() == 0 && result.confirmedMessages() == (afterRegistration ? 1 : 0), "Issue accounting");
        check(result.missingDeliveries().size() == (afterRegistration ? 3 : 0), "Issue erased obligations");
    }

    private static void invalidIssueScenarios() {
        test("issue: conflicting issue on accounted slot", () -> {
            VerifierHarness h = harness(1, 0, 0);
            h.verifier().recordSubmissionIssue(A, 0, SubmissionIssue.UNCERTAIN, "unknown");
            rejectedMutation(h, value -> value.verifier().recordSubmissionIssue(A, 0,
                    SubmissionIssue.REJECTED, "conflicting"), Code.REUSED_SLOT, null, null);
        });
        test("registration: slot already uncertain", () -> {
            VerifierHarness h = harness(1, 0, 0);
            h.verifier().recordSubmissionIssue(A, 0, SubmissionIssue.UNCERTAIN, "unknown");
            rejectedMutation(h, value -> value.register(0, id(A, 0), bytes()), Code.REUSED_SLOT, null, id(A, 0));
        });
        test("issue: null issue", () -> rejectedMutation(harness(1, 0, 0),
                value -> value.verifier().recordSubmissionIssue(A, 0, null, "x"), Code.INVALID_INPUT, null, null));
        test("issue: empty explanation", () -> rejectedMutation(harness(1, 0, 0),
                value -> value.verifier().recordSubmissionIssue(A, 0, SubmissionIssue.UNCERTAIN, ""),
                Code.INVALID_INPUT, null, null));
        test("issue: unknown sender", () -> rejectedMutation(harness(1, 0, 0),
                value -> value.verifier().recordSubmissionIssue(new ActorId(9, 1), 0, SubmissionIssue.UNCERTAIN, "x"),
                Code.UNKNOWN_ACTOR, null, null));
    }

    private static void reportingGaps() {
        VerifierHarness h = harness(1, 0, 0); MessageId m = id(A, 0);
        h.register(0, m, bytes()); all(h, m, bytes());
        h.verifier().recordObservationGap("observer evidence unavailable");
        expect(h.snapshot(), Verdict.IN_PROGRESS, Code.REPORTING_GAP);
        VerificationResult gap = finish(h); expect(gap, Verdict.INCOMPLETE, Code.REPORTING_GAP);
        VerifierHarness violation = harness(1, 0, 0); violation.register(0, m, bytes(1));
        violation.verifier().recordObservationGap("observer unavailable"); violation.deliver(A, m, bytes(2));
        expect(finish(violation), Verdict.UNSUCCESSFUL, Code.REPORTING_GAP, Code.CONTENT_MISMATCH);
        VerifierHarness lateGap = harness(0, 0, 0); VerificationResult old = finish(lateGap);
        lateGap.verifier().recordObservationGap("discovered after closure");
        expect(lateGap.snapshot(), Verdict.INCOMPLETE, Code.REPORTING_GAP);
        expect(old, Verdict.SUCCESSFUL);
        check(!lateGap.snapshot().hasDiagnostic(Code.LATE_REPORT), "Gap became concrete violation");
    }

    private static void closure() {
        VerifierHarness h = harness(1, 0, 0); MessageId m = id(A, 0);
        h.register(0, m, bytes()); all(h, m, bytes());
        expect(h.snapshot(), Verdict.IN_PROGRESS, Code.WORKLOAD_OPEN);
        expect(h.finish(CloseReason.NORMAL), Verdict.INCOMPLETE, Code.WORKLOAD_OPEN);

        VerifierHarness empty = harness(0, 0, 0); VerificationResult old = finish(empty);
        expect(old, Verdict.SUCCESSFUL);
        int diagnostics = old.diagnostics().size();
        expect(empty.finish(CloseReason.NORMAL), Verdict.SUCCESSFUL);
        check(empty.snapshot().diagnostics().size() == diagnostics, "Closure not idempotent");
        empty.deliver(A, m, bytes());
        expect(empty.snapshot(), Verdict.UNSUCCESSFUL, Code.LATE_REPORT);
        expect(old, Verdict.SUCCESSFUL); check(old.diagnostics().size() == diagnostics, "Old success mutated");

        VerifierHarness conflict = harness(0, 0, 0); finish(conflict);
        expect(conflict.finish(CloseReason.TIMEOUT), Verdict.UNSUCCESSFUL, Code.CONFLICTING_CLOSE);
        VerifierHarness badReason = harness(0, 0, 0);
        expect(badReason.finish(null), Verdict.UNSUCCESSFUL, Code.INVALID_INPUT);
        VerifierHarness lateRegistration = harness(1, 0, 0); lateRegistration.finish(CloseReason.NORMAL);
        lateRegistration.register(0, m, bytes());
        expect(lateRegistration.snapshot(), Verdict.UNSUCCESSFUL, Code.LATE_REPORT);
    }

    private static void workloadMisuse(Consumer<VerifierHarness> action) {
        VerifierHarness h = harness(1, 0, 0); h.register(0, id(A, 0), bytes()); h.closeWorkload();
        rejectedMutation(h, action, Code.WORKLOAD_CLOSED, null, null);
    }

    private static void timeoutAndInterruption() {
        for (CloseReason reason : Arrays.asList(CloseReason.TIMEOUT, CloseReason.INTERRUPTED)) {
            Code code = reason == CloseReason.TIMEOUT ? Code.TIMEOUT : Code.INTERRUPTED;
            VerifierHarness empty = harness(0, 0, 0); empty.closeWorkload();
            expect(empty.finish(reason), Verdict.INCOMPLETE, code);
            VerifierHarness missing = harness(1, 0, 0); missing.register(0, id(A, 0), bytes()); missing.closeWorkload();
            expect(missing.finish(reason), Verdict.INCOMPLETE, code, Code.MISSING_DELIVERIES);
            VerifierHarness broken = harness(1, 0, 0); broken.deliver(A, id(A, 0), bytes()); broken.closeWorkload();
            expect(broken.finish(reason), Verdict.UNSUCCESSFUL, code, Code.UNKNOWN_MESSAGE);
        }
    }

    private static void coverageAndLargePlans() {
        VerifierHarness h = new VerifierHarness(new BaselineScenario("run", "group", plan(2, 0, 0), false, 2, 6, 25, 1000));
        h.register(0, id(A, 0), bytes()); all(h, id(A, 0), bytes());
        VerificationResult result = finish(h);
        expect(result, Verdict.INCOMPLETE, Code.UNACCOUNTED_SENDS, Code.UNMET_COVERAGE);
        check(result.missingDeliveries().isEmpty() && !result.coverageSatisfied(), "Empty obligations bypassed coverage");
        VerifierHarness large = new VerifierHarness(scenario(plan(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE)));
        large.register(Integer.MAX_VALUE - 1, id(A, Long.MAX_VALUE), bytes());
        VerificationResult sparse = finish(large);
        expect(sparse, Verdict.INCOMPLETE, Code.UNACCOUNTED_SENDS);
        check(sparse.scenario().plannedMessages() == 6442450941L && sparse.unaccountedSends() == 6442450940L,
                "Large counts overflowed");
        check(sparse.scenario().possibleDeliveries() == 19327352823L
                && sparse.accountedSlots().get(A).size() == 1, "Sparse accounting expanded planned workload");
    }

    private static void practicalArithmeticBoundaries() {
        Map<ActorId, Integer> map = plan(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
        long messages = 6442450941L, deliveries = 19327352823L;
        BaselineScenario limits = new BaselineScenario("run", "group", map, false,
                messages, deliveries, Integer.MAX_VALUE, Long.MAX_VALUE);
        check(limits.plannedMessages() == messages && limits.possibleDeliveries() == deliveries
                && limits.minimumConfirmedMessages() == messages && limits.minimumValidDeliveries() == deliveries
                && limits.periodMillis() == Integer.MAX_VALUE && limits.budgetMillis() == Long.MAX_VALUE,
                "Public numeric boundaries narrowed");
        invalid(() -> new BaselineScenario("run", "group", map, false, messages + 1, deliveries, 0, 1));
        invalid(() -> new BaselineScenario("run", "group", map, false, messages, deliveries + 1, 0, 1));
        invalid(() -> new BaselineScenario("run", "group", map, false, Long.MAX_VALUE, 1, 0, 1));
        invalid(() -> new BaselineScenario("run", "group", map, false, 1, Long.MAX_VALUE, 0, 1));
        invalid(() -> new BaselineScenario("run", "group", map, false, Long.MIN_VALUE, 1, 0, 1));
        invalid(() -> new BaselineScenario("run", "group", map, false, 1, 1, Integer.MIN_VALUE, 1));
        invalid(() -> new BaselineScenario("run", "group", map, false, 1, 1, 0, Long.MIN_VALUE));
        invalid(() -> new ActorId(Integer.MIN_VALUE, 1));
        invalid(() -> id(A, Long.MIN_VALUE));
        ActorId maximum = new ActorId(Integer.MAX_VALUE, Integer.MAX_VALUE);
        check(maximum.actorNumber() == Integer.MAX_VALUE && maximum.incarnation() == Integer.MAX_VALUE,
                "Maximum actor identity changed");
        VerifierHarness h = new VerifierHarness(limits);
        MessageId last = id(A, Long.MAX_VALUE);
        h.register(Integer.MAX_VALUE - 1, last, bytes());
        check(h.snapshot().confirmedMessages() == 1 && h.snapshot().unaccountedSends() == messages - 1,
                "Last valid slot failed");
        rejectedMutation(h, value -> value.register(Integer.MAX_VALUE, id(A, 0), bytes()),
                Code.INVALID_SLOT, null, id(A, 0));
    }

    private static void provisionalEarlyDelivery() {
        VerifierHarness h = harness(1, 0, 0);
        MessageId m = id(A, 1);
        byte[] original = bytes(7);
        check(h.verifier().beginMulticast(0, m, original), "begin accepted");
        original[0] = 9;
        VerificationResult begun = h.snapshot();
        check(begun.confirmedMessages() == 0 && begun.provisionalMessages() == 1
                && begun.unaccountedSends() == 0, "provisional is accounted but not confirmed");
        for (ActorId actor : MEMBERS) check(missing(begun, m, actor), "initial obligation");
        all(h, m, bytes(7));
        VerificationResult early = h.snapshot();
        expect(early, Verdict.IN_PROGRESS, Code.PROVISIONAL_SUBMISSION);
        check(early.confirmedMessages() == 0 && early.validDeliveries() == 3
                && early.missingDeliveries().isEmpty(), "early delivery does not confirm");
        check(h.verifier().confirmMulticast(m), "confirm accepted");
        VerificationResult confirmed = h.snapshot();
        check(confirmed.confirmedMessages() == 1 && confirmed.provisionalMessages() == 0
                && confirmed.validDeliveries() == 3 && confirmed.missingDeliveries().isEmpty(), "confirmation preserves cleared obligations");
        check(!confirmed.hasDiagnostic(Code.PROVISIONAL_SUBMISSION), "resolved provisional diagnostic disappears");
        expect(finish(h), Verdict.SUCCESSFUL);
        check(begun.provisionalMessages() == 1 && begun.missingDeliveries().size() == 3
                && early.confirmedMessages() == 0, "earlier provisional snapshots immutable");
    }

    private static void unresolvedSubmission(boolean uncertain) {
        // Atomic traffic meets coverage: unresolved provisional state must independently block success.
        VerifierHarness h = harness(2, 0, 0);
        MessageId atomic = id(A, 1), provisional = id(A, 2);
        h.register(0, atomic, bytes(1));
        check(h.verifier().beginMulticast(1, provisional, bytes(2)), "begin second slot");
        all(h, atomic, bytes(1)); all(h, provisional, bytes(2));
        if (uncertain) h.verifier().recordSubmissionIssue(A, 1, SubmissionIssue.UNCERTAIN, "Invocation outcome unavailable");
        VerificationResult result = finish(h);
        expect(result, Verdict.INCOMPLETE, Code.PROVISIONAL_SUBMISSION);
        if (uncertain) expect(result, Verdict.INCOMPLETE, Code.SUBMISSION_UNCERTAIN);
        Diagnostic d = diagnostic(result, Code.PROVISIONAL_SUBMISSION);
        check(d.category() == Category.INCOMPLETE && provisional.equals(d.message()) && d.receiver() == null,
                "provisional diagnostic attribution");
        check(result.coverageSatisfied() && result.missingDeliveries().isEmpty()
                && result.unaccountedSends() == 0 && result.confirmedMessages() == 1
                && result.provisionalMessages() == 1, "unresolved state independently prevents success");
        check(result.accountedSlots().get(A).get(1).startsWith("PROVISIONAL "), "sparse provisional status");
    }

    private static void invalidConfirmation(String kind) {
        VerifierHarness h = harness(1, 0, 0);
        MessageId m = id(A, 1);
        Code code = Code.INVALID_CONFIRMATION;
        if (kind.equals("unknown")) {
            code = Code.UNKNOWN_MESSAGE;
        } else {
            check(h.verifier().beginMulticast(0, m, bytes(7)), "begin for confirmation rejection");
            if (kind.equals("repeated")) check(h.verifier().confirmMulticast(m), "first confirmation");
            if (kind.equals("uncertain")) h.verifier().recordSubmissionIssue(A, 0, SubmissionIssue.UNCERTAIN, "Unknown outcome");
            if (kind.equals("late")) { finish(h); code = Code.LATE_REPORT; }
            if (kind.equals("draining")) { h.closeWorkload(); code = Code.WORKLOAD_CLOSED; }
        }
        VerificationResult before = h.snapshot();
        check(!h.verifier().confirmMulticast(m), "invalid confirmation rejected");
        VerificationResult after = h.snapshot();
        observation(after, code, null, code == Code.WORKLOAD_CLOSED ? null : m);
        check(after.confirmedMessages() == before.confirmedMessages()
                && after.provisionalMessages() == before.provisionalMessages(), "rejected confirmation changes no counters");
        unchanged(before, after);
    }

    public static void main(String[] args) {
        test("immutable value identities", VerifierBaselineTest::identities);
        test("configuration validation and copying", VerifierBaselineTest::configuration);
        test("complete multi-sender trace, equal payloads, reverse registration order and compatible lag", VerifierBaselineTest::completeAndLag);
        test("payload, diagnostic and snapshot defensive copying", VerifierBaselineTest::defensiveCopies);
        test("first-position order divergence", () -> divergence(false));
        test("later-position order divergence", () -> divergence(true));
        test("duplicates and content length", VerifierBaselineTest::duplicatesAndLength);
        test("delivery before independent registration", VerifierBaselineTest::unknownOriginal);
        invalidInputScenarios();
        test("single-member group and self delivery", VerifierBaselineTest::singleMember);
        test("late delivery after nonempty success", VerifierBaselineTest::nonemptyLateDelivery);
        test("omission at one recipient", () -> omissions(false));
        test("omission by every recipient", () -> omissions(true));
        for (SubmissionIssue issue : SubmissionIssue.values()) {
            test("submission " + issue + " before registration", () -> issues(issue, false));
            test("submission " + issue + " after registration", () -> issues(issue, true));
        }
        invalidIssueScenarios();
        test("reporting gaps and verdict precedence", VerifierBaselineTest::reportingGaps);
        test("closure, late reports and immutable earlier success", VerifierBaselineTest::closure);
        test("closed workload: registration", () -> workloadMisuse(value -> value.register(0, id(A, 1), bytes())));
        test("closed workload: issue", () -> workloadMisuse(value -> value.verifier().recordSubmissionIssue(
                A, 0, SubmissionIssue.UNCERTAIN, "unknown")));
        test("closed workload: repeated close", () -> workloadMisuse(VerifierHarness::closeWorkload));
        test("timeout and interruption precedence", VerifierBaselineTest::timeoutAndInterruption);
        test("coverage, unaccounted sends and sparse long counts", VerifierBaselineTest::coverageAndLargePlans);
        test("practical public arithmetic boundaries", VerifierBaselineTest::practicalArithmeticBoundaries);
        test("provisional original and early deliveries then confirmation", VerifierBaselineTest::provisionalEarlyDelivery);
        test("unresolved provisional blocks success despite coverage", () -> unresolvedSubmission(false));
        test("uncertain provisional blocks success despite deliveries", () -> unresolvedSubmission(true));
        for (String kind : new String[] {"repeated", "unknown", "uncertain", "late", "draining"})
            test("confirmation: " + kind, () -> invalidConfirmation(kind));
        System.out.println("VerifierBaselineTest: " + passed + " simulated-report test groups passed");
    }
}
