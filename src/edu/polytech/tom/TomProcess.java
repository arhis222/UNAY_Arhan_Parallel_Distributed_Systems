package edu.polytech.tom;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/*
 * Totally-ordered multicast of one member, 1ST try without failures.
 */
public class TomProcess implements TotalOrderMulticast {

  public interface Transport {
    void send(int to, byte[] packet);
  }

  private final int self;
  private final int incarnation;
  private final Set<Integer> members; // all the members, me included
  private final Transport transport;
  private Listener listener;

  private long clock;
  private long sequence; // number of the next message I multicast

  // DATA received and not delivered yet
  private final PriorityQueue<TomMessage> pending = new PriorityQueue<TomMessage>();

  // for each message id, who acknowledged it ACK
  private final Map<String, Set<Integer>> acks = new HashMap<String, Set<Integer>>();

  private final Set<String> seen = new HashSet<String>();   // ids of the DATA already received

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

  @Override
  public void multicast(byte[] payload) {
    clock++;
    sendToAll(TomMessage.data(self, incarnation, sequence++, clock, payload));
  }

  /* To call for each packet that arrives for me */
  public void onPacket(byte[] packet) {
    TomMessage msg = TomMessage.fromBytes(packet);

    clock = Math.max(clock, msg.clock) + 1; // horloge de Lamport

    if (msg.type == TomMessage.DATA)
      receivedData(msg);
    else
      receivedAck(msg);

    tryDeliver();
  }

  private void receivedData(TomMessage data) {
    if (seen.add(data.id()))
      pending.add(data);

    clock++;
    sendToAll(TomMessage.ack(data, self, clock)); // Send ACK
  }

  private void receivedAck(TomMessage ack) {
    Set<Integer> ackedBy = acks.get(ack.id());

    if (ackedBy == null) {
      ackedBy = new HashSet<Integer>();
      acks.put(ack.id(), ackedBy);
    }

    ackedBy.add(ack.from);
  }

  private void sendToAll(TomMessage msg) {
    byte[] packet = msg.toBytes();

    for (int member : members)
      transport.send(member, packet);
  }

  private void tryDeliver() {
    while (!pending.isEmpty()) {
      TomMessage first = pending.peek();
      Set<Integer> ackedBy = acks.get(first.id());

      if (ackedBy == null || !ackedBy.containsAll(members))
        return;
  
      pending.poll();
      acks.remove(first.id());

      if (listener != null)
        listener.delivered(first.sender, first.payload);
    }
  }

}
