# Group Verifier and Independent Harness — Draft Design

Author: Abdallah ABDELSADEK

Status: Stage 1 implemented; envelope and baseline adapter designed, not implemented

Date: 1 November 2026

Last revised: 4 November 2026

## 1. Scope and relation to the specification

This design implements the fixed-group, no-failure observation contract in
[verifier_spec.md](verifier_spec.md), Sections 3–6 and the baseline simulated
scenarios in Section 8. The specification remains authoritative. All classes,
signatures, adapters and scheduling choices below are proposals unless explicitly
identified as confirmed TOM agreements. The confirmed agreements were supplied
by Youssef through Abdallah on 4 November 2026 and are marked **C** in the
specification. They do not imply changes already made to teammates' interfaces
or additional assignment requirements.

The first version checks a nonempty, immutable group of actor incarnations and
a finite workload. It observes direct method reports; it neither runs the
multicast protocol nor decides its ordering. Recovery and dynamic membership
are outside this design. Section 10 identifies the contracts needed later.

The implementation can use ordinary Java 8 classes and collections under the
existing Eclipse `src` directory, with no dependencies or build-system changes.
Proposed packages are `edu.polytech.verifier` and
`edu.polytech.verifier.tests`. No records, newer collection factories, runtime
inheritance or TOM implementation classes are required.

### 1.1 Current integration state

At checkout `f00a619`, the runtime and Actors exist, including task clocks,
task ownership, actor-wide death and fresh incarnations. `TomProcess` also
exists, including DATA/ACK ordering and LEFTOVER handling for failures without
reinsertion. Its current tests connect it through simulated FIFO links.

The complete connection through Actor tasks, Group and real message queues
is absent from this checkout. Group formation/readiness and membership code
are not integrated here, no queue-backed TOM transport is supplied, and
`TomProcess` still uses its own clock rather than task clock operations.
Runtime/queue tests and standalone TOM tests therefore exercise separate paths.
See `../src/edu/polytech/actors/Actor.java`,
`../src/edu/polytech/tom/TomProcess.java`,
`../src/edu/polytech/tom/tests/TomProcessTest.java`, and
`runtime_design.md`, Integration API. These existing components do not imply
an implemented verifier or a working end-to-end scenario runner at that checkout.
The subsequent stage-1 contribution implements only the independent verifier
and simulated-report harness; actual results are in [verifier_tests.md](verifier_tests.md).
Real Actor/Group/queue/TOM integration remains pending Adam/Youssef's work.
The agreements below define observations for that later work, not implemented
connections.

## 2. Small class and value model

The core consists of `GroupVerifier` plus immutable identities, configuration
and result values. Supporting enums and small result values can be nested
classes to avoid a framework of single-purpose files.

| Proposed class | Responsibility |
| --- | --- |
| `ActorId` | Immutable actor name/number and incarnation; value equality. |
| `MessageId` | Immutable execution, group, originating `ActorId` and sequence; value equality. |
| `BaselineScenario` | Validated execution/group identity, fixed participants, planned sends per actor, coverage and runner settings. |
| `GroupVerifier` | Registrations, ordered receiver histories, obligations, accounting, state and assessments. |
| `VerificationResult` | Immutable state/verdict snapshot, diagnostics, missing deliveries and coverage/accounting summaries. |
| `VerifierHarness` | Deterministic simulated-report fixture; no runtime or network. |
| `VerifierBaselineTest` | Java application entry point running independent cases with explicit checks. |

`Diagnostic`, `MissingDelivery`, `SubmissionIssue`, `Phase`, `Verdict` and
`CloseReason` are proposed nested values/enums rather than additional services.

### 2.1 Identities and payload ownership

Proposed identity signatures:

```java
public final class ActorId {
    public ActorId(int actorNumber, int incarnation);
    public int actorNumber();
    public int incarnation();
    // equals(Object), hashCode(), toString()
}

public final class MessageId {
    public MessageId(String executionId, String groupId,
                     ActorId sender, long sequence);
    public String executionId();
    public String groupId();
    public ActorId sender();
    public long sequence();
    // equals(Object), hashCode(), toString()
}
```

Fields are private and final. Strings and nested identities are immutable.
Required values must be non-null, names nonempty, and numeric identity fields
nonnegative. Equality uses every identity field, not payload, timestamp or
object reference. The chosen numeric representation must be mapped to project
identities with Youssef; this is not a prescribed TOM wire format.

The proposed integration adapter maintains an explicit mapping from runtime
`Actor.name()` to a stable numeric member ID within the scenario's group.
It combines that number with `Actor.incarnation()` to construct the verifier's
`ActorId`, and supplies the corresponding number/incarnation to TOM. Different
active actor names must not alias the same numeric ID. Names are not assumed
globally unique merely because the Actor constructor accepts them; the adapter
validates the declared group mapping. Execution/group scope remains explicit
in verifier message identities.

This mapping stays outside the independent verifier, which accepts immutable
identities without importing Actor or TOM classes. Independent tests supply
those identities directly. How a numeric ID is retained or reused after a
membership change remains an Adam/Youssef agreement, not a consequence of
this baseline mapping.

Distinct messages can contain equal payloads. No ordering comparator on these
identities is used to determine delivery order. Sequence identifies a multicast;
it is not the planned-send slot described below.

Registration copies the original `byte[]` before returning. Delivery checking
compares a stable copy of reported bytes against that original, using exact
length and byte equality. Null bytes are invalid observations; an empty array
is a valid payload. Caller arrays must not be mutated during a call and may be
reused after it returns. Payload-bearing diagnostic/result accessors return
copies; no internal mutable array or collection escapes. Retaining every
delivered payload is unnecessary: store identities in histories and retain
only the mismatch evidence needed for diagnostics.

### 2.2 Scenario configuration

Proposed configuration shape:

```java
public BaselineScenario(String executionId, String groupId,
        Map<ActorId, Integer> plannedSends,
        boolean emptyWorkloadTest,
        long minimumConfirmedMessages, long minimumValidDeliveries,
        int periodMillis, long budgetMillis);
```

The map's keys are the fixed participants; its values are finite nonnegative
send counts. Configuration makes a defensive copy and rejects empty groups,
null/invalid fields, negative periods, nonpositive budgets and count overflow.
The two coverage minima are nonnegative and cannot exceed planned messages
or their possible recipient deliveries. A normal traffic scenario must declare
both minima at least one. A dedicated empty-workload scenario requires zero
planned sends and zero minima. A contradictory configuration is rejected with
a configuration diagnostic before workload execution.

Period and budget describe runner settings; the verifier does not sleep or
interpret the period as a delivery deadline. Payload generation belongs to
the harness/runner; registration preserves the actual original independently.
The immutable configuration is retained in every result.

Stage-1 refinement: coverage minima, aggregate planned/confirmed/delivery counts
and report ordinals use `long`, with checked arithmetic for aggregate sums and
products. Individual per-actor send counts and slot indexes remain `int`.
Accounting stores only slots actually registered or assigned an issue, rather
than preallocating the declared workload. Absent slots remain PLANNED and their
count is obtained by subtraction. Result snapshots expose sparse accounted
slots plus the unaccounted count, so a large unexecuted plan does not require
enumerating billions of missing slots. Per-recipient histories use Java list
indexes with an explicit capacity guard. These are storage/representation
choices; checking requirements and completion criteria are unchanged.

## 3. Proposed verifier API and accounting

All signatures are proposed Java 8 methods on ordinary objects:

```java
public GroupVerifier(BaselineScenario scenario);

public void registerMulticast(int plannedIndex, MessageId id, byte[] original);
public void recordSubmissionIssue(ActorId sender, int plannedIndex,
                                  SubmissionIssue issue, String detail);
public void delivered(ActorId receiver, MessageId id, byte[] observed);
public void recordObservationGap(String detail);
public void closeWorkload();
public VerificationResult snapshot();
public VerificationResult closeObservation(CloseReason reason);
```

Each sender has planned slots `0 .. plannedSends(sender)-1`. A slot belongs to
that sender in this execution and is used once. It is separate from a TOM
message sequence. Initially every slot is `PLANNED`. Registration links a slot
to its original message and changes it to `CONFIRMED`.

In this baseline, `registerMulticast` represents the independently observed
valid invocation boundary, not a protocol acknowledgement. It must precede
every resulting delivery report, including synchronous/self delivery. It both
records the original and confirms the invocation for the simulated baseline.
This atomic simulated registration/confirmation remains unchanged. The agreed
real lifecycle instead records the original immediately before invocation and
confirms on normal return while the actor remained alive (Section 9.2).
The proposed minimal API additions in Section 9.4 separate these observations
without changing this atomic method. No new protocol ACK or delivery-dependent
confirmation is implied.

`SubmissionIssue` contains `NOT_INVOKED`, `REJECTED` and `UNCERTAIN`. Before
registration, an issue accounts for a slot that did not become a confirmed
multicast; rejected/uncertain invocation evidence is retained in its diagnostic
but is not promoted to confirmed by a delivery. If a registered invocation
later becomes uncertain or rejected at an adapter boundary, attach the issue
to that slot and retain its original and obligations. Do not erase evidence or
roll it back into a successful no-send execution. A demonstrated rejection or
failure to perform a required baseline invocation is a contract violation and
is unsuccessful. An uncertain outcome caused solely by unavailable evidence
prevents success and leads to incomplete closure unless another concrete
violation exists. This describes baseline verdicts. The confirmed all-or-none
rule for uncertain submissions in failures without reinsertion is separate
(Section 10.1); reinsertion/dynamic-membership submission semantics remain open.

Duplicate/conflicting slot accounting, out-of-range slots and reused message
identities are observation errors. They do not overwrite earlier records.
Calling `closeWorkload` once stops new registrations and issue accounting but
allows outstanding deliveries to arrive. Remaining `PLANNED` slots are listed
as unaccounted; they cannot be silently treated as sends that occurred.

`recordObservationGap` records unavailable reporting evidence, prevents success
and explains an incomplete assessment unless a concrete violation is already
known. It does not invent the missing events. Calling it after observation
closure records the newly known evidence gap and supersedes an earlier success
with an incomplete assessment unless a concrete violation also exists. This
differs from reporting an actual delivery or registration after closure, which
demonstrates a late-event contract violation.

## 4. Internal state and checks

### 4.1 Minimal data structures

Use Java 8 `HashMap`, `HashSet` and `ArrayList`; stable sorting for printed
diagnostics is presentation only. Maintain:

- A copied fixed member set and per-sender slot accounting.
- `Map<MessageId, RegisteredMessage>` with the original bytes and planned slot.
- For each registered message, a set of recipients still owed a valid delivery,
  initially a copy of all members.
- For each recipient, an ordered identity history and a map from identity to
  its first report position, for duplicate diagnostics.
- A diagnostic list, sticky concrete-violation status, reporting-gap evidence,
  phase, report counter and valid
  unique delivery/confirmed-message counters for coverage.

`RegisteredMessage` is a private nested state holder. Only its obligation set
changes; original bytes and registration metadata remain stable. Histories
preserve reported events rather than silently dropping duplicate entries.
Invalid observations retain their diagnostic evidence and cannot restore success.

Storage is O(messages × members + planned slots + diagnostic evidence). This
is acceptable for declared finite test workloads and avoids history cleanup
or an additional persistence layer.

### 4.2 Integrity and duplicates

For a report from a known receiver with well-formed, matching execution/group
identity, append the message identity at that receiver's next history position.
Record the first occurrence separately. A repeat is a duplicate even if its
content matches. An unregistered identity is an observation-contract error;
never create its original from the report.

For a registered identity, compare the delivered payload with the original.
A mismatch is a protocol-property violation under the faithful-reporting
assumption. Only a first, registered, content-correct delivery clears that
receiver's obligation. A duplicate or corrupted copy never clears an obligation
or increases valid-delivery coverage. A later correct duplicate cannot repair
an earlier bad report. Failure remains sticky.

Malformed reports and wrong execution/group/receiver observations produce
observation diagnostics and do not modify another execution's histories.
API misuse is recorded where safely possible rather than crashing an actor
callback. Invalid constructor configuration is rejected before an execution
exists. The eventual implementation may use `IllegalArgumentException` for
that constructor rejection, with the harness/runner reporting its cause.

### 4.3 Incremental compatible-prefix check

When receiver r appends at index k, compare its new identity with index k of
every other receiver whose history already extends that far. A different
identity establishes the first divergence for that pair. Earlier positions
were checked when the second of their two reports arrived, so no complete
history rescan is required. Each append costs O(number of members), apart
from byte comparison and diagnostics.

No reference history is chosen from submission order. For registrations `a,b`,
delivery histories `[b,a]` at every receiver are acceptable. `[b]` and `[b,a]`
are temporarily compatible; `[b,a]` and `[a,b]` are not. Growing a longer
history alone is not proof that a shorter receiver is incorrect.

The histories may retain unknown or duplicate identities for investigation;
any resulting order diagnostic is additional evidence, not a replacement for
the original observation/duplicate diagnostic. No error is hidden by filtering.

### 4.4 Obligations independent of reported histories

Registration creates all `(message, recipient)` obligations, including delivery
to the sender. This happens even if no delivery report ever arrives. In
particular, a message omitted by everyone leaves every recipient in its
outstanding set. Neither equal histories nor the union of observed messages
defines completeness.

Snapshots enumerate missing pairs with their original registration and report
planned, confirmed, issued-with-problem and unaccounted slots. Counters are
summaries, not substitutes for identity-specific checks.

## 5. Execution states, closure and results

The following states and assessment rules describe the implemented fixed-group,
no-failure baseline, including its handling of simulated submission issues.
The later failure extension has separate final survivor checks (Section 10.1).
Keep execution phase separate from verdict:

| Phase | Allowed normal activity |
| --- | --- |
| `OPEN` | Registration, slot accounting and delivery reports. |
| `DRAINING` | Workload closed; delivery reports may discharge obligations. |
| `CLOSED` | Observation ended; new reports are late events. |

`snapshot()` is non-closing. Before observation closure it returns
`IN_PROGRESS`, or `UNSUCCESSFUL` if a concrete violation exists. An empty
outstanding set is only readiness evidence, never automatic final success.

`CloseReason` contains `NORMAL`, `TIMEOUT` and `INTERRUPTED`. Closure assesses
the execution without synthesizing deliveries, closing workload on the caller's
behalf or inferring actor failure. The precedence is:

1. A demonstrated protocol-property, observation-contract or baseline
   submission-contract violation
   yields `UNSUCCESSFUL`, regardless of close reason.
2. Otherwise timeout/interruption yields `INCOMPLETE`, even if obligations happen
   to be empty; successful observation closure was not established.
3. Otherwise missing workload closure, unaccounted slots, outstanding deliveries,
   unavailable reporting evidence or unmet coverage yields `INCOMPLETE`.
4. Otherwise normal closure yields `SUCCESSFUL` for the declared interval.

Normal success also requires every planned slot confirmed, valid content,
duplicate-free compatible histories and the scenario's coverage minima. Since
every confirmed message is then present at every member, the histories are
equal. Zero sends pass only a dedicated empty-workload configuration.

`closeObservation` is idempotent for callers repeating the same close request;
it must not generate two different closure events. A conflicting second reason
is diagnosed as observation misuse. A registration/accounting call after
workload closure is likewise misuse.

Delivery or registration after `CLOSED` records `LATE_REPORT`, does not reopen
the execution, and makes the current assessment unsuccessful. Previously
returned result objects remain immutable historical snapshots; any presenter
must publish the newer assessment and identify the earlier success as
superseded. Reports must not disappear because a success was already printed.
No finite quiet period proves that no future late event can occur; the runner
must establish its observation boundary explicitly.

### 5.1 Diagnostics and immutable results

`VerificationResult` exposes copied/read-only configuration, phase, verdict,
close reason, accounting counts, coverage evidence, outstanding pairs and
diagnostics. Suggested accessors are `phase()`, `verdict()`, `diagnostics()`,
`missingDeliveries()` and `coverageSatisfied()`. Nested values expose no
mutable internals.

| Category | Examples and meaning |
| --- | --- |
| Protocol property | Demonstrated duplicate, content mismatch or incompatible order, assuming reports faithfully represent deliveries; unsuccessful. |
| Observation/configuration | Demonstrated unknown identity/receiver, wrong run, invalid payload, reused identity/slot, late event or contradictory submission evidence; unsuccessful, without proving a TOM defect. |
| Incomplete execution | Unavailable evidence/reporting gap, uncertain submission without a proven violation, timeout, interruption, unaccounted planned sends, missing deliveries or unmet coverage; incomplete at closure unless a concrete violation also exists. Does not prove death or permanent loss. |

Diagnostic presence alone does not set concrete-violation status. Unmet
obligations and coverage always prevent a pass, but do not by themselves prove
permanent message loss or protocol failure. Unavailable evidence is retained
separately from demonstrated violations throughout accounting and assessment.

Each diagnostic carries a stable code, execution/group, report ordinal,
receiver/message/slot where applicable and a readable explanation. Duplicates
include first and repeated positions. Divergences include both receivers,
the position and conflicting identities. Content errors include lengths and
first differing byte, with bounded copied byte excerpts. Missing-message
diagnostics identify all affected recipients, including “absent everywhere.”
Infrastructure errors supplied by the runner retain their origin rather than
being classified automatically as verifier defects.

## 6. Threading and report-ordering contract

The core permits only one active caller at a time and has no background thread,
timer or runtime task. It has no intrinsic thread affinity: serialization, not
the identity of the calling Java thread, protects its state.
Independent tests call it sequentially on their test thread. In real scenarios,
the proposed adapter invokes it directly during application delivery callbacks
on the shared executor thread, preserving each receiver's callback order.
Registration happens on that same serialized path before invoking multicast.
No worker forwards a receiver's reports asynchronously out of order.

The adapter must not reenter the verifier during a verifier call. The verifier
does not call actors, TOM or caller-provided listeners from inside a mutation.
The result of one receiver must not delay the protocol by waiting for another
receiver to report.

An independent watchdog must not concurrently access mutable core collections.
A proposed small `VerifierMonitor` adapter serializes access to the core and
publishes immutable snapshots. Its lock covers only bounded registration,
checking, snapshot or closure operations; it is never held across multicast,
task posting, waiting, protocol execution or output. All real-run calls go
through this adapter. The watchdog can therefore close assessment on timeout
without posting onto a stalled executor. Registration and multicast remain
ordered by the executor adapter; a timeout between them closes the execution,
and any subsequent report is late. The adapter can skip an invocation if it
observes that the execution has already closed.

This monitor serializes observations, not protocol delivery order. If future
components deliver from multiple threads or tasks, preserving receiver order
and the original-registration boundary must be agreed before integration;
lock acquisition order alone is not evidence of application order.

## 7. Core pseudocode

This pseudocode describes stage 1's atomic simulated contract. It does not
implement the real adapter lifecycle or final survivor checks described later.

The following is behavioral pseudocode, not Java implementation. A demonstrated
property or observation-contract violation adds a diagnostic and sets sticky
concrete-violation status. Unavailable evidence/reporting gaps add incomplete
evidence without setting that status. Outstanding obligations or unmet coverage
also prevent success. Basic validation precedes state mutation. Normal
independent tests use confirmed invocation records directly.

### 7.1 Register an independently observed multicast

```text
registerMulticast(plannedIndex, id, original):
    require phase OPEN, well-formed id and non-null original
    require matching execution/group and sender in fixed members
    require sender/plannedIndex is a valid unused PLANNED slot
    require id not already registered
    on any failed requirement:
        record demonstrated observation violation; return

    savedOriginal = copy(original)
    registered[id] = (savedOriginal, sender/plannedIndex,
                      outstandingRecipients = copy(fixedMembers))
    mark slot CONFIRMED with id
    increment confirmed-message coverage
```

### 7.2 Check a delivery

```text
delivered(receiver, id, observed):
    if phase CLOSED: record demonstrated LATE_REPORT violation; return
    validate receiver, id context and observed bytes
    on malformed/wrong-context input:
        record demonstrated observation violation; return

    savedObserved = copy(observed)
    k = history[receiver].size
    append id to history[receiver]
    duplicate = firstPosition[receiver] already contains id
    if duplicate: record demonstrated duplicate violation with both positions
    else: firstPosition[receiver][id] = k

    for each other receiver with history length > k:
        if history[other][k] != id:
            record demonstrated order-divergence violation at k

    if id not registered:
        record demonstrated unknown-message observation violation; return
    contentOK = savedObserved equals registered[id].original
    if not contentOK: record demonstrated content-mismatch violation
    if not duplicate and contentOK:
        remove receiver from registered[id].outstandingRecipients
        increment valid unique delivery coverage
```

### 7.3 Record unavailable evidence

```text
recordObservationGap(detail):
    record reporting-gap diagnostic and unavailable evidence
    do not set concrete-violation status or invent missing events
    if phase CLOSED: publish updated assessment using Section 5 precedence
```

### 7.4 Final assessment

```text
closeObservation(reason):
    handle repeated/conflicting closure as specified in Section 5
    remember whether workload was closed; set phase CLOSED and close reason
    collect missing pairs, unaccounted/problem slots and coverage evidence

    if demonstrated concrete violation: verdict = UNSUCCESSFUL
    else if reason is TIMEOUT or INTERRUPTED: verdict = INCOMPLETE
    else if workload not closed or unavailable evidence/reporting gap
            or unresolved accounting/submission outcome
            or outstanding obligations or unmet coverage:
        verdict = INCOMPLETE
    else: verdict = SUCCESSFUL

    return immutable result with reason and evidence
```

“Incomplete evidence” includes any obligation, unaccounted/problem slot,
unmet coverage or known reporting gap. A recorded problem that establishes a
contract violation already takes the unsuccessful branch. `snapshot` uses the
same evidence collection but cannot return success before closure.

## 8. Independent harness and baseline tests

`VerifierHarness` constructs a small scenario (normally actors A, B and C),
instantiates `GroupVerifier`, and supplies original records and delivery reports
directly. Proposed helpers are `register(int slot, MessageId id, byte[] bytes)`,
`deliver(ActorId receiver, MessageId id, byte[] bytes)`, `closeWorkload()` and
`finish(CloseReason reason)`. They delegate to the public verifier contract;
they do not implement a second oracle by duplicating its algorithms.

Tests specify expected verdicts, diagnostic codes and missing pairs explicitly.
`VerifierBaselineTest.main(String[] args)` uses an explicit `check` that throws
`AssertionError`, so checks do not depend on `-ea`. A failing case must produce
a non-successful process outcome. It can run as a plain Eclipse Java Application
using the existing Java 8 project, with no JUnit installation or launcher file
required for the first implementation.

There is no dependency on `Executor`, `Task`, channels, Group, `TomMessage` or
`TotalOrderMulticast`. Tests need no bootstrap and cannot accidentally start
the project's executor by loading its classes. They exercise observations
immediately once the verifier is implemented, without waiting for teammates'
components. Timeout tests pass `TIMEOUT` explicitly; they do not sleep.

| Independent case | Expected evidence | Specification |
| --- | --- | --- |
| Complete multiple-sender run, including self delivery | Equal histories and success; all original obligations cleared. | 3.2–3.3, 4.4–4.5 |
| Register a then b; deliver b then a everywhere | Success; registration order is not imposed. | 4.3 |
| Equal bytes under different identities | Two distinct obligations per recipient, no duplicate error. | 3.2, 4.2 |
| Caller mutates original/report arrays after calls and returned diagnostic copies | Original expectations and earlier results remain unchanged. | 3.1–3.2 |
| Shorter compatible prefix, later caught up | IN_PROGRESS before closure; success after completion, no false order error. | 4.3, 6.2 |
| Swap first deliveries or diverge at a later index | Unsuccessful with both identities/receivers and the divergence index. | 4.3 |
| Duplicate report; corrupt payload or length | Unsuccessful with exact diagnostic; no extra coverage or repaired obligation. | 4.1–4.2 |
| Unknown message, receiver, wrong incarnation/run, null payload | Observation error; no invented original or unrelated history mutation. | 3.1–3.3, 4.1 |
| Reuse message identity or planned slot | Observation error; first registration retained. | 3.2, 4.1 |
| One missing recipient, then message omitted everywhere | Incomplete at closure; exact missing pairs, including all members for the unseen message. | 4.4, 6.2 |
| Planned send never registered; rejected/uncertain/not-invoked slot | No pass; missing/uncertain evidence is incomplete, demonstrated baseline submission-contract violations unsuccessful. | 3.2, 4.5, 5 |
| Reporting gap alone, then gap plus a concrete violation | Gap alone closes incomplete; concrete violation takes precedence and closes unsuccessful. A newly known gap can supersede an earlier success. | 3.1, 6.1–6.2 |
| Close workload with outstanding deliveries, then deliver them | Draining allows catch-up; no success until observation closes. | 4.5 |
| Empty dedicated workload versus traffic-required coverage | Dedicated case succeeds without deliveries; invalid configuration is rejected, unmet feasible coverage cannot pass. | 4.5, 5 |
| Empty obligations while workload remains open; normal close without workload closure | No premature success; final result incomplete. | 4.5 |
| TIMEOUT/INTERRUPTED, with and without earlier violations | Incomplete unless violation already established unsuccessful; no inferred actor failure. | 6.1–6.2 |
| Delivery after successful closure; repeated closure | New late-event diagnostic supersedes success; prior snapshot immutable; identical closure idempotent. | 4.5, 6.4 |

The stage-1 implementation exercises these baseline cases in
`VerifierBaselineTest`; exact commands and actual results are recorded in
[verifier_tests.md](verifier_tests.md). Periodic scheduling and watchdog behavior
still require separate runner/adapter tests; simulated reports do not validate
the real runtime or TOM implementation. Results use immutable nested diagnostic
and missing-delivery values, with bounded copied payload excerpts. Identical
observation-close calls are idempotent; repeated workload closure is diagnosed
as accounting after closure, consistent with the one-time workload-close call.

## 9. Future real scenario runner and proposed adapters

### 9.1 Implementation stages

| Stage | Connection and evidence | Dependency |
| --- | --- | --- |
| 1. Independent verifier tests | Simulated registrations and reports exercise the checking/accounting contract directly, with no runtime, TOM or envelope dependency. | Abdallah can implement independently. |
| 2. TOM with simulated transport | Connect the verifier to the real `TomProcess` using FIFO test transport and the approved application-envelope option. This is TOM-component integration, not a real Group scenario. | Implement and test the proposed codec and adapter in Sections 9.3–9.5 before reusing simulated FIFO transport. |
| 3. Real Actor/Group/queue integration | Route TOM through Actor tasks, actual Group readiness and real queues; add finite periodic/burst workloads, observation closure and watchdog assessment. | Adam supplies integrated Group/transport; Arhan and Youssef connect runtime/TOM clock and task contracts; Abdallah owns runner/report adapters. |
| 4. Failure/reinsertion extensions | Begin with real failures without reinsertion under the confirmed final survivor contract, then address reinsertion and voluntary membership under further agreements. | Arhan supplies lifecycle support; Adam/Youssef supply detection/completion observations and later membership/order boundaries; Abdallah extends checking and coverage. |

Unresolved extension semantics do not block stage 1. Simulated TOM results
must not be presented as evidence of the absent real-component connection.

### 9.2 Runner scheduling and observation

The future runner retains the same core and supplies real observations through
proposed `MulticastAdapter`, `DeliveryReportAdapter` and `VerifierMonitor`
boundaries. These names describe responsibilities, not new teammate interfaces.
It waits for actual initial-group readiness, then executes a finite number of
sends per actor. **Confirmed TOM agreement:** record the independent original
immediately before invoking `multicast()`. Normal return while the actor remained
alive confirms the submission. Death preventing or interrupting invocation
leaves an uncertain outcome: retain the original and uncertainty; neither
silently remove the record nor infer that delivery is impossible.

Original registration and confirmation are separate real observations. A
delivery callback may occur before normal return, so checking must have access
to the original already, without deriving confirmation from that callback.
The current baseline API combines these observations atomically for simulated
tests and stays unchanged. Section 9.4 proposes the baseline observation API;
actual runtime lifecycle-event ordering remains an integration agreement.
It must not misclassify death-related
uncertainty as a proven baseline rejection or swallow runtime termination.

For positive periods, schedule the next send as a posted periodic event. For
0 ms, perform one send (or a bounded batch) per runnable and post its successor,
returning control between sends. Do not use an unbounded loop that monopolizes
the shared executor. Whether the runtime guarantees fair progress for those
posts must be checked with Arhan; this design does not fix runtime scheduling.
Stop generation after finite counts, close workload, then continue observing
outstanding deliveries. For failures without reinsertion, Youssef defines a
finished run as all deaths detected and no traffic left. The concrete mechanism
that supplies reliable evidence of both conditions remains open with
Adam/Youssef. Empty verifier obligations and a quiet period alone prove neither
traffic exhaustion nor safe observation closure. A runner-owned close event
must use the agreed reporting/completion evidence.

A proposed watchdog uses a separate Java thread/timer and a monotonic elapsed
budget (`System.nanoTime`), not an executor task. A lightweight executor progress
marker may aid diagnosis. If the executor stops advancing, the watchdog can
detect lack of observed progress/budget expiry and publish an incomplete
assessment through the monitor without relying on that executor to process
another event. It must not declare an actor dead or claim the reason for the
stall. It does not forcibly stop the executor or repair infrastructure. Sending
messages from survivors may exercise progress, but is not a detector for every
possible protocol stall, particularly when no mandatory obligations remain.
Timeout without an established concrete violation is incomplete.

The monitor must not hold a lock while the protocol executes, so protocol
stalls cannot prevent assessment. Unexpected failure within the monitor/core
itself is reported as a harness error where possible and is never a successful
scenario. Runner cleanup and process termination policy remain adapter design
questions; the independent verifier needs neither.

The current TOM listener exposes sender and payload but not full packet identity.
Youssef plans to return TOM identity from `multicast()` and expose it in delivery
callbacks in a future PR; that API is not available yet. The approved envelope
can identify application messages through the existing opaque payload path.
Matching solely by workload bytes is invalid when separate messages contain
equal bytes. No teammate API is changed by this document.

### 9.3 Application envelope version 1

Youssef approved an application envelope; the exact format below is a proposed
local integration design, not a TOM packet format. TOM treats the complete frame
as opaque application bytes. The core verifier remains codec-independent and
accepts `MessageId` plus workload bytes directly. Independent stage-1 tests
need no envelope. Application sequence numbers are caller-assigned and never
predicted from `TomProcess`'s private counter. The future TOM-identity API is
still unavailable and is not used here.

#### Binary layout and limits

One callback payload is exactly one frame. Fields appear consecutively in this
order, with no padding. All multibyte integers are big-endian. `int32`/`int64`
are signed Java integer representations; accepted identity values are
nonnegative. Version is an unsigned byte.

| Field | Encoding | Validation |
| --- | --- | --- |
| Magic | 4 bytes: `53 41 52 56` (ASCII `SARV`) | Exact match. |
| Version | 1 byte | Exactly `01`; unknown versions are rejected. |
| Execution-ID byte length E | int32 | 1–1,024 inclusive. |
| Execution ID | E bytes | Strict UTF-8; name rules below. |
| Group-ID byte length G | int32 | 1–1,024 inclusive. |
| Group ID | G bytes | Strict UTF-8; name rules below. |
| Sender actor number | int32 | 0–`Integer.MAX_VALUE`. |
| Sender incarnation | int32 | 0–`Integer.MAX_VALUE`, consistent with `ActorId`; the external group mapping determines the actual incarnation. |
| Application sequence | int64 | 0–`Long.MAX_VALUE`. |
| Workload byte length P | int32 | 0–1,048,576 inclusive (1 MiB). |
| Workload | P bytes | Arbitrary bytes, including empty content and zeros. |

The exact frame length is `33 + E + G + P`: at least 35 and at most
1,050,657 bytes. These are codec limits, not restrictions on the independent
verifier's payloads or all TOM messages. There is no checksum, compression,
reserved field or optional trailer in version 1. This is not authentication;
identity/original validation and the verifier detect observable corruption,
substitution, duplicates and omissions under the reporting contract.

Names are encoded with `StandardCharsets.UTF_8`, not Java modified UTF-8
(`writeUTF`/`readUTF`). Encoding and decoding use charset encoder/decoder error
actions `REPORT` for malformed or unmappable input. Reject unpaired Java
surrogates on encoding and malformed/overlong/truncated UTF-8 sequences on
decoding; never silently insert replacement characters. The byte limits apply
after encoding, not to character count. Reject decoded names whose
`String.trim().isEmpty()` is true, matching current `MessageId` validation.
Do not trim, normalize Unicode or change case: identities preserve exact
strings. Any BOM code point is ordinary name content, not a removed marker.

The encoder validates names and payload size before building the output frame.
Because valid UTF-8 uses at least one byte per UTF-16 code unit, a name longer
than 1,024 Java code units can be rejected before allocating its UTF-8 output;
then encode with the strict encoder and check the actual byte length. Use
checked/long size arithmetic before narrowing to an array size. Return a new
frame; callers never receive references to retained originals.

The decoder rejects null and out-of-range total lengths before cloning the
bounded input. Parse that stable copy. Before every fixed-width read, check
remaining bytes; before allocating a variable field, validate its signed length,
its field-specific maximum and that it fits in the remaining frame. Do not add
untrusted int lengths to offsets before checking them. Construct `ActorId` and
`MessageId` only after validating their complete fields; a successful decode
requires the workload length to consume exactly the remaining bytes. Any
truncation, invalid length, invalid name/identity, wrong magic/version or trailing
byte produces a checked `EnvelopeFormatException` with a reason and byte offset.
Do not reinterpret an invalid frame as a legacy raw payload or concatenate frames.

#### Proposed codec values

Keep two small classes under a proposed `edu.polytech.verifier.tom` package:

```java
public final class ApplicationEnvelope {
    public ApplicationEnvelope(MessageId id, byte[] workload);
    public MessageId id();
    public byte[] workload();
}
public final class ApplicationEnvelopeCodec {
    public static byte[] encode(ApplicationEnvelope value)
            throws EnvelopeFormatException;
    public static ApplicationEnvelope decode(byte[] frame)
            throws EnvelopeFormatException;
}
```

`EnvelopeFormatException` is a small checked exception, potentially nested in
the codec. `ApplicationEnvelope` stores an immutable ID and copied workload;
its accessor returns a copy. It rejects null inputs. Codec-specific limits
are enforced by encode/decode. Encoding, decoding, retained expected frames
and TOM send buffers must not alias application-owned mutable arrays.

### 9.4 Small baseline TOM observation adapter

#### Minimal core changes needed before implementation

The existing `GroupVerifier.registerMulticast` atomically records and confirms.
Calling it before `multicast()` and marking uncertainty afterward would already
have falsely incremented confirmed counts. Calling it only after return would
leave early callbacks without a core original. Buffering callbacks until return
would delay observation and complicate receiver order when an invocation stays
uncertain. Prefer a small protocol-independent split in the core:

```java
// Proposed additions; none exists in the current implementation.
public boolean beginMulticast(int plannedIndex, MessageId id, byte[] original);
public boolean confirmMulticast(MessageId id);
public void recordInvalidDelivery(ActorId receiver, MessageId id, String detail);
```

`beginMulticast` applies the existing context, identity, slot and copying checks,
reserves the slot and original, and creates recipient obligations. It records a
PROVISIONAL submission without incrementing confirmed-message coverage. Return
false and a concrete observation diagnostic on rejection; the adapter must not
invoke TOM then. A provisional original is sufficient to check a delivery's
integrity/order, but not to confirm the invocation.

`confirmMulticast` accepts an existing provisional, issue-free record once,
marks it CONFIRMED and increments the confirmed count once. It neither changes
the original nor recreates discharged obligations. Unknown IDs, repeated or
conflicting confirmation and confirmation after closure are concrete observation
violations. A delivery cannot invoke this transition. The existing atomic
`registerMulticast` retains its behavior and one logical registration event,
using shared private validation/state operations rather than two public calls.
All current stage-1 expectations must remain valid.

The existing `recordSubmissionIssue(..., UNCERTAIN, ...)` can attach uncertainty
to the provisional record without removing it, counting it as confirmed or
rolling back reports. An unconfirmed provisional/uncertain record prevents
baseline success even if every recipient has reported. Snapshots need explicit
provisional accounting (a long count and per-slot status, with an incomplete
`PROVISIONAL_SUBMISSION` diagnostic). A provisional slot is accounted, not an
unperformed planned send; its unresolved confirmation is a separate condition.
Normal baseline closure additionally requires zero provisional/uncertain slots.
These additions refine observation of the agreed lifecycle, not baseline
checking requirements or the separate survivor contract.

`recordInvalidDelivery` records one sticky OBSERVATION diagnostic with proposed
code `INVALID_DELIVERY_REPORT`, actual receiver, a fully decoded message ID when
available, and a reason such as malformed frame, sender mismatch or unknown
application ID. It does not invent originals, append an accepted history entry,
clear obligations or count valid delivery coverage. After observation closure,
it records a late-report violation instead. Reporting a concrete malformed
callback must not use `recordObservationGap`, which means evidence is unavailable.
No codec, Actor, Group or TOM dependency is added to the core. These API/status
changes must be reviewed and implemented with regression tests before an adapter
is coded; no Java changes are part of this design step.

#### Proposed adapter structure and independent originals

Use one run-scoped `BaselineTomObserver` and a nested receiver-bound `Endpoint`
per member. The observer owns the baseline scenario/core verifier, a copied
numeric-ID-to-`ActorId` map and an expected-envelope registry shared by its
endpoints. Each map key must equal its value's `actorNumber()`. Require a
one-to-one map matching the declared fixed participants;
validate unique numeric member IDs before attaching listeners. Runtime names
are mapped externally as in Section 2.1. The observer does not import Actor or
inspect TOM private state.

Proposed public shape:

```java
public BaselineTomObserver(BaselineScenario scenario,
        Map<Integer, ActorId> members);
public Endpoint attach(ActorId receiver, TotalOrderMulticast tom,
        java.util.function.BooleanSupplier incarnationAlive);
public VerificationResult snapshot();
public void closeWorkload();
public VerificationResult closeObservation(CloseReason reason);
// Endpoint binds receiver, TOM instance and liveness source once:
public boolean multicast(int plannedIndex, MessageId id, byte[] workload);
```

The observer creates its core from the same scenario; it does not expose an
unrestricted mutable-core handle. `attach` installs the endpoint as the sole
TOM application listener before traffic starts. Reject undeclared receivers,
duplicate attachment or reuse of one TOM object for two members. Application
delivery forwarding, if later needed, must be explicit rather than overwriting
this listener. The liveness supplier refers to the bound incarnation, whose
dead status cannot revert; simulated no-failure tests supply constant true.
Actual lifecycle wiring is pending and is not implemented here.

Attach every declared endpoint before offering the first send; the simulated
fixture supplies this readiness condition without claiming Group formation.
Close-workload/normal-close requests made inside a still-active multicast are
rejected as observer misuse, rather than changing core phase before confirmation.

On send, validate that the ID's execution/group match the observer and its
sender equals the bound endpoint actor. The caller supplies a unique application
sequence, independently of planned slot index and TOM sequence. Copy workload
and encode before invoking any protocol operation. Immediately before multicast,
begin provisional core registration and retain a separate immutable expected
frame keyed by application ID. The registry is populated from send-side originals
only and is never overwritten from callbacks. Pass TOM a distinct encoded buffer;
retain no caller-owned array as evidence. Invalid local encoding/configuration
must produce a concrete scenario/observation diagnostic and prevent invocation,
not just leave the planned item silently missing. The invalid-delivery hook is
only for callbacks; before-invocation validation failures can use the existing
`recordSubmissionIssue(boundActor, slot, REJECTED, detail)` with candidate-ID
context in the detail. That operation also diagnoses invalid planned slots.
The later `beginMulticast` validates registration/reuse independently. Never
call atomic `registerMulticast` just to produce a diagnostic for a provisional
send. No protocol ACK is introduced by the endpoint's boolean confirmation
result.

#### Callback and invocation ordering

For each delivery callback:

1. Capture the endpoint's fixed receiver and callback sender integer; neither
   is supplied by the envelope. Take a bounded stable copy and decode strictly.
2. On decoding failure, call `recordInvalidDelivery` with receiver, null ID,
   reason/offset and bounded byte evidence in the detail; never silently drop
   the callback or make up an identity from a partially parsed frame.
3. For a fully decoded frame, require matching execution/group, a declared
   sender incarnation, a sender number equal to the callback sender, and a
   matching independently registered expected ID. Invalid metadata produces
   `recordInvalidDelivery` with the decoded ID and actual receiver; obligations
   remain outstanding.
4. Compare the entire frame with the independently encoded expected frame.
   With strict version-1 encoding and matching identity fields, any difference
   must be workload content/length. Forward the decoded workload to the core's
   `delivered` method in callback order; it performs the definitive comparison
   against the independently copied original and diagnoses content corruption.
   If the frame differs despite equal workload bytes, report an adapter/codec
   inconsistency rather than silently accepting it. Do not suppress repeated
   valid frames: the core must see duplicates. A fully valid provisional
   original allows checking now, without buffering or confirming it.

All observer calls, simulated packet dispatch and callbacks are serialized on
one test thread. No core lock is held across TOM invocation, no asynchronous
forwarding can reorder reports, and the observer never sends from inside a
checking callback. A callback during an in-progress multicast is allowed after
`beginMulticast` returns; it is not reentry into a core operation. Recursive
protocol transport is avoided by the FIFO fixture, but a synchronous test double
is used to test this early-callback case.

Invocation pseudocode (after preflight encoding/validation):

```text
if beginMulticast(slot, id, independentWorkload) rejected: return false
retain independent expected frame before any TOM call
normalReturn = false
try:
    if bound incarnation is alive:
        tom.multicast(distinctSendFrame)
        normalReturn = true
finally:
    if normalReturn and bound incarnation is still alive:
        confirmMulticast(id)
    else:
        recordSubmissionIssue(sender, slot, UNCERTAIN, outcome evidence)
return whether this invocation was confirmed
```

The finally path records uncertainty on an exceptional/interrupted invocation
without swallowing or replacing the original exception/internal termination
signal. Failures in observation itself must be surfaced as harness errors while
preserving that original cause. No exception means successful invocation only
when the bound incarnation remained alive; deliveries alone never confirm it.
No rollback of originals or obligations is allowed. This conservative bookkeeping
covers uncertain invocation outcomes; it does not implement actor failure,
recovery, reinsertion or survivor verification.

Workload closure waits until active invocation calls have unwound; a normal
completion cannot race their confirmation. If a call remains blocked, a caller
can explicitly end observation as incomplete; no watchdog is implemented in
this step. Uncertain outcomes remain accounted but unresolved. Late callbacks
still produce concrete late-report diagnostics and supersede earlier success.

### 9.5 Planned codec/adapter tests, then simulated FIFO TOM integration

These are future tests, not executed evidence. Keep existing stage-1 tests
independent of the codec and require them all to pass after any core additions.
Tests use plain Java 8 main methods and explicit assertions, in the configured
`src` tree, with no JUnit/dependencies or Eclipse configuration changes.

| Order / scope | Focused scenarios and expected evidence |
| --- | --- |
| 1. Codec round trips | Empty/binary workload, supplementary Unicode names, distinct equal-content IDs and numeric maxima; decoded values equal originals, with no caller/result array aliasing. |
| 1. Codec exact format | Hand-authored big-endian version-1 fixture, independent of encoder/decoder; check exact field bytes and `33 + E + G + P` size. No modified UTF-8 or implicit name normalization. |
| 1. Codec boundaries | Exactly 1,024 encoded name bytes and 1 MiB workload accepted; one above rejected, zero name length rejected, empty workload accepted. Test byte count versus character count. |
| 1. Malformed input | Null/too-short/oversized frame, bad magic/version, negative/oversized lengths or identities, truncated scalar/string/workload, invalid UTF-8 and unpaired surrogate on encoding. Append one byte or a second frame: reject trailing data. Exercise truncation at every offset of a small fixture without huge allocations. |
| 2. Core split contract | Begin reserves originals/obligations but confirmed count stays zero. Early valid deliveries do not confirm; unfinished provisional or uncertain records cannot pass. Normal confirmation counts once; duplicate/unknown/late confirmation fails. Atomic `registerMulticast` preserves stage-1 results. |
| 2. Adapter send | Registration exists inside a TOM test-double invocation; no confirmation until normal alive return. Wrong endpoint sender, encoding rejection, reused slot/ID or invalid attachment cannot invoke TOM. Caller mutation cannot change expected evidence. |
| 2. Early callback | Synchronous test double delivers before returning: content/order/duplicate checks run against the provisional original. After return confirm once; observation still cannot succeed before explicit normal closure. |
| 2. Exceptional outcome | A test double delivers then throws, or the supplied liveness observation prevents confirmation: original and reports remain, confirmed count stays zero, baseline closure is incomplete absent another violation. Original thrown cause is preserved. This simulates invocation outcomes, not runtime failure integration. |
| 2. Bad callbacks | Fresh observer per malformed frame, unknown app ID, wrong run/group/incarnation and callback-sender mismatch. Assert concrete code/category, receiver/decoded ID or null, reason, unchanged obligations and no false confirmation; no silent drop. |
| 2. Copy/content/late reports | Change encoded workload with valid framing: content mismatch against independent original. Equal workload bytes with distinct IDs remain distinct. Duplicate and post-closure callbacks reach the appropriate checker diagnostics; earlier snapshots remain immutable. |
| 3. Actual TOM over fake FIFO links | One/three/five members; self delivery, multiple senders, equal workload bytes, Unicode metadata and randomized cross-link schedules. All originals registered independently; final histories/content match and every confirmed obligation clears. Use receiver-bound callbacks, not string-only payload comparison. |
| 3. Negative transport traces | Suppress one application message at all destinations: independent obligations prevent success. Corrupt one envelope or inject a duplicate callback: expected diagnostics, not a passing integration run. These invalid traces pass their tests only when the checker rejects them correctly. |

Use a small dedicated FIFO fixture following `TomProcessTest`'s per-link queue
model, without changing Youssef's tests or importing its mutable static test
state. The fixture copies outgoing packet bytes, services FIFO order per directed
link including self, and can vary order across links. It never provides DATA
reception as verifier evidence. The verifier neither drives ACK decisions nor
repairs delivery. Once finite sends have returned and all fake link queues are
empty with no dispatch/invocation active, the fixture has concrete completion
evidence for this simulated no-failure run; assess obligations and close
observation explicitly. Drained links with missing obligations cannot pass.
Use a finite dispatch-step budget to report incomplete simulation if progress
does not finish, not to infer death or claim real runtime stall detection.

This validates the real TOM algorithm through simulated transport only. It does
not validate real Actors, Group readiness, queues, task clocks, periodic load,
a watchdog, failure detection, reinsertion or dynamic membership. Real traffic
completion and lifecycle observation mechanisms remain integration agreements.
No API proposed in this section is implemented by this documentation step.

## 10. Extension points and unresolved integration questions

Keep immutable actor incarnations, independent originals, categorized
diagnostics and explicit scenario coverage. Later extensions may replace the
fixed-recipient obligation rule and baseline prefix comparison with agreed
participation-aware rules. Do not expose mutable membership or implement a
generic recovery-policy framework in the baseline.

### 10.1 Agreed final survivor checks, without reinsertion

The confirmed contract in specification Section 7.5 is non-uniform agreement
among survivors. At established completion, every confirmed message from a
sender that survives must appear at every survivor. Independently recorded
messages from dead senders and uncertain submissions follow all-or-none
application delivery across survivors. Keep uncertain originals and their
uncertainty even when the observed outcome is all or none; application delivery
does not retroactively confirm an invocation. Survivors' final histories must
agree on application identities, original content and order, without duplicates.
Dead actors' histories are excluded from these survivor agreement checks.

These are separate final checks, not an instruction to apply stage 1's
fixed-group prefix algorithm blindly during intermediate failure states. A
later extension will derive mandatory survivor obligations from independent
confirmed submissions and sender survival. It will evaluate optional all-or-none
outcomes against independently recorded identities/originals, not reconstruct
originals from whichever survivor reports first. An uncertain submission's
all-or-none treatment is distinct from a reporting gap with unavailable evidence.
The baseline's rule that uncertainty prevents its success remains unchanged.

No DATA-reception evidence or hook is required. The protocol's internal
“a survivor had it” rule belongs to recovery; the verifier checks application
delivery only. Reliable completion evidence must establish all deaths detected
and no traffic left. Until that mechanism is agreed, empty obligations or
silence cannot establish completion. Missing mandatory survivor deliveries
prevent success; an unfinished run timing out without a concrete violation
is incomplete. Final partial all-or-none delivery or inconsistent survivor
histories at established completion violates the agreed contract.

### 10.2 Remaining integration and membership questions

Actual death, detector notifications, resurrection and effective membership
changes still require distinct observations and agreed reporting order. Real
Actor/Group/queue/TOM wiring remains pending Adam/Youssef. Reinsertion and
dynamic-membership participation boundaries and agreement semantics remain
unresolved; the survivor-only contract does not settle them. Future failure
scenarios must prove actual failure coverage; reinsertion scenarios must prove
admission and eligible deliveries afterward, as well as no forbidden replay.
No state transfer or in-flight reinsertion policy is designed here. Runtime/TOM
identity mapping remains outside the independent verifier.

| Teammate | Remaining integration agreement |
| --- | --- |
| Youssef | Review the version-1 encoding and baseline adapter/API design above; agree real completion evidence with Adam/Abdallah. Future packet-identity API remains pending. Agree identity mapping, callback order and reinsertion/dynamic-membership ordering with Adam. No protocol ACK or DATA-reception hook is required. |
| Arhan | Confirm executor posting/yield behavior, thread ownership and observation/watchdog boundary; later provide authoritative actor lifecycle and failure controls. Runtime fixes remain runtime work. |
| Adam | Supply actual initial-group readiness, fixed member identities and the external Actor-name/numeric-ID mapping with Youssef; later define effective membership evidence jointly with Youssef. |
| All members | Agree concrete completion/report-completeness evidence and remaining integration details; retain the confirmed survivor-only contract, and resolve reinsertion/dynamic-membership questions before those stages. |

## 11. Proposed class/API summary

`ActorId` and `MessageId` provide immutable value identities. `BaselineScenario`
declares the fixed workload and coverage. `GroupVerifier` offers registration,
submission-issue accounting, delivery reports, workload closure, snapshots and
explicit observation closure. `VerificationResult` returns immutable evidence.
`VerifierHarness` and `VerifierBaselineTest` exercise these contracts directly.

The next simulated-TOM stage adds only the proposed envelope codec, run-scoped
observer/endpoints and reviewed protocol-independent core observation hooks.
A watchdog-safe monitor and real runner remain later work. No runtime, Group
or TOM dependency is needed for the baseline verifier or its independent tests.
