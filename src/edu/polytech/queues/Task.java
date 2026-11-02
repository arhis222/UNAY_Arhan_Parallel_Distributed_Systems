package edu.polytech.queues;

import edu.polytech.utils.Executor;

public abstract class Task {

  public abstract String getName();
  public abstract QueueBroker getBroker();
  public abstract void post(Runnable r);
  public abstract void post(Runnable r, int delay);
  
  public abstract boolean dead();

  public long clock() { throw new UnsupportedOperationException(); }
  public long receiveClock(long timestamp) { throw new UnsupportedOperationException(); }
  public boolean canFail() { return false; }
  public void setCanFail(boolean value) { throw new UnsupportedOperationException(); }
  public void kill() { Executor.self().kill(this); }
    
  public abstract Task newTask(String name);
  public abstract QueueBroker newBroker(String name);
  
  public static Task task() {
    return Executor.task();
  }
  
  public interface Listener {
    public void failed(Task t, Throwable th);    
    public void completed(Task t,Object o);    
  }
  public abstract void set(Listener l);
  public abstract void exit(Object o);
  public abstract void fail(Throwable th);
  
}
