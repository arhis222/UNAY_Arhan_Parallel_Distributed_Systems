package edu.polytech.channels.empty;

import java.util.HashMap;

public class BrokerManager {
  private static final HashMap<String, CBroker> brokers = new HashMap<String, CBroker>();

  BrokerManager() {}

  public void add(CBroker broker) {
    if (broker == null) throw new IllegalArgumentException("Null broker");
    synchronized (brokers) {
      if (brokers.containsKey(broker.getName()))
        throw new IllegalArgumentException("Duplicate broker: " + broker.getName());
      brokers.put(broker.getName(), broker);
    }
  }

  public void remove(CBroker broker) {
    if (broker == null) throw new IllegalArgumentException("Null broker");
    synchronized (brokers) {
      if (brokers.get(broker.getName()) == broker) brokers.remove(broker.getName());
    }
  }

  public CBroker get(String name) {
    synchronized (brokers) { return brokers.get(name); }
  }
}
