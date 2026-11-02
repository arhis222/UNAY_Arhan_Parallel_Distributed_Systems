/*
 * Copyright (C) 2023 Pr. Olivier Gruber                                    
 *                                                                       
 * This program is free software: you can redistribute it and/or modify  
 * it under the terms of the GNU General Public License as published by  
 * the Free Software Foundation, either version 3 of the License, or     
 * (at your option) any later version.                                   
 *                                                                       
 * This program is distributed in the hope that it will be useful,       
 * but WITHOUT ANY WARRANTY; without even the implied warranty of        
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the         
 * GNU General Public License for more details.                          
 *                                                                       
 * You should have received a copy of the GNU General Public License     
 * along with this program.  If not, see <http://www.gnu.org/licenses/>. 
 */
package edu.polytech.utils;

import edu.polytech.queues.Task;
import edu.polytech.queues.local.CMessageQueue;
import edu.polytech.queues.local.CQueueBroker;

public class Executor extends Thread {

  private static class Event {
    final CTask task;
    final Runnable runnable;
    final long due;
    final long order;

    Event(CTask task, Runnable runnable, long due, long order) {
      this.task = task;
      this.runnable = runnable;
      this.due = due;
      this.order = order;
    }
  }

  private static final Executor self = new Executor();
  static { self.start(); }

  private final java.util.ArrayDeque<Event> ready = new java.util.ArrayDeque<>();
  private final java.util.PriorityQueue<Event> delayed = new java.util.PriorityQueue<>(
      (a, b) -> a.due == b.due ? Long.compare(a.order, b.order) : Long.compare(a.due, b.due));
  private final java.util.Random random = new java.util.Random();
  private double failureProbability;
  private long sequence;
  private CTask current;
  private final CTask control;

  private Executor() {
    super("Event Pump");
    control = new CTask("runtime-control", this);
  }

  public static Executor self() { return self; }

  public static Task task() {
    return Thread.currentThread() == self ? self.current : null;
  }

  static void log(String message, Throwable cause) {
    System.err.println(message);
    if (cause != null) cause.printStackTrace(System.err);
  }

  public synchronized void configureFailures(double probability, long seed) {
    if (Double.isNaN(probability) || probability < 0 || probability > 1)
      throw new IllegalArgumentException("Probability must be between 0 and 1");
    failureProbability = probability;
    random.setSeed(seed);
  }

  @Override
  public void run() {
    while (true) {
      Event event;
      synchronized (this) {
        for (;;) {
          long now = System.nanoTime();
          while (!delayed.isEmpty() && delayed.peek().due <= now)
            ready.addLast(delayed.remove());
          if (!ready.isEmpty()) break;
          try {
            if (delayed.isEmpty()) wait();
            else {
              long remaining = Math.max(1, delayed.peek().due - now);
              wait(remaining / 1000000, (int) (remaining % 1000000));
            }
          } catch (InterruptedException ignored) {
            // Recheck the queues.
          }
        }
        event = ready.removeFirst();
      }
      current = event.task;
      try {
        if (!current.dead()) {
          boolean fail;
          synchronized (this) {
            fail = current.canFail() && random.nextDouble() < failureProbability;
          }
          if (fail) current.fail(new IllegalStateException("Simulated task failure"));
          else {
            current.tick();
            event.runnable.run();
          }
        }
      } catch (CTask.Stopped stopped) {
        // Explicit exit or failure ends the current callback.
      } catch (Throwable cause) {
        current.terminate(cause, null, true);
      } finally {
        current = null;
      }
    }
  }

  public void set(Task task, CQueueBroker broker) { ((CTask) task).set(broker); }
  public void register(Task task, CMessageQueue queue) { ((CTask) task).register(queue); }
  public Task newTask(String name) { return new CTask(name, this); }

  public synchronized void post(Task task, Runnable runnable) {
    enqueue(task, runnable, 0);
  }

  public synchronized void post(Task task, Runnable runnable, int delay) {
    if (delay < 0) throw new IllegalArgumentException("Negative delay");
    enqueue(task, runnable, delay);
  }

  private void enqueue(Task task, Runnable runnable, int delay) {
    if (runnable == null) throw new NullPointerException("runnable");
    if (task.dead()) return;
    Event event = new Event((CTask) task, runnable,
        System.nanoTime() + delay * 1000000L, sequence++);
    if (delay == 0) ready.addLast(event);
    else delayed.add(event);
    notifyAll();
  }

  synchronized void discard(CTask task) {
    ready.removeIf(event -> event.task == task);
    delayed.removeIf(event -> event.task == task);
    notifyAll();
  }

  public void kill(Task task) {
    CTask target = (CTask) task;
    control(() -> target.terminate(new IllegalStateException("Task killed"), null, true));
  }

  public void control(Runnable action) {
    if (action == null) throw new NullPointerException("action");
    if (Thread.currentThread() == this) {
      action.run();
      if (current != null && current.dead()) throw new CTask.Stopped();
    } else {
      synchronized (this) {
        ready.addFirst(new Event(control, action, 0, sequence++));
        notifyAll();
      }
    }
  }

  public static void check() {
    Thread thread = Thread.currentThread();
    if (!(thread instanceof Executor)) {
      failStop("Executor: wrong thread");
    }
  }

  public static void ensure(boolean cond) {
    if (!cond)
      failStop();
  }

  public static void failStop() {
    failStop("fail stop.", null);
  }

  public static void failStop(String msg) {
    failStop(msg, null);
  }

  public static void failStop(String msg, Throwable th) {
    if (th == null) {
      try {
        throw new Error();
      } catch (Error e) {
        th = e;
      }
    }
    synchronized (System.out) {
      synchronized (System.err) {
        System.err.println("\n\n==============================");
        System.err.println("PANIC: " + msg);
        th.printStackTrace(System.err);
        System.err.flush();
        System.exit(-1);
      }
    }
  }

  public static void failStop(Throwable th) {
    failStop("fail stop.", th);
  }

}
