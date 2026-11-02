package edu.polytech.groups;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import edu.polytech.actors.Actor;
import edu.polytech.queues.MessageQueue;
import edu.polytech.queues.QueueBroker;
import edu.polytech.queues.Task;
import edu.polytech.utils.Executor;

/**
 * Group manager.
 *
 * Step 1 (initial formation): waits for 'initialSize' members, sends them the
 * first view, each member links to every other member through MessageQueues,
 * then the manager starts the group (viewInstalled on every member).
 *
 * After formation the group is peer-to-peer: the manager is no longer used.
 *
 * Step 2 (failures, no reinsertion): a perfect failure detector (Actor.Listener,
 * same JVM) tells every survivor directly about a death. Each survivor computes
 * the next view itself and installs it only once its link to the dead member
 * is closed. MessageQueue reports closed() after the last buffered
 * packet, and a callback runs entirely or not at all, so every survivor has
 * processed exactly the same packets from the dead member before the change.
 *
 * Not supported yet: failures during formation, joins, leaves, reinsertion.
 * Note: the manager uses the Actor's (single) Actor.Listener.
 */
public final class GroupManager {
  static final int PORT = 4242;
  private static final byte HELLO = 0;
  private static final byte DATA = 1;
  private static int instances;

  private final String prefix;
  private final Task task;
  private final int initialSize;
  private final Map<Integer, Member> members = new TreeMap<Integer, Member>();
  private View view; // first view, built by the manager
  private int ready;
  private volatile boolean started; // set once the group is peer-to-peer

  public GroupManager(int initialSize) {
    Executor.check();
    if (initialSize < 1) throw new IllegalArgumentException("Group size must be positive");
    this.initialSize = initialSize;
    prefix = "group" + (++instances);
    task = Executor.self().newTask(prefix + "-manager");
  }

  /** Asks 'actor' to join the group under number 'id'. Call on the event pump. */
  public void join(Actor actor, int id, Group.JoinListener listener) {
    Executor.check();
    if (actor == null || listener == null) throw new NullPointerException();
    if (actor.dead()) throw new IllegalStateException("Dead actor");
    final Member member = new Member(actor, new MemberId(id, actor.incarnation()), listener);
    actor.setListener((dead, cause) -> {
      if (started) detected(member);                // peer-to-peer failure detection
      else task.post(() -> diedBeforeStart(member)); // formation not finished
    });
    member.start();
  }

  // ---------------------------------------------------------------- manager task

  private void registered(Member m) {
    if (view != null) throw new IllegalStateException("Joining a formed group is not supported yet");
    if (members.containsKey(m.self.id)) throw new IllegalArgumentException("Duplicate member id " + m.self.id);
    members.put(m.self.id, m);
    if (members.size() < initialSize) return;

    List<MemberId> ids = new ArrayList<MemberId>();
    Map<Integer, String> brokers = new HashMap<Integer, String>();
    for (Member each : members.values()) {
      ids.add(each.self);
      brokers.put(each.self.id, each.brokerName);
    }
    final View first = new View(1, ids);
    view = first;
    for (final Member each : members.values())
      each.task.post(() -> each.connect(first, brokers));
  }

  private void ready(Member m) {
    if (++ready < view.size()) return;
    final View first = view;
    final List<Member> group = new ArrayList<Member>(members.values());
    // From now on the group is peer-to-peer: the manager is no longer used.
    for (final Member each : group) {
      each.peers = group;
      each.task.post(() -> each.install(first));
    }
    started = true;
  }

  /** Before formation only: forget a member that died while registering. */
  private void diedBeforeStart(Member m) {
    if (members.get(m.self.id) == m) members.remove(m.self.id);
  }

  /**
   * Perfect failure detector, after formation. Runs on the dead Actor's monitor
   * task (not on the manager). The runtime runs one callback at a time and tasks
   * are FIFO, so every survivor is told about deaths in the same order.
   */
  private static void detected(Member dead) {
    for (final Member each : dead.peers)
      if (each != dead) each.task.post(() -> each.failed(dead.self.id)); // ignored if 'each' is dead
  }

  // ---------------------------------------------------------------- member side

  /** One member's endpoint. Everything below runs on the member's group task. */
  private final class Member implements Group {
    final Actor actor;
    final MemberId self;
    final String brokerName;
    final Group.JoinListener joinListener;
    final Task task;

    private QueueBroker broker;
    private final Map<Integer, MessageQueue> links = new HashMap<Integer, MessageQueue>();
    private final Set<Integer> closedLinks = new HashSet<Integer>();
    private final ArrayDeque<Object[]> pendingFailures = new ArrayDeque<Object[]>();
    private final List<Object[]> early = new ArrayList<Object[]>();
    private View forming;
    private View installed;
    private View latest;          // installed view, or the last pending one
    private List<Member> peers;   // all members of view 1, set at start
    private boolean readySent;
    private boolean joined;
    private Listener listener;

    Member(Actor actor, MemberId self, Group.JoinListener joinListener) {
      this.actor = actor;
      this.self = self;
      // Broker names are never freed, so each incarnation needs its own name.
      this.brokerName = prefix + "-member-" + self.id + "-" + self.incarnation;
      this.joinListener = joinListener;
      this.task = actor.newTask("group");
    }

    void start() {
      task.post(() -> {
        broker = Task.task().newBroker(brokerName);
        broker.bind(PORT, new QueueBroker.BindListener() {
          @Override public void accepted(MessageQueue queue) { queue.setListener(new Link(queue, -1)); }
          @Override public void unbound() { }
        });
        GroupManager.this.task.post(() -> registered(this));
      });
    }

    /** Step 1: link to every member with a smaller id; the others link to me. */
    void connect(View v, Map<Integer, String> brokers) {
      forming = v;
      for (MemberId m : v.members) {
        if (m.id >= self.id) continue;
        final int peer = m.id;
        broker.connect(brokers.get(peer), PORT, new QueueBroker.ConnectListener() {
          @Override
          public void connected(MessageQueue queue) {
            queue.setListener(new Link(queue, peer));
            byte[] hello = ByteBuffer.allocate(5).put(HELLO).putInt(self.id).array();
            queue.send(hello, 0, hello.length, null);
            linkUp(peer, queue);
          }

          @Override
          public void refused() {
            Executor.failStop("Member " + self + " could not connect to member " + peer);
          }
        });
      }
      checkReady();
    }

    private void linkUp(int peer, MessageQueue queue) {
      links.put(peer, queue);
      checkReady();
    }

    private void checkReady() {
      if (forming == null || readySent || links.size() < forming.size() - 1) return;
      readySent = true;
      GroupManager.this.task.post(() -> ready(this));
    }

    void install(View v) {
      installed = v;
      if (latest == null || v.viewId > latest.viewId) latest = v;
      forming = null;
      if (!joined) {
        joined = true;
        joinListener.joined(this);
      }
      if (listener != null) listener.viewInstalled(v);
      List<Object[]> buffered = new ArrayList<Object[]>(early);
      early.clear();
      for (Object[] p : buffered) received((Integer) p[0], (byte[]) p[1]);
    }

    /**
     * Step 2: each survivor computes the next view itself, then installs it once
     * the dead member's link is drained (see the design document).
     */
    void failed(int dead) {
      if (!latest.contains(dead)) return;
      latest = latest.without(dead);
      pendingFailures.addLast(new Object[] { latest, dead });
      installFailures();
    }

    private void installFailures() {
      while (!pendingFailures.isEmpty()) {
        Object[] head = pendingFailures.peekFirst();
        int dead = (Integer) head[1];
        if (links.containsKey(dead) && !closedLinks.contains(dead)) return; // packets may remain
        pendingFailures.removeFirst();
        links.remove(dead);
        closedLinks.remove(dead);
        install((View) head[0]);
      }
    }

    private void received(int from, byte[] packet) {
      if (installed == null) {
        early.add(new Object[] { from, packet }); // the group is not started here yet
        return;
      }
      if (!installed.contains(from) || listener == null) return; // drop
      listener.received(from, packet);
    }

    // ------------------------------------------------------------------ Group

    @Override public MemberId self() { return self; }
    @Override public View view() { return installed; }

    @Override
    public void setListener(Listener listener) {
      Executor.check();
      this.listener = listener;
    }

    @Override
    public void send(int to, byte[] packet) {
      Executor.check();
      if (installed == null || !installed.contains(to) || actor.dead()) return;
      if (to == self.id) {
        final byte[] copy = packet.clone();
        task.post(() -> received(self.id, copy));
        return;
      }
      MessageQueue queue = links.get(to);
      if (queue == null) return;
      byte[] frame = new byte[packet.length + 1];
      frame[0] = DATA;
      System.arraycopy(packet, 0, frame, 1, packet.length);
      queue.send(frame, 0, frame.length, null); // returns false if the peer is dead: dropped
    }

    /** Listener of one peer-to-peer queue. */
    private final class Link implements MessageQueue.Listener {
      private final MessageQueue queue;
      private int peer; // -1 until the HELLO packet on accepted queues

      Link(MessageQueue queue, int peer) {
        this.queue = queue;
        this.peer = peer;
      }

      @Override
      public void received(byte[] frame) {
        if (frame[0] == HELLO) {
          peer = ByteBuffer.wrap(frame, 1, 4).getInt();
          linkUp(peer, queue);
        } else {
          Member.this.received(peer, Arrays.copyOfRange(frame, 1, frame.length));
        }
      }

      @Override
      public void closed() {
        if (peer < 0) return;
        closedLinks.add(peer);
        installFailures();
      }
    }
  }
}