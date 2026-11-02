# Group Layer - Specification

Author: Adam TAWFIK 
Package: `edu.polytech.groups`
Status: steps 1 (initial formation) and 2 (failures without reinsertion) implemented.
Steps 3 (reinsertion) and 4 (voluntary joins and leaves) are not implemented yet.

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

The group layer does not order messages. It only decides who is in the group
and which packets may reach which member.

## Concepts

- **Member**: an Actor that joined the group under an integer `id`, unique in
  the group.
- **MemberId**: `(id, incarnation)`. The incarnation is the Actor's
  incarnation (`Actor.incarnation()`); it increases each time the Actor is
  resurrected.
- **View**: `(viewId, members)`. `viewId` starts at 1 and increases by exactly
  one on every membership change. Members are sorted by id.
- **Installed view**: the view a member currently uses. Each member installs
  views through the `viewInstalled` callback.

## Interface

```java
GroupManager manager = new GroupManager(n);         // on the event pump
manager.join(actor, id, group -> {                  // once per member
  group.setListener(listener);                      // set it in joined()
});
```

`Group` (one per member):

| Method | Contract |
| --- | --- |
| `MemberId self()` | Identity of this member. |
| `View view()` | Installed view, or `null` before the group starts. |
| `void send(int to, byte[] packet)` | Sends a packet to member `to`, possibly itself. Silently dropped if `to` is not in the installed view or this member is dead. |
| `void setListener(Listener l)` | Receives packets and view changes. |

`Group` implements `TomProcess.Transport`, so TOM uses it directly:
`new TomProcess(id, incarnation, group.view().ids(), group)`.

`Group.Listener`:

| Callback | Called when |
| --- | --- |
| `received(int from, byte[] packet)` | A packet arrives from a member of the installed view. |
| `viewInstalled(View view)` | A new view is installed: initial group, then each failure. This is the group's listener for deaths: a member missing from the new view is dead (later also for joins and leaves). |

`Group.JoinListener.joined(Group g)` is called once, just before the first
`viewInstalled`.

All methods must be called on the event pump. All callbacks run on the member's
group task, which belongs to its Actor.

## Guarantees

**G0 - Manager used only for formation.** Once view 1 is installed, the group
is peer-to-peer; the group manager is no longer used.

**G1 - Common initial view.** Once `n` members have joined, every member
installs view 1 containing exactly these `n` members, with the same order.

**G2 - Links.** In an installed view, every member can send to every other
member and to itself. Packets between two members arrive in FIFO order, with
their content unchanged.

**G3 - No callback after death.** A dead member never receives `received` or
`viewInstalled` again. Its pending callbacks are cancelled by the runtime.

**G4 - Failure detection.** When a member of the installed view dies, every
survivor installs the next view, without the dead member, exactly once.
Detection is perfect: no live member is ever excluded. A member is considered
dead by a survivor from the moment that survivor installs this view; from then
on, every packet to or from the dead member is dropped, as required by the
assignment.

**G5 - Same packets from a dead member.** Before installing the view that
excludes a dead member `X`, every survivor has received exactly the same set
of packets from `X`. After that view, no survivor receives anything from `X`.

**G6 - Same sequence of views.** All survivors install the same views in the
same order. Concurrent failures produce one view per failure.

**G7 - Traffic to dead members is dropped.** `send` to a dead or excluded member
has no effect and does not raise an error.

## Assumptions and limits

- Group members never fail during formation, before view 1 is installed. The tests
  create members with `canFail = false`.
- `GroupManager` registers itself as the Actor's `Actor.Listener`. The current
  Actor API accepts only one listener; another component that needs death
  notifications must get them from the group, or Actor must support
  several listeners.
- Member ids must be unique within one `GroupManager`.
- A packet sent in one callback to several members is atomic with respect to
  failure: either all of them receive it or none do (see the design).
  This does not hold if the sender calls `suicide()` in the middle of the loop.

## Planned extensions

- **Step 3 - Reinsertion:** a resurrected Actor (`incarnation + 1`) joins
  again as a new member. It must not receive any packet sent while it was dead.
- **Step 4 - Voluntary join and leave:** same view-change mechanism, triggered
  by the member itself.
- The point at which a view change takes effect relative to TOM delivery is to be
  defined jointly with Youssef (TOM).