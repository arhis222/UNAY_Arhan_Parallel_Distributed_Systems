package edu.polytech.queues.local;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import edu.polytech.queues.MessageQueue;
import edu.polytech.queues.QueueBroker;
import edu.polytech.queues.Task;
import edu.polytech.utils.Executor;

public class CMessageQueue implements MessageQueue {
    private final CQueueBroker broker;
    private final ArrayDeque<byte[]> incoming = new ArrayDeque<>();
    private final Set<Task> registered = new HashSet<>();
    private CMessageQueue peer;
    private Listener listener;
    private Task listenerTask;
    private boolean closed;
    private boolean notified;
    private boolean scheduled;
    private int listenerVersion;

    private CMessageQueue(CQueueBroker broker) {
        this.broker = broker;
    }

    static CMessageQueue[] pair(CQueueBroker client, CQueueBroker server) {
        CMessageQueue a = new CMessageQueue(client);
        CMessageQueue b = new CMessageQueue(server);
        a.peer = b;
        b.peer = a;
        return new CMessageQueue[] { a, b };
    }

    @Override public QueueBroker broker() { return broker; }

    @Override
    public void setListener(Listener listener) {
        Executor.check();
        this.listener = listener;
        listenerTask = Task.task();
        listenerVersion++;
        scheduled = false;
        if (listener != null && registered.add(listenerTask))
            Executor.self().register(listenerTask, this);
        schedule();
    }

    @Override
    public boolean send(byte[] bytes, int offset, int length, SendListener listener) {
        Executor.check();
        if (bytes == null || offset < 0 || length < 0 || offset > bytes.length - length)
            throw new IllegalArgumentException();
        Task sender = Task.task();
        boolean accepted = !closed && !peer.closed;
        if (accepted) {
            peer.incoming.addLast(Arrays.copyOfRange(bytes, offset, offset + length));
            peer.schedule();
        }
        if (listener != null) sender.post(() -> listener.sent(bytes, offset, length));
        return accepted;
    }

    @Override
    public void close() {
        Executor.check();
        if (closed) return;
        closed = true;
        incoming.clear();
        schedule();
        peer.schedule();
    }

    @Override
    public boolean closed() {
        Executor.check();
        return closed || (peer.closed && incoming.isEmpty());
    }

    private void schedule() {
        if (listener == null || listenerTask.dead() || scheduled || notified) return;
        if (incoming.isEmpty() && !closed()) return;
        int version = listenerVersion;
        scheduled = true;
        listenerTask.post(() -> deliver(version));
    }

    private void deliver(int version) {
        if (version != listenerVersion) return;
        scheduled = false;
        if (closed()) {
            notified = true;
            listener.closed();
        } else if (!incoming.isEmpty()) {
            byte[] message = incoming.removeFirst();
            try {
                listener.received(message);
            } finally {
                schedule();
            }
        }
    }
}
