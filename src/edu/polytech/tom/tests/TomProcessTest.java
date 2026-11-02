package edu.polytech.tom.tests;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
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
      procs[i].setListener((sender, payload) -> mine.add(new String(payload)));
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

  // members 0 and 1 multicast, member 2 dies and never multicasts anything
  static void checkSurvivors(int seed) {
    List<String> first = delivered.get(0);
    if (first.size() != 2 || !first.equals(delivered.get(1)))
      check(false, "members 0 and 1 deliver a and b in the same order (seed " + seed + ")");
  }

  static void testOneDiesBeforeAck() {
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

  static void testOneDiesLater() {
    for (int seed = 0; seed < 100; seed++) {
      setup(3);
      Random random = new Random(seed);
      procs[0].multicast("a".getBytes());
      procs[1].multicast("b".getBytes());
      for (int k = random.nextInt(15); k > 0; k--)
        step(random);
      kill(2);
      run(random);
      checkSurvivors(seed);
    }
    check(true, "member 2 dies at a random moment: members 0 and 1 still deliver");
  }

  // member 0 multicasts and dies, its DATA reached member 1 only.
  // Member 0 has the smallest id, so its message m comes first in the order
  static void testSenderDiesMidMulticast() {
    int deliveredM = 0;

    for (int seed = 0; seed < 100; seed++) {
      setup(3);
      Random random = new Random(seed);
      procs[0].multicast("m".getBytes());
      procs[1].multicast("a".getBytes());
      procs[2].multicast("b".getBytes());
      links[0 * n + 2].packets.clear();
      dead[0] = true;

      for (int k = random.nextInt(10); k > 0; k--)
        step(random);
      procs[1].memberDied(0);
      for (int k = random.nextInt(10); k > 0; k--)
        step(random);
      procs[2].memberDied(0);
      run(random);

      List<String> first = delivered.get(1);
      boolean ok = first.equals(delivered.get(2)) && first.contains("a") && first.contains("b");
      ok = ok && (first.size() == 2 || (first.size() == 3 && first.contains("m")));
      if (!ok)
        check(false, "members 1 and 2 agree after the sender died (seed " + seed + ")");

      if (first.contains("m"))
        deliveredM++;
    }
    check(true, "member 0 dies during its multicast: members 1 and 2 deliver the same messages ("
        + deliveredM + " times with its message, " + (100 - deliveredM) + " times without)");
  }

  public static void main(String[] args) {
    testOneSender();
    testSeveralSenders();
    testOneDiesBeforeAck();
    testOneDiesLater();
    testSenderDiesMidMulticast();
    System.out.println("TomProcessTest: all tests passed");
  }
}
