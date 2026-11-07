package edu.polytech.verifier.tom.tests;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import edu.polytech.verifier.ActorId;
import edu.polytech.verifier.MessageId;
import edu.polytech.verifier.VerificationResult;
import edu.polytech.verifier.VerificationResult.Verdict;
import edu.polytech.verifier.tom.FixedGroupTomRunner;

/** Real fixed-group integration at zero and positive per-sender sending periods. */
public final class VerifierTomGroupTest {
    private static void check(boolean condition, String detail) {
        if (!condition) throw new AssertionError(detail);
    }

    private static void scenario(int periodMillis) throws Exception {
        String runId = "verifier-period-" + periodMillis;
        VerificationResult result = new FixedGroupTomRunner(runId, periodMillis).run();
        check(result.verdict() == Verdict.SUCCESSFUL, "Expected success: " + result + " " + result.diagnostics());
        check(result.confirmedMessages() == 6 && result.validDeliveries() == 18,
                "Expected six confirmations and eighteen deliveries");
        check(result.provisionalMessages() == 0 && result.unaccountedSends() == 0
                && result.missingDeliveries().isEmpty(), "Incomplete accounting");
        check(result.scenario().periodMillis() == periodMillis, "Period not retained");
        Set<MessageId> expected = new HashSet<>();
        for (ActorId actor : result.scenario().plannedSends().keySet())
            for (int slot = 0; slot < 2; slot++)
                expected.add(new MessageId(runId, "fixed-three", actor, 100L + slot));
        check(result.histories().size() == 3, "Expected three recipients");
        List<MessageId> reference = null;
        for (List<MessageId> history : result.histories().values()) {
            check(history.size() == 6 && new HashSet<>(history).equals(expected), "Expected each identity exactly once");
            if (reference == null) reference = history;
            check(history.equals(reference), "Delivery histories differ");
        }
        System.out.println("VerifierTomGroupTest: period=" + periodMillis
                + " ms SUCCESSFUL; 3 members, 6 messages, 18 deliveries, identical histories");
    }

    public static void main(String[] args) {
        int status = 0;
        try { scenario(0); scenario(5); }
        catch (Throwable failure) { failure.printStackTrace(); status = 1; }
        // Executor has no shutdown API; match TomGroupTest's standalone-process cleanup.
        System.exit(status);
    }
}
