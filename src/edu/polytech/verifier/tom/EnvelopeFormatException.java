package edu.polytech.verifier.tom;

/** Invalid version-1 envelope; offset identifies the offending field or read. */
public final class EnvelopeFormatException extends Exception {
    private static final long serialVersionUID = 1L;
    private final String reason;
    private final int offset;

    public EnvelopeFormatException(String reason, int offset) {
        super(reason + " at byte " + offset);
        this.reason = reason;
        this.offset = offset;
    }

    public String reason() { return reason; }
    public int offset() { return offset; }
}
