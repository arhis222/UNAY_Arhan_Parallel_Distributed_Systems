package edu.polytech.tom;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/*
 * Totally-ordered multicast of one member, with members that can die.
 */
public class TomProcess implements TotalOrderMulticast {

  public interface Transport {
    void send(int to, byte[] packet);
  }

  /* The logical clock. A TomProcess counts by itself, unless it is given another clock. */
  public interface Clock {
    /* A local event, like sending a packet: gives a number bigger than all the ones given before. */
    long tick();

    /* A packet with the timestamp t arrives: gives a number bigger than t and than all the ones before. */
    long receive(long t);
  }

  private static class Counter implements Clock {
    long value;

    public long tick() {
      return ++value;
    }

    public long receive(long t) {
      value = Math.max(value, t) + 1;
      return value;
    }
  }

  private final int self;
  private final int incarnation;
  private final Set<Integer> members; // all the members, me included
  private final Transport transport;
  private Listener listener;

  private Clock clock = new Counter();
  private long sequence; // number of the next message I multicast

  // DATA received and not delivered yet
  private final PriorityQueue<TomMessage> pending = new PriorityQueue<TomMessage>();

  // for each message id, who acknowledged it
  private final Map<String, Set<Integer>> acks = new HashMap<String, Set<Integer>>();

  private final Set<String> seen = new HashSet<String>(); // ids of the DATA already received

  private final Set<Integer> dead = new HashSet<Integer>(); // members that died

  // for each dead member, who sent me a LEFTOVER about it
  private final Map<Integer, Set<Integer>> leftoverFrom = new HashMap<Integer, Set<Integer>>();

  public TomProcess(int self, int incarnation, Set<Integer> members, Transport transport) {
    if (!members.contains(self))
      throw new IllegalArgumentException("member " + self + " is not in the group " + members);

    this.self = self;
    this.incarnation = incarnation;
    this.members = new HashSet<Integer>(members);
    this.transport = transport;
  }

  @Override
  public void setListener(Listener l) {
    listener = l;
  }

  /* To use another clock than the counter of this TomProcess, before anything is sent. */
  public void setClock(Clock c) {
    clock = c;
  }

  @Override
  public long multicast(byte[] payload) {
    long id = sequence++;
    sendToAll(TomMessage.data(self, incarnation, id, clock.tick(), payload));
    return id;
  }

  /* The sequence number of the next message, to go on with it in the next TomProcess. */
  public long nextSequence() {
    return sequence;
  }

  public void setNextSequence(long sequence) {
    this.sequence = sequence;
  }

  /* True when everything received was delivered and nothing is waited for after a death. */
  public boolean finished() {
    return pending.isEmpty() && !waitingLeftover();
  }

  /*
   * To call when a member is removed from a group that gives the same packets of this
   * member to everybody (the Group does): nothing is waited for from it any more,
   * and no LEFTOVER is needed.
   */
  public void removeMember(int id) {
    if (members.remove(id))
      tryDeliver();
  }

  /* To call when the group says that the member "id" is dead. */
  public void memberDied(int id) {
    if (!members.remove(id))
      return;

    dead.add(id);

    List<TomMessage> left = new ArrayList<TomMessage>();
    for (TomMessage m : pending) {
      if (dead.contains(m.sender))
        left.add(m);
    }

    byte[] packet = TomMessage.leftover(id, self, clock.tick(), left).toBytes();

    for (int member : members) {
      if (member != self)
        transport.send(member, packet);
    }
    leftoverReceived(id, self);

    tryDeliver();
  }

  /* To call for each packet that arrives for me */
  public void onPacket(byte[] packet) {
    TomMessage msg = TomMessage.fromBytes(packet);

    if (dead.contains(msg.from))
      return;

    clock.receive(msg.clock);

    if (msg.type == TomMessage.DATA)
      receivedData(msg);
    else if (msg.type == TomMessage.ACK)
      receivedAck(msg);
    else
      receivedLeftover(msg);

    tryDeliver();
  }

  private void receivedData(TomMessage data) {
    if (seen.add(data.id()))
      pending.add(data);

    sendToAll(TomMessage.ack(data, self, clock.tick())); // Send ACK
  }

  private void receivedAck(TomMessage ack) {
    Set<Integer> ackedBy = acks.get(ack.id());

    if (ackedBy == null) {
      ackedBy = new HashSet<Integer>();
      acks.put(ack.id(), ackedBy);
    }

    ackedBy.add(ack.from);
  }

  private void receivedLeftover(TomMessage left) {
    for (TomMessage data : left.leftoverData()) {
      if (!seen.contains(data.id()))
        receivedData(data);
    }

    // left.sender is the member that died, left.from is who sent this LEFTOVER
    leftoverReceived(left.sender, left.from);
  }

  private void leftoverReceived(int deadMember, int from) {
    Set<Integer> got = leftoverFrom.get(deadMember);

    if (got == null) {
      got = new HashSet<Integer>();
      leftoverFrom.put(deadMember, got);
    }

    got.add(from);
  }

  private boolean waitingLeftover() {
    for (int deadMember : dead) {
      Set<Integer> got = leftoverFrom.get(deadMember);

      if (got == null || !got.containsAll(members))
        return true;
    }
    return false;
  }

  private void sendToAll(TomMessage msg) {
    byte[] packet = msg.toBytes();

    for (int member : members)
      transport.send(member, packet);
  }

  private void tryDeliver() {
    if (waitingLeftover())
      return;

    while (!pending.isEmpty()) {
      TomMessage first = pending.peek();
      Set<Integer> ackedBy = acks.get(first.id());

      if (ackedBy == null || !ackedBy.containsAll(members))
        return;

      pending.poll();
      acks.remove(first.id());

      if (listener != null)
        listener.delivered(first.sender, first.incarnation, first.sequence, first.payload);
    }
  }

}
