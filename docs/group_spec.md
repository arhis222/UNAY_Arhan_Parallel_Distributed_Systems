# Group Layer - Specification

Author: Adam TAWFIK (`adam-master`)
Package: `edu.polytech.groups`
Status: all four steps implemented and tested: initial formation, failures,
resurrection and reinsertion, voluntary joins and leaves.

## Purpose

The group layer gives each member Actor a view of the group and point-to-point
links to every other member. It sits between Arhan's Actor runtime and
Youssef's totally-ordered multicast (TOM):

```
Application / Verifier
        |
  TOM (TomProcess)          ordering, ACKs, delivery
        |
  Group (this layer)        membership, views, links, failure detection
        |
  Actor + QueueBroker / MessageQueue
```

The group layer does not order messages. It decides who is in the group and
which packets may reach which member.

## Concepts

- **Member**: an Actor that joined the group under an integer `id`, unique
  among the current members.
- **MemberId**: `(id, incarnation)`. The incarnation is the Actor's
  incarnation (`Actor.incarnation()`); it increases each time the Actor is
  resurrected. A `MemberId` identifies one membership: it is used only once
  per group.
- **View**: `(viewId, members)`. `viewId` starts at 1 and increases by exactly
  one on every membership change (failure, join, reinsertion, leave). Members
  are sorted by id.
- **Installed view**: the view a member currently uses, announced by the
  `viewInstalled` callback.

## Interface

Initial group (step 1):

```java
GroupManager manager = new GroupManager(n);         // on the event pump
manager.join(actor, id, group -> {                  // once per initial member
  group.setListener(listener);                      // set it in joined()
});
```

Running group (steps 3 and 4), through any current member, the contact:

```java
contact.admit(newActor, 7, joinListener);           // voluntary join
contact.admit(actor.resurrect(), 2, joinListener);  // reinsertion, same id
group.leave();                                      // voluntary leave
```

`Group` (one per member):

| Method | Contract |
| --- | --- |
| `MemberId self()` | Identity of this member. |
| `View view()` | Installed view; `null` before the first view and after `leave()`. |
| `void send(int to, byte[] packet)` | Sends a packet to member `to`, possibly itself. Silently dropped if `to` is not in the installed view, or if this member is dead or has left. |
| `void setListener(Listener l)` | Receives packets and view changes. |
| `void admit(Actor a, int id, JoinListener l)` | Asks the group to admit `a` under `id`. Throws `IllegalArgumentException` if `id` is used by a live member or `(id, incarnation)` was already used in this group. |
| `void leave()` | Leaves the group. Calling it again has no effect. |

`Group` implements `TomProcess.Transport`, so TOM uses it directly:
`new TomProcess(id, incarnation, group.view().ids(), group)`.

`Group.Listener`:

| Callback | Called when |
| --- | --- |
| `received(int from, byte[] packet)` | A packet arrives from a member of the installed view. |
| `viewInstalled(View view)` | A new view is installed. This is the listener for deaths, joins, reinsertions and leaves: the difference with the previous view tells who left or arrived. |

`Group.JoinListener.joined(Group g)` is called once, just before the member's
first `viewInstalled`.

All methods must be called on the event pump. All callbacks run on the member's
group task, which belongs to its Actor.

## Guarantees

**G0 - Manager used only for formation.** Once view 1 is installed, the group
is peer-to-peer; the group manager is no longer used.

**G1 - Common initial view.** Once `n` members have joined, every member
installs view 1 containing exactly these `n` members.

**G2 - Links.** In an installed view, every member can send to every other
member and to itself. Packets between two members arrive in FIFO order, with
their content unchanged. If a member `X` sends a packet to `R` while `R` is in
`X`'s installed view, and `R` neither dies nor leaves, `R` receives it.

**G3 - No callback after death or leave.** A dead member, or a member that
left, never receives `received` or `viewInstalled` again.

**G4 - Failure detection.** When a member dies, every other member installs
the next view, without it, exactly once. Detection is perfect: no live member
is ever excluded. A member is considered dead by another member from the
moment that member installs this view; from then on, every packet to or
from the dead member is dropped, as required by the assignment.

**G5 - Same packets from a member that is removed.** When `X` dies or leaves,
every member that installs the view without `X` has received, before that
view, exactly the same packets from `X`, in the same order. After that view,
nothing from `X` is received.

**G6 - Same sequence of views.** Two members that install a view with the
same `viewId` install the same members. Each member installs consecutive
views, in the same order as every other member.

**G7 - Traffic to removed members is dropped.** `send` to a dead, removed
or departed member has no effect and does not raise an error.

**G8 - Missed messages stay missed.** A member that joins, or a resurrected
Actor that is reinserted, first installs the view that includes it. It never
receives a packet sent before that view, in particular nothing sent while it
was dead.

**G9 - Reinsertion.** A resurrected Actor rejoins with the same `id` and
incarnation + 1. If its previous incarnation is still in the view (its death
was not reported yet), the members first install the view without the old
incarnation, then the view with the new one.

## Assumptions and limits

- Members never fail during formation, before view 1 is installed. The tests
  create the initial members with failures disabled during formation.
- `GroupManager` and `admit` register the group as the Actor's
  `Actor.Listener`. The current Actor API accepts only one listener; another
  component that needs death notifications must get them from the group, or
  Actor must support several listeners.
- An identity `(id, incarnation)` is used only once per group. An Actor that
  left can rejoin under a new id. This is needed because packets, and TOM
  message ids, are identified by `(id, incarnation)`.
- A packet sent in one callback to several members is atomic with respect to
  failure: either all of them receive it or none do (see the design). This does
  not hold if the sender calls `suicide()` in the middle of the loop.
- The group layer does not decide which TOM messages are delivered across a view
  change; this is TOM's part (Youssef), using `viewInstalled`.