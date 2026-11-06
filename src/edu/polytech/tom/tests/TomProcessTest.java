package edu.polytech.tom.tests;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.Set;

import edu.polytech.tom.TomProcess;

/*
 * TEST for TomProcess class
 * Members are TomProcess objects connected by a fake network in memory.
 */
public class TomProcessTest {

  static void check(boolean ok, String what) {
    if (!ok)
      throw new AssertionError("FAILED: " + what);
    System.out.println("OK: " + what);
  }

  // for a test that runs many seeds: stop at the first one that goes wrong, say nothing otherwise
  static void checkSeed(boolean ok, int seed, String what) {
    if (!ok)
      throw new AssertionError("FAILED: " + what + " (seed " + seed + ")");
  }

  static class Link {
    int to;
    LinkedList<byte[]> packets = new LinkedList<byte[]>();
  }

  static int n;
  static Link[] links;
  static TomProcess[] procs;
  static List<List<String>> delivered;
  static boolean[] dead; // members killed for the TEST

  static void setup(int members) {
    n = members;
    dead = new boolean[n];
    links = new Link[n * n];
    for (int from = 0; from < n; from++) {
      for (int to = 0; to < n; to++) {
        Link l = new Link();
        l.to = to;
        links[from * n + to] = l;
      }
    }

    Set<Integer> ids = new HashSet<Integer>();
    for (int i = 0; i < n; i++)
      ids.add(i);

    procs = new TomProcess[n];
    delivered = new ArrayList<List<String>>();
    for (int i = 0; i < n; i++) {
      final int me = i;
      final List<String> mine = new ArrayList<String>();
      delivered.add(mine);
      procs[i] = new TomProcess(i, 1, ids, (to, packet) -> links[me * n + to].packets.add(packet));
      procs[i].setListener((sender, incarnation, sequence, payload) -> mine.add(new String(payload)));
    }
  }

  static boolean step(Random random) {
    List<Link> waiting = new ArrayList<Link>();

    for (Link l : links) {
      if (!l.packets.isEmpty())
        waiting.add(l);
    }

    if (waiting.isEmpty())
      return false;

    Link l = waiting.get(random.nextInt(waiting.size()));
    byte[] packet = l.packets.removeFirst();

    if (!dead[l.to])
      procs[l.to].onPacket(packet);
    return true;
  }

  static void run(Random random) {
    while (step(random))
      ;
  }

  // the member die
  static void kill(int victim) {
    dead[victim] = true;

    for (int i = 0; i < n; i++) {
      if (!dead[i])
        procs[i].memberDied(victim);
    }
  }

  static void loseLastPackets(int victim, Random random) {
    for (int to = 0; to < n; to++) {
      LinkedList<byte[]> packets = links[victim * n + to].packets;
      int keep = random.nextInt(packets.size() + 1);

      while (packets.size() > keep)
        packets.removeLast();
    }
  }

  static void killWhileSending(int victim, Random random) {
    loseLastPackets(victim, random);
    kill(victim);
  }

  static void deliverNext(int from, int to) {
    byte[] packet = links[from * n + to].packets.removeFirst();

    if (!dead[to])
      procs[to].onPacket(packet);
  }

  static void stepSome(Random random, int max) {
    for (int k = random.nextInt(max); k > 0; k--)
      step(random);
  }

  static boolean sameDelivered(int count, int... members) {
    List<String> first = delivered.get(members[0]);

    if (first.size() != count)
      return false;

    for (int member : members) {
      if (!first.equals(delivered.get(member)))
        return false;
    }
    return true;
  }

  static void testOneSender() {
    setup(3);
    procs[0].multicast("a".getBytes());
    procs[0].multicast("b".getBytes());
    procs[0].multicast("c".getBytes());
    run(new Random(1));

    for (int i = 0; i < n; i++)
      check(delivered.get(i).toString().equals("[a, b, c]"), "member " + i + " delivers a, b, c in order");
  }

  static void testSeveralSenders() {
    for (int seed = 0; seed < 100; seed++) {
      setup(3);
      for (int i = 0; i < n; i++) {
        procs[i].multicast(("m" + i + "-1").getBytes());
        procs[i].multicast(("m" + i + "-2").getBytes());
      }
      run(new Random(seed));

      List<String> first = delivered.get(0);
      boolean same = first.size() == 6;
      for (int i = 1; i < n; i++)
        same = same && first.equals(delivered.get(i));
      if (!same)
        check(false, "same messages in the same order everywhere (seed " + seed + ")");
    }
    check(true, "Same messages in the same order everywhere");
  }

  static void testAckArrivesBeforeData() {
    setup(3);
    procs[0].multicast("a".getBytes());
    deliverNext(0, 1); // member 1 gets the DATA and acknowledges it to everybody
    deliverNext(1, 2); // member 2 gets this ACK, and has no DATA yet
    check(delivered.get(2).isEmpty(), "member 2 delivers nothing with an ACK and no DATA");

    run(new Random(1));
    for (int i = 0; i < n; i++)
      check(delivered.get(i).toString().equals("[a]"), "member " + i + " delivers a, its ACK came before the DATA");
  }

  // multicast returns the sequence number
  static void testIdentity() {
    setup(3);
    final List<List<String>> identities = new ArrayList<List<String>>();

    for (int i = 0; i < n; i++) {
      final List<String> mine = new ArrayList<String>();
      identities.add(mine);
      procs[i].setListener((sender, incarnation, sequence, payload) ->
          mine.add(sender + "." + incarnation + "#" + sequence + ":" + new String(payload)));
    }

    long first = procs[1].multicast("a".getBytes());
    long second = procs[1].multicast("b".getBytes());
    run(new Random(1));
    check(first == 0 && second == 1, "multicast returns the sequence number: 0 then 1");

    for (int i = 0; i < n; i++)
      check(identities.get(i).toString().equals("[1.1#0:a, 1.1#1:b]"), "member " + i + " delivers a and b with their identities");
  }

  static void testFiveMembers() {
    for (int seed = 0; seed < 50; seed++) {
      setup(5);
      for (int i = 0; i < n; i++) {
        for (int k = 1; k <= 3; k++)
          procs[i].multicast(("m" + i + "-" + k).getBytes());
      }
      run(new Random(seed));

      checkSeed(sameDelivered(15, 0, 1, 2, 3, 4), seed, "5 members deliver the same 15 messages in the same order");
    }
    check(true, "5 members, 3 messages each: same messages in the same order everywhere");
  }

  static void testManyMessagesAtOnce() {
    for (int seed = 0; seed < 20; seed++) {
      setup(3);
      for (int k = 0; k < 20; k++) {
        for (int i = 0; i < n; i++)
          procs[i].multicast(("m" + i + "-" + k).getBytes());
      }
      run(new Random(seed));

      checkSeed(sameDelivered(60, 0, 1, 2), seed, "3 members deliver the same 60 messages in the same order");
    }
    check(true, "60 messages sent at once: same messages in the same order everywhere");
  }

  static void checkSurvivors(int seed) {
    checkSeed(sameDelivered(2, 0, 1), seed, "members 0 and 1 deliver a and b in the same order");
  }

  static void testMemberDiesBeforeAck() {
    for (int seed = 0; seed < 100; seed++) {
      setup(3);
      procs[0].multicast("a".getBytes());
      procs[1].multicast("b".getBytes());
      kill(2);
      run(new Random(seed));
      checkSurvivors(seed);
    }
    check(true, "member 2 dies before ACK: members 0 and 1 still deliver");
  }

  static void testMemberDiesLater() {
    for (int seed = 0; seed < 100; seed++) {
      setup(3);
      Random random = new Random(seed);
      procs[0].multicast("a".getBytes());
      procs[1].multicast("b".getBytes());
      stepSome(random, 15);
      kill(2);
      run(random);
      checkSurvivors(seed);
    }
    check(true, "member 2 dies at a random moment: members 0 and 1 still deliver");
  }

  static void testMemberDiesAfterDelivery() {
    setup(3);
    procs[0].multicast("a".getBytes());
    procs[1].multicast("b".getBytes());
    run(new Random(1));
    kill(2);
    procs[0].multicast("c".getBytes());
    run(new Random(2));

    check(delivered.get(0).toString().equals("[a, b, c]"), "member 0 delivers a, b, c");
    check(delivered.get(1).toString().equals("[a, b, c]"), "member 1 delivers a, b, c");
    check(delivered.get(2).toString().equals("[a, b]"), "member 2 delivered a and b before it died");
  }

  static void testSenderDiesWhileSending() {
    for (int seed = 0; seed < 100; seed++) {
      setup(3);
      Random random = new Random(seed);
      procs[0].multicast("m".getBytes());
      procs[1].multicast("a".getBytes());
      procs[2].multicast("b".getBytes());
      links[0 * n + 2].packets.clear();
      dead[0] = true;

      stepSome(random, 10);
      procs[1].memberDied(0);
      stepSome(random, 10);
      procs[2].memberDied(0);
      run(random);

      List<String> first = delivered.get(1);
      boolean ok = sameDelivered(first.size(), 1, 2) && first.contains("a") && first.contains("b")
          && (first.size() == 2 || first.contains("m"));
      checkSeed(ok, seed, "members 1 and 2 deliver the same messages after the sender died");
    }
    check(true, "member 0 dies while sending: members 1 and 2 deliver the same messages");
  }

  static void testTwoMembersDie() {
    for (int seed = 0; seed < 100; seed++) {
      setup(4);
      Random random = new Random(seed);
      for (int i = 0; i < n; i++)
        procs[i].multicast(("m" + i).getBytes());

      stepSome(random, 10);
      killWhileSending(3, random);
      stepSome(random, 10);
      killWhileSending(2, random);
      run(random);

      List<String> first = delivered.get(0);
      boolean ok = sameDelivered(first.size(), 0, 1) && first.contains("m0") && first.contains("m1");
      checkSeed(ok, seed, "members 0 and 1 deliver the same messages after two deaths");
    }
    check(true, "members 3 and 2 die: members 0 and 1 deliver the same messages");
  }

  static void testSurvivorDiesWhileOthersWait() {
    for (int seed = 0; seed < 100; seed++) {
      setup(4);
      Random random = new Random(seed);
      for (int i = 0; i < n; i++)
        procs[i].multicast(("m" + i).getBytes());
      links[0 * n + 2].packets.clear();
      links[0 * n + 3].packets.clear();
      dead[0] = true;

      stepSome(random, 10);
      procs[1].memberDied(0); 
      stepSome(random, 10);
      killWhileSending(1, random);
      stepSome(random, 10);
      procs[2].memberDied(0);
      procs[3].memberDied(0);
      run(random);

      List<String> first = delivered.get(2);
      boolean ok = sameDelivered(first.size(), 2, 3) && first.contains("m2") && first.contains("m3");
      checkSeed(ok, seed, "members 2 and 3 deliver the same messages when a survivor dies while they wait");
    }
    check(true, "a survivor dies while the others wait for its LEFTOVER: members 2 and 3 deliver the same messages");
  }

  public static void main(String[] args) {
	System.out.println("==== TOM Process TEST ====");
    testOneSender();
    testSeveralSenders();
    testAckArrivesBeforeData();
    testIdentity();
    testFiveMembers();
    testManyMessagesAtOnce();
    testMemberDiesBeforeAck();
    testMemberDiesLater();
    testMemberDiesAfterDelivery();
    testSenderDiesWhileSending();
    testTwoMembersDie();
    testSurvivorDiesWhileOthersWait();
    System.out.println("TomProcessTest: all tests passed");
  }
}
