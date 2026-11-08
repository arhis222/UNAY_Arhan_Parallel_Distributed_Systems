package edu.polytech.groups;

import edu.polytech.tom.TomProcess;

/**
 * The group as seen by one member. It implements TomProcess.Transport so that
 * TOM can use it directly: new TomProcess(id, incarnation, view.ids(), group).
 *
 * All methods must be called on the event pump. All listener callbacks run on
 * the member's group task, which belongs to its Actor: they never run after
 * the actor's death.
 */
public interface Group extends TomProcess.Transport {

  interface Listener {
    /** A packet from a member of the current view. */
    void received(int from, byte[] packet);

    /**
     * A new view: initial group, then every failure, join, reinsertion or leave.
     * This is the group's listener for deaths, joins and leaves: compare with
     * the previous view to know who left or arrived.
     */
    void viewInstalled(View view);
  }

  interface JoinListener {
    /** Called once, just before the first viewInstalled. Set the Listener here. */
    void joined(Group group);
  }

  MemberId self();

  /** The installed view, or null before the group is formed. */
  View view();

  /** Sends a packet to a member. Silently dropped if 'to' is not in the view. */
  @Override
  void send(int to, byte[] packet);

  void setListener(Listener listener);

  /**
   * Asks the group to admit 'newcomer' under number 'id': a new actor
   * (voluntary join), or a resurrected actor reusing its id (reinsertion). This
   * member is only the contact. The newcomer's first view is the one that
   * includes it: it never receives a packet sent before that view.
   * An identity (id, incarnation) can be used only once per group: a member
   * that left rejoins under a new id. Throws IllegalArgumentException otherwise.
   */
  void admit(edu.polytech.actors.Actor newcomer, int id, JoinListener listener);

  /**
   * Leaves the group voluntarily. The others receive every packet I sent before,
   * then install a view without me. Afterwards I receive nothing more.
   */
  void leave();
}
