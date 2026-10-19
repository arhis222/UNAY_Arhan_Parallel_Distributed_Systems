package edu.polytech.channels.empty;

import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import edu.polytech.channels.Broker;
import edu.polytech.channels.Channel;
import edu.polytech.channels.Task;

public final class ComplianceTests {
    private static int passed;
    private static void check(boolean value) {
        if (!value) throw new AssertionError("Specification check failed");
    }
    private static void pass(String name) {
        passed++;
        System.out.println("PASS: " + name);
    }
    private static final class Job<T> {
        final FutureTask<T> result;
        final Thread thread;
        Job(Callable<T> body) {
            result = new FutureTask<T>(body);
            thread = new Thread(result);
            thread.setDaemon(true);
            thread.start();
        }
        T get() throws Exception { return result.get(5, TimeUnit.SECONDS); }
        void waiting() throws Exception {
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (thread.getState() != Thread.State.WAITING) {
                if (result.isDone() || System.nanoTime() > end)
                    throw new AssertionError("Expected a blocked operation");
                Thread.sleep(1);
            }
        }
    }
    private static Channel[] pair(final Broker server, final Broker client,
                                  final int port, boolean connectFirst) throws Exception {
        if (connectFirst) {
            Job<Channel> first = new Job<Channel>(() -> client.connect(server.getName(), port));
            first.waiting();
            Channel accepted = server.accept(port);
            return new Channel[] {first.get(), accepted};
        }
        Job<Channel> first = new Job<Channel>(() -> server.accept(port));
        first.waiting();
        Channel connected = client.connect(server.getName(), port);
        return new Channel[] {connected, first.get()};
    }
    private static void invalid(Runnable operation) {
        try { operation.run(); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected IllegalArgumentException");
    }
    private static void writeAll(Channel ch, byte[] bytes) {
        for (int offset = 0; offset < bytes.length;)
            offset += ch.write(bytes, offset, bytes.length - offset);
    }
    private static byte[] readAll(Channel ch, int length) {
        byte[] bytes = new byte[length];
        for (int offset = 0; offset < length;) {
            int n = ch.read(bytes, offset, length - offset);
            check(n > 0);
            offset += n;
        }
        return bytes;
    }
    public static void main(String[] args) throws Exception {
        Boot boot = new Boot();
        Broker server = boot.newBroker("server"), client = boot.newBroker("client");
        check(client.connect("absent", 42) == null);
        invalid(() -> boot.newBroker("server"));
        invalid(() -> boot.newBroker(null));
        pass("broker names and unknown broker returning null");

        Channel[] p = pair(server, client, 42, true);
        byte[] message = {1, 2, 3, 4, 5};
        check(p[0].write(message, 1, 3) == 3);
        p[0].disconnect();
        check(p[0].disconnected() && !p[1].disconnected());
        byte[] result = new byte[5];
        check(p[1].read(result, 1, 2) == 2);
        check(!p[1].disconnected());
        check(p[1].read(result, 3, 2) == 1);
        check(Arrays.equals(result, new byte[]{0, 2, 3, 4, 0}));
        check(p[1].disconnected() && p[1].read(result, 0, 1) == 0);
        check(p[0].write(message, 0, 5) == 5);
        p[0].disconnect();
        p[1].disconnect();
        pass("remote close drains FIFO bytes before reporting disconnection");

        Channel[] q = pair(server, client, 42, false);
        Job<Integer> read = new Job<Integer>(() -> q[0].read(new byte[1], 0, 1));
        read.waiting();
        q[0].write(message, 0, 1);
        check(q[1].read(result, 0, 1) == 1);
        q[1].disconnect();
        check(read.get() == 0);
        pass("blocked read permits local write and wakes on remote disconnect");

        Channel[] r = pair(server, client, 43, false);
        byte[] large = new byte[4096];
        int filled = r[0].write(large, 0, large.length);
        check(filled > 0 && filled < large.length);
        Job<Integer> write = new Job<Integer>(() -> r[0].write(message, 0, 5));
        write.waiting();
        r[1].write(message, 0, 1);
        check(r[0].read(result, 0, 1) == 1);
        r[1].disconnect();
        check(write.get() == 5 && r[0].disconnected());
        pass("blocked write permits local read and drops bytes after disconnect");

        Channel[] s = pair(server, client, 44, false);
        s[0].write(message, 0, 1);
        s[1].write(message, 0, 1);
        Job<Boolean> closeA = new Job<Boolean>(() -> { s[0].disconnect(); return true; });
        Job<Boolean> closeB = new Job<Boolean>(() -> { s[1].disconnect(); return true; });
        check(closeA.get() && closeB.get());
        check(s[0].read(result, 0, 1) == 0 && s[1].read(result, 0, 1) == 0);
        pass("simultaneous local disconnects");

        final int size = 100000;
        byte[] payload = new byte[size];
        for (int i = 0; i < size; i++) payload[i] = (byte) i;
        Channel[] duplex = pair(server, client, 45, false);
        Job<Boolean> w0 = new Job<Boolean>(() -> { writeAll(duplex[0], payload); return true; });
        Job<Boolean> w1 = new Job<Boolean>(() -> { writeAll(duplex[1], payload); return true; });
        Job<byte[]> r0 = new Job<byte[]>(() -> readAll(duplex[0], size));
        Job<byte[]> r1 = new Job<byte[]>(() -> readAll(duplex[1], size));
        check(w0.get() && w1.get());
        check(Arrays.equals(payload, r0.get()) && Arrays.equals(payload, r1.get()));
        duplex[0].disconnect();
        pass("100000 bytes each direction with concurrent readers/writers and wraparound");

        Job<Channel> c0 = new Job<Channel>(() -> client.connect("server", 46));
        c0.waiting();
        Job<Channel> c1 = new Job<Channel>(() -> client.connect("server", 46));
        c1.waiting();
        Channel a0 = server.accept(46), a1 = server.accept(46);
        Channel e0 = c0.get(), e1 = c1.get();
        e0.write(new byte[]{7}, 0, 1);
        e1.write(new byte[]{9}, 0, 1);
        check(a0.read(result, 0, 1) == 1 && result[0] == 7);
        check(a1.read(result, 0, 1) == 1 && result[0] == 9);
        e0.disconnect(); e1.disconnect();
        pass("multiple queued connects and independent sessions on the same port");

        Job<Channel> pending = new Job<Channel>(() -> server.accept(47));
        pending.waiting();
        try { server.accept(47); throw new AssertionError("Duplicate accept allowed"); }
        catch (IllegalStateException expected) { }
        client.connect("server", 47).disconnect();
        pending.get();
        pass("one pending accept per port");

        invalid(() -> p[0].read(null, 0, 1));
        invalid(() -> p[0].read(result, -1, 1));
        invalid(() -> p[0].write(message, 1, Integer.MAX_VALUE));
        invalid(() -> p[0].write(message, 0, 0));
        pass("invalid ranges use IllegalArgumentException");

        FutureTask<Boolean> body = new FutureTask<Boolean>(() -> {
            Task current = Task.task();
            check(current.getBroker() == client && current.alive() && !current.dead());
            Broker nested = current.newBroker("nested");
            FutureTask<Boolean> childBody = new FutureTask<Boolean>(() ->
                Task.task().getBroker() == nested);
            Task child = current.newTask(nested, childBody, "child");
            check(childBody.get(5, TimeUnit.SECONDS));
            child.join(5000);
            return child.dead() && !child.alive();
        });
        Task task = boot.newTask(client, body, "task check");
        check(body.get(5, TimeUnit.SECONDS));
        task.join(5000);
        check(task.dead() && !task.alive());
        pass("automatic task start, task-local broker and child creation");
        check(task instanceof CTask);
        check(server instanceof CBroker && p[0] instanceof CChannel);
        try { task.start(); throw new AssertionError("Task restarted"); }
        catch (IllegalThreadStateException expected) { }
        BrokerManager manager = new BrokerManager();
        CBroker old = new CBroker("replaceable");
        check(manager.get("replaceable") == old);
        manager.remove(old);
        check(manager.get("replaceable") == null);
        CBroker replacement = new CBroker("replaceable");
        manager.remove(old);
        check(manager.get("replaceable") == replacement);
        manager.remove(replacement);
        pass("supplied C classes, task restart guard and BrokerManager removal");
        System.out.println(passed + " compliance groups passed");
    }
}
