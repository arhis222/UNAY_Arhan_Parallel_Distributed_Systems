package edu.polytech.tom;

import java.nio.ByteBuffer;

/*
 * A packet of the multicast: 2 types. 
 * DATA carries a payload, 
 * ACK acknowledges a DATA
 */
public class TomMessage implements Comparable<TomMessage> {

  public static final int DATA = 0;
  public static final int ACK = 1;

  public final int type; // Packet type: DATA or ACK
  public final int sender; // who is sending the DATA
  public final int incarnation; // goes up each time the sender is resurrected
  public final long sequence; // counter of the sender's messages, for this incarnation
  public final long timestamp; // clock of the sender
  public final int from; // who sent this packet
  public final long clock; // clock of "from" when it sent this packet
  public final byte[] payload; // content of the message if DATA, empty for an ACK

  private TomMessage(int type, int sender, int incarnation, long sequence, long timestamp, int from, long clock,
      byte[] payload) {
    this.type = type;
    this.sender = sender;
    this.incarnation = incarnation;
    this.sequence = sequence;
    this.timestamp = timestamp;
    this.from = from;
    this.clock = clock;
    this.payload = payload;
  }

  public static TomMessage data(int sender, int incarnation, long sequence, long timestamp, byte[] payload) {
    return new TomMessage(DATA, sender, incarnation, sequence, timestamp, sender, timestamp, payload);
  }

  public static TomMessage ack(TomMessage data, int from, long clock) {
    return new TomMessage(ACK, data.sender, data.incarnation, data.sequence, data.timestamp, from, clock,
        new byte[0]);
  }

  public String id() {
    return sender + "." + incarnation + "#" + sequence;
  }

  @Override
  public int compareTo(TomMessage other) {
    if (timestamp != other.timestamp)
      return timestamp < other.timestamp ? -1 : 1;
    return Integer.compare(sender, other.sender);
  }

  public byte[] toBytes() {
    ByteBuffer b = ByteBuffer.allocate(1 + 4 + 4 + 8 + 8 + 4 + 8 + 4 + payload.length);
    b.put((byte) type);
    b.putInt(sender);
    b.putInt(incarnation);
    b.putLong(sequence);
    b.putLong(timestamp);
    b.putInt(from);
    b.putLong(clock);
    b.putInt(payload.length);
    b.put(payload);
    return b.array();
  }

  public static TomMessage fromBytes(byte[] bytes) {
    ByteBuffer b = ByteBuffer.wrap(bytes);
    int type = b.get();
    int sender = b.getInt();
    int incarnation = b.getInt();
    long sequence = b.getLong();
    long timestamp = b.getLong();
    int from = b.getInt();
    long clock = b.getLong();
    byte[] payload = new byte[b.getInt()];
    b.get(payload);
    return new TomMessage(type, sender, incarnation, sequence, timestamp, from, clock, payload);
  }

  @Override
  public String toString() {
    return (type == DATA ? "DATA " : "ACK ") + id() + " ts=" + timestamp + " from=" + from;
  }
}
