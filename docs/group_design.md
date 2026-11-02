# Group Layer - Design

Author: Adam TAWFIK 
Code: `src/edu/polytech/groups/`
Tests: `src/edu/polytech/groups/tests/GroupTest.java`

## Classes

| Class | Role |
| --- | --- |
| `MemberId` | Value class `(id, incarnation)`. |
| `View` | Immutable view `(viewId, members)`; `without(id)` builds the next view. |
| `Group` | Member-side interface, with nested `Listener` and `JoinListener`. |
| `GroupManager` | Manager logic, plus the private inner class `Member`, which implements `Group`. |

## Tasks and brokers

- The manager runs on its own task, `groupN-manager`. As required by the
  assignment, it is used only to form the initial group: once view 1 is
  installed, the group is peer-to-peer and the manager is no longer used.
- Each member gets a dedicated task created with `actor.newTask("group")`. The
  task belongs to the Actor, so the runtime kills it with the Actor and cancels
  its pending callbacks (guarantee G3). The Actor's main task is left
  free for the application.
- Each member task creates its own `QueueBroker`, named
  `groupN-member-<id>-<incarnation>`, and binds port 4242. The incarnation is
  part of the name because `CQueueBroker` never frees a name: a resurrected
  Actor reusing the same name would raise `Duplicate broker`.
- During formation, the manager and members exchange control information by
  posting callbacks on each other's tasks (same JVM). All member data packets
  travel through `MessageQueue`.

## Packets on links

| Type | Format | Use |
| --- | --- | --- |
| `HELLO` | `0`, `int id` | First packet on a new link: tells the accepting side who connected. |
| `DATA` | `1`, payload | Application/TOM packet. |

Sending to oneself does not use a queue: the packet is copied and posted on the
member's own task.

## Step 1 - Initial formation

```
member i                         manager
--------                         -------
create broker, bind 4242
                 registered(i) ->
                                 wait for n members
                 <- connect(view 1, broker names)
connect to every j < i
send HELLO(i) on each new link
(accept links from every j > i)
when n-1 links are up
                 ready(i) ->
                                 wait for n ready
                 <- install(view 1)
joined(group), viewInstalled(view 1)
deliver buffered packets
```

- The member with the larger id connects to the member with the smaller id, so
  each pair has exactly one link.
- A member may receive DATA before its own `install` (another member started
  first). These packets are buffered in `early` and delivered after
  `viewInstalled`.

## Step 2 - Failures without reinsertion

### Detection

`GroupManager.join` registers an `Actor.Listener` on each member Actor: this
is the group's perfect failure detector. When an Actor dies, the runtime calls
it on the Actor's protected monitor task. After formation, the detector posts
`failed(deadId)` directly on the task of every other member; the manager is
not involved. Each survivor then computes the next view itself:
`latest.without(deadId)`, with viewId + 1.

- Detection is perfect because it comes from the runtime itself and not from a
  timeout.
- All survivors compute the same views: the runtime runs one callback at a time
  and each task is FIFO, so a detector callback posts the notice of one death
  to every survivor before any other death is reported. Every survivor sees the
  deaths in the same order, starts from the same view 1 and applies the same
  `without` operation.

### Why the view is not installed immediately

Measured on the real runtime: member X sends m0, m1, m2 to Y and then commits
suicide. Y observes:

```
Y got m0
died event: X     <- the failure notice arrives here
Y got m1          <- but X's packets are still arriving
Y got m2
Y: link closed    <- closed() comes after the last packet
```

If a survivor dropped X's packets as soon as it learned about the death,
one survivor could drop m1 while another had already processed it. The
survivors would no longer agree, and TOM ordering would break.

### Rule

A survivor installs the view without X only when both conditions hold:

1. it has received `failed(X)` from the failure detector, and
2. its link to X reported `closed()`.

Pending failures are kept in a FIFO (`pendingFailures`) and installed in order,
so concurrent failures produce views 2, 3, ... in the same order everywhere
(G6).

### Why this is correct (G5)

1. **`closed()` comes after every packet.** When X dies, the runtime closes
   X's queues. On the survivor side, `CMessageQueue.closed()` becomes true only
   when the incoming buffer is empty. So `closed()` is reported after
   the last packet X sent.
2. **A send to several members is atomic.** The Executor runs one callback at
   a time and draws a random failure *before* running a callback,
   never in the middle. `MessageQueue.send` puts the packet directly in the peer's
   buffer. So a callback that sends to all members either runs
   entirely (all survivors get the packet) or not at all (none do).
3. Therefore, every survivor receives exactly the packets X enqueued before dying
   (point 2), receives all of them before `closed()` (point 1), and installs
   the new view only after `closed()` (the rule). Packets from X are
   delivered while X is still in the installed view, so they are not dropped.

### Dropping traffic

- To X: `MessageQueue.send` returns `false` once X's side is closed; the
  packet is lost, as required (G7). After the new view, `send` checks
  `view.contains(to)` and drops the packet before using a queue.
- From X: after the new view, the link to X is removed and `received`
  drops any packet whose sender is not in the installed view.

## Tests

`GroupTest` (Run As > Java Application). Each test waits for callbacks with
timeouts and fails with an `AssertionError`.

| Test | Checks |
| --- | --- |
| `formation` | 4 members install the same view 1; every member receives one packet from each member, including itself (G1, G2). |
| `failure` | Member 2 sends 3 packets to everyone then dies. Each survivor sees exactly `view 1, 2:x0, 2:x1, 2:x2, view 2 [1, 3, 4]`; member 2 receives nothing after death; a send to 2 is dropped; survivors keep communicating (G3, G4, G5, G7). |
| `twoFailures` | Members 4 and 2 die in the same callback. All survivors install view 2 then view 3 and receive the last packet of each dead member (G5, G6). |

Result: all tests pass locally, both from the command line (5 runs) and in Eclipse.

## Known limits and next steps

- A failure during formation is not handled (the manager forgets the member, but
  survivors would wait for its link).
- `GroupManager` takes the single `Actor.Listener`; a multi-listener API is
  requested from Arhan.
- Step 3: `join` of a resurrected Actor (`incarnation + 1`, new broker name,
  new links). The new member's first view is the one that includes it, so
  it cannot receive packets sent before (links are new).
- Step 4: voluntary `leave()` and dynamic `join`, using the same view change
  mechanism. Since the manager is no longer used after formation, a join must
  be handled by the members themselves (for example through a current member
  acting as contact).
- Integration with TOM: `TomProcess` needs a method to update its members on
  `viewInstalled`; the delivery boundary at a view change is defined with Youssef.