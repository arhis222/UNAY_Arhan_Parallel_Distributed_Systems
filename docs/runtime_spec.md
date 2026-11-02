# Task runtime and Actors - Specification

Arhan UNAY

## Task events and clocks

Every task has its own logical clock, initially zero. The clock advances by
one immediately before each posted runnable starts. A dropped runnable does
not advance the clock. Immediate events are FIFO; delayed events become
eligible after their delay. Posting from another Java thread is supported.
`Task.task()` returns the running task only on the event pump thread.

`clock()` reads the counter. `receiveClock(t)` applies `max(clock, t) + 1`
inside the receiving task. It is an additional receive event, after the tick
for starting the runnable. The message protocol must call it with the sender's
timestamp; the queue transport does not decode timestamps itself.

## Termination

`kill()` fails a task, removes immediate and delayed pending events and closes
its registered queues. Future posts are ignored. Killing an already dead task
has no additional effect. `fail(cause)` and `exit(result)` run on the event
pump; exit is normal completion. A task listener is notified on the task that
registered it, provided that observer task is still alive.

Failure and exit of the currently running task abort the current callback.
Callbacks must not catch the runtime's internal termination signal. They must
be short and nonblocking. External kill requests are processed at an event
boundary, before further pending application callbacks. They do not interrupt
arbitrary Java instructions inside an already running callback. An infinite
loop or a blocking callback would stall this single event pump.

## Actors

An Actor owns a main task and any child tasks created through it. A child
created through an owned task also belongs to the same Actor. Actor-to-actor
application communication must use message queues; lifecycle objects are for
runtime/group management, not shared application state.

Failure of any owned task fails the whole actor and stops all its tasks before
a death notification is delivered. Normal completion of a child does not
fail its actor. `suicide()` kills the actor even if its main task has already
completed. Death is reported once on a protected runtime monitor task.

`resurrect()` is allowed once on a dead incarnation and returns a new Actor
with the same name and an incremented incarnation. Its tasks have fresh clocks
and no old callbacks or application state. It does not join a group, reconnect
queues or replay missed messages. Group admission is a separate operation.

## Random failures

`Executor.configureFailures(probability, seed)` sets the probability of failure
before each eligible callback. The default probability is zero. A task's
`canFail` flag controls random failure only; explicit failures still work.
An actor's initial flag is copied to its owned tasks. Infrastructure tasks are
protected by default. A fixed seed reproduces choices for the same event order;
concurrent external posting can change that order.
