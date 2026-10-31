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

  static void setup(int members) {
    n = members;
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

  static void run(Random random) {
    while (true) {
      List<Link> waiting = new ArrayList<Link>();
      for (Link l : links) {
        if (!l.packets.isEmpty())
          waiting.add(l);
      }
      if (waiting.isEmpty())
        return;

      Link l = waiting.get(random.nextInt(waiting.size()));
      procs[l.to].onPacket(l.packets.removeFirst());
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

  public static void main(String[] args) {
    testOneSender();
    testSeveralSenders();
    System.out.println("TomProcessTest: all tests passed");
  }
}
