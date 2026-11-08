package edu.polytech.groups;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import edu.polytech.actors.Actor;
import edu.polytech.queues.Task;
import edu.polytech.utils.Executor;

/**
 * Group manager: forms the initial group of N actors.
 *
 * It waits for 'initialSize' members, sends them the first view, each member
 * links to every other member through MessageQueues, then the manager starts
 * the group (viewInstalled on every member). From then on the group is
 * peer-to-peer and the manager is no longer used: failures, joins, leaves and
 * reinsertions are handled by Membership and GroupMember.
 */
public final class GroupManager {
  private static int instances;

  private final String prefix;
  private final Task task;
  private final int initialSize;
  private final Map<Integer, GroupMember> members = new TreeMap<Integer, GroupMember>();
  private View first;
  private int ready;

  public GroupManager(int initialSize) {
    Executor.check();
    if (initialSize < 1) throw new IllegalArgumentException("Group size must be positive");
    this.initialSize = initialSize;
    prefix = "group" + (++instances);
    task = Executor.self().newTask(prefix + "-manager");
  }

  /**
   * Asks 'actor' to be one of the N initial members, under number 'id'. Call on
   * the event pump. To join a running group, use Group.admit instead.
   */
  public void join(Actor actor, int id, Group.JoinListener listener) {
    Executor.check();
    if (actor == null || listener == null) throw new NullPointerException();
    if (actor.dead()) throw new IllegalStateException("Dead actor");
    MemberId self = new MemberId(id, actor.incarnation());
    final GroupMember member = new GroupMember(actor, self,
        prefix + "-member-" + id + "-" + self.incarnation, prefix, listener);
    actor.setListener((dead, cause) -> {
      if (member.membership != null) member.membership.failed(member); // perfect failure detector
      else task.post(() -> diedBeforeStart(member));                  // formation not finished
    });
    member.open(() -> task.post(() -> registered(member)));
  }

  private void registered(GroupMember m) {
    if (first != null) throw new IllegalStateException("The initial group is already formed: use Group.admit");
    if (members.containsKey(m.self.id)) throw new IllegalArgumentException("Duplicate member id " + m.self.id);
    members.put(m.self.id, m);
    if (members.size() < initialSize) return;

    List<MemberId> ids = new ArrayList<MemberId>();
    final Map<Integer, String> brokers = new HashMap<Integer, String>();
    for (GroupMember each : members.values()) {
      ids.add(each.self);
      brokers.put(each.self.id, each.brokerName);
    }
    final View v = new View(1, ids);
    first = v;
    for (final GroupMember each : members.values())
      each.task.post(() -> each.connect(v, brokers, () -> task.post(() -> ready(each))));
  }

  private void ready(GroupMember m) {
    if (++ready < first.size()) return;
    // From now on the group is peer-to-peer: the manager is no longer used.
    Membership membership = new Membership(first, members.values());
    final View v = first;
    for (final GroupMember each : members.values()) {
      each.membership = membership;
      each.task.post(() -> each.start(v));
    }
  }

  /** Before formation only: forget a member that died while registering. */
  private void diedBeforeStart(GroupMember m) {
    if (members.get(m.self.id) == m) members.remove(m.self.id);
  }
}
