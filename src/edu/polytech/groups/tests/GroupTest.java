package edu.polytech.groups.tests;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import edu.polytech.actors.Actor;
import edu.polytech.groups.Group;
import edu.polytech.groups.GroupManager;
import edu.polytech.groups.View;
import edu.polytech.queues.Task;
import edu.polytech.utils.Executor;

/** Group formation and failure tests (steps 1 and 2). Run as a Java application. */
public final class GroupTest {
  static final Task driver = Executor.self().newTask("group-test-driver");

  static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  static void onPump(Runnable action) throws Exception {
    CompletableFuture<Void> done = new CompletableFuture<Void>();
    driver.post(() -> {
      try { action.run(); done.complete(null); }
      catch (Throwable failure) { done.completeExceptionally(failure); }
    });
    done.get(5, TimeUnit.SECONDS);
  }

  static void await(CountDownLatch latch, String what) throws Exception {
    check(latch.await(5, TimeUnit.SECONDS), "Timed out: " + what);
  }

  static void pause(int ms) throws Exception {
    CountDownLatch done = new CountDownLatch(1);
    driver.post(done::countDown, ms);
    await(done, "pause");
  }

  /** Records what one member observes: "view <id> <ids>" or "<from>:<text>". */
  static final class Probe implements Group.Listener {
    final List<String> events = Collections.synchronizedList(new ArrayList<String>());
    final CountDownLatch started = new CountDownLatch(1);
    volatile Group group;

    @Override
    public void viewInstalled(View view) {
      events.add("view " + view.viewId + " " + view.ids());
      started.countDown();
    }

    @Override
    public void received(int from, byte[] packet) {
      events.add(from + ":" + new String(packet));
    }

    List<String> snapshot() {
      synchronized (events) { return new ArrayList<String>(events); }
    }

    long count(String prefix) {
      return snapshot().stream().filter(e -> e.startsWith(prefix)).count();
    }
  }

  static Probe[] form(int n, Actor[] actors, String name) throws Exception {
    Probe[] probes = new Probe[n + 1];
    for (int i = 1; i <= n; i++) probes[i] = new Probe();
    onPump(() -> {
      GroupManager manager = new GroupManager(n);
      for (int i = 1; i <= n; i++) {
        final Probe probe = probes[i];
        actors[i] = new Actor(name + "-" + i, false);
        manager.join(actors[i], i, group -> {
          probe.group = group;
          group.setListener(probe);
        });
      }
    });
    for (int i = 1; i <= n; i++) await(probes[i].started, "group formation");
    return probes;
  }

  static void sendToAll(Group g, String text) {
    for (int to : g.view().ids()) g.send(to, text.getBytes());
  }

  /** Every member gets the same first view, and packets flow between all members. */
  private static void formation() throws Exception {
    int n = 4;
    Actor[] actors = new Actor[n + 1];
    Probe[] probes = form(n, actors, "formation");
    for (int i = 1; i <= n; i++) {
      check(probes[i].snapshot().get(0).equals("view 1 [1, 2, 3, 4]"), "Wrong first view at " + i);
      check(probes[i].group.self().id == i && probes[i].group.self().incarnation == 1, "Wrong identity");
    }
    onPump(() -> { for (int i = 1; i <= n; i++) sendToAll(probes[i].group, "hello" + i); });
    pause(100);
    for (int i = 1; i <= n; i++) {
      List<String> got = probes[i].snapshot();
      check(got.size() == 1 + n, "Member " + i + " got " + got);
      for (int from = 1; from <= n; from++)
        check(got.contains(from + ":hello" + from), "Member " + i + " missed hello" + from);
    }
    System.out.println("formation: OK");
  }

  /**
   * Member 2 multicasts 3 packets in one callback and dies. Every survivor gets
   * the 3 packets, then exactly one new view without 2, and nothing from 2 after.
   */
  private static void failure() throws Exception {
    int n = 4;
    Actor[] actors = new Actor[n + 1];
    Probe[] probes = form(n, actors, "failure");
    onPump(() -> {
      for (int k = 0; k < 3; k++) sendToAll(probes[2].group, "x" + k);
      actors[2].suicide();
    });
    pause(100);
    for (int i : new int[] { 1, 3, 4 }) {
      List<String> got = probes[i].snapshot();
      List<String> expected = Arrays.asList("view 1 [1, 2, 3, 4]", "2:x0", "2:x1", "2:x2", "view 2 [1, 3, 4]");
      check(got.equals(expected), "Member " + i + " got " + got);
    }
    check(probes[2].count("2:") == 0, "Dead member received its own packets after death");

    // Traffic to the dead member is dropped; survivors keep talking.
    onPump(() -> {
      probes[1].group.send(2, "lost".getBytes());
      sendToAll(probes[1].group, "after");
    });
    pause(100);
    for (int i : new int[] { 1, 3, 4 })
      check(probes[i].count("1:after") == 1 && probes[i].count("view") == 2, "Member " + i + ": " + probes[i].snapshot());
    check(probes[2].snapshot().size() == 1, "Dead member got something: " + probes[2].snapshot());
    System.out.println("failure without reinsertion: OK");
  }

  /**
   * A large backlog: member 2 sends 100 packets in one callback and dies. Every
   * survivor receives all 100 packets before the view without 2.
   */
  private static void failureWithBacklog() throws Exception {
    int n = 3;
    Actor[] actors = new Actor[n + 1];
    Probe[] probes = form(n, actors, "backlog");
    onPump(() -> {
      for (int k = 0; k < 100; k++) sendToAll(probes[2].group, "y" + k);
      actors[2].suicide();
    });
    pause(200);
    for (int i : new int[] { 1, 3 }) {
      List<String> got = probes[i].snapshot();
      check(got.size() == 102, "Member " + i + " got " + got.size() + " events");
      for (int k = 0; k < 100; k++) check(got.get(1 + k).equals("2:y" + k), "Member " + i + " event " + (1 + k) + ": " + got.get(1 + k));
      check(got.get(101).equals("view 2 [1, 3]"), "Member " + i + " last event " + got.get(101));
    }
    System.out.println("failure with a backlog of 100 packets: OK");
  }

  /** Two failures in a row: survivors install views 2 and 3, in that order. */
  private static void twoFailures() throws Exception {
    int n = 5;
    Actor[] actors = new Actor[n + 1];
    Probe[] probes = form(n, actors, "two-failures");
    onPump(() -> {
      sendToAll(probes[4].group, "a");
      actors[4].suicide();
      sendToAll(probes[2].group, "b");
      actors[2].suicide();
    });
    pause(100);
    for (int i : new int[] { 1, 3, 5 }) {
      List<String> views = new ArrayList<String>();
      for (String e : probes[i].snapshot()) if (e.startsWith("view")) views.add(e);
      check(views.equals(Arrays.asList("view 1 [1, 2, 3, 4, 5]", "view 2 [1, 2, 3, 5]", "view 3 [1, 3, 5]")),
          "Member " + i + " views " + views);
      check(probes[i].count("4:a") == 1 && probes[i].count("2:b") == 1, "Member " + i + ": " + probes[i].snapshot());
    }
    System.out.println("two failures: OK");
  }

  public static void main(String[] args) {
    try {
      formation();
      failure();
      failureWithBacklog();
      twoFailures();
    } catch (Throwable failure) {
      failure.printStackTrace();
      System.out.println("FAIL");
      System.exit(1);
    }
    System.out.println("All group tests passed");
    System.exit(0);
  }
}
