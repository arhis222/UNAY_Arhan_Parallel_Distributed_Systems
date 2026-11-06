package edu.polytech.verifier.tom.tests;

import java.nio.ByteBuffer;
import java.util.Arrays;

import edu.polytech.verifier.ActorId;
import edu.polytech.verifier.MessageId;
import edu.polytech.verifier.tom.ApplicationEnvelope;
import edu.polytech.verifier.tom.ApplicationEnvelopeCodec;
import edu.polytech.verifier.tom.EnvelopeFormatException;

/** Independent codec checks; no TOM, runtime, adapter or simulated delivery. */
public final class ApplicationEnvelopeCodecTest {
    private static int checks;

    private ApplicationEnvelopeCodecTest() { }

    public static void main(String[] args) throws Exception {
        roundTrips();
        exactFrame();
        boundaries();
        malformed();
        copies();
        System.out.println("ApplicationEnvelopeCodecTest: " + checks + " checks passed");
    }

    private static void check(boolean condition, String detail) {
        if (!condition) throw new AssertionError(detail);
        checks++;
    }

    private static ApplicationEnvelope value(String execution, String group, byte[] payload) {
        return new ApplicationEnvelope(new MessageId(execution, group, new ActorId(0, 0), 0), payload);
    }

    private static void roundTrip(ApplicationEnvelope original) throws Exception {
        ApplicationEnvelope decoded = ApplicationEnvelopeCodec.decode(ApplicationEnvelopeCodec.encode(original));
        check(decoded.id().equals(original.id()), "round-trip identity");
        check(Arrays.equals(decoded.workload(), original.workload()), "round-trip workload");
    }

    private static void roundTrips() throws Exception {
        roundTrip(value("e", "g", new byte[0]));
        roundTrip(value(" e\uFEFF\u00e9\uD83D\uDE80 ", "\u4e16\u754c", new byte[] {0, -1, 127, -128}));
        roundTrip(new ApplicationEnvelope(new MessageId("e", "g",
                new ActorId(Integer.MAX_VALUE, Integer.MAX_VALUE), Long.MAX_VALUE), new byte[] {1}));
        ApplicationEnvelope a = value("e", "g", new byte[] {7});
        ApplicationEnvelope b = new ApplicationEnvelope(new MessageId("e", "g", new ActorId(0, 0), 1), new byte[] {7});
        check(!ApplicationEnvelopeCodec.decode(ApplicationEnvelopeCodec.encode(a)).id().equals(
                ApplicationEnvelopeCodec.decode(ApplicationEnvelopeCodec.encode(b)).id()), "equal payloads retain distinct IDs");
    }

    // Manually authored, not assembled by the codec or a matching fixture encoder.
    private static byte[] fixture() {
        return new byte[] {
            0x53, 0x41, 0x52, 0x56, 1,
            0, 0, 0, 1, 0x65,
            0, 0, 0, 1, 0x67,
            0, 0, 0, 2, 0, 0, 0, 3,
            0, 0, 0, 0, 0, 0, 0, 4,
            0, 0, 0, 2, 0, (byte) 0xff
        };
    }

    private static void exactFrame() throws Exception {
        ApplicationEnvelope expected = new ApplicationEnvelope(new MessageId("e", "g", new ActorId(2, 3), 4),
                new byte[] {0, -1});
        check(Arrays.equals(fixture(), ApplicationEnvelopeCodec.encode(expected)), "exact big-endian fixture");
        ApplicationEnvelope decoded = ApplicationEnvelopeCodec.decode(fixture());
        check(decoded.id().equals(expected.id()), "independent fixture identity");
        check(Arrays.equals(decoded.workload(), new byte[] {0, -1}), "independent fixture workload");
        check(fixture().length == 33 + 1 + 1 + 2, "exact length formula");
    }

    private static String repeat(char c, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, c);
        return new String(chars);
    }

    private static void boundaries() throws Exception {
        ApplicationEnvelope maximum = value(repeat('e', 1024), repeat('\u00e9', 512), new byte[1048576]);
        byte[] frame = ApplicationEnvelopeCodec.encode(maximum);
        check(frame.length == 1050657, "maximum combined frame");
        roundTrip(maximum);
        check(ApplicationEnvelopeCodec.encode(value("e", "g", new byte[0])).length == 35, "minimum frame");
        rejectEncode(value(repeat('e', 1025), "g", new byte[0]));
        rejectEncode(value("e", repeat('g', 1025), new byte[0]));
        rejectEncode(value(repeat('\u00e9', 513), "g", new byte[0]));
        rejectEncode(value("e", repeat('\u00e9', 513), new byte[0]));
        rejectEncode(value("e", "g", new byte[1048577]));
        rejectDecode(new byte[1050658], 0);
    }

    private static byte[] integerAt(int offset, int value) {
        byte[] bytes = fixture();
        ByteBuffer.wrap(bytes).putInt(offset, value);
        return bytes;
    }

    private static void malformed() throws Exception {
        rejectDecode(null, 0);
        rejectEncode(null);
        byte[] bytes = fixture();
        bytes[0] = 0;
        rejectDecode(bytes, 0);
        for (int version : new int[] {0, 2, 255}) {
            bytes = fixture();
            bytes[4] = (byte) version;
            rejectDecode(bytes, 4);
        }
        for (int offset : new int[] {5, 10}) {
            for (int length : new int[] {-1, Integer.MIN_VALUE, 0, 1025, Integer.MAX_VALUE}) {
                rejectDecode(integerAt(offset, length), offset);
            }
            rejectDecode(integerAt(offset, 1024), offset + 4); // bounded but absent field
        }
        for (int offset : new int[] {15, 19}) {
            rejectDecode(integerAt(offset, -1), offset);
            rejectDecode(integerAt(offset, Integer.MIN_VALUE), offset);
        }
        bytes = fixture();
        ByteBuffer.wrap(bytes).putLong(23, Long.MIN_VALUE);
        rejectDecode(bytes, 23);
        bytes = fixture();
        ByteBuffer.wrap(bytes).putLong(23, -1);
        rejectDecode(bytes, 23);
        for (int length : new int[] {-1, Integer.MIN_VALUE, 1048577, Integer.MAX_VALUE}) {
            rejectDecode(integerAt(31, length), 31);
        }
        rejectDecode(integerAt(31, 3), 35); // truncated workload
        rejectDecode(integerAt(31, 1), 36); // trailing byte
        for (int offset : new int[] {9, 14}) {
            bytes = fixture();
            bytes[offset] = ' ';
            rejectDecode(bytes, offset);
            bytes = fixture();
            bytes[offset] = (byte) 0x80;
            rejectDecode(bytes, offset);
        }
        // Replace execution name with deliberately invalid UTF-8 byte sequences.
        for (byte[] invalid : new byte[][] {
                {(byte) 0xc0, (byte) 0xaf}, // overlong
                {(byte) 0xc2, 0x41}, // invalid continuation
                {(byte) 0xed, (byte) 0xa0, (byte) 0x80}, // surrogate
                {(byte) 0xe2, (byte) 0x82}, // incomplete code point
                {(byte) 0xf4, (byte) 0x90, (byte) 0x80, (byte) 0x80}}) { // above Unicode maximum
            byte[] original = fixture();
            bytes = new byte[original.length - 1 + invalid.length];
            System.arraycopy(original, 0, bytes, 0, 9);
            ByteBuffer.wrap(bytes).putInt(5, invalid.length);
            System.arraycopy(invalid, 0, bytes, 9, invalid.length);
            System.arraycopy(original, 10, bytes, 9 + invalid.length, original.length - 10);
            rejectDecode(bytes, 9);
        }
        rejectEncode(value("\uD800", "g", new byte[0]));
        rejectEncode(value("e", "\uDC00", new byte[0]));
        for (int length = 0; length < fixture().length; length++) {
            rejectDecode(Arrays.copyOf(fixture(), length), -1);
        }
        rejectDecode(Arrays.copyOf(fixture(), fixture().length + 1), fixture().length);
        bytes = new byte[fixture().length * 2];
        System.arraycopy(fixture(), 0, bytes, 0, fixture().length);
        System.arraycopy(fixture(), 0, bytes, fixture().length, fixture().length);
        rejectDecode(bytes, fixture().length);
    }

    private static void rejectDecode(byte[] bytes, int offset) throws Exception {
        try {
            ApplicationEnvelopeCodec.decode(bytes);
            throw new AssertionError("Invalid frame accepted");
        } catch (EnvelopeFormatException e) {
            check(e.reason() != null && !e.reason().isEmpty(), "decode reason");
            check(e.offset() >= 0 && (bytes == null || e.offset() <= bytes.length), "bounded decode offset");
            if (offset >= 0) check(e.offset() == offset, "decode offset: " + e.getMessage());
        }
    }

    private static void rejectEncode(ApplicationEnvelope value) throws Exception {
        try {
            ApplicationEnvelopeCodec.encode(value);
            throw new AssertionError("Invalid value encoded");
        } catch (EnvelopeFormatException e) {
            check(!e.reason().isEmpty() && e.offset() >= 0, "encode reason and offset");
        }
    }

    private static void copies() throws Exception {
        byte[] caller = {7, 8};
        ApplicationEnvelope envelope = value("e", "g", caller);
        caller[0] = 99;
        byte[] exposed = envelope.workload();
        exposed[0] = 88;
        check(envelope.workload()[0] == 7, "constructor and accessor copies");
        byte[] encoded = ApplicationEnvelopeCodec.encode(envelope);
        ApplicationEnvelope decoded = ApplicationEnvelopeCodec.decode(encoded);
        Arrays.fill(encoded, (byte) 0);
        byte[] decodedPayload = decoded.workload();
        decodedPayload[0] = 77;
        check(decoded.workload()[0] == 7, "decode input and output copies");
        check(ApplicationEnvelopeCodec.decode(ApplicationEnvelopeCodec.encode(envelope)).workload()[0] == 7,
                "encoded output mutation cannot change value");
        try {
            new ApplicationEnvelope(null, new byte[0]);
            throw new AssertionError("null identity accepted");
        } catch (IllegalArgumentException expected) { checks++; }
        try {
            new ApplicationEnvelope(envelope.id(), null);
            throw new AssertionError("null workload accepted");
        } catch (IllegalArgumentException expected) { checks++; }
    }
}
