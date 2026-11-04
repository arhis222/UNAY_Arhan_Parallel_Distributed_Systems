# Totally-ordered multicast - Files Structure 

Youssef Kirlos

The code is in `src/edu/polytech/tom/`.

The tests in `src/edu/polytech/tom/tests/`.

The documents are in `docs/totally-ordered-multicast/`.

## Code

| File | What it does |
| --- | --- |
| `TotalOrderMulticast.java` | The interface used by an actor: `multicast(payload)` to send to the whole group, and a listener called with `delivered(senderId, payload)`, in the same order on every member. |
| `TomMessage.java` | The packets exchanged between members: DATA, ACK and LEFTOVER. It gives the message id, the order (timestamp, then sender) and the conversion to and from bytes. |
| `TomProcess.java` | The multicast of one member. It has the logical clock, the queue of pending messages and the ACKs, and it delivers a message when everybody acknowledged it. |

## Tests

| File | What it checks |
| --- | --- |
| `TomMessageTest.java` | The packets: DATA, ACK and LEFTOVER keep their content through bytes, the message id, and the order. |
| `TomProcessTest.java` | `TomProcess` on a fake network in memory (random order, FIFO per link): one or several senders, an ACK before its DATA, 5 members, a burst of 60 messages, and members dying before, during and after a multicast. All the members still alive deliver the same messages in the same order. |
| `TomRandomDeathsTest.java` | Random runs where some members die at random moments in the middle of what they send. The survivors must deliver the same messages in the same order, without duplicates and without losing their own messages. It uses `TomProcessTest`. |

## Documents

| File | What it says |
| --- | --- |
| `tom_spec.md` | The multicast without failures: assumptions, messages, order, the protocol, an example, and why it is correct. |
| `tom_process_spec.md` | How to create and use `TomProcess`, with an example. |
| `tom_version2_spec.md` | The multicast with members that die and never come back: the LEFTOVER packet, the rules, an example, and why it is correct. |
