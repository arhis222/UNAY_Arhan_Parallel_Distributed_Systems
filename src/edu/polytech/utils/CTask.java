package edu.polytech.utils;

import java.util.ArrayList;
import java.util.List;
import edu.polytech.queues.QueueBroker;
import edu.polytech.queues.Task;
import edu.polytech.queues.local.CMessageQueue;
import edu.polytech.queues.local.CQueueBroker;

public class CTask extends Task {
  public interface Owner {
    void taskFailed(CTask task, Throwable cause);
    Task newTask(String name);
  }

  static final class Stopped extends Error {
    private static final long serialVersionUID = 1L;
  }

  private final Executor executor;
  private final String name;
  private final List<CMessageQueue> queues = new ArrayList<>();
  private volatile boolean dead;
  private volatile boolean canFail;
  private volatile long clock;
  private QueueBroker broker;
  private Listener listener;
  private Task listenerTask;
  private Owner owner;

  CTask(String name, Executor executor) {
    if (name == null) throw new NullPointerException("name");
    this.name = name;
    this.executor = executor;
  }

  public void setOwner(Owner owner) {
    Executor.check();
    if (this.owner != null || dead) throw new IllegalStateException("Task already owned or dead");
    this.owner = java.util.Objects.requireNonNull(owner);
  }

  public void set(QueueBroker broker) {
    Executor.check();
    if (this.broker != null || dead) throw new IllegalStateException("Task already has a broker or is dead");
    this.broker = broker;
  }

  @Override public String toString() { return "Task " + name; }
  @Override public String getName() { return name; }
  @Override public QueueBroker getBroker() { return broker; }
  @Override public boolean dead() { return dead; }
  @Override public long clock() { return clock; }
  @Override public boolean canFail() { return canFail; }
  @Override public void setCanFail(boolean value) { canFail = value; }
  @Override public void post(Runnable runnable) { executor.post(this, runnable); }
  @Override public void post(Runnable runnable, int delay) { executor.post(this, runnable, delay); }

  void tick() { clock = Math.addExact(clock, 1); }

  @Override
  public long receiveClock(long timestamp) {
    Executor.check();
    if (Task.task() != this || dead) throw new IllegalStateException("Not the running task");
    if (timestamp < 0) throw new IllegalArgumentException("Negative timestamp");
    clock = Math.addExact(Math.max(clock, timestamp), 1);
    return clock;
  }

  @Override
  public Task newTask(String name) {
    Executor.check();
    if (dead) throw new IllegalStateException("Dead task");
    return owner == null ? executor.newTask(name) : owner.newTask(name);
  }

  @Override public QueueBroker newBroker(String name) { return new CQueueBroker(name); }

  @Override
  public void set(Listener listener) {
    Executor.check();
    if (dead) throw new IllegalStateException("Dead task");
    listenerTask = Task.task();
    this.listener = listener;
  }

  @Override
  public void fail(Throwable cause) {
    Executor.check();
    terminate(cause, null, true);
    Task current = Task.task();
    if (current != null && current.dead()) throw new Stopped();
  }

  @Override
  public void exit(Object result) {
    Executor.check();
    terminate(null, result, false);
    if (Task.task() == this) throw new Stopped();
  }

  void terminate(Throwable cause, Object result, boolean failed) {
    if (dead) return;
    dead = true;
    executor.discard(this);
    // Propagate death before scheduling any surviving observer's callback.
    if (failed && owner != null) owner.taskFailed(this, cause);
    for (CMessageQueue queue : queues) queue.close();
    queues.clear();
    if (listener != null && listenerTask != null) {
      Listener observer = listener;
      listenerTask.post(() -> {
        if (failed) observer.failed(this, cause);
        else observer.completed(this, result);
      });
    } else if (failed && owner == null) {
      Executor.log("Failed task " + name, cause);
    }
  }

  public void stopWithOwner(Throwable cause) {
    Executor.check();
    terminate(cause, null, true);
  }

  void register(CMessageQueue queue) {
    Executor.check();
    if (dead) throw new IllegalStateException("Dead task");
    queues.add(queue);
  }
}
