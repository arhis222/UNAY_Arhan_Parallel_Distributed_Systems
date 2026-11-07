# Totally-ordered multicast - Files Structure

Youssef Kirlos

The code is in `src/edu/polytech/tom/`.

The tests in `src/edu/polytech/tom/tests/`.

The documents are in `docs/totally-ordered-multicast/`.

## Code

| File | What it does |
| --- | --- |
| `TotalOrderMulticast.java` | The interface used by an actor: `multicast(payload)` to send to the whole group, it returns the sequence number of the message. A listener is called with `delivered(senderId, incarnation, sequence, payload)`, in the same order on every member. |
| `TomMessage.java` | The packets exchanged between members: DATA, ACK and LEFTOVER. It gives the message id, the order (timestamp, then sender) and the conversion to and from bytes. |
| `TomProcess.java` | The multicast of one member of a group that does not change. It has the logical clock, the queue of pending messages and the ACKs, and it delivers a message when everybody acknowledged it. It handles members that die. |
| `TaskClock.java` | The logical clock of a member, on top of the clocks of the tasks of the runtime: it follows the clock of the running task and counts by itself. |
| `TomMember.java` | The link with the Group, for a group that changes: each view is a period with its own `TomProcess`, and the periods deliver in order. So members can die, leave, join and come back. It also passes the views on to whoever asks with `setViewListener`, for instance to know when the group is ready. |

## Tests

Each test is run as a Java application: Run As > Java Application on its file in Eclipse, with the option `-ea`.

| File | What it checks |
| --- | --- |
| `TomMessageTest.java` | The packets: DATA, ACK and LEFTOVER keep their content through bytes, the message id, and the order. |
| `TomProcessTest.java` | `TomProcess` on a fake network in memory (random order, FIFO per link): one or several senders, an ACK before its DATA, 5 members, a burst of 60 messages, and members dying before, during and after a multicast. All the members still alive deliver the same messages in the same order. |
| `TomRandomDeathsTest.java` | Random runs where some members die at random moments in the middle of what they send. The survivors must deliver the same messages in the same order, without duplicates and without losing their own messages. It uses `TomProcessTest`. |
| `TomMemberTest.java` | `TomMember` on a Group simulated in memory, where the test decides when each member installs a view and when the packets arrive, to make some members late: a late member after a death, a join with late members, an older message that comes before a newer one, and a member removed while an older message waits. |
| `TomGroupTest.java` | `TomMember` with real Actors and a real Group: a suicide, a member that comes back and misses what was sent while it was dead, a join, a leave, a load of messages with a death at 5 ms and at 0 ms, and a join while the others are sending. |

## Documents

| File | What it says |
| --- | --- |
| `tom_spec.md` | The multicast without failures: assumptions, messages, order, the protocol, an example, and why it is correct. |
| `tom_process_spec.md` | How to create and use `TomProcess`, with an example. |
| `tom_version2_spec.md` | The multicast with members that die and never come back: the LEFTOVER packet, the rules, an example, why it is correct, and the contract for the verifier. |
| `tom_version3_spec.md` | The multicast with members that leave, join and come back: a period for each view, an example and the tests. |
