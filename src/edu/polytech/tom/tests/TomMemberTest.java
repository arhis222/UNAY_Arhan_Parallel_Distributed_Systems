package edu.polytech.tom.tests;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import edu.polytech.actors.Actor;
import edu.polytech.groups.Group;
import edu.polytech.groups.MemberId;
import edu.polytech.groups.View;
import edu.polytech.tom.TomMember;

/*
 * TEST of TomMember on a Group simulated in memory. The test decides when each member
 * installs a view and when the packets of a link arrive, to make some members late.
 */
public class TomMemberTest {

  static class Packet {
    int from, to;
    byte[] data;
  }

  static class Node implements Group {
    final MemberId self;
    final List<String> delivered = new ArrayList<String>();
    final TomMember tom;
    Listener listener;
    View installed;
    boolean alive = true;

    Node(int id) {
      self = new MemberId(id, 1);
      tom = new TomMember(this);
      tom.setListener((sender, incarnation, sequence, payload) -> delivered.add(new String(payload)));
    }

    public MemberId self() { return self; }
    public View view() { return installed; }
    public void setListener(Listener l) { listener = l; }
    public void admit(Actor newcomer, int id, JoinListener l) { throw new UnsupportedOperationException(); }
    public void leave() { throw new UnsupportedOperationException(); }

    public void send(int to, byte[] packet) {
      if (!alive || installed == null || !installed.contains(to))
        return;

      Packet p = new Packet();
      p.from = self.id;
      p.to = to;
      p.data = packet.clone();
      network.add(p);
    }
  }

  static final List<Packet> network = new ArrayList<Packet>(); // in the order they were sent
  static final Map<Integer, Node> nodes = new HashMap<Integer, Node>();
  static final Set<String> blocked = new HashSet<String>(); // the links whose packets are late

  static void check(boolean ok, String what) {
    if (!ok)
      throw new AssertionError("FAILED: " + what);
    System.out.println("OK: " + what);
  }

  // the constructor of View is not public
  static View view(int number, int... ids) throws Exception {
    List<MemberId> members = new ArrayList<MemberId>();
    for (int id : ids)
      members.add(new MemberId(id, 1));

    Constructor<View> c = View.class.getDeclaredConstructor(long.class, Collection.class);
    c.setAccessible(true);
    return c.newInstance((long) number, members);
  }

  static void install(int id, View v) {
    nodes.get(id).installed = v;
    nodes.get(id).listener.viewInstalled(v);
  }

  static void start(int... ids) throws Exception {
    network.clear();
    nodes.clear();
    blocked.clear();
    for (int id : ids)
      nodes.put(id, new Node(id));
    for (int id : ids)
      install(id, view(1, ids));
  }

  static void join(int id, View v) {
    nodes.put(id, new Node(id));
    install(id, v);
  }

  static void multicast(int id, String text) {
    nodes.get(id).tom.multicast(text.getBytes());
  }

  static void kill(int id) {
    nodes.get(id).alive = false;
  }

  static void block(int from, int to) {
    blocked.add(from + ">" + to);
  }

  static void unblock(int from, int to) {
    blocked.remove(from + ">" + to);
  }

  static void deliverAll() {
    boolean given = true;

    while (given) {
      given = false;
      for (Packet p : network) {
        Node to = nodes.get(p.to);
        if (!blocked.contains(p.from + ">" + p.to) && to.alive && to.installed != null && to.installed.contains(p.from)) {
          network.remove(p);
          to.listener.received(p.from, p.data);
          given = true;
          break;
        }
      }
    }
  }

  static List<String> got(int id) {
    return nodes.get(id).delivered;
  }

  static void testLateMemberAfterDeath() throws Exception {
    start(1, 2, 3);
    multicast(1, "a");
    deliverAll();
    kill(3);

    install(1, view(2, 1, 2));
    multicast(1, "b");
    deliverAll();
    check(got(1).equals(Arrays.asList("a")) && got(2).equals(Arrays.asList("a")),
        "b waits for member 2, that did not install the view yet");

    install(2, view(2, 1, 2));
    deliverAll();
    check(got(1).equals(Arrays.asList("a", "b")) && got(2).equals(Arrays.asList("a", "b")),
        "members 1 and 2 deliver b when member 2 installed the view");
  }

  static void testJoinWithLateMembers() throws Exception {
    start(1, 2, 3);
    multicast(1, "a");
    deliverAll();

    join(4, view(2, 1, 2, 3, 4));
    install(1, view(2, 1, 2, 3, 4));
    multicast(1, "b");
    deliverAll();
    check(got(1).equals(Arrays.asList("a")) && got(4).isEmpty(), "b waits for members 2 and 3, that are late");

    install(2, view(2, 1, 2, 3, 4));
    install(3, view(2, 1, 2, 3, 4));
    deliverAll();
    check(got(1).equals(Arrays.asList("a", "b")) && got(2).equals(got(1)) && got(3).equals(got(1)),
        "members 1, 2 and 3 deliver a and b");
    check(got(4).equals(Arrays.asList("b")), "member 4 delivers b only, a was sent before it joined");
  }

  static void testOlderMessageComesFirst() throws Exception {
    start(1, 2, 3);
    multicast(1, "p");
    block(1, 3);

    join(4, view(2, 1, 2, 3, 4));
    install(1, view(2, 1, 2, 3, 4));
    install(2, view(2, 1, 2, 3, 4));
    multicast(2, "q");
    deliverAll();
    install(3, view(2, 1, 2, 3, 4));
    deliverAll();
    check(got(1).isEmpty() && got(2).isEmpty(), "q is acknowledged by all but waits for p, that member 3 did not get");

    unblock(1, 3);
    deliverAll();
    check(got(1).equals(Arrays.asList("p", "q")) && got(2).equals(got(1)) && got(3).equals(got(1)),
        "members 1, 2 and 3 deliver p then q");
    check(got(4).equals(Arrays.asList("q")), "member 4 delivers q only");
  }

  static void testMemberRemovedWhileOlderMessageWaits() throws Exception {
    start(1, 2, 3);
    multicast(1, "p");
    block(1, 3);
    deliverAll();
    kill(3);

    join(4, view(2, 1, 2, 3, 4));
    install(1, view(2, 1, 2, 3, 4));
    install(2, view(2, 1, 2, 3, 4));
    install(1, view(3, 1, 2, 4));
    install(2, view(3, 1, 2, 4));
    install(4, view(3, 1, 2, 4));
    deliverAll();
    check(got(1).equals(Arrays.asList("p")) && got(2).equals(Arrays.asList("p")),
        "members 1 and 2 deliver p, member 3 is not waited for any more");
    check(got(4).isEmpty(), "member 4 does not deliver p");
  }

  public static void main(String[] args) throws Exception {
    System.out.println("==== TOM Member TEST ====");
    testLateMemberAfterDeath();
    testJoinWithLateMembers();
    testOlderMessageComesFirst();
    testMemberRemovedWhileOlderMessageWaits();
    System.out.println("TomMemberTest: all tests passed");
    System.exit(0);
  }
}
