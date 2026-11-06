# Group Verifier and Common Scenario Runner — Draft Specification

Author: Abdallah ABDELSADEK

Status: Draft for group review; failure and membership rules still need agreement

Date: 1 November 2026

## 1. Purpose

The Group Verifier checks delivered messages and actor lifecycle events for
one multicast group. The scenario runner generates a finite workload,
triggers lifecycle actions and reports the results.

The labels distinguish the assignment from our design choices:

- **Assignment requirement (A):** required by `project.pdf`.
- **Proposed rule (P):** our proposed behavior, to be agreed by the group.
- **Open question (Q):** a decision still needed before testing that feature.

Section 4 defines the checks for a fixed group without failures. Section 7
lists the remaining decisions for failures and membership changes.

Sources:

- `project.pdf`, pp. 1–3: runtime, groups, failures, verifier, workloads and stages.
- `../README.md`: division of responsibilities and integration order.
- `../lesson-only-arhans-personal/L2-Diverses-Techniques.pdf`, pp. 15–18:
  reliable broadcast, atomicity and reception versus delivery.
- `../lesson-only-arhans-personal/L3-Multicast.pdf`, pp. 16–26:
  total-order multicast, logical clocks and staged failure extensions.
- Coursework `specs_*.txt`: conventions for observable guarantees, assumptions,
  scope and explicit unresolved questions. Student recovery choices are not
  treated as assignment requirements.

## 2. Scope and responsibilities

**A:** Each group has a verifier that is not an actor. Actors forward every
delivered message to it in delivery order. Delivery and lifecycle reports use
direct method invocations, not message exchange (`project.pdf`, p. 2).

**P:** Verification observes results. The verifier must not choose the delivery
order, acknowledge protocol messages, admit members, repair failures or supply
missing application deliveries. Runner lifecycle requests stimulate the real
components; they do not themselves establish that the requested transition
has happened.

Abdallah owns this verifier contract, the common runner, independent verifier
tests and the integration scenarios assigned in the README. Arhan owns runtime
and Actor behavior; Adam owns Group behavior; Youssef owns TOM behavior.
Each member remains responsible for their component tests and fixes. Transport,
scheduling, failure-detection and ordering defects are reported to their owners;
they are not additional verifier implementation responsibilities.

Implementation mechanisms, storage structures, scheduling algorithms, final
Java signatures and the multicast recovery algorithm belong to later design
documents.

## 3. Assumptions and observable inputs

### 3.1 Execution assumptions

**A:** Actors and tasks run in one JVM. Actors communicate with each other
through messages. The initial group forms with N actors and subsequently works
peer-to-peer. The project assumes fail-stop actors and a perfect failure
detector (`project.pdf`, pp. 1–2).

**P:** A checked execution has an identity, a declared group and an explicit
scenario configuration. Observations from another execution or group must not
be mixed into its result. In the fixed-group baseline, membership is established
before workload generation and remains unchanged; no actor fails.

**P:** Reporting is complete and faithful: every application delivery is reported
once, with its original receiver-local order and observed content. A reporting
gap, altered report or missing lifecycle evidence limits what can be concluded.
The verifier cannot prove that an unreported application event never happened.

**P:** Reported payload values and independently recorded originals remain stable
for checking. They must not change because an application later reuses mutable
bytes. The eventual design will decide how this contract is maintained.

### 3.2 Multicast records independent of deliveries

**P:** Before an actual multicast submission can produce any delivery report,
the runner records its execution/group, unique message identity, originating
actor incarnation and exact original payload bytes. This record is independent
of recipient reports and is never reconstructed from the first delivered copy.

A message identity distinguishes separate multicasts even when their sender
and payload bytes are identical. It remains unambiguous across actor
incarnations. Its concrete representation and association with TOM identities
require agreement with Youssef; this document does not prescribe fields or a
Java type.

The observations distinguish:

- A planned send that has not executed.
- An actual submission whose identity and original payload have been recorded.
- A confirmed submission that establishes a delivery obligation.
- A submission rejected or left uncertain by the submission contract.

“Submission confirmation” is a **P** observation boundary, not an assignment
requirement for a new protocol acknowledgement or public API. It does not depend
on recipient deliveries. Its mapping to the existing multicast operation must
be agreed with Youssef before integration.

For the baseline, the proposed submission contract must unambiguously confirm
every valid multicast invoked by a live member of the established group.
Confirmation must be independent of recipient deliveries and observable before
any resulting delivery report. For independent baseline tests, each supplied
valid invocation record is confirmed at invocation and creates its obligations
immediately. Missing or uncertain confirmation cannot silently erase a planned
workload item or permit a success verdict.

### 3.3 Delivery reports

**P:** Each application delivery report identifies the execution/group, receiving
actor incarnation, multicast identity and exact delivered payload. Reports
preserve the order at that receiver. Cross-receiver report arrival order is not
the multicast delivery order.

Network reception, DATA buffering, ACK receipt and a successful transport send
are not application deliveries. A sender reports its own application delivery
through the same contract as every other receiver.

### 3.4 Lifecycle and execution reports

**A:** Death, resurrection and reinsertion are reported to the verifier.

**P:** Additional observations distinguish actual death, each relevant detector
notification, resurrection, membership requests and effective admission/removal.
They identify the affected actor incarnation and their established relationship
to deliveries and submissions. Observation arrival time alone must not invent
an effective membership boundary.

**P:** The runner also reports workload exhaustion, submissions that did not
occur or were rejected/uncertain, unresolved lifecycle requests, execution
interruption and the end of observation. Exact report signatures are deferred.

## 4. Fixed group without failures

All requirements in this section are **P**, refining the assignment's required
same-message, same-order, same-content scenarios for an independently testable
baseline. The members include every sender as a recipient.

Let M be the fixed, nonempty set of participating actor incarnations. Let S be
the independently recorded, confirmed multicasts. For each receiver r in M,
let H(r) be its delivery sequence, preserving report order.

### 4.1 Origin and content integrity

Every delivery identity must belong to S, and its delivered bytes must equal
the independently recorded original bytes in length and value. An unknown
identity, an undeclared receiver or inconsistent metadata is unsuccessful.

A report arriving before its required independent submission record violates
the observation contract. It is diagnosed as such; it must not be used to
create the missing original record. Reusing an identity for a different
multicast also violates the contract, even if the payload happens to match.

### 4.2 Duplicate detection

For each r in M and each m in S, H(r) contains m at most once. A second delivery
report for that receiver/message pair is unsuccessful, including when the bytes
are identical. Reports for two distinct identities with equal bytes are not
duplicates.

### 4.3 Compatible delivery prefixes

At every observation point, any two histories are prefix-compatible: the shorter
history must equal the beginning of the longer history, comparing identities.
Content integrity is checked separately against the originals.

For example, `[a]` and `[a,b]` are compatible; `[a,b]` and `[b,a]` are not.
`[a,b]` and `[a,c]` are not compatible either. A shorter compatible history
means that actor may be behind; it is not by itself a violation.

The verifier does not prescribe which concurrent multicast comes first. It
requires a common order and does not infer it from submission-record order,
physical time, or the order reports from different receivers reach it.

### 4.4 Agreement and expected deliveries

Every confirmed multicast m in S is owed to every r in M, including its sender.
There is exactly one delivery obligation for each pair (m,r). A delivery clears
only its matching obligation and only if it meets the integrity and duplicate
conditions.

At successful completion, every H(r) contains exactly S, once per identity,
and all histories are identical. Thus all members agree on the message set,
content and order.

The obligation set comes from confirmed multicast records and declared
membership, never from the union of reported deliveries. Consequently, if a
confirmed message is omitted by every recipient, its obligations remain
outstanding for every member. Equal or empty histories cannot conceal it.

### 4.5 Completion and empty workloads

Successful completion requires all of the following:

- The declared finite workload has ended, with every planned send accounted
  for; there are no unexplained, rejected or uncertain submissions.
- Every confirmed multicast has its original record and every required delivery
  obligation is satisfied exactly once with matching content.
- All histories are compatible and therefore equal at completion.
- No contract violation, unresolved lifecycle action or reporting gap exists.
- Every declared scenario coverage condition has been observed (Section 5).
- The declared observation interval has closed.

A workload of zero sends may succeed in a dedicated empty-workload test only
with zero deliveries and the same valid completion evidence. It cannot satisfy
a scenario requiring traffic. Any unexpected delivery still fails.

An intermediate checkpoint with no outstanding obligations is not final success
while more workload or reports can still occur. Conclusions cover the declared
observation interval. A delivery after the runner has declared the execution
closed is an unexpected event and must be reported; it cannot silently preserve
an earlier success claim.

## 5. Common scenario runner and workload contract

**A:** Scenarios correspond to each required project stage and support
controllable periodic multicast load down to 0 ms. Failure scenarios support
explicit suicide and random failures (`project.pdf`, pp. 2–3).

**P:** A scenario declares its initial actors, finite send count per sender,
payload description, nonnegative sending period, failure/lifecycle actions,
failure mode, execution budget, completion criteria and coverage conditions.
Invalid configuration
must produce a configuration diagnostic before a success verdict is possible.

**P:** Coverage conditions declare the events and traffic that must actually
occur for the scenario to exercise its intended behavior. Every failure scenario
must observe at least one actual failure in its declared failure mode; a failure
request or enabled random-failure setting alone is insufficient. Every
reinsertion scenario must complete effective reinsertion and demonstrate
subsequent eligible application deliveries to the reinserted actor. Scenarios
requiring traffic must declare and observe that traffic; zero sends cannot
satisfy them. These conditions supplement correctness checks and delivery
obligations. Unmet coverage cannot pass, even if all observed histories are
otherwise valid.

Workload generation starts after the relevant initial group is ready. Each
planned send is accounted for independently of delivery. In the baseline, all
planned sends must become confirmed submissions. In extensions, a send prevented
by an actual death is distinguished from an invoked multicast whose outcome
is uncertain; the applicable obligations depend on the agreed extension contract.

The sending period controls offered load, not a delivery deadline. Positive
periods request periodic sends; the runner does not promise exact wall-clock
spacing under load. A period of 0 ms means no intentional delay between offered
sends. It must still allow protocol events, reports and lifecycle actions to
progress. Every workload remains finite, including burst mode.

Explicit failures request suicide through the Actor/runtime contract. Random
failures exercise the runtime's failure mechanism and eligibility settings;
the runner must not substitute missing reports or timeouts for actual failures.
The configuration identifies tasks or services intended to remain protected,
according to Arhan's contract.

For repeatability, the execution report records the configuration and any
random seed, as well as actual observed failure decisions and scenario
landmarks. A seed alone is not a promise of identical execution when scheduling
or the order of random choices differs. The mechanism for recording or replaying
those decisions is deferred to design.

## 6. Failure behavior, progress conditions and diagnostics

### 6.1 Outcomes

The following outcomes are **P**:

| Outcome | Meaning |
| --- | --- |
| Successful | All applicable properties, completion conditions and declared coverage conditions hold for the declared interval. |
| Unsuccessful | A concrete checked-property violation or observation/configuration contract violation has been established. |
| Incomplete | Success cannot be established: coverage is unmet, obligations, reports, transitions or required semantic decisions remain unresolved, or execution stopped before completion. |

A concrete violation remains unsuccessful even if the execution later times
out. An incomplete result is never counted as a pass. Diagnostics distinguish
multicast-property failures, invalid observations, unmet scenario conditions
and infrastructure errors; they do not assign every error to the verifier.

### 6.2 Missing messages and execution budgets

**P:** While an execution is open, missing deliveries remain outstanding
obligations. Compatible prefixes do not justify declaring a slower actor faulty.
If the execution ends or exceeds its budget with outstanding obligations, it
cannot succeed and must list the missing receiver/message pairs.

Budget exhaustion is incomplete unless an independent concrete violation has
already established failure. It demonstrates failure to finish the scenario
within its budget, not permanent message loss, actor death or impossibility
of eventual delivery. A finite period of silence alone proves none of these.

### 6.3 Conditions for progress

**P:** Baseline completion depends on a ready group, finite confirmed workload,
continued scheduling of participating tasks and reporting, eventual transport
and TOM progress, and enough execution budget to observe the obligations.
Finite workload exhaustion alone does not prove delivery completion.

**A/P:** Failure extensions additionally depend on the promised detector and
membership/TOM transition progress. A perfect detector has no false failure
notifications and eventually detects failures; it need not detect them
immediately. The verifier observes these conditions and does not implement or
repair them. Unsupported total-group loss or stalled recovery cannot be marked
successful merely because no further delivery reports arrive.

### 6.4 Useful evidence

**P:** Results identify the execution, configuration, applicable contract/stage,
outcome and reason. When relevant, diagnostics include:

- Receiver and sender incarnations, message identity and original submission.
- Expected and observed payload length/value differences.
- Duplicate positions or the first incompatible history positions and identities.
- Outstanding obligations, including messages absent from every history.
- Planned, invoked, confirmed, rejected and uncertain submission counts.
- Relevant actual death, detection, resurrection and membership observations.
- Declared coverage conditions and the evidence satisfying each, or what is missing.
- Unresolved transitions, unavailable evidence, unmet scenario conditions and
  the execution budget that expired.

Diagnostics must distinguish observed facts from expectations and unresolved
interpretations. They must provide enough evidence to investigate an error
without claiming an unobserved cause.

## 7. Required extensions and open contracts

### 7.1 Explicit and random failures; death versus detection

**A:** Both explicit suicide and random failure are required. Failure is
fail-stop, and actor failure kills its tasks. The group reports deaths and
drops traffic to/from members considered dead.

**P:** Actual death ends the activity of the affected incarnation. A later
detector notification records when another component learned about it; it does
not move the actual death boundary. Delayed reporting of an event that happened
before death must be distinguishable from a new delivery performed after death.
Reports without enough evidence for this distinction are insufficient for the
affected check. A timeout is neither a death event nor a detector notification.

**Q:** Arhan and Adam must agree on authoritative lifecycle observations and
their relationship to application deliveries and peer notifications. Adam and
Youssef must define which in-flight messages remain deliverable or obligatory
when death and detection occur at different times.

### 7.2 Resurrection versus effective reinsertion

**A:** Resurrection and reinsertion are distinct reported events. Messages
missed while dead remain missed forever, including after reinsertion.

**P:** Resurrection identifies a new actor incarnation. It does not alone
establish membership or create normal delivery obligations. Effective
reinsertion is a separate admission observation. Old and new incarnations
remain distinguishable, including in delayed reports and pending traffic.

**Q:** The team must define eligibility during the interval between resurrection
and effective reinsertion, the treatment of pending pre-death messages and
previously delivered messages, and what protocol state may be transferred
without replaying forbidden application deliveries.

### 7.3 Voluntary joins and departures

**A:** The final stage includes voluntary joins and departures, with Group
listener notifications.

**P:** A requested join, leave or reinsertion is recorded separately from its
effective transition. A voluntary departure is not automatically a failure.
Participation records must distinguish successive admitted intervals, including
a returning actor if the protocol permits it.

**Q:** Adam and Youssef must define effective boundaries, notification meaning,
obligations for multicasts already in flight, and any draining allowed during
departure. The verifier must not select these boundaries itself.

### 7.4 Participation-aware comparisons and no replay

**P:** Extension comparisons use the recipients and participation intervals
established by the agreed Group/TOM contract. They must not compare a newcomer's
whole history against the entire history of a long-standing member or apply
the baseline prefix rule indiscriminately across different eligible sets.

For message pairs that are comparable under that contract, relative delivery
orders must agree. Required recipient/message obligations are checked separately
from order, so projecting histories onto common messages cannot hide omissions.
Cross-interval order requirements remain a team decision.

**A/P:** Once independent submission and lifecycle evidence establishes that
a message was missed while an actor was dead, it must never later be delivered
to that actor after resurrection or reinsertion. The evidence must remain
available throughout the scenario; a new incarnation must not erase this
prohibition. Messages submitted clearly inside an established dead interval
provide a scenario that avoids disputed transition boundaries. Classification
of messages spanning death or reinsertion must await the agreed contract.

This rule must also be exercised after the returning actor has resumed useful
participation, rather than treating failure to reinsert it as a successful
no-replay test.

### 7.5 Failed senders and agreement strength

**Q — failed senders:** Which multicasts become obligatory if a sender dies
during submission or partial dissemination? What must survivors do if some
members received or delivered a message and others did not? An observed
delivery must not be the only basis for the expected set, and an uncertain
submission must not silently disappear. Youssef and Adam must define the
protocol contract and its independent observable evidence.

**Q — agreement strength:** Does agreement constrain only actors that remain
correct, or also deliveries made by actors that subsequently fail (uniform
agreement)? How does “correct” apply across incarnations and participation
intervals? The coursework explicitly chooses a non-uniform interpretation;
that student choice does not settle the final assignment. All members must
review the requirement, with Arhan coordinating clarification if necessary.

Until these questions are settled, an execution may provide useful partial
evidence but cannot receive an unconditional success verdict for properties
whose meaning depends on the unanswered questions.

## 8. Acceptance scenarios

These are acceptance requirements for future work, not executed test results.
“Simulated” tests exercise the verifier using supplied records and reports.
“Integration” scenarios exercise the actual runtime, Actor, Group and TOM
components. Each member's component tests remain separate responsibilities.

| Kind | Scenario | Required observation/outcome |
| --- | --- | --- |
| Simulated | Fixed group, complete equal histories; equal payloads under distinct identities | Success with declared traffic coverage; identities remain distinct. |
| Simulated | Dedicated empty-workload test; zero sends in a traffic-required scenario | Empty test may succeed with zero deliveries; traffic-required scenario cannot pass. |
| Simulated | Failure requested but not observed; reinsertion incomplete or no eligible delivery afterward | Coverage remains unmet; incomplete unless a concrete violation already establishes failure. |
| Simulated | One receiver temporarily has a shorter compatible prefix | No premature violation or success; success only after obligations finish. |
| Simulated | Unknown message, altered content or duplicate at one receiver | Unsuccessful with identity, receiver and concrete evidence. |
| Simulated | Inverted order or different identities at the same history position | Unsuccessful with the first incompatible positions. |
| Simulated | One receiver omits a confirmed message | Obligation stays outstanding; closing/budget exhaustion cannot pass. |
| Simulated | Every receiver omits the same confirmed message | All corresponding obligations remain visible despite equal histories. |
| Simulated | Reused identity, missing original, reporting gap or uncertain submission | Contract diagnostic; never silently accepted as a complete execution. |
| Simulated | Death then delayed detection; resurrection then later reinsertion | Distinct events and incarnations retained; no inferred participation boundary. |
| Simulated | Different participation histories; established missed message delivered after reinsertion | Compare only contractually comparable histories; forbidden replay is unsuccessful. |
| Integration | Stable group, multiple senders and finite payload workloads | All members, including each sender, deliver every confirmed multicast once with equal content and order. |
| Integration | Long and short positive periods, then 0 ms burst | Finite workload completes with the same properties; burst allows protocol/report progress. |
| Integration | Explicit suicide and random failure without reinsertion | Actual failures observed separately from detection; verify survivor obligations under the agreed failed-sender/agreement contracts. |
| Integration | Death, traffic while dead, resurrection, effective reinsertion, further traffic | Reinsertion actually completes; missed-while-dead traffic never reappears and eligible later traffic is delivered. Exercise both failure modes. |
| Integration | Voluntary join and departure while sending; combined lifecycle changes | Participation-aware checks and in-flight obligations follow agreed boundaries. Exercise positive periods and burst load. |
| Integration | Interrupted run, expired budget, stalled transition or unmet coverage | Incomplete with outstanding work/evidence; never infer death from timeout or report a pass. |

Boundary-sensitive integration scenarios remain conditional on the Section 7
agreements. Simulated extension tests validate the verifier against an explicitly
supplied policy; they do not establish that the project protocol implements it.

## 9. Decisions requested for group review

| Member | Decisions needed |
| --- | --- |
| Arhan UNAY | Authoritative actual-death and resurrection observations; incarnation/task lifecycle semantics; explicit/random failure controls and protected tasks; clock/report ordering guarantees. Coordinate assignment clarification where needed. |
| Adam TAWFIK | Meaning and timing of detection and effective membership reports; admission/removal/reinsertion boundaries; participation evidence. Agree in-flight behavior jointly with Youssef. |
| Youssef KIRLOS | Message identity and delivery metadata; original payload ownership; observable submission confirmation; failed-sender obligations and order across transitions. Agree membership boundaries jointly with Adam. |
| Abdallah ABDELSADEK | Refine this draft from the agreed contracts; confirm verdicts, workload controls, completion evidence and diagnostics with the team before design. |

All four members must agree on agreement strength and the scope of supported
failure/recovery executions. Approval of this draft does not approve an
unspecified recovery algorithm or resolve the open questions by implication.
