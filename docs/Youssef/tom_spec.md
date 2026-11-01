# Totally-ordered multicast - Specification

Youssef Kirlos

## 1. What it does

Every correct actor of the group delivers the same messages, with the same
content, in the same order.

A message is received when it arrives from the network. It is delivered when
we give it to the actor. In between, we keep it until we are sure about its
place in the order.

## 2. What we assume

- All actors run in the same JVM and all runnables are executed one at a time
  by the `Executor`, so no locks are needed.
- A link between two actors is FIFO and reliable, as long as both are alive.
- `MessageQueue.send` can return `false` when the buffer is full. So for each
  destination we keep our own outgoing queue and retry in order. Messages on
  one link must never be reordered. `TomProcess` does not do this itself, the
  transport that talks to the real `MessageQueue` does.
- Actors are fail-stop and the failure detector is perfect.
- The group layer drops everything sent to or from a dead actor.

## 3. Interface

Class `edu.polytech.tom.TotalOrderMulticast`:

- `multicast(payload)` sends a message to the whole group, including myself.
- The listener gets `delivered(senderId, payload)`, in the common order.

From the group layer we need: send a packet to a member, the list of members
at the start, and the notifications of death, join and leave.

## 4. Messages and order

A message has an id: sender, incarnation, sequence number. The incarnation
goes up when an actor is resurrected, so old packets are not mixed up with new
ones.

There are two kinds of packets:

- DATA: id, timestamp, payload.
- ACK: id and timestamp of the DATA it acknowledges, who acknowledges, and the
  clock of that actor.

Each actor has a logical clock (Lamport). It goes up by one for each event,
and sending is an event. When a packet with clock `t` arrives:
`clock = max(clock, t) + 1`.

DATA is ordered by (timestamp, sender id). The sender id decides when two
timestamps are equal, so everybody gets the same order.

## 5. Without failures

Each actor keeps its clock, the list of members, a queue of pending DATA sorted
by the order above, and for each pending message who has acknowledged it.

- Multicast: increase the clock, build the DATA, send it to all members
  including myself.
- On DATA: update the clock, put it in the queue if it is new, send an ACK to
  all members, then try to deliver.
- On ACK: update the clock, remember it, then try to deliver. If the DATA has
  not arrived yet, keep the ACK until it does.
- Try to deliver: while the first message of the queue has an ACK from every
  member, remove it and deliver it.

### Example

P1 and P2 send at the same time. `a` from P1 gets timestamp 1 and `b` from P2
gets timestamp 1. The tie goes to the smaller sender, so `a` is before `b` for
everybody. Even if P3 receives `b` first, it cannot deliver it before `a`:
P1 sent `a` before its ACK of `b`, so `a` reaches P3 before that ACK.