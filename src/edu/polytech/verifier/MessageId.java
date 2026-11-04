package edu.polytech.verifier;

import java.util.Objects;

/** Application identity; does not prescribe a TOM packet format or order. */
public final class MessageId {
    private final String executionId;
    private final String groupId;
    private final ActorId sender;
    private final long sequence;

    public MessageId(String executionId, String groupId, ActorId sender, long sequence) {
        this.executionId = requireName(executionId);
        this.groupId = requireName(groupId);
        if (sender == null || sequence < 0) throw new IllegalArgumentException("Invalid message identity");
        this.sender = sender;
        this.sequence = sequence;
    }

    static String requireName(String value) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException("Name required");
        return value;
    }

    public String executionId() { return executionId; }
    public String groupId() { return groupId; }
    public ActorId sender() { return sender; }
    public long sequence() { return sequence; }

    @Override public boolean equals(Object other) {
        if (!(other instanceof MessageId)) return false;
        MessageId id = (MessageId) other;
        return executionId.equals(id.executionId) && groupId.equals(id.groupId)
                && sender.equals(id.sender) && sequence == id.sequence;
    }

    @Override public int hashCode() { return Objects.hash(executionId, groupId, sender, sequence); }
    @Override public String toString() { return executionId + "/" + groupId + "/" + sender + "#" + sequence; }
}
