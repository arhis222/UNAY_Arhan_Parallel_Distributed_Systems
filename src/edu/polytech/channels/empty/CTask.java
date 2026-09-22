package edu.polytech.channels.empty;

import edu.polytech.channels.Broker;
import edu.polytech.channels.Task;

public class CTask extends Task {
  protected Broker broker;
  protected Runnable boot;
  protected volatile boolean alive;
  protected volatile boolean dead;

  public CTask(Broker b, Runnable r, String name) {
    super(name);
    if (!(b instanceof CBroker) || r == null)
      throw new IllegalArgumentException("Expected a CBroker and a runnable");
    broker = b;
    boot = r;
    start();
  }

  private void requireCurrentTask() {
    if (this != Thread.currentThread())
      throw new IllegalStateException("Operation must be called on the current task");
  }

  @Override public Broker getBroker() {
    requireCurrentTask();
    return broker;
  }
  @Override public boolean alive() { return alive && isAlive(); }
  @Override public boolean dead() { return dead && getState() == State.TERMINATED; }

  @Override public Broker newBroker(String name) {
    requireCurrentTask();
    return new CBroker(name);
  }
  @Override public Task newTask(Broker b, Runnable r, String n) {
    requireCurrentTask();
    return new CTask(b, r, n);
  }

  @Override public synchronized void start() {
    if (getState() != State.NEW || alive || dead)
      throw new IllegalThreadStateException("Task already started");
    alive = true;
    try { super.start(); }
    catch (RuntimeException | Error e) { alive = false; throw e; }
  }

  @Override public final void run() {
    requireCurrentTask();
    try { boot.run(); }
    finally {
      alive = false;
      dead = true;
    }
  }
}
