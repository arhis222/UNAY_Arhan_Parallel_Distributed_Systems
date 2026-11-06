package edu.polytech.tom.tests;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import edu.polytech.actors.Actor;
import edu.polytech.groups.GroupManager;
import edu.polytech.groups.View;
import edu.polytech.queues.Task;
import edu.polytech.tom.TomMember;
import edu.polytech.utils.Executor;

/*
 * TEST of TomMember with real Actors and a real Group.
 * Members are numbered from 1.
 */
public class TomGroupTest {

  static final Task driver = Executor.self().newTask("tom-test-driver");

  static Actor[] actors;
  static TomMember[] members;
  static View[] views; // the last view of each member
  static List<List<String>> delivered;

  static void check(boolean ok, String what) {
    if (!ok)
      throw new AssertionError("FAILED: " + what);
    System.out.println("OK: " + what);
  }

  static void onPump(Runnable action) throws Exception {
    CompletableFuture<Void> done = new CompletableFuture<Void>();
    driver.post(() -> {
      try {
        action.run();
        done.complete(null);
      } catch (Throwable failure) {
        done.completeExceptionally(failure);
      }
    });
    done.get(5, TimeUnit.SECONDS);
  }

  static boolean waitFor(Supplier<Boolean> condition) throws Exception {
    for (int i = 0; i < 250; i++) {
      final boolean[] ok = new boolean[1];
      onPump(() -> ok[0] = condition.get());
      if (ok[0])
        return true;
      Thread.sleep(20);
    }
    return false;
  }

  // a group of n members, the name must be different for each group
  static void form(int n, String name) throws Exception {
    actors = new Actor[n + 1];
    members = new TomMember[n + 1];
    views = new View[n + 1];
    delivered = new ArrayList<List<String>>();
    delivered.add(null);

    onPump(() -> {
      GroupManager manager = new GroupManager(n);
      for (int i = 1; i <= n; i++) {
        final int me = i;
        final List<String> mine = Collections.synchronizedList(new ArrayList<String>());
        delivered.add(mine);
        actors[i] = new Actor(name + "-" + i, false);
        manager.join(actors[i], i, group -> {
          members[me] = new TomMember(group);
          members[me].setViewListener(view -> views[me] = view);
          members[me].setListener((sender, incarnation, sequence, payload) ->
              mine.add(sender + "." + incarnation + "#" + sequence + ":" + new String(payload)));
        });
      }
    });

    check(waitFor(() -> {
      for (int i = 1; i <= n; i++) {
        if (views[i] == null)
          return false;
      }
      return true;
    }), "the group of " + n + " members is formed");
  }

  static boolean allDelivered(int count, int... who) {
    for (int member : who) {
      if (delivered.get(member).size() < count)
        return false;
    }
    return true;
  }

  static void testEveryoneDelivers() throws Exception {
    form(3, "everyone");

    onPump(() -> {
      for (int i = 1; i <= 3; i++) {
        members[i].multicast(("m" + i + "-0").getBytes());
        members[i].multicast(("m" + i + "-1").getBytes());
      }
    });

    check(waitFor(() -> allDelivered(6, 1, 2, 3)), "the 3 members deliver the 6 messages");
    check(delivered.get(1).equals(delivered.get(2)) && delivered.get(1).equals(delivered.get(3)),
        "same messages in the same order");
    check(delivered.get(1).contains("2.1#1:m2-1"), "a message comes with its sender, incarnation and sequence");
  }

  static void testSurvivorsDeliverTheSame() throws Exception {
    form(4, "survivors");

    onPump(() -> {
      for (int i = 1; i <= 4; i++)
        members[i].multicast(("m" + i).getBytes());
      actors[2].suicide();
    });

    check(waitFor(() -> allDelivered(3, 1, 3, 4)), "members 1, 3 and 4 deliver after member 2 died");
    Thread.sleep(300);

    check(delivered.get(1).equals(delivered.get(3)) && delivered.get(1).equals(delivered.get(4)),
        "the survivors deliver the same messages in the same order");
    check(waitFor(() -> views[1].ids().size() == 3 && !views[1].contains(2)), "member 1 sees the view without member 2");
    check(delivered.get(1).toString().contains("m1") && delivered.get(1).toString().contains("m3")
        && delivered.get(1).toString().contains("m4"), "the survivors deliver their own messages");
  }

  public static void main(String[] args) {
    try {
      System.out.println("==== TOM Group TEST ====");
      testEveryoneDelivers();
      testSurvivorsDeliverTheSame();
    } catch (Throwable failure) {
      failure.printStackTrace();
      System.out.println("FAIL");
      System.exit(1);
    }
    System.out.println("TomGroupTest: all tests passed");
    System.exit(0);
  }
}
