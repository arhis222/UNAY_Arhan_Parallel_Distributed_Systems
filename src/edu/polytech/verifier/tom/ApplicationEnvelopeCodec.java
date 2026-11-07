package edu.polytech.verifier.tom;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

import edu.polytech.verifier.ActorId;
import edu.polytech.verifier.MessageId;

/** Strict, bounded SARV version-1 framing. No runtime or TOM dependency. */
public final class ApplicationEnvelopeCodec {
    public static final int MAX_NAME_BYTES = 1024;
    public static final int MAX_WORKLOAD_BYTES = 1048576;
    public static final int MIN_FRAME_BYTES = 35;
    public static final int MAX_FRAME_BYTES = 1050657;
    private static final int MAGIC = 0x53415256;

    private ApplicationEnvelopeCodec() { }

    public static byte[] encode(ApplicationEnvelope value) throws EnvelopeFormatException {
        if (value == null) throw error("Envelope required", 0);
        if (value.workloadLength() > MAX_WORKLOAD_BYTES) throw error("Workload too large", 0);
        MessageId id = value.id();
        byte[] execution = nameBytes(id.executionId(), 9);
        int groupOffset = 13 + execution.length;
        byte[] group = nameBytes(id.groupId(), groupOffset);
        long size = 33L + execution.length + group.length + value.workloadLength();
        if (size > MAX_FRAME_BYTES) throw error("Frame too large", 0);
        ByteBuffer out = ByteBuffer.allocate((int) size).order(ByteOrder.BIG_ENDIAN);
        out.putInt(MAGIC).put((byte) 1);
        out.putInt(execution.length).put(execution);
        out.putInt(group.length).put(group);
        out.putInt(id.sender().actorNumber()).putInt(id.sender().incarnation());
        out.putLong(id.sequence());
        out.putInt(value.workloadLength()).put(value.workload());
        return out.array();
    }

    private static byte[] nameBytes(String name, int offset) throws EnvelopeFormatException {
        if (name.length() > MAX_NAME_BYTES) throw error("Name too long", offset);
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(name));
            if (encoded.remaining() < 1 || encoded.remaining() > MAX_NAME_BYTES) {
                throw error("Invalid name byte length", offset);
            }
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException e) {
            throw error("Invalid UTF-16 name", offset);
        }
    }

    public static ApplicationEnvelope decode(byte[] frame) throws EnvelopeFormatException {
        if (frame == null) throw error("Frame required", 0);
        if (frame.length < MIN_FRAME_BYTES || frame.length > MAX_FRAME_BYTES) {
            throw error("Invalid total frame length", 0);
        }
        Reader in = new Reader(frame.clone());
        if (in.integer("magic") != MAGIC) throw error("Invalid magic", 0);
        if ((in.bytes.get() & 0xff) != 1) throw error("Unsupported version", 4);
        String execution = in.name();
        String group = in.name();
        int actorOffset = in.bytes.position();
        int actor = in.integer("actor number");
        if (actor < 0) throw error("Negative actor number", actorOffset);
        int incarnationOffset = in.bytes.position();
        int incarnation = in.integer("incarnation");
        if (incarnation < 0) throw error("Negative incarnation", incarnationOffset);
        int sequenceOffset = in.bytes.position();
        in.require(8, "sequence");
        long sequence = in.bytes.getLong();
        if (sequence < 0) throw error("Negative sequence", sequenceOffset);
        int lengthOffset = in.bytes.position();
        int length = in.integer("workload length");
        if (length < 0 || length > MAX_WORKLOAD_BYTES) throw error("Invalid workload length", lengthOffset);
        in.require(length, "workload");
        if (length != in.bytes.remaining()) throw error("Trailing data", in.bytes.position() + length);
        byte[] workload = new byte[length];
        in.bytes.get(workload);
        return new ApplicationEnvelope(new MessageId(execution, group,
                new ActorId(actor, incarnation), sequence), workload);
    }

    private static EnvelopeFormatException error(String reason, int offset) {
        return new EnvelopeFormatException(reason, offset);
    }

    private static final class Reader {
        private final ByteBuffer bytes;

        Reader(byte[] frame) { bytes = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN); }

        void require(int count, String field) throws EnvelopeFormatException {
            if (count > bytes.remaining()) throw error("Truncated " + field, bytes.position());
        }

        int integer(String field) throws EnvelopeFormatException {
            require(4, field);
            return bytes.getInt();
        }

        String name() throws EnvelopeFormatException {
            int lengthOffset = bytes.position();
            int length = integer("name length");
            if (length < 1 || length > MAX_NAME_BYTES) throw error("Invalid name length", lengthOffset);
            require(length, "name");
            int offset = bytes.position();
            ByteBuffer field = bytes.slice();
            field.limit(length);
            String name;
            try {
                name = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(field).toString();
            } catch (CharacterCodingException e) {
                throw error("Invalid UTF-8 name", offset + field.position());
            }
            if (name.trim().isEmpty()) throw error("Blank name", offset);
            bytes.position(offset + length);
            return name;
        }
    }
}
