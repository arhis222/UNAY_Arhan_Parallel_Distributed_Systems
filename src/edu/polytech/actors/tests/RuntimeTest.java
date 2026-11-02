package edu.polytech.actors.tests;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import edu.polytech.actors.Actor;
import edu.polytech.queues.Task;
import edu.polytech.queues.MessageQueue;
import edu.polytech.queues.QueueBroker;
import edu.polytech.utils.Executor;

public final class RuntimeTest {
  private static final Executor executor = Executor.self();
  private static final Task driver = executor.newTask("test-driver");

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  private static void onPump(Runnable action) throws Exception {
    CompletableFuture<Void> done = new CompletableFuture<>();
    driver.post(() -> {
      try { action.run(); done.complete(null); }
      catch (Throwable failure) { done.completeExceptionally(failure); }
    });
    done.get(5, TimeUnit.SECONDS);
  }

  private static void await(CountDownLatch latch) throws Exception {
    check(latch.await(5, TimeUnit.SECONDS), "Timed out");
  }

  private static void afterDelays() throws Exception {
    CountDownLatch done = new CountDownLatch(1);
    driver.post(done::countDown, 100);
    await(done);
  }

  private static void clocks() throws Exception {
    check(Task.task() == null, "Task context leaked outside the pump");
    Task task = executor.newTask("clock");
    long[] values = new long[4];
    CountDownLatch done = new CountDownLatch(1);
    task.post(() -> {
      values[0] = Task.task().clock();
      values[1] = task.receiveClock(50);
      task.post(() -> { values[2] = task.clock(); done.countDown(); });
    });
    await(done);
    onPump(() -> values[3] = driver.clock());
    check(values[0] == 1 && values[1] == 51 && values[2] == 52, "Wrong Lamport clock");
    check(values[3] < 50, "Task clocks are not independent");
  }

  private static void cancellation() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    Task target = executor.newTask("cancelled");
    onPump(() -> {
      target.post(calls::incrementAndGet);
      target.post(calls::incrementAndGet, 30);
      target.kill();
      target.kill();
      target.post(calls::incrementAndGet);
    });
    afterDelays();
    check(target.dead() && calls.get() == 0 && target.clock() == 0, "Cancelled callback executed");

    Task outside = executor.newTask("outside-kill");
    outside.post(calls::incrementAndGet, 1000);
    outside.kill();
    onPump(() -> check(outside.dead(), "External kill was not processed"));
  }

  private static void actors() throws Exception {
    Actor[] actor = new Actor[2];
    Task[] child = new Task[1];
    AtomicInteger unexpected = new AtomicInteger();
    AtomicInteger deaths = new AtomicInteger();
    CountDownLatch died = new CountDownLatch(1);
    onPump(() -> {
      actor[0] = new Actor("A", false);
      actor[0].setListener((a, failure) -> { deaths.incrementAndGet(); died.countDown(); });
      actor[0].post(() -> {
        child[0] = Task.task().newTask("child");
        child[0].post(unexpected::incrementAndGet);
        actor[0].post(unexpected::incrementAndGet, 30);
        actor[0].suicide();
        unexpected.incrementAndGet();
      });
    });
    await(died);
    onPump(() -> {
      check(actor[0].dead() && child[0].dead(), "Actor death did not stop every task");
      actor[0].suicide();
      actor[1] = actor[0].resurrect();
      check(actor[1].incarnation() == 2 && !actor[1].dead(), "Bad resurrection");
      try { actor[0].resurrect(); throw new AssertionError("Duplicate resurrection accepted"); }
      catch (IllegalStateException expected) { }
    });
    CountDownLatch ran = new CountDownLatch(1);
    long[] clock = new long[1];
    actor[1].post(() -> { clock[0] = Task.task().clock(); ran.countDown(); });
    actor[0].post(unexpected::incrementAndGet);
    await(ran);
    afterDelays();
    check(clock[0] == 1 && deaths.get() == 1 && unexpected.get() == 0, "Old incarnation ran again");

    CountDownLatch childFailure = new CountDownLatch(1);
    onPump(() -> {
      Actor other = new Actor("B", false);
      other.setListener((a, failure) -> {
        check(a.dead(), "Failure not visible to observer");
        childFailure.countDown();
      });
      Task worker = other.newTask("worker");
      worker.post(() -> { throw new IllegalStateException("Test failure"); });
      other.post(unexpected::incrementAndGet);
    });
    await(childFailure);
    afterDelays();
    check(unexpected.get() == 0, "Sibling callback survived child failure");
  }

  private static void randomFailures() throws Exception {
    AtomicInteger unexpected = new AtomicInteger();
    CountDownLatch dead = new CountDownLatch(1);
    CountDownLatch protectedRan = new CountDownLatch(1);
    onPump(() -> {
      executor.configureFailures(1, 123);
      Actor actor = new Actor("random", true);
      actor.setListener((a, cause) -> dead.countDown());
      actor.post(unexpected::incrementAndGet);
      Actor protectedActor = new Actor("protected", false);
      protectedActor.post(protectedRan::countDown);
    });
    await(dead);
    await(protectedRan);
    executor.configureFailures(0, 123);
    check(unexpected.get() == 0, "Failed actor executed its callback");
  }

  private static void concurrentPosts() throws Exception {
    CountDownLatch done = new CountDownLatch(1000);
    Task target = executor.newTask("concurrent");
    Thread a = new Thread(() -> { for (int i = 0; i < 500; i++) target.post(done::countDown); });
    Thread b = new Thread(() -> { for (int i = 0; i < 500; i++) target.post(done::countDown); });
    a.start(); b.start(); a.join(); b.join();
    await(done);
    check(target.clock() == 1000, "Lost or duplicated concurrent post");
  }

  private static void delaysAndCompletion() throws Exception {
    java.util.List<Integer> order = new java.util.ArrayList<>();
    CountDownLatch delayed = new CountDownLatch(1);
    onPump(() -> {
      driver.post(() -> { order.add(3); delayed.countDown(); }, 60);
      driver.post(() -> order.add(2), 20);
      driver.post(() -> order.add(1));
    });
    await(delayed);
    check(order.equals(java.util.Arrays.asList(1, 2, 3)), "Wrong delayed event order");

    AtomicInteger unexpected = new AtomicInteger();
    AtomicInteger completions = new AtomicInteger();
    CountDownLatch completed = new CountDownLatch(1);
    Task target = executor.newTask("normal-exit");
    onPump(() -> {
      target.set(new Task.Listener() {
        public void failed(Task t, Throwable cause) { unexpected.incrementAndGet(); completed.countDown(); }
        public void completed(Task t, Object result) {
          if (!"done".equals(result)) unexpected.incrementAndGet();
          completions.incrementAndGet();
          completed.countDown();
        }
      });
      target.post(() -> { target.exit("done"); unexpected.incrementAndGet(); });
      target.post(unexpected::incrementAndGet);
      target.post(unexpected::incrementAndGet, 20);
    });
    await(completed);
    afterDelays();
    check(target.dead() && unexpected.get() == 0 && completions.get() == 1,
        "Normal completion did not stop callbacks or notified twice");
  }

  private static void killDuringCallback() throws Exception {
    Task target = executor.newTask("running-kill");
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger unexpected = new AtomicInteger();
    target.post(() -> {
      entered.countDown();
      try { check(release.await(5, TimeUnit.SECONDS), "Test release timed out"); }
      catch (InterruptedException failure) { throw new AssertionError(failure); }
    });
    await(entered);
    target.post(unexpected::incrementAndGet);
    target.kill();
    release.countDown();
    onPump(() -> check(target.dead(), "Kill not processed at event boundary"));
    check(unexpected.get() == 0, "Pending runnable started after kill request");
  }

  private static void queueClosure() throws Exception {
    CountDownLatch connected = new CountDownLatch(2);
    CountDownLatch closed = new CountDownLatch(1);
    Actor[] server = new Actor[1];
    AtomicInteger receivedAfterDeath = new AtomicInteger();
    onPump(() -> {
      server[0] = new Actor("queue-server", false);
      server[0].post(() -> {
        QueueBroker broker = Task.task().newBroker("runtime-test-server");
        broker.bind(7, new QueueBroker.BindListener() {
          public void unbound() { }
          public void accepted(MessageQueue queue) {
            queue.setListener(new MessageQueue.Listener() {
              public void received(byte[] message) { receivedAfterDeath.incrementAndGet(); }
              public void closed() { }
            });
            connected.countDown();
          }
        });
      });
      Actor client = new Actor("queue-client", false);
      client.post(() -> {
        QueueBroker broker = Task.task().newBroker("runtime-test-client");
        broker.connect("runtime-test-server", 7, new QueueBroker.ConnectListener() {
          public void refused() { throw new AssertionError("Connection refused"); }
          public void connected(MessageQueue queue) {
            queue.setListener(new MessageQueue.Listener() {
              public void received(byte[] message) { }
              public void closed() { closed.countDown(); }
            });
            connected.countDown();
          }
        });
      });
    });
    await(connected);
    server[0].suicide();
    await(closed);
    check(server[0].dead() && receivedAfterDeath.get() == 0, "Queue cleanup failed");
  }

  public static void main(String[] args) {
    try {
      clocks();
      cancellation();
      actors();
      randomFailures();
      concurrentPosts();
      queueClosure();
      delaysAndCompletion();
      killDuringCallback();
      System.out.println("RuntimeTest: PASS");
      System.exit(0);
    } catch (Throwable failure) {
      failure.printStackTrace();
      System.exit(1);
    }
  }
}
