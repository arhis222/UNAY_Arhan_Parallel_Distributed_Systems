package edu.polytech.tom;

import edu.polytech.queues.Task;

/*
 * The logical clock of a member of a group, on top of the clocks of the tasks of the runtime.
 */
public class TaskClock implements TomProcess.Clock {

  private long value;

  private long taskClock() {
    Task task = Task.task();
    return task == null ? 0 : task.clock();
  }

  @Override
  public long tick() {
    value = Math.max(value + 1, taskClock());
    return value;
  }

  @Override
  public long receive(long t) {
    Task task = Task.task();
    long fromTask = task == null ? 0 : task.receiveClock(t);
    value = Math.max(Math.max(value, t) + 1, fromTask);
    return value;
  }
}
