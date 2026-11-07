package edu.polytech.verifier.tom;

import edu.polytech.verifier.MessageId;

/** Application identity and workload; independent of TOM packet identities. */
public final class ApplicationEnvelope {
    private final MessageId id;
    private final byte[] workload;

    public ApplicationEnvelope(MessageId id, byte[] workload) {
        if (id == null || workload == null) {
            throw new IllegalArgumentException("Identity and workload required");
        }
        this.id = id;
        this.workload = workload.clone();
    }

    public MessageId id() { return id; }
    public byte[] workload() { return workload.clone(); }

    // Allows the codec to reject oversize values before making another copy.
    int workloadLength() { return workload.length; }
}
