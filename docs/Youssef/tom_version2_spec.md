# TOM Step 2 - With Failures and No reinsertion - Specification

## 1. What it does

Some members die and never come back. The members that stay alive keep delivering the same messages, with the same content, in the same order.

A dead member is not checked: it may have delivered less than the others.

For now  we do nothing about resurrection, join or leave here.

## 2. What we assume

On top of the assumptions of `tom_spec.md`:

- A member either works or is dead. Only actor failures are
  considered. A dead member sends nothing anymore.
- The failure detector is perfect: every survivor is told about every death,
  and nobody alive is declared dead.
- The group layer tells `TomProcess` with `memberDied(id)`.
- Messages on a link are never reordered or skipped. So if a member sent packet A before packet B to the same
  destination, nobody gets B without A.
- Once a member is dead, the group layer drops what is sent to it and what
  arrives from it.

## 3. What is new in TomProcess

- A method `memberDied(id)`, called by the group layer.
- A third kind of packet, `LEFTOVER`.
- `TomProcess` can be blocked for a while: it receives and acknowledges
  messages, but does not deliver.

## 4. The problem

Version 1 (Without failures) waits for the ACK of every member before delivering. A dead member
never sends its ACK, so all the other members would wait forever.

Removing the dead member from the list is not enough. If member `p` dies while
it multicasts, its DATA may have reached only some of the member still alive:

- the members who have it wait for an ACK from the others,
- the others never saw it, so they never send this ACK.

The first ones are stuck. If they drop the message to get unstuck, one of them
may already have delivered it, and then the members still alive do not deliver the same
messages anymore.

## 5. The steps

When member `q` is told that `p` died:

1. Remove `p` from the members. Its ACKs are not needed any more.
2. Ignore what arrives from `p` from now on.
3. Send the new LEFTOVER(`p`) packet to every survivor. It contains the DATA from `p` that `q`
   has received and not delivered yet.
4. Stop delivering until a LEFTOVER(`p`) has arrived from every survivor.
5. When a LEFTOVER arrives, every DATA in it that `q` does not know is handled like a DATA received normally: put in the queue, and acknowledged to all.
6. When all the LEFTOVER packets have arrived, deliver again, as in step 1.

During the wait, `q` still receives DATA and ACK, and still can multicast. It just does not deliver.

## 6. Example

Members 1, 2 and 3. Member 1 multicasts `m` and dies after sending it to member 2 only.

1. Member 2 has `m` in its queue and acknowledges it. Member 3 does not know
   `m`, so it sends nothing about it.
2. Members 2 and 3 are told that 1 died. Both remove 1 and stop delivering.
3. Member 2 sends LEFTOVER(1) with `m`. Member 3 sends LEFTOVER(1) with nothing.
4. Member 3 receives the LEFTOVER of 2: `m` is new, so it queues it and acknowledges it to 2 and 3. Member 2 receives the LEFTOVER of 3: nothing new.

5. Both have all the LEFTOVER packets, both have the ACK of 2 and 3 for `m`: both deliver `m`.
