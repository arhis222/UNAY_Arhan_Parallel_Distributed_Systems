package edu.polytech.groups;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Membership of a running group: the perfect failure detector, and the order of
 * view changes. Created when the initial group starts; from then on the
 * GroupManager is no longer used.
 *
 * Every change (death, join, leave) is handled inside one callback of the event
 * pump: it computes the next view and posts the change on the task of every
 * current member. The pump runs one callback at a time and each task is FIFO,
 * so all members receive the same changes, in the same order, with the same
 * views. Each member then installs them following its own rules (GroupMember).
 *
 * All methods run on the event pump.
 */
final class Membership {
  private final Map<Integer, GroupMember> live = new HashMap<Integer, GroupMember>();
  private final Set<MemberId> used = new HashSet<MemberId>(); // every identity ever admitted
  private final Map<Integer, GroupMember> joining = new HashMap<Integer, GroupMember>(); // admitted, not joined yet
  private View latest;
  private int brokers; // makes broker names unique, see brokerName()

  Membership(View first, Iterable<GroupMember> members) {
    latest = first;
    for (GroupMember m : members) {
      live.put(m.self.id, m);
      used.add(m.self);
    }
  }

  /** Broker names are never freed by CQueueBroker, so every join needs a new one. */
  String brokerName(String prefix, MemberId id) {
    return prefix + "-member-" + id.id + "-" + id.incarnation + "-j" + (++brokers);
  }

  /** Reserves the newcomer's identity. Returns why it cannot be admitted, or null. */
  String reserve(GroupMember newcomer) {
    MemberId who = newcomer.self;
    GroupMember m = live.get(who.id);
    if (m != null && !m.actor.dead()) return "Member id " + who.id + " is already in the group";
    if (joining.containsKey(who.id)) return "Member id " + who.id + " is already joining";
    // Packets and TOM message ids are identified by (id, incarnation): reusing a
    // pair would mix old and new packets. A member that left must rejoin under
    // a new id; a resurrected actor has a new incarnation.
    if (used.contains(who)) return "Identity " + who + " was already used in this group";
    used.add(who);
    joining.put(who.id, newcomer);
    return null;
  }

  /** Failure detector: called on the dead Actor's monitor task. */
  void failed(GroupMember dead) {
    if (joining.get(dead.self.id) == dead) joining.remove(dead.self.id); // died before joining
    if (live.get(dead.self.id) != dead) return; // already removed, or older endpoint
    remove(dead, GroupMember.FAIL);
  }

  /** Voluntary leave: called by the leaving member itself. */
  void left(GroupMember leaving) {
    if (live.get(leaving.self.id) != leaving) return;
    remove(leaving, GroupMember.LEAVE);
  }

  private void remove(GroupMember m, int type) {
    live.remove(m.self.id);
    final View next = latest.without(m.self.id);
    latest = next;
    final MemberId who = m.self;
    for (final GroupMember each : live.values())
      each.task.post(() -> each.change(type, who, next, null)); // dropped if 'each' is dead
  }

  /**
   * Join or reinsertion: called on the newcomer's group task, once its broker is
   * bound. A reinserted Actor reuses its id with a higher incarnation.
   */
  void joined(GroupMember newcomer) {
    joining.remove(newcomer.self.id);
    if (newcomer.actor.dead()) return;
    GroupMember old = live.get(newcomer.self.id);
    if (old != null) {
      // Reinsertion before the detector reported the old incarnation's death:
      // remove the old incarnation first, so every member sees FAIL then JOIN.
      if (old.actor.dead()) failed(old);
      else throw new IllegalStateException("Member id " + newcomer.self.id + " is already in the group");
    }
    final View next = latest.with(newcomer.self);
    latest = next;
    final MemberId who = newcomer.self;
    final String broker = newcomer.brokerName;
    for (final GroupMember each : live.values())
      each.task.post(() -> each.change(GroupMember.JOIN, who, next, broker));
    live.put(newcomer.self.id, newcomer);
    newcomer.newcomer(next); // we are on the newcomer's task
  }
}
