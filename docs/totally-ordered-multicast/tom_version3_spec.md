# TOM with membership changes - Specification

## 1. What it does

Members can die, leave, join, or come back after a resurrection. The members that
are in the group deliver the same messages in the same order. A member that joins or
comes back never delivers what was sent before it was in the group.

## 2. What we assume

From the Group of Adam (`group_spec.md`):

- Every member installs the same views, in the same order (G6).
- Links are FIFO (G2).
- When a member is removed, everybody got the same packets from it before the new
  view (G5).
- A member that joins or comes back gets nothing sent before its first view (G8).

## 3. The idea: one period for each view

Each view is a period with its own `TomProcess`, for a group that does not change.
`TomMember` manages the periods:

- Each packet has the number of its period. A packet of a view that I did not
  install yet is kept until I install it.
- A member that left or died is removed from the periods not over (`removeMember`).
- When I install a new view, I send CLOSE for the period that ends: I send nothing
  more in it.
- A period is over when the members still there closed it and its messages are
  delivered. The application gets nothing from a period until the older ones are over.
- The sequence numbers go on from one period to the next, so a message identity is
  never used twice.

## 4. Example

Members 1, 2 and 3. Member 3 dies and comes back.

1. View 1 `{1, 2, 3}`: `a1`, `a2`, `a3` are delivered by all.
2. View 2 `{1, 2}`: `b1`, `b2` are delivered by 1 and 2 only.
3. View 3 `{1, 2, 3}`: member 3 is back with a new `TomProcess`. `c1`, `c2`, `c3` are
   delivered by all three. Member 3 does not deliver `a` and `b`.

If member 2 installs view 3 late, it keeps what arrives for view 3 until it does.

## 5. Why it is correct

- Inside a period the group does not change: it is the protocol of the first versions.
- Everybody delivers the periods in the same order, so the order across periods is
  the same for everybody.
- A period is over when everybody still there closed it, and the link is FIFO, so
  nothing more can come. For a removed member, G5 says everybody has the same packets.
- A newcomer has no `TomProcess` for the older periods and receives nothing from
  them, so it cannot deliver what it missed.

## 6. Tests

- `TomGroupTest` (real Actors and Group): a member comes back, joins, leaves, a load
  at 5 ms and 0 ms with a death, and a join while the others send.
- `TomMemberTest` (simulated Group, with late members): a late member after a death,
  a join with late members, an older message before a newer one, and a member
  removed while an older message waits.
