package edu.polytech.actors;

import java.util.ArrayList;
import java.util.List;
import edu.polytech.queues.Task;
import edu.polytech.utils.CTask;
import edu.polytech.utils.Executor;

/** Lifecycle of one actor incarnation. Application communication uses queues. */
public final class Actor implements CTask.Owner {
  public interface Listener {
    void died(Actor actor, Throwable cause);
  }

  private final String name;
  private final int incarnation;
  private final List<CTask> tasks = new ArrayList<>();
  private final Task monitor;
  private final Task main;
  private final boolean canFail;
  private volatile boolean dead;
  private Listener listener;
  private boolean resurrected;

  public Actor(String name, boolean canFail) {
    this(name, 1, canFail);
  }

  private Actor(String name, int incarnation, boolean canFail) {
    Executor.check();
    if (name == null || name.isEmpty()) throw new IllegalArgumentException("Actor name required");
    this.name = name;
    this.incarnation = incarnation;
    this.canFail = canFail;
    monitor = Executor.self().newTask(name + "-monitor-" + incarnation);
    main = newTask("main");
  }

  public String name() { return name; }
  public int incarnation() { return incarnation; }
  public boolean dead() { return dead; }
  public void post(Runnable runnable) { main.post(runnable); }
  public void post(Runnable runnable, int delay) { main.post(runnable, delay); }

  public void setListener(Listener listener) {
    Executor.check();
    if (dead) throw new IllegalStateException("Dead actor");
    this.listener = listener;
  }

  @Override
  public Task newTask(String name) {
    Executor.check();
    if (dead) throw new IllegalStateException("Dead actor");
    CTask task = (CTask) Executor.self().newTask(this.name + "-" + incarnation + "-" + name);
    task.setOwner(this);
    task.setCanFail(canFail);
    tasks.add(task);
    return task;
  }

  public void suicide() {
    Executor.self().control(() -> taskFailed(null, new IllegalStateException("Actor suicide")));
  }

  @Override
  public void taskFailed(CTask task, Throwable cause) {
    Executor.check();
    if (dead) return;
    dead = true;
    for (CTask owned : tasks) owned.stopWithOwner(cause);
    Listener observer = listener;
    monitor.post(() -> {
      if (observer != null) observer.died(this, cause);
      monitor.exit(null);
    });
  }

  public Actor resurrect() {
    Executor.check();
    if (!dead || resurrected) throw new IllegalStateException("Actor is alive or already resurrected");
    Actor replacement = new Actor(name, Math.addExact(incarnation, 1), canFail);
    replacement.listener = listener;
    resurrected = true;
    return replacement;
  }
}
