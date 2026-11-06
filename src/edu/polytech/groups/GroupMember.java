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
import edu.polytech.actors.Actor;
import edu.polytech.queues.MessageQueue;
import edu.polytech.queues.QueueBroker;
import edu.polytech.queues.Task;
import edu.polytech.utils.Executor;

/**
 * One member's endpoint. Everything runs on the member's group task, which
 * belongs to its Actor, so nothing runs here after the Actor's death.
 *
 * View changes are queued in 'pending' and installed in order, each one when
 * its condition holds (see ready()). This is what keeps every member's view
 * of the packets consistent across a change.
 */
final class GroupMember implements Group {
  static final int PORT = 4242;
  static final int FAIL = 0, LEAVE = 1, JOIN = 2, SELF = 3;
  private static final byte HELLO = 0;
  private static final byte DATA = 1;

  /** A view change waiting to be installed. */
  private static final class Change {
    final int type;
    final MemberId who;
    final View view;

    Change(int type, MemberId who, View view) {
      this.type = type;
      this.who = who;
      this.view = view;
    }
  }

  final Actor actor;
  final MemberId self;
  final String brokerName;
  final Task task;
  private final String prefix;
  private final Group.JoinListener joinListener;
  Membership membership; // set when the group starts, or at creation for a newcomer

  private QueueBroker broker;
  private final Map<MemberId, MessageQueue> links = new HashMap<MemberId, MessageQueue>();
  private final List<MessageQueue> queues = new ArrayList<MessageQueue>(); // every queue, identified or not
  private final Set<MemberId> closed = new HashSet<MemberId>();      // links whose closed() was reported
  private final Set<MemberId> connecting = new HashSet<MemberId>();  // connect() not answered yet
  private final Set<MemberId> leaving = new HashSet<MemberId>();     // FAIL or LEAVE queued
  private final ArrayDeque<Change> pending = new ArrayDeque<Change>();
  private final List<Object[]> early = new ArrayList<Object[]>();   // packets kept until a view is installed
  private View installed;
  private boolean joined;
  private boolean left;
  private Listener listener;

  // Initial formation only (see GroupManager).
  private View forming;
  private boolean readySent;
  private Runnable onLinksReady;

  GroupMember(Actor actor, MemberId self, String brokerName, String prefix, Group.JoinListener joinListener) {
    this.actor = actor;
    this.self = self;
    this.brokerName = brokerName;
    this.prefix = prefix;
    this.joinListener = joinListener;
    this.task = actor.newTask("group");
  }

  /** Creates the broker and binds the port, then runs 'then' on this task. */
  void open(Runnable then) {
    task.post(() -> {
      broker = Task.task().newBroker(brokerName);
      broker.bind(PORT, new QueueBroker.BindListener() {
        @Override
        public void accepted(MessageQueue queue) {
          queues.add(queue);
          if (left) queue.close();
          else queue.setListener(new Link(queue, null)); // identified by its HELLO
        }

        @Override public void unbound() { }
      });
      then.run();
    });
  }

  // ------------------------------------------------------------ step 1: formation

  /** Links to every member with a smaller id; the others link to me. */
  void connect(View v, Map<Integer, String> brokers, Runnable linksReady) {
    forming = v;
    onLinksReady = linksReady;
    for (MemberId m : v.members)
      if (m.id < self.id) connectTo(m, brokers.get(m.id));
    checkReady();
  }

  private void checkReady() {
    if (forming == null || readySent || links.size() < forming.size() - 1) return;
    readySent = true;
    onLinksReady.run();
  }

  /** Installs the first view, sent by the manager. */
  void start(View first) {
    forming = null;
    install(first);
  }

  // ------------------------------------------------------------ view changes

  /** A change from Membership: FAIL, LEAVE or JOIN of 'who'. */
  void change(int type, MemberId who, View next, String newcomerBroker) {
    if (left) return;
    if (type == JOIN) connectTo(who, newcomerBroker); // old members link to the newcomer
    else leaving.add(who);
    pending.addLast(new Change(type, who, next));
    tryInstall();
  }

  /** I am the newcomer: my first view is 'first', once every old member linked to me. */
  void newcomer(View first) {
    pending.addLast(new Change(SELF, self, first));
    tryInstall();
  }

  private void tryInstall() {
    while (!left && !pending.isEmpty()) {
      Change c = pending.peekFirst();
      if (!ready(c)) return;
      pending.removeFirst();
      if (c.type == FAIL || c.type == LEAVE) {
        links.remove(c.who);
        closed.remove(c.who);
        leaving.remove(c.who);
      }
      install(c.view);
    }
  }

  /** The condition to install a change. */
  private boolean ready(Change c) {
    switch (c.type) {
    case SELF: // a link from every old member, except those already gone
      for (MemberId m : c.view.members)
        if (!m.equals(self) && !links.containsKey(m) && !leaving.contains(m)) return false;
      return true;
    case JOIN: // my connect() to the newcomer was answered
      return !connecting.contains(c.who);
    default: // FAIL or LEAVE: every packet 'who' sent me has been received
      // A link 'who' opened to me is known (HELLO) before this change arrives:
      // see "Accepted links" in the design document.
      if (connecting.contains(c.who)) return false;
      return !links.containsKey(c.who) || closed.contains(c.who);
    }
  }

  private void install(View v) {
    installed = v;
    if (!joined) {
      joined = true;
      joinListener.joined(this);
    }
    if (listener != null) listener.viewInstalled(v);
    List<Object[]> kept = new ArrayList<Object[]>(early);
    early.clear();
    for (Object[] p : kept) received((MemberId) p[0], (byte[]) p[1]);
  }

  // ------------------------------------------------------------ links

  private void connectTo(final MemberId peer, String peerBroker) {
    connecting.add(peer);
    broker.connect(peerBroker, PORT, new QueueBroker.ConnectListener() {
      @Override
      public void connected(MessageQueue queue) {
        connecting.remove(peer);
        queues.add(queue);
        if (left) {
          queue.close();
        } else {
          queue.setListener(new Link(queue, peer));
          byte[] hello = ByteBuffer.allocate(9).put(HELLO).putInt(self.id).putInt(self.incarnation).array();
          queue.send(hello, 0, hello.length, null);
          links.put(peer, queue);
          checkReady();
        }
        tryInstall();
      }

      @Override
      public void refused() { // the peer died first
        connecting.remove(peer);
        tryInstall();
      }
    });
  }

  private void received(MemberId from, byte[] packet) {
    if (left) return;
    if (installed != null && installed.members.contains(from)) {
      if (listener != null) listener.received(from.id, packet);
    } else if (installed == null || !pending.isEmpty()) {
      early.add(new Object[] { from, packet }); // 'from' may be in a view not installed yet
    }
    // otherwise: not a member, drop
  }

  private final class Link implements MessageQueue.Listener {
    private final MessageQueue queue;
    private MemberId peer; // null until the HELLO on accepted queues

    Link(MessageQueue queue, MemberId peer) {
      this.queue = queue;
      this.peer = peer;
    }

    @Override
    public void received(byte[] frame) {
      if (frame[0] == HELLO) {
        ByteBuffer b = ByteBuffer.wrap(frame, 1, 8);
        peer = new MemberId(b.getInt(), b.getInt());
        links.put(peer, queue);
        checkReady();
        tryInstall();
      } else if (peer != null) {
        GroupMember.this.received(peer, Arrays.copyOfRange(frame, 1, frame.length));
      }
    }

    @Override
    public void closed() {
      if (peer == null) return;
      closed.add(peer);
      tryInstall();
    }
  }

  // ------------------------------------------------------------ Group

  @Override public MemberId self() { return self; }
  @Override public View view() { return left ? null : installed; }

  @Override
  public void setListener(Listener listener) {
    Executor.check();
    this.listener = listener;
  }

  @Override
  public void send(int to, byte[] packet) {
    Executor.check();
    if (left || installed == null || actor.dead()) return;
    MemberId target = installed.member(to);
    if (target == null) return; // not in my view: dropped
    if (target.equals(self)) {
      final byte[] copy = packet.clone();
      task.post(() -> received(self, copy));
      return;
    }
    MessageQueue queue = links.get(target);
    if (queue == null) return;
    byte[] frame = new byte[packet.length + 1];
    frame[0] = DATA;
    System.arraycopy(packet, 0, frame, 1, packet.length);
    queue.send(frame, 0, frame.length, null); // false if the peer is gone: dropped
  }

  @Override
  public void admit(Actor newcomer, int id, Group.JoinListener listener) {
    Executor.check();
    if (left || installed == null) throw new IllegalStateException("Not a member of a running group");
    if (newcomer == null || listener == null) throw new NullPointerException();
    if (newcomer.dead()) throw new IllegalStateException("Dead actor");
    MemberId who = new MemberId(id, newcomer.incarnation());
    final Membership group = membership;
    final GroupMember m = new GroupMember(newcomer, who, group.brokerName(prefix, who), prefix, listener);
    String refusal = group.reserve(m);
    if (refusal != null) throw new IllegalArgumentException(refusal);
    m.membership = group;
    newcomer.setListener((dead, cause) -> group.failed(m)); // perfect failure detector
    m.open(() -> group.joined(m));
  }

  @Override
  public void leave() {
    Executor.check();
    if (left || installed == null) return;
    membership.left(this); // the others are told first
    left = true;
    for (MessageQueue q : queues) q.close(); // they receive what I sent, then closed()
    queues.clear();
    links.clear();
    pending.clear();
    early.clear();
    broker.unbind(PORT);
  }
}
