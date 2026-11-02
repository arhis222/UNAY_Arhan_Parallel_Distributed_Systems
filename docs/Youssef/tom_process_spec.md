# TomProcess - Specification


## 1. What it is

`TomProcess` is the multicast of one member. There is one `TomProcess` per
actor. It runs the protocol of the spec (clock, pending queue, ACKs, delivery)
without failures for now.

## 2. How to create it

    new TomProcess(self, incarnation, members, transport)

- `self`: id of this member.
- `incarnation`: 1 at the start, goes up if the actor is resurrected.
- `members`: ids of all the members of the group, including `self`. The
  constructor throws if `self` is not in it, and keeps its own copy of the set.
- `transport`: gives `send(to, packet)`, to send a packet to a member. It must
  also work for `to == self`.

## 3. What we can call

- `setListener(l)`: `l.delivered(senderId, payload)` is called for each
  message, in the same order on every member.
- `multicast(payload)`: sends the payload to all members, including me.
- `onPacket(packet)`: to call each time a packet arrives for this member.


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
