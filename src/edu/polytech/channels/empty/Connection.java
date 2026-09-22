package edu.polytech.channels.empty;

import edu.polytech.utils.CircularBuffer;

final class Connection {
    final CircularBuffer[] incoming = {
        new CircularBuffer(257), new CircularBuffer(257)
    };
    final boolean[] closed = new boolean[2];

    boolean disconnected(int side) {
        return closed[side] || (closed[1 - side] && incoming[side].empty());
    }
}
