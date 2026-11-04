package edu.polytech.verifier.tests;

import edu.polytech.verifier.ActorId;
import edu.polytech.verifier.BaselineScenario;
import edu.polytech.verifier.GroupVerifier;
import edu.polytech.verifier.MessageId;
import edu.polytech.verifier.VerificationResult;
import edu.polytech.verifier.VerificationResult.CloseReason;

/** Sequential simulated-report fixture. Contains no protocol or runtime. */
public final class VerifierHarness {
    private final GroupVerifier verifier;

    public VerifierHarness(BaselineScenario scenario) { verifier = new GroupVerifier(scenario); }
    public GroupVerifier verifier() { return verifier; }
    public void register(int slot, MessageId id, byte[] bytes) { verifier.registerMulticast(slot, id, bytes); }
    public void deliver(ActorId receiver, MessageId id, byte[] bytes) { verifier.delivered(receiver, id, bytes); }
    public void closeWorkload() { verifier.closeWorkload(); }
    public VerificationResult snapshot() { return verifier.snapshot(); }
    public VerificationResult finish(CloseReason reason) { return verifier.closeObservation(reason); }
}
