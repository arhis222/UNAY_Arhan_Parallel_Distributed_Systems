package edu.polytech.groups.tests;

import static edu.polytech.groups.tests.GroupTest.await;
import static edu.polytech.groups.tests.GroupTest.check;
import static edu.polytech.groups.tests.GroupTest.driver;
import static edu.polytech.groups.tests.GroupTest.form;
import static edu.polytech.groups.tests.GroupTest.onPump;
import static edu.polytech.groups.tests.GroupTest.pause;
import static edu.polytech.groups.tests.GroupTest.sendToAll;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import edu.polytech.actors.Actor;
import edu.polytech.groups.Group;
import edu.polytech.groups.GroupManager;
import edu.polytech.groups.MemberId;
import edu.polytech.groups.View;
import edu.polytech.groups.tests.GroupTest.Probe;
import edu.polytech.utils.Executor;

/**
 * Steps 3 and 4: reinsertion, voluntary joins and leaves, then random
 * scenarios (explicit and random failures) checked automatically.
 * Run as a Java application. Optional arguments: sending period in ms
 * (default: runs with 5 ms, then 0 ms burst mode) and random seed.
 */
public final class GroupDynamicTest {

  private static List<String> views(Probe p) {
    List<String> views = new ArrayList<String>();
    for (String e : p.snapshot()) if (e.startsWith("view")) views.add(e);
    return views;
  }

  private static Probe admit(Group contact, Actor actor, int id) {
    Probe probe = new Probe();
    contact.admit(actor, id, group -> {
      probe.group = group;
      group.setListener(probe);
    });
    return probe;
  }

  /** A dead member is resurrected and reinserted; it misses what was sent while it was dead. */
  private static void reinsertion() throws Exception {
    Actor[] actors = new Actor[5];
    Probe[] p = form(4, actors, "reinsert");
    onPump(() -> actors[2].suicide());
    pause(50);
    onPump(() -> { for (int i : new int[] { 1, 3, 4 }) sendToAll(p[i].group, "during" + i); });
    pause(50);
    Probe[] back = new Probe[1];
    onPump(() -> back[0] = admit(p[1].group, actors[2].resurrect(), 2));
    await(back[0].started, "reinsertion");
    pause(50);

    check(back[0].group.self().incarnation == 2, "Wrong incarnation " + back[0].group.self());
    check(back[0].snapshot().equals(Arrays.asList("view 3 [1, 2, 3, 4]")), "Reinserted got " + back[0].snapshot());
    for (int i : new int[] { 1, 3, 4 }) {
      check(views(p[i]).equals(Arrays.asList("view 1 [1, 2, 3, 4]", "view 2 [1, 3, 4]", "view 3 [1, 2, 3, 4]")),
          "Member " + i + " views " + views(p[i]));
      check(p[i].group.view().member(2).incarnation == 2, "Old incarnation still in the view");
    }
    p[2] = back[0];
    onPump(() -> { for (int i = 1; i <= 4; i++) sendToAll(p[i].group, "after" + i); });
    pause(50);
    for (int i = 1; i <= 4; i++)
      for (int from = 1; from <= 4; from++)
        check(p[i].count(from + ":after" + from) == 1, "Member " + i + " missed after" + from + ": " + p[i].snapshot());
    check(back[0].count("1:during") + back[0].count("3:during") + back[0].count("4:during") == 0,
        "Reinserted member received a packet sent while it was dead");
    System.out.println("reinsertion: OK");
  }

  /** Death, resurrection and reinsertion in one callback, before the detector runs. */
  private static void quickReinsertion() throws Exception {
    Actor[] actors = new Actor[4];
    Probe[] p = form(3, actors, "quick");
    Probe[] back = new Probe[1];
    onPump(() -> {
      actors[2].suicide();
      back[0] = admit(p[1].group, actors[2].resurrect(), 2);
    });
    await(back[0].started, "quick reinsertion");
    pause(50);
    for (int i : new int[] { 1, 3 })
      check(views(p[i]).equals(Arrays.asList("view 1 [1, 2, 3]", "view 2 [1, 3]", "view 3 [1, 2, 3]")),
          "Member " + i + " views " + views(p[i]));
    check(views(back[0]).equals(Arrays.asList("view 3 [1, 2, 3]")), "Reinserted views " + views(back[0]));
    System.out.println("quick reinsertion: OK");
  }

  /** A new actor joins; it does not get what was sent before its first view. */
  private static void join() throws Exception {
    Actor[] actors = new Actor[4];
    Probe[] p = form(3, actors, "join");
    Probe[] n = new Probe[1];
    onPump(() -> {
      sendToAll(p[1].group, "before");
      n[0] = admit(p[3].group, new Actor("join-7", false), 7);
    });
    await(n[0].started, "join");
    pause(50);
    for (int i = 1; i <= 3; i++)
      check(views(p[i]).equals(Arrays.asList("view 1 [1, 2, 3]", "view 2 [1, 2, 3, 7]")), "Member " + i + " views " + views(p[i]));
    check(n[0].snapshot().equals(Arrays.asList("view 2 [1, 2, 3, 7]")), "Newcomer got " + n[0].snapshot());
    onPump(() -> { sendToAll(n[0].group, "hi"); sendToAll(p[2].group, "welcome"); });
    pause(50);
    for (Probe q : new Probe[] { p[1], p[2], p[3], n[0] })
      check(q.count("7:hi") == 1 && q.count("2:welcome") == 1, "Missing packets: " + q.snapshot());
    boolean refused = false;
    try { onPump(() -> admit(p[1].group, new Actor("join-dup", false), 7)); }
    catch (Exception e) { refused = true; }
    check(refused, "Duplicate id accepted");
    System.out.println("join: OK");
  }

  /** A member leaves: the others get its last packets, then a view without it. */
  private static void leave() throws Exception {
    Actor[] actors = new Actor[5];
    Probe[] p = form(4, actors, "leave");
    onPump(() -> {
      sendToAll(p[3].group, "bye");
      p[3].group.leave();
    });
    pause(50);
    for (int i : new int[] { 1, 2, 4 })
      check(p[i].snapshot().equals(Arrays.asList("view 1 [1, 2, 3, 4]", "3:bye", "view 2 [1, 2, 4]")),
          "Member " + i + " got " + p[i].snapshot());
    onPump(() -> { p[1].group.send(3, "lost".getBytes()); sendToAll(p[1].group, "after"); });
    pause(50);
    check(p[3].snapshot().equals(Arrays.asList("view 1 [1, 2, 3, 4]")), "Member that left got " + p[3].snapshot());
    check(p[3].group.view() == null, "Member that left still has a view");

    // The same actor rejoins, under a new id.
    Probe[] again = new Probe[1];
    onPump(() -> again[0] = admit(p[2].group, actors[3], 8));
    await(again[0].started, "rejoin");
    check(views(again[0]).equals(Arrays.asList("view 3 [1, 2, 4, 8]")), "Rejoined views " + views(again[0]));
    System.out.println("leave and rejoin: OK");
  }

  /**
   * A newcomer sends as soon as it joins, while the old members are still
   * draining a dead member's backlog: they keep its packet until they install
   * the view that contains it, instead of dropping it.
   */
  private static void joinDuringFailure() throws Exception {
    Actor[] actors = new Actor[4];
    Probe[] p = form(3, actors, "drain");
    Probe n = new Probe();
    onPump(() -> {
      for (int k = 0; k < 100; k++) sendToAll(p[2].group, "y" + k);
      actors[2].suicide();
      p[1].group.admit(new Actor("drain-9", false), 9, group -> {
        n.group = group;
        group.setListener(n);
        sendToAll(group, "hi"); // in my first view, before anything else
      });
    });
    await(n.started, "join during failure");
    pause(200);
    for (int i : new int[] { 1, 3 }) {
      List<String> got = p[i].snapshot();
      check(got.size() == 104, "Member " + i + " got " + got.size() + " events, ending with "
          + got.subList(Math.max(0, got.size() - 3), got.size()));
      for (int k = 0; k < 100; k++) check(got.get(1 + k).equals("2:y" + k), "Member " + i + " event " + (1 + k));
      check(got.subList(101, 104).equals(Arrays.asList("view 2 [1, 3]", "view 3 [1, 3, 9]", "9:hi")),
          "Member " + i + " end: " + got.subList(101, got.size()));
    }
    check(n.snapshot().equals(Arrays.asList("view 3 [1, 3, 9]", "9:hi")), "Newcomer got " + n.snapshot());
    System.out.println("join while a failure is draining: OK");
  }

  /** Join, failure and leave at the same time: everybody installs the same views. */
  private static void concurrentChanges() throws Exception {
    Actor[] actors = new Actor[6];
    Probe[] p = form(5, actors, "concurrent");
    Probe[] n = new Probe[1];
    onPump(() -> {
      sendToAll(p[4].group, "z");
      n[0] = admit(p[1].group, new Actor("concurrent-6", false), 6);
      sendToAll(p[2].group, "last");
      actors[2].suicide();
      p[5].group.leave();
    });
    await(n[0].started, "concurrent join");
    pause(100);
    List<String> reference = views(p[1]);
    check(reference.get(reference.size() - 1).equals("view 4 [1, 3, 4, 6]"), "Final view " + reference);
    for (int i : new int[] { 3, 4 })
      check(views(p[i]).equals(reference), "Member " + i + " views " + views(p[i]) + " vs " + reference);
    List<String> newcomer = views(n[0]);
    check(reference.subList(reference.size() - newcomer.size(), reference.size()).equals(newcomer),
        "Newcomer views " + newcomer + " vs " + reference);
    for (int i : new int[] { 1, 3, 4 })
      check(p[i].count("4:z") == 1 && p[i].count("2:last") == 1, "Member " + i + ": " + p[i].snapshot());
    System.out.println("concurrent join, failure and leave: OK");
  }

  // ------------------------------------------------------------------ random scenarios

  /** Everything one endpoint observed: views and packets, in order. */
  private static final class Recorder implements Group.Listener {
    final List<Object[]> events = Collections.synchronizedList(new ArrayList<Object[]>());
    volatile Group group;
    volatile MemberId self;
    final Actor actor;

    Recorder(Actor actor) { this.actor = actor; }

    @Override public void viewInstalled(View v) { events.add(new Object[] { v.viewId, v.members.toString(), v }); }
    @Override public void received(int from, byte[] packet) { events.add(new Object[] { new String(packet) }); }

    List<Object[]> snapshot() {
      synchronized (events) { return new ArrayList<Object[]>(events); }
    }
  }

  private static final class Scenario {
    final int period;
    final Random random;
    final boolean randomFailures;
    final List<Recorder> recorders = Collections.synchronizedList(new ArrayList<Recorder>());
    final Map<Integer, Actor> actors = new HashMap<Integer, Actor>(); // by current id, on the pump only
    final List<Actor> dead = new ArrayList<Actor>();
    final Map<Actor, Integer> deadIds = new HashMap<Actor, Integer>();
    final List<Actor> out = new ArrayList<Actor>(); // left, alive
    // sender -> packets sent, each with the identities it was sent to
    final Map<String, List<String[]>> sent = new HashMap<String, List<String[]>>();
    volatile boolean stop;
    int nextId = 100;
    int actions;

    Scenario(int period, long seed, boolean randomFailures) {
      this.period = period;
      this.random = new Random(seed);
      this.randomFailures = randomFailures;
    }

    Group.JoinListener joiner(Actor actor) {
      Recorder r = new Recorder(actor);
      recorders.add(r);
      return group -> {
        r.group = group;
        r.self = group.self();
        group.setListener(r);
        actor.post(() -> sendLoop(actor, group, 0));
      };
    }

    /**
     * Every 'period' ms, multicasts a batch of 1 to 8 packets
     * "id#incarnation:viewId:seq" to the view. Batches leave packets in transit
     * when a member dies or leaves.
     */
    void sendLoop(Actor actor, Group group, int seq) {
      View v = group.view();
      if (stop || v == null || seq >= 300) return;
      int batch = 1 + (seq * 7 + group.self().id) % 8;
      for (int k = 0; k < batch; k++) {
        String text = group.self() + ":" + v.viewId + ":" + (seq + k);
        for (int to : v.ids()) group.send(to, text.getBytes());
        synchronized (sent) {
          List<String[]> mine = sent.get(String.valueOf(group.self()));
          if (mine == null) sent.put(String.valueOf(group.self()), mine = new ArrayList<String[]>());
          mine.add(new String[] { text, v.members.toString() });
        }
      }
      actor.post(() -> sendLoop(actor, group, seq + batch), period);
    }

    List<Recorder> live() {
      List<Recorder> live = new ArrayList<Recorder>();
      synchronized (recorders) {
        for (Recorder r : recorders)
          if (r.group != null && r.group.view() != null && !r.actor.dead()) live.add(r);
      }
      return live;
    }

    /** One random membership action, on the pump. */
    void step() {
      List<Recorder> live = live();
      for (Actor a : new ArrayList<Actor>(actors.values()))
        if (a.dead() && !dead.contains(a)) {
          dead.add(a);
          for (Map.Entry<Integer, Actor> e : actors.entrySet()) if (e.getValue() == a) deadIds.put(a, e.getKey());
        }
      if (live.size() < 2) return;
      Group contact = live.get(random.nextInt(live.size())).group;
      double r = random.nextDouble();
      if (r < 0.25 && live.size() > 2) {
        live.get(random.nextInt(live.size())).actor.suicide();
      } else if (r < 0.5 && !dead.isEmpty()) {
        Actor a = dead.remove(random.nextInt(dead.size()));
        int id = deadIds.remove(a);
        if (actors.get(id) != a) return; // id reused meanwhile
        Actor back = a.resurrect();
        actors.put(id, back);
        contact.admit(back, id, joiner(back));
      } else if (r < 0.65 && live.size() > 2) {
        Recorder leaving = live.get(random.nextInt(live.size()));
        leaving.group.leave();
        out.add(leaving.actor);
      } else if (r < 0.8 && !out.isEmpty()) {
        Actor a = out.remove(random.nextInt(out.size()));
        if (a.dead()) return;
        int id = nextId++;
        actors.values().remove(a); // one id per actor
        actors.put(id, a);
        contact.admit(a, id, joiner(a));
      } else if (r < 0.9) {
        int id = nextId++;
        Actor a = new Actor("random-" + id, randomFailures);
        actors.put(id, a);
        contact.admit(a, id, joiner(a));
      }
      actions++;
    }

    void run() throws Exception {
      int n = 5;
      onPump(() -> {
        GroupManager manager = new GroupManager(n);
        for (int i = 1; i <= n; i++) {
          Actor a = new Actor("random-" + i, randomFailures);
          actors.put(i, a);
          manager.join(a, i, joiner(a));
        }
      });
      pause(100);
      if (randomFailures) Executor.self().configureFailures(period == 0 ? 0.0001 : 0.0005, random.nextLong());
      CountDownLatch done = new CountDownLatch(1);
      Runnable[] loop = new Runnable[1];
      int[] steps = { 0 };
      loop[0] = () -> {
        if (++steps[0] > 40) { stop = true; done.countDown(); return; }
        step();
        driver.post(loop[0], 15);
      };
      driver.post(loop[0]);
      await(done, "scenario");
      Executor.self().configureFailures(0, 0);
      pause(300); // let every packet and view change arrive
      verify();
    }

    /** Checks the group guarantees on everything that was observed. */
    void verify() {
      Map<Long, String> viewContent = new HashMap<Long, String>();
      Map<String, Long> joinView = new HashMap<String, Long>();   // member -> first view containing it
      Map<String, Long> removeView = new HashMap<String, Long>(); // member -> first view without it
      List<List<Object[]>> logs = new ArrayList<List<Object[]>>();
      List<String> selves = new ArrayList<String>();
      int packets = 0;
      synchronized (recorders) {
        for (Recorder r : recorders) {
          logs.add(r.snapshot());
          selves.add(String.valueOf(r.self));
        }
      }

      for (int k = 0; k < logs.size(); k++) {
        long first = -1, last = -1;
        Map<String, Integer> lastSeq = new HashMap<String, Integer>();
        for (Object[] e : logs.get(k)) {
          if (e.length == 3) {
            long id = (Long) e[0];
            String members = (String) e[1];
            String known = viewContent.put(id, members);
            check(known == null || known.equals(members), "Two different views " + id + ": " + known + " / " + members);
            check(last < 0 || id == last + 1, "Views not consecutive at endpoint " + k + ": " + last + " -> " + id);
            if (first < 0) first = id;
            last = id;
          } else {
            packets++;
            String[] parts = ((String) e[0]).split(":");
            check(Long.parseLong(parts[1]) >= first, "Packet from view " + parts[1] + " received by a member whose first view is " + first);
            Integer previous = lastSeq.put(parts[0], Integer.parseInt(parts[2]));
            check(previous == null || previous < Integer.parseInt(parts[2]), "FIFO broken from " + parts[0]);
          }
        }
      }
      // Join and removal view of every identity.
      List<Long> ids = new ArrayList<Long>(viewContent.keySet());
      Collections.sort(ids);
      for (long id : ids) {
        String members = viewContent.get(id);
        for (String m : members.substring(1, members.length() - 1).split(", "))
          if (!joinView.containsKey(m)) joinView.put(m, id);
        for (String m : joinView.keySet())
          if (!removeView.containsKey(m) && !(", " + members.substring(1, members.length() - 1) + ", ").contains(", " + m + ", "))
            removeView.put(m, id);
      }
      // G5: endpoints present from X's first view to X's removal (or the end)
      // received exactly the same packets from X.
      long lastView = ids.get(ids.size() - 1);
      for (String x : joinView.keySet()) {
        long from = joinView.get(x);
        Long to = removeView.get(x);
        List<String> reference = null;
        for (int k = 0; k < logs.size(); k++) {
          List<Object[]> log = logs.get(k);
          boolean sawFrom = false, sawTo = false;
          List<String> fromX = new ArrayList<String>();
          for (Object[] e : log) {
            if (e.length == 3) {
              long id = (Long) e[0];
              if (id == from) sawFrom = true;
              if (to != null ? id == to : id == lastView) sawTo = true;
            } else if (((String) e[0]).startsWith(x + ":")) {
              fromX.add((String) e[0]);
            }
          }
          if (!sawTo || selves.get(k).equals(x)) continue;
          if (to == null && !endsAt(log, lastView)) continue;
          // Agreement, between the members present during X's whole membership.
          if (sawFrom) {
            if (reference == null) reference = fromX;
            else check(reference.equals(fromX), "Members disagree on packets from " + x + ":\n" + reference + "\n" + fromX);
          }
          // Completeness: every packet X sent to this member was received, in order.
          List<String> expected = new ArrayList<String>();
          synchronized (sent) {
            List<String[]> byX = sent.get(x);
            if (byX != null)
              for (String[] p : byX)
                if (p[1].contains("[" + selves.get(k) + ",") || p[1].contains(" " + selves.get(k) + ",")
                    || p[1].contains(" " + selves.get(k) + "]") || p[1].contains("[" + selves.get(k) + "]"))
                  expected.add(p[0]);
          }
          check(expected.equals(fromX), "Member " + selves.get(k) + " did not receive exactly what " + x + " sent it: expected "
              + expected.size() + " packets, got " + fromX.size());
        }
      }
      System.out.println("random scenario (period " + period + " ms" + (randomFailures ? ", random failures" : "")
          + "): OK - " + actions + " membership actions, " + ids.size() + " views, " + logs.size()
          + " endpoints, " + packets + " packets checked");
    }

    private boolean endsAt(List<Object[]> log, long view) {
      long last = -1;
      for (Object[] e : log) if (e.length == 3) last = (Long) e[0];
      return last == view;
    }
  }

  public static void main(String[] args) {
    try {
      reinsertion();
      quickReinsertion();
      join();
      leave();
      concurrentChanges();
      joinDuringFailure();
      long seed = args.length > 1 ? Long.parseLong(args[1]) : 42;
      int[] periods = args.length > 0 ? new int[] { Integer.parseInt(args[0]) } : new int[] { 5, 0 };
      for (int period : periods) {
        new Scenario(period, seed, false).run();
        new Scenario(period, seed + 1, true).run();
      }
    } catch (Throwable failure) {
      failure.printStackTrace();
      System.out.println("FAIL");
      System.exit(1);
    }
    System.out.println("All dynamic group tests passed");
    System.exit(0);
  }
}
