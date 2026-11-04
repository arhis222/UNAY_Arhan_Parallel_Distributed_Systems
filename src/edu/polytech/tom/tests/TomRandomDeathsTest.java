package edu.polytech.tom.tests;

import static edu.polytech.tom.tests.TomProcessTest.dead;
import static edu.polytech.tom.tests.TomProcessTest.delivered;
import static edu.polytech.tom.tests.TomProcessTest.loseLastPackets;
import static edu.polytech.tom.tests.TomProcessTest.procs;
import static edu.polytech.tom.tests.TomProcessTest.setup;
import static edu.polytech.tom.tests.TomProcessTest.step;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

/*
 * TEST with random deaths, it uses the fake network of TomProcessTest.
 * Some members die at random moments while they send
 */
public class TomRandomDeathsTest {

  static class Death {
    int member;
    int diesAt;
    boolean happened;
    int[] toldAt;
    boolean[] told;

    Death(int member, int members, Random random) {
      this.member = member;
      diesAt = random.nextInt(40);
      toldAt = new int[members];
      told = new boolean[members];

      for (int i = 0; i < members; i++)
        toldAt[i] = diesAt + random.nextInt(40);
    }

    boolean finished() {
      if (!happened)
        return false;

      for (boolean t : told) {
        if (!t)
          return false;
      }
      return true;
    }
  }

  static String runOnce(long seed) {
    Random random = new Random(seed);
    int members = 3 + random.nextInt(3);
    int perMember = 1 + random.nextInt(3);
    setup(members);

    List<Integer> order = new ArrayList<Integer>();
    for (int i = 0; i < members; i++)
      order.add(i);
    Collections.shuffle(order, random);

    List<Death> deaths = new ArrayList<Death>();
    int howMany = 1 + random.nextInt(members - 1);
    for (int i = 0; i < howMany; i++)
      deaths.add(new Death(order.get(i), members, random));

    for (int i = 0; i < members; i++) {
      for (int k = 0; k < perMember; k++)
        procs[i].multicast(("m" + i + "-" + k).getBytes());
    }

    for (int time = 0; time < 100000; time++) {
      boolean allFinished = true;

      for (Death d : deaths) {
        if (!d.happened && time >= d.diesAt) {
          loseLastPackets(d.member, random);
          dead[d.member] = true;
          d.happened = true;
        }

        for (int i = 0; i < members && d.happened; i++) {
          if (!d.told[i] && time >= d.toldAt[i]) {
            d.told[i] = true;
            if (!dead[i])
              procs[i].memberDied(d.member);
          }
        }

        if (!d.finished())
          allFinished = false;
      }

      if (!step(random) && allFinished)
        break;
    }

    List<Integer> alive = new ArrayList<Integer>();
    for (int i = 0; i < members; i++) {
      if (!dead[i])
        alive.add(i);
    }

    // the members still alive deliver the same messages in the same order
    List<String> first = delivered.get(alive.get(0));
    for (int member : alive) {
      if (!first.equals(delivered.get(member)))
        return "seed " + seed + ": members " + alive.get(0) + " and " + member + " deliver different messages, "
            + first + " and " + delivered.get(member);
    }

    // no message delivered twice
    if (new HashSet<String>(first).size() != first.size())
      return "seed " + seed + ": a message is delivered twice, " + first;

    // the messages of the members still alive are not lost
    for (int member : alive) {
      for (int k = 0; k < perMember; k++) {
        if (!first.contains("m" + member + "-" + k))
          return "seed " + seed + ": message m" + member + "-" + k + " is lost, they delivered " + first;
      }
    }
    return null;
  }

  public static void main(String[] args) {
    int runs = 2000;

    for (int seed = 0; seed < runs; seed++) {
      String problem = runOnce(seed);
      if (problem != null)
        throw new AssertionError("FAILED: " + problem);
    }
    System.out.println("==== TOM Random Deaths TEST ====");
    System.out.println("OK: " + runs + " runs with random deaths");
    System.out.println("TomRandomDeathsTest: all tests passed");
  }
}
