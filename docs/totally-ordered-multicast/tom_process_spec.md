# TomProcess - Specification


## 1. What it is

`TomProcess` is the multicast of one member of a group that does not change. It
runs the protocol of the spec (clock, pending queue, ACKs, delivery), and it can
handle members that die. When the group changes (members that leave, join or come
back), `TomMember` gives a new `TomProcess` to each view, see `tom_version3_spec.md`.

## 2. How to create it

    new TomProcess(self, incarnation, members, transport)

- `self`: id of this member.
- `incarnation`: 1 at the start, goes up if the actor is resurrected.
- `members`: ids of all the members of the group, including `self`. The
  constructor throws if `self` is not in it, and keeps its own copy of the set.
- `transport`: gives `send(to, packet)`, to send a packet to a member. It must
  also work for `to == self`.

## 3. What we can call

- `setListener(l)`: `l.delivered(senderId, incarnation, sequence, payload)` is
  called for each message, in the same order on every member.
- `multicast(payload)`: sends the payload to all members, including me. It
  returns the sequence number of the message.
- `onPacket(packet)`: to call each time a packet arrives for this member.
- `memberDied(id)`: the member is dead. It runs the LEFTOVER exchange of
  `tom_version2_spec.md`, for a group where the survivors may not have the same
  packets of the dead member.
- `removeMember(id)`: the member is removed and nothing is waited for from it. For
  a group that gives the same packets of this member to everybody, like the Group:
  no LEFTOVER is needed.
- `nextSequence()`, `setNextSequence(n)`: to go on with the sequence numbers in
  the next `TomProcess`, so that a message identity is not used twice.
- `finished()`: true when everything received was delivered and nothing is
  waited for.
- `setClock(c)`: to use another logical clock than the counter of the
  `TomProcess` (`TomMember` gives it `TaskClock`). A clock has `tick()` for a
  packet that is sent and `receive(t)` for a packet that arrives with the timestamp `t`.


## Example of what should happen

Two members, 1 and 2. Member 1 multicasts "hi".

1. Member 1: clock 1, sends DATA("hi", timestamp 1) to 1 and 2.
2. Member 1 receives its own DATA: clock 2, puts it in the queue, then clock 3
   and sends ACK to 1 and 2.
3. Member 2 receives the DATA: clock 2, puts it in the queue, then clock 3 and
   sends ACK to 1 and 2.
4. Member 1 receives the two ACKs, from 1 and from 2. Now "hi" has the ACK of
   everybody and is first in the queue: member 1 delivers "hi".
5. Member 2 receives the two ACKs and delivers "hi" too.

The ACKs can arrive in any order, and member 2 may even get an ACK before the
DATA.
