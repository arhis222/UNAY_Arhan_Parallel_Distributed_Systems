package edu.polytech.tom.tests;

import java.util.Arrays;

import edu.polytech.tom.TomMessage;

/*
 * TEST for now, to be more adjusted 
 */
public class TomMessageTest {

  static void check(boolean ok, String what) {
    if (!ok)
      throw new AssertionError("FAILED: " + what);
    System.out.println("OK: " + what);
  }

  static void testDataBytes() {
    byte[] payload = { 1, 2, 3 };
    TomMessage m = TomMessage.data(2, 1, 7, 10, payload);
    TomMessage copy = TomMessage.fromBytes(m.toBytes());
    check(copy.type == TomMessage.DATA, "DATA keeps its type");
    check(copy.sender == 2 && copy.incarnation == 1 && copy.sequence == 7, "DATA keeps its id");
    check(copy.timestamp == 10 && copy.clock == 10 && copy.from == 2, "DATA keeps timestamp, clock and from");
    check(Arrays.equals(copy.payload, payload), "DATA keeps its payload");
  }

  static void testAck() {
    TomMessage data = TomMessage.data(2, 1, 7, 10, new byte[] { 9 });
    TomMessage ack = TomMessage.fromBytes(TomMessage.ack(data, 3, 12).toBytes());
    check(ack.type == TomMessage.ACK, "ACK has the ACK type");
    check(ack.id().equals(data.id()), "ACK has the id of its DATA");
    check(ack.timestamp == 10, "ACK keeps the timestamp of its DATA");
    check(ack.from == 3 && ack.clock == 12, "ACK has the acknowledger and its clock");
    check(ack.payload.length == 0, "ACK has no payload");
  }

  static void testId() {
    TomMessage a = TomMessage.data(1, 1, 1, 5, new byte[0]);
    check(a.id().equals(TomMessage.data(1, 1, 1, 8, new byte[0]).id()), "same sender, incarnation and sequence give the same id");
    check(!a.id().equals(TomMessage.data(1, 1, 2, 5, new byte[0]).id()), "another sequence gives another id");
    check(!a.id().equals(TomMessage.data(1, 2, 1, 5, new byte[0]).id()), "another incarnation gives another id");
    check(!a.id().equals(TomMessage.data(2, 1, 1, 5, new byte[0]).id()), "another sender gives another id");
  }

  static void testOrder() {
    TomMessage early = TomMessage.data(3, 1, 1, 9, new byte[0]);
    TomMessage p1 = TomMessage.data(1, 1, 1, 10, new byte[0]);
    TomMessage p2 = TomMessage.data(2, 1, 1, 10, new byte[0]);
    check(early.compareTo(p1) < 0 && p1.compareTo(early) > 0, "smaller timestamp comes first");
    check(p1.compareTo(p2) < 0 && p2.compareTo(p1) > 0, "same timestamp: smaller sender comes first");
    check(p1.compareTo(TomMessage.data(1, 1, 2, 10, new byte[0])) == 0, "same timestamp and sender are equal in the order");
  }

  public static void main(String[] args) {
    testDataBytes();
    testAck();
    testId();
    testOrder();
    System.out.println("TomMessageTest: all checks passed");
  }
}
