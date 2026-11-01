package edu.polytech.tom;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/*
 * A packet of the multicast: 3 types.
 * DATA carries a payload,
 * ACK acknowledges a DATA,
 * LEFTOVER carries the DATA of a dead member that an alive member has not delivered
 */
public class TomMessage implements Comparable<TomMessage> {

  public static final int DATA = 0;
  public static final int ACK = 1;
  public static final int LEFTOVER = 2;

  public final int type; // Packet type: DATA, ACK or LEFTOVER
  public final int sender; // who is sending the DATA (for a LEFTOVER: the dead member)
  public final int incarnation; // goes up each time the sender is resurrected
  public final long sequence; // counter of the sender's messages, for this incarnation
  public final long timestamp; // clock of the sender
  public final int from; // who sent this packet
  public final long clock; // clock of "from" when it sent this packet
  public final byte[] payload; // content of the message if DATA, empty for an ACK, the list of DATA for a LEFTOVER

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

  public static TomMessage leftover(int dead, int from, long clock, List<TomMessage> datas) {
	    byte[][] packets = new byte[datas.size()][];
	    int size = 4;
	    
	    for (int i = 0; i < packets.length; i++) {
	      packets[i] = datas.get(i).toBytes();
	      size += packets[i].length;
	    }

	    ByteBuffer b = ByteBuffer.allocate(size);
	    b.putInt(packets.length);
	    
	    for (byte[] packet : packets) {
	      b.put(packet);
	      b.putInt(packet.length);
	    }
	    
	    return new TomMessage(LEFTOVER, dead, 0, 0, 0, from, clock, b.array());
	  }

  public List<TomMessage> leftoverData() {
    if (type != LEFTOVER)
      throw new IllegalStateException("not a LEFTOVER packet");

    ByteBuffer b = ByteBuffer.wrap(payload);
    int count = b.getInt();
    List<TomMessage> datas = new ArrayList<TomMessage>();
    
    for (int i = 0; i < count; i++) {
      byte[] packet = new byte[b.getInt()];
      b.get(packet);
      datas.add(fromBytes(packet));
    }
    
    return datas;
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
    if (type == LEFTOVER)
      return "LEFTOVER of " + sender + " from=" + from;
    return (type == DATA ? "DATA " : "ACK ") + id() + " ts=" + timestamp + " from=" + from;
  }
}
