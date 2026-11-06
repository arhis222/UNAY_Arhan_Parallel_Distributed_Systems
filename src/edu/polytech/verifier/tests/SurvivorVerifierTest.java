package edu.polytech.verifier.tests;

import java.util.Arrays;
import java.util.LinkedHashSet;

import edu.polytech.verifier.ActorId;
import edu.polytech.verifier.MessageId;
import edu.polytech.verifier.SurvivorVerifier;
import edu.polytech.verifier.VerificationResult.Verdict;

/** Simulated application reports; invalid traces pass when correctly rejected. */
public final class SurvivorVerifierTest {
    private static final ActorId A = new ActorId(1, 1), D = new ActorId(2, 1), B = new ActorId(3, 1);
    private static final MessageId REQUIRED = new MessageId("r", "g", A, 1);
    private static final MessageId OPTIONAL = new MessageId("r", "g", D, 1);
    private static int passed;
    private static void check(boolean ok, String detail) { if (!ok) throw new AssertionError(detail); }
    private static SurvivorVerifier fixture(boolean secondRemoval) {
        SurvivorVerifier v = new SurvivorVerifier("r", "g", new LinkedHashSet<>(Arrays.asList(A, D, B)), D);
        byte[] bytes = {7};
        v.beginMulticast(REQUIRED, bytes); v.confirmMulticast(REQUIRED);
        v.beginMulticast(OPTIONAL, bytes); v.confirmMulticast(OPTIONAL);
        bytes[0] = 99; // Registered originals must be independent.
        v.observedDeath(D); v.observedRemoval(A);
        if (secondRemoval) v.observedRemoval(B);
        return v;
    }
    private static void both(SurvivorVerifier v, MessageId id) {
        v.delivered(A, id, new byte[] {7}); v.delivered(B, id, new byte[] {7});
    }
    private static void expect(SurvivorVerifier.Result result, Verdict verdict, String code) {
        check(result.verdict() == verdict, "Expected " + verdict + ": " + result);
        if (code != null) check(result.diagnostics().stream().anyMatch(s -> s.startsWith(code)), "Missing " + code);
        passed++;
    }
    public static void main(String[] args) {
        SurvivorVerifier v = fixture(true); both(v, REQUIRED); both(v, OPTIONAL);
        expect(v.assess(true, true, false), Verdict.SUCCESSFUL, null);
        v = fixture(true); both(v, REQUIRED);
        expect(v.assess(true, true, false), Verdict.SUCCESSFUL, null); // dead sender delivered nowhere
        v = fixture(true); both(v, REQUIRED); v.delivered(A, OPTIONAL, new byte[] {7});
        expect(v.assess(true, true, false), Verdict.UNSUCCESSFUL, "PARTIAL_ALL_OR_NONE");
        v = fixture(true);
        SurvivorVerifier.Result missing = v.assess(true, true, false);
        expect(missing, Verdict.INCOMPLETE, "MISSING_REQUIRED"); // omitted by everyone, equal empty histories
        check(missing.diagnostics().stream().filter(s -> s.startsWith("MISSING_REQUIRED")).count() == 2,
                "Both receiver/message obligations must be listed");
        v = fixture(true); v.delivered(A, REQUIRED, new byte[] {7});
        expect(v.assess(true, true, false), Verdict.UNSUCCESSFUL, "SURVIVOR_DISAGREEMENT");
        v = fixture(false); both(v, REQUIRED);
        expect(v.assess(true, true, false), Verdict.INCOMPLETE, "MISSING_REMOVAL");
        v = fixture(true); both(v, REQUIRED);
        expect(v.assess(true, false, true), Verdict.INCOMPLETE, "INCOMPLETE_EVIDENCE");
        v = fixture(true); both(v, REQUIRED); v.observationGap();
        expect(v.assess(true, true, false), Verdict.INCOMPLETE, "INCOMPLETE_EVIDENCE");
        v = fixture(true); both(v, REQUIRED); v.delivered(A, REQUIRED, new byte[] {7});
        expect(v.assess(true, false, true), Verdict.UNSUCCESSFUL, "DUPLICATE");
        v = fixture(true); v.delivered(A, REQUIRED, new byte[] {8}); v.delivered(B, REQUIRED, new byte[] {7});
        expect(v.assess(true, true, false), Verdict.UNSUCCESSFUL, "CONTENT_MISMATCH");
        v = fixture(true); both(v, REQUIRED);
        MessageId uncertain = new MessageId("r", "g", A, 2);
        v.beginMulticast(uncertain, new byte[] {7}); v.uncertainMulticast(uncertain);
        expect(v.assess(true, true, false), Verdict.SUCCESSFUL, null); // uncertainty retained, optional none
        v = fixture(true); both(v, REQUIRED);
        v.beginMulticast(uncertain, new byte[] {7}); v.uncertainMulticast(uncertain); both(v, uncertain);
        expect(v.assess(true, true, false), Verdict.SUCCESSFUL, null);
        v = fixture(true); both(v, REQUIRED);
        v.beginMulticast(uncertain, new byte[] {7});
        expect(v.assess(true, true, false), Verdict.INCOMPLETE, "PROVISIONAL_SUBMISSION");
        v = fixture(true); both(v, REQUIRED);
        v.delivered(D, REQUIRED, new byte[] {99}); // Dead history excluded from survivor comparison.
        expect(v.assess(true, true, false), Verdict.SUCCESSFUL, null);
        v = fixture(true);
        v.delivered(A, REQUIRED, new byte[] {7}); v.delivered(A, OPTIONAL, new byte[] {7});
        v.delivered(B, OPTIONAL, new byte[] {7}); v.delivered(B, REQUIRED, new byte[] {7});
        expect(v.assess(true, true, false), Verdict.UNSUCCESSFUL, "SURVIVOR_DISAGREEMENT");
        System.out.println("SurvivorVerifierTest: " + passed + " simulated-report cases passed");
    }
}
