package edu.polytech.tom.tests;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import edu.polytech.actors.Actor;
import edu.polytech.groups.Group;
import edu.polytech.groups.GroupManager;
import edu.polytech.groups.View;
import edu.polytech.queues.Task;
import edu.polytech.tom.TaskClock;
import edu.polytech.tom.TomMember;
import edu.polytech.utils.Executor;

/*
 * TEST of TomMember with real Actors and a real Group: failures, a member that
 * comes back, joins and leaves. Members are numbered from 1.
 */
public class TomGroupTest {

  static final Task driver = Executor.self().newTask("tom-test-driver");
  static final int MAX = 10; // the biggest member number

  static Actor[] actors = new Actor[MAX + 1];
  static Group[] groups = new Group[MAX + 1];
  static TomMember[] members = new TomMember[MAX + 1];
  static View[] views = new View[MAX + 1]; // the last view of each member
  static List<List<String>> delivered = new ArrayList<List<String>>(); // "sender.incarnation#sequence:text"

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

  // a member starts, with a new list: a member that comes back keeps nothing of its old life
  static void start(int id, Group group) {
    final List<String> mine = Collections.synchronizedList(new ArrayList<String>());
    delivered.set(id, mine);
    groups[id] = group;
    views[id] = null;
    members[id] = new TomMember(group);
    members[id].setViewListener(view -> views[id] = view);
    members[id].setListener((sender, incarnation, sequence, payload) ->
        mine.add(sender + "." + incarnation + "#" + sequence + ":" + new String(payload)));
  }

  // a group of n members, the name must be different for each group
  static void form(int n, String name) throws Exception {
    delivered.clear();
    for (int i = 0; i <= MAX; i++) {
      delivered.add(null);
      actors[i] = null;
      groups[i] = null;
      members[i] = null;
      views[i] = null;
    }

    onPump(() -> {
      GroupManager manager = new GroupManager(n);
      for (int i = 1; i <= n; i++) {
        final int me = i;
        actors[i] = new Actor(name + "-" + i, false);
        manager.join(actors[i], i, group -> start(me, group));
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

  // the actor joins the group, or comes back with the number of its old life, through the member "contact"
  static void admit(int contact, Actor actor, int id) {
    actors[id] = actor;
    views[id] = null;
    groups[contact].admit(actor, id, group -> start(id, group));
  }

  // true if this member sees exactly this group
  static boolean sees(int member, int... ids) {
    return views[member] != null && views[member].ids().toString().equals(Arrays.toString(ids));
  }

  // each of these members multicasts the message "prefix" followed by its number
  static void multicastAll(String prefix, int... who) throws Exception {
    onPump(() -> {
      for (int i : who)
        members[i].multicast((prefix + i).getBytes());
    });
  }

  static List<String> list(int member) {
    synchronized (delivered.get(member)) {
      return new ArrayList<String>(delivered.get(member));
    }
  }

  // the messages whose text starts with this prefix
  static List<String> only(List<String> entries, String prefix) {
    List<String> result = new ArrayList<String>();
    for (String e : entries) {
      if (e.substring(e.indexOf(':') + 1).startsWith(prefix))
        result.add(e);
    }
    return result;
  }

  // the small list is in the big one, in the same order
  static boolean inOrder(List<String> small, List<String> big) {
    int next = 0;
    for (String e : big) {
      if (next < small.size() && e.equals(small.get(next)))
        next++;
    }
    return next == small.size();
  }

  // no two messages of a member have the same identity
  static boolean uniqueIdentities(int member) {
    List<String> identities = new ArrayList<String>();
    for (String e : list(member))
      identities.add(e.substring(0, e.indexOf(':')));
    return new HashSet<String>(identities).size() == identities.size();
  }

  static boolean allDelivered(int count, int... who) {
    for (int member : who) {
      if (delivered.get(member).size() < count)
        return false;
    }
    return true;
  }

  static boolean sameMessages(int first, int... others) {
    for (int member : others) {
      if (!list(first).equals(list(member)))
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
    check(sameMessages(1, 2, 3), "same messages in the same order");
    check(list(1).contains("2.1#1:m2-1"), "a message comes with its sender, incarnation and sequence");
  }

  static void testSurvivorsDeliverTheSame() throws Exception {
    form(4, "survivors");

    onPump(() -> {
      for (int i = 1; i <= 4; i++)
        members[i].multicast(("m" + i).getBytes());
      actors[2].suicide();
    });

    check(waitFor(() -> allDelivered(3, 1, 3, 4) && sees(1, 1, 3, 4)), "members 1, 3 and 4 deliver after member 2 died");
    Thread.sleep(300);

    check(sameMessages(1, 3, 4), "the survivors deliver the same messages in the same order");
    check(list(1).toString().contains("m1") && list(1).toString().contains("m3") && list(1).toString().contains("m4"),
        "the survivors deliver their own messages");
  }

  // member 2 dies, comes back and is admitted again: it misses what was sent while it was dead
  static void testMemberComesBack() throws Exception {
    form(4, "back");
    multicastAll("a", 1, 2, 3, 4);
    check(waitFor(() -> allDelivered(4, 1, 2, 3, 4)), "the 4 members deliver a1 to a4");

    onPump(() -> actors[2].suicide());
    check(waitFor(() -> sees(1, 1, 3, 4) && sees(3, 1, 3, 4) && sees(4, 1, 3, 4)), "the others see that member 2 died");

    multicastAll("b", 1, 3, 4);
    check(waitFor(() -> allDelivered(7, 1, 3, 4)), "the others deliver b1, b3 and b4 while member 2 is dead");

    onPump(() -> admit(1, actors[2].resurrect(), 2));
    check(waitFor(() -> sees(1, 1, 2, 3, 4) && sees(2, 1, 2, 3, 4) && sees(3, 1, 2, 3, 4) && sees(4, 1, 2, 3, 4)),
        "member 2 is back in the group");

    multicastAll("c", 1, 2, 3, 4);
    check(waitFor(() -> allDelivered(11, 1, 3, 4) && allDelivered(4, 2)), "everybody delivers c1 to c4");
    Thread.sleep(200);

    check(sameMessages(1, 3, 4), "members 1, 3 and 4 deliver the same messages in the same order");
    check(list(2).equals(only(list(1), "c")), "member 2 delivers only the c messages, a and b stay missed");
    check(list(1).contains("2.2#0:c2"), "the message of member 2 has its new incarnation");
  }

  // a new member joins: it gets only what is sent after it joined
  static void testMemberJoins() throws Exception {
    form(3, "join");
    multicastAll("a", 1, 2, 3);
    check(waitFor(() -> allDelivered(3, 1, 2, 3)), "the 3 members deliver a1 to a3");

    onPump(() -> admit(3, new Actor("join-7", false), 7));
    check(waitFor(() -> sees(1, 1, 2, 3, 7) && sees(2, 1, 2, 3, 7) && sees(3, 1, 2, 3, 7) && sees(7, 1, 2, 3, 7)),
        "member 7 is in the group");

    multicastAll("b", 1, 2, 3, 7);
    check(waitFor(() -> allDelivered(7, 1, 2, 3) && allDelivered(4, 7)), "everybody delivers b1, b2, b3 and b7");
    Thread.sleep(200);

    check(sameMessages(1, 2, 3), "members 1, 2 and 3 deliver the same messages in the same order");
    check(list(7).equals(only(list(1), "b")), "member 7 delivers only the b messages, a stays missed");
  }

  // a member leaves: what it sent before leaving is delivered, then the others go on
  static void testMemberLeaves() throws Exception {
    form(4, "leave");
    multicastAll("a", 1, 2, 3, 4);
    check(waitFor(() -> allDelivered(4, 1, 2, 3, 4)), "the 4 members deliver a1 to a4");

    onPump(() -> {
      members[3].multicast("bye".getBytes());
      groups[3].leave();
    });
    check(waitFor(() -> sees(1, 1, 2, 4) && sees(2, 1, 2, 4) && sees(4, 1, 2, 4)), "the others see that member 3 left");

    multicastAll("b", 1, 2, 4);
    check(waitFor(() -> allDelivered(8, 1, 2, 4)), "the others deliver bye and b1, b2, b4");
    Thread.sleep(200);

    check(sameMessages(1, 2, 4), "members 1, 2 and 4 deliver the same messages in the same order");
    check(list(1).contains("3.1#1:bye"), "the message sent before leaving is delivered");
    check(only(list(3), "b").isEmpty(), "member 3 delivers nothing after it left");
  }

  // every member multicasts one message, the next ones come after the period (0 is as fast as possible)
  // member 2 dies after the fifth
  static void tick(int k, int period) {
    for (int i = 1; i <= 4; i++) {
      if (!actors[i].dead())
        members[i].multicast(("m" + i + "-" + k).getBytes());
    }

    if (k == 4)
      actors[2].suicide();
    if (k < 9)
      driver.post(() -> tick(k + 1, period), period);
  }

  static void testLoadWithDeath(String name, int period) throws Exception {
    form(4, name);
    onPump(() -> tick(0, period));

    check(waitFor(() -> allDelivered(30, 1, 3, 4) && sees(1, 1, 3, 4)),
        "period " + period + " ms: members 1, 3 and 4 deliver their 30 messages after member 2 died");
    Thread.sleep(300);

    check(sameMessages(1, 3, 4), "period " + period + " ms: the survivors deliver the same messages in the same order");
    check(uniqueIdentities(1), "period " + period + " ms: no two messages have the same identity");
  }

  // member 7 joins while the others are sending
  static void tickJoin(int k) {
    for (int i : new int[] { 1, 2, 3, 7 }) {
      if (views[i] != null)
        members[i].multicast(("m" + i + "-" + k).getBytes());
    }

    if (k == 3)
      admit(1, new Actor("joinload-7", false), 7);
    if (k < 9)
      driver.post(() -> tickJoin(k + 1), 5);
  }

  static void testJoinWhileSending() throws Exception {
    form(3, "joinload");
    onPump(() -> tickJoin(0));

    check(waitFor(() -> sees(1, 1, 2, 3, 7) && sees(2, 1, 2, 3, 7) && sees(3, 1, 2, 3, 7) && sees(7, 1, 2, 3, 7)),
        "member 7 joined while the others were sending");
    Thread.sleep(500);
    multicastAll("z", 1, 2, 3, 7);

    check(waitFor(() -> list(1).toString().contains("z7") && list(7).toString().contains("z3")),
        "everybody delivers z1, z2, z3 and z7");
    Thread.sleep(300);

    check(sameMessages(1, 2, 3), "members 1, 2 and 3 deliver the same messages in the same order");
    check(inOrder(list(7), list(1)), "member 7 delivers some of them, in the same order");
    check(only(list(7), "z").size() == 4, "member 7 delivers all the z messages");
  }

  // the clock of a member follows the clock of the running task
  static void testTaskClock() throws Exception {
    final boolean[] ok = new boolean[4];

    onPump(() -> {
      TaskClock clock = new TaskClock();
      long first = clock.tick();
      long second = clock.tick();
      ok[0] = second > first && first >= Task.task().clock();

      long received = clock.receive(1000);
      ok[1] = received > 1000 && Task.task().clock() > 1000;
      ok[2] = clock.tick() > received;
      ok[3] = new TaskClock().tick() >= Task.task().clock();
    });

    check(ok[0], "two ticks in the same runnable give two numbers, not below the clock of the task");
    check(ok[1], "a packet with the timestamp 1000 moves the member and the task after 1000");
    check(ok[2], "the next tick is after it");
    check(ok[3], "a new clock does not start below the clock of the task");
  }

  public static void main(String[] args) {
    try {
      System.out.println("==== TOM Group TEST ====");
      testTaskClock();
      testEveryoneDelivers();
      testSurvivorsDeliverTheSame();
      testMemberComesBack();
      testMemberJoins();
      testMemberLeaves();
      testLoadWithDeath("load5", 5);
      testLoadWithDeath("load0", 0);
      testJoinWhileSending();
    } catch (Throwable failure) {
      failure.printStackTrace();
      System.out.println("FAIL");
      System.exit(1);
    }
    System.out.println("TomGroupTest: all tests passed");
    System.exit(0);
  }
}
