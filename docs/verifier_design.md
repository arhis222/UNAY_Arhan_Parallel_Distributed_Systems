# Group Verifier and Independent Harness — Draft Design

Author: Abdallah ABDELSADEK

Status: Stage 1 implemented; integration adapters remain proposals for group review

Date: 1 November 2026

Last revised: 4 November 2026

## 1. Scope

This design covers the fixed-group, no-failure checks in
[verifier_spec.md](verifier_spec.md), Sections 3–6, and the simulated tests
in Section 8. The first version checks a fixed, nonempty group and a finite
workload using direct method calls. Recovery and dynamic membership still
need the decisions listed in Section 10.

The implementation uses Java 8 classes in `edu.polytech.verifier` and
`edu.polytech.verifier.tests`, under the Eclipse `src` directory. It needs
no additional dependencies. The later integration adapters are proposals
that the group still needs to agree on.

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
Mapping this boundary onto the real `multicast` operation requires Youssef's
agreement; no new public API, ACK or delivery-dependent confirmation is implied.

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
violation exists. Extension-specific submission semantics are still open.

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
| 2. TOM with simulated transport | Connect the verifier to the real `TomProcess` using FIFO test transport and an agreed identity-reporting adapter. This is TOM-component integration, not a real Group scenario. | Youssef reviews the identity option; existing simulated transport is reusable. |
| 3. Real Actor/Group/queue integration | Route TOM through Actor tasks, actual Group readiness and real queues; add finite periodic/burst workloads, observation closure and watchdog assessment. | Adam supplies integrated Group/transport; Arhan and Youssef connect runtime/TOM clock and task contracts; Abdallah owns runner/report adapters. |
| 4. Failure/reinsertion extensions | Begin with real failures without reinsertion, then effective reinsertion and voluntary membership scenarios under agreed participation rules. | Arhan supplies lifecycle support; Adam/Youssef agree and implement membership/order boundaries; Abdallah extends checking and coverage. |

Unresolved extension semantics do not block stage 1. Simulated TOM results
must not be presented as evidence of the absent real-component connection.

### 9.2 Runner scheduling and observation

The future runner retains the same core and supplies real observations through
proposed `MulticastAdapter`, `DeliveryReportAdapter` and `VerifierMonitor`
boundaries. These names describe responsibilities, not new teammate interfaces.
It waits for actual initial-group readiness, then executes a finite number of
sends per actor. It registers each original before invoking the existing
multicast operation through the agreed boundary. Exceptions or uncertain
invocations are recorded explicitly rather than inferred from deliveries.

For positive periods, schedule the next send as a posted periodic event. For
0 ms, perform one send (or a bounded batch) per runnable and post its successor,
returning control between sends. Do not use an unbounded loop that monopolizes
the shared executor. Whether the runtime guarantees fair progress for those
posts must be checked with Arhan; this design does not fix runtime scheduling.
Stop generation after finite counts, close workload, then continue observing
outstanding deliveries. A runner-owned observation-close event follows the
agreed reporting boundary; a quiet delay alone is not proof of completion.

A proposed watchdog uses a separate Java thread/timer and a monotonic elapsed
budget (`System.nanoTime`), not an executor task. A lightweight executor progress
marker may aid diagnosis. If the executor stops advancing, the watchdog can
detect lack of observed progress/budget expiry and publish an incomplete
assessment through the monitor without relying on that executor to process
another event. It must not declare an actor dead or claim the reason for the
stall. It does not forcibly stop the executor or repair infrastructure.

The monitor must not hold a lock while the protocol executes, so protocol
stalls cannot prevent assessment. Unexpected failure within the monitor/core
itself is reported as a harness error where possible and is never a successful
scenario. Runner cleanup and process termination policy remain adapter design
questions; the independent verifier needs neither.

The current TOM listener exposes sender and payload but not full message
identity. An agreed adapter or reporting hook is needed; matching by payload
is invalid when two messages contain equal bytes. The proposed adapter keeps the existing APIs
by this document.

### 9.3 Proposed application identity envelope

Pending Youssef's review, one integration option is to encode a runner-assigned
application `MessageId` and the workload payload in the bytes passed to
`multicast`. The adapter independently registers that identity and original
content before invocation. At application delivery it captures the receiver
identity from the installed listener, decodes the envelope, checks the callback
sender against the declared origin and supplies the identity and observed
workload bytes to the verifier. The adapter must validate envelope metadata
against the registered execution/group/origin and preserve evidence of malformed
or altered envelopes; it must not silently discard them or manufacture records
from delivered bytes. Exact encoding remains an adapter design decision.

This option checks application-message integrity, duplicates, common delivery
order and independently expected completeness, including equal workload bytes
under distinct identities. An application sequence is not a prediction of
`TomProcess`'s private sequence counter. Application identities are distinct
from TOM's internal sender/incarnation/sequence packet identities: this option
does not independently validate those internal fields or their assignment.
That additional check would require an agreed TOM observation hook.

The envelope belongs only to an integration adapter. The baseline verifier's
registration/delivery methods continue to accept identities and payload bytes
directly; they neither encode nor decode envelopes and gain no TOM dependency.
The envelope is optional: a reviewed delivery-metadata hook could instead
satisfy the same specification contract. Neither option adds a protocol ACK
or delivery-dependent submission confirmation.

## 10. Extension points and unresolved integration questions

Keep immutable actor incarnations, independent originals, categorized
diagnostics and explicit scenario coverage. Later extensions may replace the
fixed-recipient obligation rule and baseline prefix comparison with agreed
participation-aware rules. Do not expose mutable membership or implement a
generic recovery-policy framework in the baseline.

Actual death, detector notifications, resurrection and effective membership
changes will require distinct observations. Their authoritative boundaries,
failed-sender obligations and uniform/non-uniform agreement remain the open
questions in specification Section 7. Unknown lifecycle behavior must not be
silently processed as a successful no-failure scenario. Future failure scenarios
must prove actual failure coverage; reinsertion scenarios must prove admission
and eligible deliveries afterward, as well as no forbidden replay. This design
does not select which in-flight messages qualify or design state transfer.

| Teammate | Remaining integration agreement |
| --- | --- |
| Youssef | Review the proposed application envelope or an alternative metadata hook; map independent registration/confirmation to multicast invocation; agree numeric identity mapping with Adam, preserve callback order and define synchronous delivery possibilities. No new protocol ACK is assumed. |
| Arhan | Confirm executor posting/yield behavior, thread ownership and observation/watchdog boundary; later provide authoritative actor lifecycle and failure controls. Runtime fixes remain runtime work. |
| Adam | Supply actual initial-group readiness, fixed member identities and the external Actor-name/numeric-ID mapping with Youssef; later define effective membership evidence jointly with Youssef. |
| All members | Approve observation closure/report completeness, scenario coverage and diagnostic classification; resolve specification Section 7 before failure/dynamic-group integration. |

## 11. Proposed class/API summary

`ActorId` and `MessageId` provide immutable value identities. `BaselineScenario`
declares the fixed workload and coverage. `GroupVerifier` offers registration,
submission-issue accounting, delivery reports, workload closure, snapshots and
explicit observation closure. `VerificationResult` returns immutable evidence.
`VerifierHarness` and `VerifierBaselineTest` exercise these contracts directly.

Only the later real runner needs proposed multicast/report adapters and a
watchdog-safe monitor. No runtime, Group or TOM dependency is needed for the
baseline verifier or its independent tests.
