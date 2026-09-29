package edu.polytech.queues.local;

import java.util.HashMap;
import java.util.Map;
import edu.polytech.queues.QueueBroker;
import edu.polytech.queues.Task;
import edu.polytech.utils.Executor;

public class CQueueBroker implements QueueBroker {
    private static final Map<String, CQueueBroker> brokers = new HashMap<>();
    private final Map<Integer, BindListener> ports = new HashMap<>();
    private final String name;
    private final Task task;

    public CQueueBroker(String name) {
        Executor.check();
        if (name == null || name.isEmpty()) throw new IllegalArgumentException();
        task = Task.task();
        if (task == null || task.dead()) throw new IllegalStateException();
        if (brokers.containsKey(name)) throw new IllegalArgumentException("Duplicate broker: " + name);
        if (task.getBroker() != null) throw new IllegalStateException("Task already has a broker");
        this.name = name;
        Executor.self().set(task, this);
        brokers.put(name, this);
    }

    @Override public String getName() { return name; }
    @Override public Task getTask() { return task; }

    @Override
    public boolean bind(int port, BindListener listener) {
        Executor.check();
        if (listener == null) throw new IllegalArgumentException();
        if (task.dead() || ports.containsKey(port)) return false;
        ports.put(port, listener);
        return true;
    }

    @Override
    public boolean unbind(int port) {
        Executor.check();
        BindListener listener = ports.remove(port);
        if (listener == null) return false;
        task.post(listener::unbound);
        return true;
    }

    @Override
    public boolean connect(String name, int port, ConnectListener listener) {
        Executor.check();
        if (name == null || listener == null) throw new IllegalArgumentException();
        CQueueBroker remote = brokers.get(name);
        if (remote == null) return false;
        task.post(() -> {
            BindListener accepting = remote.ports.get(port);
            if (remote.task.dead() || accepting == null) {
                listener.refused();
                return;
            }
            CMessageQueue[] queues = CMessageQueue.pair(this, remote);
            remote.task.post(() -> accepting.accepted(queues[1]));
            task.post(() -> listener.connected(queues[0]));
        });
        return true;
    }
}
