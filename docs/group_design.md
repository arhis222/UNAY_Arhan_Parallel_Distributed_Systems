# Group Layer - Design

Author: Adam TAWFIK (`adam-master`)
Code: `src/edu/polytech/groups/`
Tests: `src/edu/polytech/groups/tests/` (`GroupTest`, `GroupDynamicTest`)
Specification: [group_spec.md](group_spec.md)

## Classes

| Class | Role |
| --- | --- |
| `MemberId` | Value class `(id, incarnation)`. |
| `View` | Immutable view `(viewId, members)`; `with` and `without` build the next view. |
| `Group` | Public member-side interface, with nested `Listener` and `JoinListener`. |
| `GroupManager` | Forms the initial group, and nothing else (step 1). |
| `Membership` | Running group: perfect failure detector, and the order of view changes (steps 2 to 4). |
| `GroupMember` | One member's endpoint: links, packets, and installation of view changes. Implements `Group`. |

## Object graph

```
GroupManager                    (formation only, then no longer used)
  |-- task --> protected CTask "groupN-manager"
  |-- members --> GroupMember   (until view 1 is installed)
  +-- creates --> Membership    (when view 1 starts)

Membership                      (one per group, shared by its members)
  |-- latest --> View
  |-- live --> GroupMember      (by id)
  |-- joining --> GroupMember   (admitted, not joined yet)
  +-- used --> MemberId         (every identity ever admitted)

GroupMember  implements Group, TomProcess.Transport
  |-- self --> MemberId (id, incarnation)
  |-- actor --> Actor
  |              +-- death listener --> Membership.failed(member)
  |-- task --> CTask "group", owned by the Actor
  |              +-- broker --> CQueueBroker, bound on port 4242
  |-- links --> MessageQueue    (by MemberId)
  |              +-- listener --> Link (HELLO / DATA)
  |-- installed --> View
  |-- pending --> Change (type, who, View)   FIFO of view changes
  |-- early --> buffered packets
  |-- membership --> Membership
  +-- listener --> Group.Listener   (TOM, or a test probe)

View
  |-- viewId
  +-- members --> MemberId      (sorted by id)
```

A group of three members, after formation:

```
             Membership
           /     |      \
   GroupMember1  GroupMember2  GroupMember3
        |  \________/ \________/  |
        |   MessageQueue links    |
        \_________________________/
     (one link per pair; each member also sends to itself
      by posting on its own task)
```

## Tasks and brokers

- The manager runs on its own task, `groupN-manager`. As required by the
  assignment, it is used only to form the initial group: once view 1 is
  installed, the group is peer-to-peer and the manager is no longer used.
- Each member gets a dedicated task created with `actor.newTask("group")`. The
  task belongs to the Actor, so the runtime kills it with the Actor and cancels
  its pending callbacks (G3). The Actor's main task is left free for the
  application.
- Each member task creates its own `QueueBroker` and binds port 4242. Initial
  members use `groupN-member-<id>-<incarnation>`; members admitted later add a
  counter (`-j<k>`). `CQueueBroker` never frees a name, so a resurrected or
  rejoining Actor needs a new one.
- All data packets travel through `MessageQueue`. Control information (the
  formation steps, and membership changes) is passed by posting callbacks on
  member tasks, since everything runs in the same JVM.

## Packets on links

| Type | Format | Use |
| --- | --- | --- |
| `HELLO` | `0`, `int id`, `int incarnation` | First packet on a new link: tells the accepting side who connected. |
| `DATA` | `1`, payload | Application/TOM packet. |

Sending to oneself does not use a queue: the packet is copied and posted on the
member's own task. Links are indexed by `MemberId`, so the link of a new
incarnation is never confused with the link of the old one.

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
                                 wait for n ready, create Membership
                 <- start(view 1)
joined(group), viewInstalled(view 1)
deliver buffered packets
```

- The member with the larger id connects to the member with the smaller id, so
  each pair has exactly one link.
- A member may receive DATA before its own `start` (another member started
  first). These packets are kept in `early` and delivered after
  `viewInstalled`.

## Membership: one order of changes for everybody

`Membership` is created by the manager when view 1 starts, and shared by the
members. It keeps the latest view and the live members. A change is always
handled inside one callback of the event pump:

| Change | Triggered by | Runs on |
| --- | --- | --- |
| `FAIL(X)` | `Actor.Listener.died` (registered by `join` and `admit`) | X's protected monitor task |
| `LEAVE(X)` | `X.leave()` | the caller of `leave()` |
| `JOIN(N)` | `contact.admit(...)`, once N's broker is bound | N's group task |

In that callback, `Membership` computes the next view (`viewId + 1`) and posts
the change, with that view, on the task of every live member. The pump runs one
callback at a time and each task is FIFO. So **every member receives the same
changes, in the same order, with the same views** (G6). Members never compute
views themselves.

Detection is perfect because it comes from the runtime itself, not from a
timeout (G4).

## Installing changes

A member does not install a change as soon as it receives it. Changes wait in a
FIFO (`pending`) and are installed in order, each one when its condition holds:

| Change | Installed when | Why |
| --- | --- | --- |
| `FAIL(X)` / `LEAVE(X)` | my link to X reported `closed()` (or there is none) | every packet from X has been received (G5) |
| `JOIN(N)`, at an old member | my `connect()` to N was answered | packets I send in the new view can reach N (G2) |
| my own join, at newcomer N | I have a link from every old member, except those already leaving | I can reach everybody in my first view (G2) |

Packets from a member that is not in my installed view are kept in `early`
while changes are pending, and delivered (or dropped) after each installation.
This is needed for a newcomer: it may install its first view and send before an
old member installs `JOIN(newcomer)`, for example while the old member is still
waiting for a dead member's link to close.

### Why the failure view is not installed immediately

Measured on the real runtime: member X sends m0, m1, m2 to Y and then commits
suicide. Y observes:

```
Y got m0
died event: X     <- the failure notice arrives here
Y got m1          <- but X's packets are still arriving
Y got m2
Y: link closed    <- closed() comes after the last packet
```

If a member dropped X's packets as soon as it learned about the death, one
member could drop m1 while another had already processed it. The members
would no longer agree, and TOM ordering would break.

### Why this is correct (G5)

1. **`closed()` comes after every packet.** When X dies, the runtime closes
   X's queues. On the other side, `CMessageQueue.closed()` becomes true only
   when the incoming buffer is empty. So `closed()` is reported after the
   last packet X sent. A member that leaves closes its queues itself, after
   `Membership` has posted `LEAVE`: the same argument applies.
2. **A send to several members is atomic.** The Executor runs one callback at
   a time and draws a random failure *before* running a callback, never in the
   middle. `MessageQueue.send` puts the packet directly in the peer's buffer.
   So a callback that sends to all members either runs entirely (every member
   gets the packet) or not at all (none does).
3. Therefore every member that installs the view without X has received exactly
   the packets X sent to it (point 2), all of them before `closed()` (point 1),
   and installs the view only after `closed()` (the rule). Packets from X are
   delivered while X is still in the installed view, so they are not dropped.

### Accepted links

A link opened by X to me is identified by X's HELLO. Could `FAIL(X)` be
installed while that HELLO is still unread, so that the link is ignored?
No: in `CQueueBroker.connect`, the `accepted` callback is posted on my task
*before* `connected` is posted on X's task. So I register the queue's listener
before X can send its HELLO, and the HELLO's delivery is posted before X dies,
hence before `FAIL(X)` reaches me. This relies on the order of the course's
`CQueueBroker`, which is part of the repository.

### Joins and missed messages (G2, G8)

- Old members connect to the newcomer and send HELLO; the newcomer has a brand
  new broker and new links.
- An old member sends to the newcomer only once its installed view contains
  it, which happens after its link to the newcomer is up. So the newcomer
  receives nothing sent in an earlier view, in particular nothing sent while a
  reinserted Actor was dead (G8).
- The newcomer installs its first view only when every old member has linked to
  it, so it can reach all of them.

### Reinsertion (G9)

`actor.resurrect()` gives a new Actor with incarnation + 1. It is admitted with
its old id. If the old incarnation is still in the view (the detector has not
run yet: suicide, resurrection and admission in the same callback),
`Membership.joined` first applies `FAIL(old)`, then `JOIN(new)`. Every member
installs both views, in this order.

### Leave

`leave()` first calls `Membership.left`, which posts `LEAVE` to the others,
then closes all the member's queues and unbinds its port. Later callbacks of the
member that left are ignored, and `view()` returns `null`. Its Actor stays alive.

### One identity per membership

`Membership` refuses to admit an identity `(id, incarnation)` that was already
used in the group, and reserves it at `admit` time. Otherwise an Actor that left
and came back with the same identity would have old and new packets mixed up,
both here and in TOM (message ids are `sender.incarnation#sequence`). An Actor
that left rejoins under a new id.

## Dropping traffic

- To a removed member: after the new view, `send` checks the installed view and
  drops the packet before using a queue. Before that, `MessageQueue.send`
  returns `false` once the other side is closed, and the packet is lost (G7).
- From a removed member: after the new view, its link is removed, and
  `received` drops any packet whose sender is not in the installed view.

## Tests

Both test classes have a `main` method; run them with **Run As > Java
Application** (launchers `Group Tests.launch` and `Group Dynamic Tests.launch`).
A failed check prints `FAIL` and exits with status 1.

`GroupTest` (steps 1 and 2):

| Test | Checks |
| --- | --- |
| `formation` | 4 members install the same view 1; every member receives one packet from each member, including itself (G1, G2). |
| `failure` | Member 2 sends 3 packets then dies. Each survivor sees exactly `view 1, 2:x0, 2:x1, 2:x2, view 2 [1, 3, 4]`; member 2 receives nothing after death; a send to 2 is dropped (G3, G4, G5, G7). |
| `failureWithBacklog` | Member 2 sends 100 packets in one callback then dies: each survivor receives all 100, in order, then view 2 (G5). |
| `twoFailures` | Members 4 and 2 die in the same callback: all survivors install view 2 then view 3 and receive the last packet of each (G5, G6). |

`GroupDynamicTest` (steps 3 and 4):

| Test | Checks |
| --- | --- |
| `reinsertion` | Member 2 dies, the others send, 2 is resurrected and admitted. Views 1, 2, 3 everywhere; 2#2 in view 3; the reinserted member's first event is view 3 and it never gets what was sent while it was dead (G8, G9). |
| `quickReinsertion` | Suicide, resurrection and admission in one callback: views `[1,2,3]`, `[1,3]`, `[1,2,3]` (G9). |
| `join` | A new actor joins; it does not receive a packet sent just before; traffic flows both ways; a duplicate id is refused. |
| `leave` | Member 3 sends then leaves: the others get its packet, then the view without it; it receives nothing more; it rejoins under a new id. |
| `concurrentChanges` | Join, failure and leave in the same callback: everybody installs the same views. |
| `joinDuringFailure` | A newcomer sends at once while the others drain a dead member's 100 packets: its packet is kept and delivered after its view. |
| Random scenarios | 5 initial members multicast batches of 1 to 8 packets every *period* ms (5 ms and 0 ms burst mode), while 40 random actions run: suicides, resurrections with reinsertion, leaves, rejoins, new joins; then the same with random failures from the runtime. |

The random scenarios check, on every packet and view observed:

- views with the same `viewId` are identical, and each member installs
  consecutive views (G6);
- no member receives a packet from a view before its first one (G8);
- FIFO order per sender;
- agreement: members present during X's whole membership received exactly
  the same packets from X (G5);
- completeness: every packet X sent to a member that survived was received by
  it, in order (G2).

The period and the seed can be passed as program arguments: `0 42` runs only
the burst mode with seed 42.

**Checking the tests themselves.** Each installation condition was broken on
purpose, one at a time (`FAIL` installed at once, `JOIN` installed at once,
newcomer installed without its links, no buffering of a newcomer's early
packets). Each of these modified versions fails at least one test.

**Results.** All tests pass: `GroupTest` and `GroupDynamicTest` were run
repeatedly, with periods 0, 3, 5 and 7 ms and more than ten seeds. Arhan's
`RuntimeTest` and Youssef's `TomProcessTest` still pass.

## Known limits

- A failure during formation, before view 1, is not handled.
- `GroupManager` and `admit` take the single `Actor.Listener`; a
  multi-listener API is requested from Arhan if the Verifier needs it.
- Integration with TOM: `TomProcess` needs a method to update its members on
  `viewInstalled`, and the delivery rule at a view change is defined with
  Youssef.