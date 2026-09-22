package edu.polytech.channels.empty;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import edu.polytech.channels.Broker;
import edu.polytech.channels.Channel;

public class CBroker implements Broker {
    private final BrokerManager manager = new BrokerManager();
    private final String name;
    private final Map<Integer, Port> ports = new HashMap<Integer, Port>();

    private static final class Request { Channel endpoint; CBroker caller; }
    private static final class Port {
        Request accept;
        final ArrayDeque<Request> connects = new ArrayDeque<Request>();
    }

    CBroker(String name) {
        if (name == null || name.isEmpty())
            throw new IllegalArgumentException("A broker needs a non-empty name");
        this.name = name;
        manager.add(this);
    }

    @Override public String getName() { return name; }

    @Override public Channel connect(String name, int port) {
        if (name == null) throw new IllegalArgumentException("Null broker name");
        CBroker destination = manager.get(name);
        if (destination == null) return null;
        return destination.rendezvous(port, true, this);
    }

    @Override public Channel accept(int port) { return rendezvous(port, false, this); }

    private Channel rendezvous(int number, boolean client, CBroker caller) {
        boolean interrupted = false;
        synchronized (this) {
            Port port = ports.get(number);
            if (port == null) {
                port = new Port();
                ports.put(number, port);
            }
            Request request = new Request();
            request.caller = caller;
            if (client) port.connects.addLast(request);
            else {
                if (port.accept != null)
                    throw new IllegalStateException("Another accept is waiting on port " + number);
                port.accept = request;
            }
            if (port.accept != null && !port.connects.isEmpty()) {
                Request connecting = port.connects.removeFirst();
                CChannel[] endpoints = CChannel.pair(connecting.caller, this, number);
                connecting.endpoint = endpoints[0];
                port.accept.endpoint = endpoints[1];
                port.accept = null;
                notifyAll();
            }
            if (port.accept == null && port.connects.isEmpty()) ports.remove(number);
            while (request.endpoint == null) {
                try { wait(); }
                catch (InterruptedException e) { interrupted = true; }
            }
            if (interrupted) Thread.currentThread().interrupt();
            return request.endpoint;
        }
    }
}
