package edu.polytech.tom;

import java.util.function.Consumer;

import edu.polytech.groups.Group;
import edu.polytech.groups.MemberId;
import edu.polytech.groups.View;

/*
 * The multicast of one member of a Group: it gives the packets and the views
 * of the Group to a TomProcess.
 */
public class TomMember implements TotalOrderMulticast, Group.Listener {

  private final Group group;
  private TomProcess process;
  private Listener listener;
  private Consumer<View> viewListener;
  private View view;

  public TomMember(Group group) {
    this.group = group;
    group.setListener(this);
  }

  public void setViewListener(Consumer<View> l) {
    viewListener = l;
  }

  @Override
  public void setListener(Listener l) {
    listener = l;

    if (process != null)
      process.setListener(l);
  }

  @Override
  public long multicast(byte[] payload) {
    return process.multicast(payload);
  }

  @Override
  public void viewInstalled(View newView) {
    if (process == null) {
      process = new TomProcess(group.self().id, group.self().incarnation, newView.ids(), group);
      process.setListener(listener);
    } else {
      // A member that left or died
      // A join is not handled yet
      for (MemberId m : view.members) {
        if (!newView.contains(m.id))
          process.memberDied(m.id);
      }
    }
    view = newView;

    if (viewListener != null)
      viewListener.accept(newView);
  }

  @Override
  public void received(int from, byte[] packet) {
    process.onPacket(packet);
  }
}
