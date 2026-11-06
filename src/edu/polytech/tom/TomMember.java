package edu.polytech.tom;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import edu.polytech.groups.Group;
import edu.polytech.groups.MemberId;
import edu.polytech.groups.View;

/*
 * The multicast of one member of a Group. Each view of the Group is a period
 * with its own TomProcess, for a group that does not change. A period delivers
 * nothing to the application until all the older periods are over, so every
 * member delivers the messages of a period before the ones of the next period.
 * A member that arrives or comes back has no TomProcess for the older periods:
 * it never gets what was sent before its first view.
 * It relies on the Group: same views in the same order for everybody, and the
 * same packets of a removed member for everybody before the view without it.
 */
public class TomMember implements TotalOrderMulticast, Group.Listener {

  private static final int PACKET = 0; // a packet of the TomProcess of a period
  private static final int CLOSE = 1; // the sender will send nothing more in this period

  private static class Delivery {
    int sender;
    int incarnation;
    long sequence;
    byte[] payload;
  }

  private static class Held {
    int from;
    byte[] frame;
  }

  private class Period {
    final long number; // the number of the view
    final List<MemberId> members;
    final TomProcess process;
    final Set<Integer> closedBy = new HashSet<Integer>();
    final List<Delivery> waiting = new ArrayList<Delivery>(); // kept until the older periods are over

    Period(View view, long sequence) {
      number = view.viewId;
      members = view.members;
      process = new TomProcess(group.self().id, group.self().incarnation, view.ids(),
          (to, packet) -> group.send(to, frame(number, PACKET, packet)));
      process.setNextSequence(sequence);
      process.setClock(clock);
      process.setListener((sender, incarnation, seq, payload) -> {
        Delivery d = new Delivery();
        d.sender = sender;
        d.incarnation = incarnation;
        d.sequence = seq;
        d.payload = payload;
        delivered(this, d);
      });
    }
  }

  private final Group group;
  private final TaskClock clock = new TaskClock(); // the same clock for all the periods
  private Listener listener;
  private Consumer<View> viewListener;
  private View view;
  private final List<Period> periods = new ArrayList<Period>(); // the ones not over, the oldest first
  private List<Held> held = new ArrayList<Held>(); // packets of a view that I did not install yet

  public TomMember(Group group) {
    this.group = group;
    group.setListener(this);
  }

  /* The Group has only one listener and it is this TomMember, so the views are passed on here. */
  public void setViewListener(Consumer<View> l) {
    viewListener = l;
  }

  @Override
  public void setListener(Listener l) {
    listener = l;
  }

  @Override
  public long multicast(byte[] payload) {
    return periods.get(periods.size() - 1).process.multicast(payload);
  }

  @Override
  public void viewInstalled(View newView) {
    long sequence = 0;

    if (view != null) {
      sequence = periods.get(periods.size() - 1).process.nextSequence();

      // a member that left or died is not waited for any more, in all the periods not over
      // (a LEFTOVER is not needed, the Group gave everybody the same packets of it)
      for (MemberId m : view.members) {
        if (!newView.members.contains(m)) {
          for (Period p : periods)
            p.process.removeMember(m.id);
        }
      }

      // I send nothing more in the period that ends, the members that stay are told
      byte[] close = frame(view.viewId, CLOSE, new byte[0]);
      for (MemberId m : view.members) {
        if (newView.members.contains(m))
          group.send(m.id, close);
      }
    }

    view = newView;
    periods.add(new Period(newView, sequence));

    // the packets of this view that arrived before I installed it
    List<Held> before = held;
    held = new ArrayList<Held>();
    for (Held h : before)
      received(h.from, h.frame);

    if (viewListener != null)
      viewListener.accept(newView);

    removeOverPeriods();
  }

  @Override
  public void received(int from, byte[] frame) {
    ByteBuffer b = ByteBuffer.wrap(frame);
    long number = b.getLong();
    int kind = b.get();
    byte[] body = new byte[b.remaining()];
    b.get(body);

    if (number > view.viewId) {
      Held h = new Held();
      h.from = from;
      h.frame = frame;
      held.add(h);
      return;
    }

    for (Period p : periods) {
      if (p.number == number) {
        if (kind == PACKET)
          p.process.onPacket(body);
        else
          p.closedBy.add(from);
      }
    }
    removeOverPeriods();
  }

  private void delivered(Period p, Delivery d) {
    if (p == periods.get(0))
      pass(d);
    else
      p.waiting.add(d);
  }

  private void pass(Delivery d) {
    if (listener != null)
      listener.delivered(d.sender, d.incarnation, d.sequence, d.payload);
  }

  // A period is over when the members that are still there closed it and all its messages are delivered
  private boolean isOver(Period p) {
    for (MemberId m : p.members) {
      if (view.members.contains(m) && !p.closedBy.contains(m.id))
        return false;
    }
    return p.process.finished();
  }

  private void removeOverPeriods() {
    while (!periods.isEmpty() && isOver(periods.get(0))) {
      periods.remove(0);

      if (!periods.isEmpty()) {
        Period next = periods.get(0);
        for (Delivery d : next.waiting)
          pass(d);
        next.waiting.clear();
      }
    }
  }

  private static byte[] frame(long number, int kind, byte[] body) {
    ByteBuffer b = ByteBuffer.allocate(8 + 1 + body.length);
    b.putLong(number);
    b.put((byte) kind);
    b.put(body);
    return b.array();
  }
}
