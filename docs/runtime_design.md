# Task runtime and Actors - Design

Arhan UNAY

## Scheduling

I use the existing Executor and Task platform so queue callbacks keep their
current task context. Executor has an immediate deque and a priority queue
for delayed events, ordered by deadline and insertion sequence. Queue changes
are synchronized. The pump waits when idle and is notified when work arrives.
Deadlines use a monotonic clock. Application callbacks run outside the queue
lock, allowing other threads to post events.

Only the pump changes task lifecycle state. An external kill is added as a
priority control event on a protected task. When death is processed, both
pending queues are purged. Dispatch also checks the task's dead flag.

CTask keeps its clock, owner, listener and registered message queues. It marks
itself dead before propagating failure to its Actor. The Actor marks itself
dead before stopping siblings, so recursive notifications have no effect.
After all tasks are stopped, its monitor delivers one death notification.

A private termination signal unwinds a callback after suicide, fail or exit.
It is caught by the pump. Other uncaught callback errors become task failures.
The runtime does not use Thread.stop.

## Object graph

    Executor
      |-- ready --> Event(task, runnable)
      |-- delayed --> Event(task, runnable, deadline)
      |-- current --> CTask
      +-- control --> protected CTask

    Actor (name, incarnation)
      |-- main --> CTask
      |-- tasks --> owned CTasks
      |                +-- owner --> Actor
      +-- monitor --> protected CTask --> death listener

    CTask
      |-- clock
      |-- queues --> registered CMessageQueues
      +-- listener --> observer registered on another task

## Integration API

Create Actors from a bootstrap callback or another runtime event:

    Actor actor = new Actor("member-1", true);
    actor.setListener((deadActor, cause) -> {
        // Group management records the death here.
    });
    actor.post(() -> {
        Task current = Task.task();
        long timestamp = current.clock();
        Task worker = current.newTask("worker");
    });

`actor.newTask(name)` also creates an owned task. `actor.post(r, delay)` posts
to its main task. `actor.suicide()` can be called inside or outside the pump.
Constructors, listener registration and resurrection run on the pump.

Adam's group layer must use the death listener, filter traffic from dead
incarnations and admit replacement Actors before enabling communication.
Broker names should include the incarnation: the current broker registry
retains names, so restarting with an identical broker name is not supported.

Youssef's TomProcess currently has its own protocol clock. It is unchanged in
this contribution. Integration must connect its send/receive timestamp rules
to Task.clock()/receiveClock(), with protocol callbacks on the actor's task.
The standalone TOM tests still exercise its existing clock.

Abdallah can set the failure probability and seed from the scenario runner.
The Verifier and group infrastructure must use protected tasks. Random failure
happens before a runnable executes, so a failed runnable produces no delivery.

## Validation

Run `Runtime Tests.launch` in Eclipse, or run
`edu.polytech.actors.tests.RuntimeTest` as a Java application with assertions.
The test has its own main method and needs no JUnit dependency.

It checks independent clocks, receive timestamps, immediate/delayed event
cancellation, external kills, suicide, sibling failure, one-time resurrection,
protected tasks, random failure at probability 1, concurrent posting and queue
closure on actor death. It also checks normal completion, delayed event order
and a kill request arriving during a running callback. Failures exit with
status 1; success prints PASS. Deliberate kills can print failure traces.

Java sources were compiled with `javac --release 8`. RuntimeTest passed 20
consecutive runs and a final rerun. TomMessageTest, TomProcessTest and the
existing channel ComplianceTests passed. The channel echo test passed with
4 clients, 5 connections each and 32768 bytes per connection. The queue echo
test passed with one client sending one byte, and with 8 clients making
10 connections each and sending 65536 bytes per connection.

On 2 November 2026, a clean copy of commit 1855c18 was imported into a new
Eclipse 2026-09 workspace on macOS ARM64, using the existing project files.
Eclipse generated the class files without changing the build configuration.
The project reported a warning about its unspecified text encoding.

RuntimeTest, the channel and queue launch configurations, TomMessageTest,
TomProcessTest and ComplianceTests all passed using these Eclipse-built
classes and the Java runtime bundled with Eclipse. The tests were started
from the terminal because UI automation timed out after import. Running the
launch configurations from the Eclipse UI still needs to be checked. The
final integrated submission will also need a fresh import test.

The final runtime check also covers changing a task's failure flag and normal
completion of an Actor's main and child tasks before suicide. The expanded
RuntimeTest passed ten consecutive runs. All six test suites passed again
with the Eclipse Java compiler and bundled Java runtime, including the
channel and queue stress settings above. The tracked project preferences
already specify UTF-8. The import still produced an encoding warning, so
its workspace handling needs to be checked during the final UI validation.
