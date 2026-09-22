package edu.polytech.channels.empty;

import edu.polytech.channels.Channel;
import edu.polytech.utils.CircularBuffer;

public class CChannel implements Channel {
    protected final CBroker broker;
    protected final int port;
    private final Connection connection;
    private final int side;

    protected CChannel(CBroker broker, int port) {
        this(broker, port, new Connection(), 0);
    }

    private CChannel(CBroker broker, int port, Connection connection, int side) {
        this.broker = broker;
        this.port = port;
        this.connection = connection;
        this.side = side;
    }

    static CChannel[] pair(CBroker client, CBroker server, int port) {
        CChannel first = new CChannel(client, port);
        CChannel second = new CChannel(server, port, first.connection, 1);
        return new CChannel[] {first, second};
    }

    private static void validate(byte[] bytes, int offset, int length) {
        if (bytes == null || offset < 0 || length <= 0 || offset > bytes.length - length)
            throw new IllegalArgumentException("Expected a valid, non-empty byte range");
    }

    @Override public int read(byte[] bytes, int offset, int length) {
        validate(bytes, offset, length);
        boolean interrupted = false;
        synchronized (connection) {
            try {
                CircularBuffer input = connection.incoming[side];
                while (input.empty() && !connection.disconnected(side)) {
                    try { connection.wait(); }
                    catch (InterruptedException e) { interrupted = true; }
                }
                if (connection.closed[side] || input.empty()) return 0;
                int count = 0;
                while (count < length && !input.empty())
                    bytes[offset + count++] = input.pull();
                connection.notifyAll();
                return count;
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }

    @Override public int write(byte[] bytes, int offset, int length) {
        validate(bytes, offset, length);
        boolean interrupted = false;
        synchronized (connection) {
            try {
                CircularBuffer output = connection.incoming[1 - side];
                while (output.full() && !connection.closed[side] && !connection.closed[1 - side]) {
                    try { connection.wait(); }
                    catch (InterruptedException e) { interrupted = true; }
                }
                if (connection.closed[side] || connection.closed[1 - side]) return length;
                int count = 0;
                while (count < length && !output.full())
                    output.push(bytes[offset + count++]);
                connection.notifyAll();
                return count;
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }

    @Override public boolean disconnected() {
        synchronized (connection) { return connection.disconnected(side); }
    }

    @Override public void disconnect() {
        synchronized (connection) {
            connection.closed[side] = true;
            connection.notifyAll();
        }
    }
}
