# Independent Verifier — Stage 1 Test Results

Author: Abdallah ABDELSADEK

Date: 4 November 2026

## Scope

These are **simulated-report tests of the independent fixed-group, no-failure
verifier**. The harness invokes registration, delivery and closure methods
directly. It does not run Actors, Group, queues, `TomProcess`, periodic workload
generation, a watchdog or recovery. An intentionally invalid trace passes its
test when the verifier produces the expected verdict and diagnostic evidence.
Only behavior differing from the expected result fails the test process.

The separate `TomMessageTest` run below is a packet-representation regression
check, not TOM integration.

## Command-line toolchain and exact commands

Commands ran from the repository root on `abdallah-master`, based on checkout
`f00a619`, with the uncommitted design refinements preserved. The default system
JDK is 21; validation explicitly used the already installed Java 8 toolchain.
No dependencies were installed. Both test mains use explicit checks and were
run without `-ea`.

Toolchain commands:

```sh
/home/abdallah/.sdkman/candidates/java/8.0.392-tem/bin/javac -version
/home/abdallah/.sdkman/candidates/java/8.0.392-tem/bin/java -version
```

Observed versions:

```text
javac 1.8.0_392
openjdk version "1.8.0_392"
OpenJDK Runtime Environment (Temurin)(build 1.8.0_392-b08)
OpenJDK 64-Bit Server VM (Temurin)(build 25.392-b08, mixed mode)
```

Output directory creation:

```sh
mktemp -d /tmp/sar-verifier-review.XXXXXX
```

It returned `/tmp/sar-verifier-review.xbh0s5`. The following exact compilation
and run commands used that directory; all generated class files stayed outside
the repository. For a fresh rerun, replace that path with a newly created
temporary directory.

```sh
/home/abdallah/.sdkman/candidates/java/8.0.392-tem/bin/javac -encoding UTF-8 -Xlint:all -d /tmp/sar-verifier-review.xbh0s5 src/edu/polytech/verifier/*.java src/edu/polytech/verifier/tests/*.java src/edu/polytech/tom/TomMessage.java src/edu/polytech/tom/tests/TomMessageTest.java
/home/abdallah/.sdkman/candidates/java/8.0.392-tem/bin/java -cp /tmp/sar-verifier-review.xbh0s5 edu.polytech.verifier.tests.VerifierBaselineTest
/home/abdallah/.sdkman/candidates/java/8.0.392-tem/bin/java -cp /tmp/sar-verifier-review.xbh0s5 edu.polytech.tom.tests.TomMessageTest
```

## Command-line results

Compilation and both suites were rerun on 4 November 2026.
The verifier count increased from 23 to 49 groups because malformed delivery,
registration, submission-issue and closed-workload traces are now isolated in
fresh verifier instances, and focused boundary/self-delivery/late-delivery
cases were added. Verdict semantics are unchanged. Undeclared sender diagnostics
now retain the actual receiver and message identity, with the invalid sender
identified in the detail; receiver validation identifies its own role separately.
Accounting events have no receiving actor, so their diagnostic receiver is null.

| Check | Actual result |
| --- | --- |
| Java 8 compilation, `-Xlint:all` | Exit 0; no warnings or errors. |
| `VerifierBaselineTest` | Exit 0; all 49 simulated-report test groups passed. |
| `TomMessageTest` | Exit 0; all 23 packet assertions passed. |

Verifier output:

```text
PASS: immutable value identities
PASS: configuration validation and copying
PASS: complete multi-sender trace, equal payloads, reverse registration order and compatible lag
PASS: payload, diagnostic and snapshot defensive copying
PASS: first-position order divergence
PASS: later-position order divergence
PASS: duplicates and content length
PASS: delivery before independent registration
PASS: delivery: unknown receiver
PASS: delivery: wrong receiver incarnation
PASS: delivery: null receiver
PASS: delivery: unknown sender
PASS: delivery: wrong sender incarnation
PASS: delivery: wrong execution
PASS: delivery: wrong group
PASS: delivery: null identity
PASS: delivery: null payload
PASS: registration: reused identity
PASS: registration: reused slot
PASS: registration: negative slot
PASS: registration: past last slot
PASS: registration: null payload
PASS: registration: null identity
PASS: registration: wrong execution
PASS: registration: wrong group
PASS: registration: unknown sender
PASS: single-member group and self delivery
PASS: late delivery after nonempty success
PASS: omission at one recipient
PASS: omission by every recipient
PASS: submission NOT_INVOKED before registration
PASS: submission NOT_INVOKED after registration
PASS: submission REJECTED before registration
PASS: submission REJECTED after registration
PASS: submission UNCERTAIN before registration
PASS: submission UNCERTAIN after registration
PASS: issue: conflicting issue on accounted slot
PASS: registration: slot already uncertain
PASS: issue: null issue
PASS: issue: empty explanation
PASS: issue: unknown sender
PASS: reporting gaps and verdict precedence
PASS: closure, late reports and immutable earlier success
PASS: closed workload: registration
PASS: closed workload: issue
PASS: closed workload: repeated close
PASS: timeout and interruption precedence
PASS: coverage, unaccounted sends and sparse long counts
PASS: practical public arithmetic boundaries
VerifierBaselineTest: 49 simulated-report test groups passed
```

The packet regression ended with:

```text
TomMessageTest: all tests passed
```

## Manual Eclipse validation

On 4 November 2026, **Abdallah ABDELSADEK** manually verified commit
`fc7e574` in Eclipse IDE for Java Developers, version **2024-12 (4.34.0)**,
build **20241128-0757**. The results below were reported by Abdallah.

In the fresh workspace `sar-verification`, Abdallah used **File → Import →
General → Existing Projects into Workspace**, selected the repository root,
and left **Copy projects into workspace** unchecked. Eclipse detected
`Arhan_UNAY_Channels`. No additional project configuration or dependency
installation was needed. The build reported **0 errors and 25 warnings**.

Both launch configurations were checked manually and used the project execution
environment **JavaSE-1.8**, mapped to **Temurin 8.0.392**. Running the two entry
points as Java Applications produced:

| Entry point | Observed console result |
| --- | --- |
| `VerifierBaselineTest` | `VerifierBaselineTest: 49 simulated-report test groups passed` |
| `TomMessageTest` | `TomMessageTest: all tests passed` |

Abdallah inspected all 25 warnings: raw `Class`/`Constructor` generic types,
associated type-safety warnings, and unused imports, fields or local variables
in existing channel, queue and TOM test sources. None of the listed warnings
points to `src/edu/polytech/verifier/`. No warning suppression or source changes
were needed for this validation.

Commit `fc7e574` therefore imported, built and ran these two test entry points.
This does not establish that every project test passed or validate real
multicast integration, failures, reinsertion or dynamic membership. The complete
final submission must be checked again in a fresh Eclipse workspace after
integration.

## Coverage and interpretation

| Specification/design requirement | Evidence exercised |
| --- | --- |
| Independent originals and identities; S §§3.1–3.3, D §2 | Value equality includes execution/group/incarnation/sequence; equal payloads under distinct identities remain separate. Input arrays, configuration and returned collections/excerpts cannot alter retained evidence. |
| Integrity, duplicates and prefixes; S §§4.1–4.3, D §§4.2–4.3 | Content and length mismatches, duplicates, first/later divergence, compatible lag and delivery order different from registration order. A later correct duplicate cannot repair an earlier corrupted report. |
| Independent completeness; S §4.4, D §4.4 | Exact missing recipient/message pairs, including a confirmed message absent from every history. Delivery reports never create an original. |
| Planned accounting; S §§3.2, 4.5, D §3 | Reused/out-of-range slots and identities, unaccounted sends, submission issues before/after registration, preserved obligations after uncertainty, conflicting issues and malformed input. |
| Verdict precedence; S §6, D §§5, 7 | Gap alone is incomplete at closure; concrete violation plus gap/timeout is unsuccessful. Uncertain outcome alone does not become a concrete violation. |
| Closure and coverage; S §§4.5–5, D §5 | Workload draining, no premature success, empty dedicated workload, rejected contradictory traffic configurations, unmet feasible coverage, normal/timeout/interrupted closure, repeated/conflicting closes and late delivery superseding immutable earlier success. |
| Safe workload counts; D §2.2 | A plan of 6,442,450,941 messages and 19,327,352,823 possible deliveries stores only one accounted slot; its 6,442,450,940 unaccounted sends remain visible without per-send preallocation. |

Additional focused checks verify:

- Each malformed-report trace produces exactly one concrete diagnostic with
  the expected code, observation category and receiver/message context. Actor
  validation details distinguish Sender from Receiver and identify the invalid
  identity. Histories, sparse slots, counters and every missing pair/slot stay
  unchanged after rejection. Registration reuse attempts cannot overwrite the
  first original payload.
- One member owes itself exactly one delivery. Its self report discharges that
  obligation and permits success only after normal observation closure.
- A late delivery after a nonempty successful run produces `LATE_REPORT` with
  the actual receiver/message, changes the current verdict to unsuccessful,
  and leaves both delivery accounting and the earlier successful snapshot intact.
- Public numeric boundaries include `Integer.MAX_VALUE` send counts, the last
  valid slot (`Integer.MAX_VALUE - 1`), rejection of the next slot, long coverage
  minima equal to the declared totals, rejection one above those totals,
  maximum period/budget metadata, and extreme invalid negative values.

Arithmetic inspection: aggregate sums/products use `Math.addExact` and
`Math.multiplyExact`; confirmed/valid-delivery counters cannot exceed the
validated planned/possible totals because slots and obligations are unique.
The report ordinal is guarded at `Long.MAX_VALUE`; history positions are guarded
at `Integer.MAX_VALUE`. Tests do not force actual long overflow, exhaust report
ordinals or allocate histories near JVM list capacity. Those paths are assessed
by inspection; no reflection, production test hooks or enormous allocations
were introduced solely to reach them.

S refers to [verifier_spec.md](verifier_spec.md); D refers to
[verifier_design.md](verifier_design.md). The source test table in D §8 remains
the acceptance-case mapping. A test group can exercise several related traces.

## Application envelope version-1 codec — command-line validation

On 4 November 2026, the independent codec and unchanged verifier baseline were
compiled and run from the repository root on `abdallah-master` using installed
Temurin 8.0.392 (`javac 1.8.0_392`, runtime `1.8.0_392-b08`). These are codec
tests and simulated-report verifier regression tests, not TOM integration.
Generated classes were kept outside the repository. Exact commands:

```sh
mktemp -d /tmp/sar-envelope-codec.XXXXXX
# Returned /tmp/sar-envelope-codec.7LMS43
/home/abdallah/.sdkman/candidates/java/8.0.392-tem/bin/javac -version
/home/abdallah/.sdkman/candidates/java/8.0.392-tem/bin/java -version
/home/abdallah/.sdkman/candidates/java/8.0.392-tem/bin/javac -encoding UTF-8 -Xlint:all -d /tmp/sar-envelope-codec.7LMS43 src/edu/polytech/verifier/*.java src/edu/polytech/verifier/tests/*.java src/edu/polytech/verifier/tom/*.java src/edu/polytech/verifier/tom/tests/*.java
/home/abdallah/.sdkman/candidates/java/8.0.392-tem/bin/java -cp /tmp/sar-envelope-codec.7LMS43 edu.polytech.verifier.tom.tests.ApplicationEnvelopeCodecTest
/home/abdallah/.sdkman/candidates/java/8.0.392-tem/bin/java -cp /tmp/sar-envelope-codec.7LMS43 edu.polytech.verifier.tests.VerifierBaselineTest
```

Compilation exited 0 with no warnings or errors under `-Xlint:all`. Both test
processes exited 0 without `-ea`:

```text
ApplicationEnvelopeCodecTest: 225 checks passed
VerifierBaselineTest: 49 simulated-report test groups passed
```

Codec coverage includes empty/binary workloads, exact Unicode preservation
(including supplementary characters and BOM), numeric maxima, distinct IDs
with equal bytes, a hand-authored big-endian frame, minimum/maximum frame sizes,
1,024-byte names and 1 MiB workload boundaries, one-above rejection, byte versus
character limits, and defensive copies. Invalid cases check reason/offset
evidence for magic/version, signed lengths and identities, blank names, invalid
UTF-8 and unpaired Java surrogates. Every truncation offset of the 37-byte
fixture, trailing bytes and concatenated frames are rejected. Invalid inputs
pass their checks only when the expected checked exception is produced.

The codec has no runtime or TOM dependency and does not confirm submissions,
check registered originals or report deliveries; those remain adapter/core work.
Allocation tests are bounded at approximately 1 MiB per frame; they do not test
memory exhaustion, concurrent caller mutation or exhaustive malformed inputs.
No provisional-registration API, observer, transport integration, lifecycle or
membership behavior was implemented or validated. No new Eclipse UI validation
was performed: the manual `fc7e574` record above predates these codec files.
Sources remain under `src`, use Java 8 standard-library APIs, and require no
Eclipse configuration changes. The new files and final integrated submission
still need fresh-workspace Eclipse import/build/run verification.

## Provisional multicast accounting — 6 November 2026

Added protocol-independent `beginMulticast` and `confirmMulticast` observations.
Beginning copies the original, reserves the slot and creates all recipient
obligations without confirmation. Early deliveries can discharge obligations;
confirmation counts once without recreating them. Immutable results expose
`provisionalMessages()`, sparse PROVISIONAL slot descriptions and attributed
`PROVISIONAL_SUBMISSION` incomplete diagnostics. Unresolved or uncertain
submissions prevent baseline success even when coverage and deliveries are
otherwise complete. Atomic registration still uses one report event.

Commands executed from the repository root with installed Temurin Java 8:

```sh
mktemp -d /tmp/sar-verifier-provisional.XXXXXX
# Returned /tmp/sar-verifier-provisional.eHbrnS
/home/abdallah/.sdkman/candidates/java/8.0.392-tem/bin/javac -version
/home/abdallah/.sdkman/candidates/java/8.0.392-tem/bin/java -version
/home/abdallah/.sdkman/candidates/java/8.0.392-tem/bin/javac -encoding UTF-8 -Xlint:all -d /tmp/sar-verifier-provisional.eHbrnS src/edu/polytech/verifier/*.java src/edu/polytech/verifier/tests/*.java src/edu/polytech/verifier/tom/*.java src/edu/polytech/verifier/tom/tests/*.java
/home/abdallah/.sdkman/candidates/java/8.0.392-tem/bin/java -cp /tmp/sar-verifier-provisional.eHbrnS edu.polytech.verifier.tests.VerifierBaselineTest
/home/abdallah/.sdkman/candidates/java/8.0.392-tem/bin/java -cp /tmp/sar-verifier-provisional.eHbrnS edu.polytech.verifier.tom.tests.ApplicationEnvelopeCodecTest
```

Tool versions were `javac 1.8.0_392` and Temurin `1.8.0_392-b08`.
Compilation exited 0 with no warnings under `-Xlint:all`; both test processes
exited 0 without `-ea`:

```text
VerifierBaselineTest: 57 simulated-report test groups passed
ApplicationEnvelopeCodecTest: 225 checks passed
```

Eight new groups cover early delivery and later confirmation, independent
original copying, immutable provisional snapshots, unresolved/uncertain records
despite satisfied coverage, and repeated, unknown, uncertain, late and
post-workload-close confirmation. Rejections assert diagnostic category/context
and unchanged histories, obligations and counters. Confirmation after workload
closure is rejected as accounting misuse; callers must finish invocation
confirmation before closing the workload, as required by the adapter design.
All previous 49 baseline groups still pass. These remain simulated-report tests:
no TOM adapter, invalid-delivery hook, transport integration, runtime lifecycle,
watchdog or membership behavior was added. Class files stayed under `/tmp`.
Eclipse configuration was unchanged; no new Eclipse UI validation was performed.
The earlier manual Eclipse evidence applies to its recorded commit only.

## Real Group/TOM baseline integration — 6 November 2026

`edu.polytech.verifier.tom.tests.VerifierTomGroupTest` reuses `TomGroupTest`'s
real `Actor`/`GroupManager`/`TomMember` setup, bounded pump calls and polling,
and standalone-process `System.exit` cleanup (the executor has no shutdown API).
It waits for every initial view and validates the fixed member/incarnation set
before sending. Three non-failing actors each send two enveloped messages.
Each original is provisionally registered immediately before invocation and
confirmed only after normal return with the sender alive. Application IDs use
caller-assigned sequences, independent of TOM sequences. Receiver-bound listeners
decode each frame, validate callback sender/incarnation and report workload bytes
to the verifier. Callback errors are retained and fail the test, never silently
dropped. All verifier access occurs on the event pump.

Commands executed from the repository root with installed Temurin Java 8:

```sh
mktemp -d /tmp/sar-verifier-tom-group.XXXXXX
# Returned /tmp/sar-verifier-tom-group.LPCPlx
/home/abdallah/.sdkman/candidates/java/8.0.392-tem/bin/javac -encoding UTF-8 -Xlint:all -sourcepath src -d /tmp/sar-verifier-tom-group.LPCPlx src/edu/polytech/verifier/tom/tests/VerifierTomGroupTest.java src/edu/polytech/verifier/tests/VerifierBaselineTest.java src/edu/polytech/verifier/tom/tests/ApplicationEnvelopeCodecTest.java src/edu/polytech/tom/tests/TomGroupTest.java
timeout 30s /home/abdallah/.sdkman/candidates/java/8.0.392-tem/bin/java -cp /tmp/sar-verifier-tom-group.LPCPlx edu.polytech.verifier.tom.tests.VerifierTomGroupTest
timeout 30s /home/abdallah/.sdkman/candidates/java/8.0.392-tem/bin/java -cp /tmp/sar-verifier-tom-group.LPCPlx edu.polytech.tom.tests.TomGroupTest
/home/abdallah/.sdkman/candidates/java/8.0.392-tem/bin/java -cp /tmp/sar-verifier-tom-group.LPCPlx edu.polytech.verifier.tests.VerifierBaselineTest
/home/abdallah/.sdkman/candidates/java/8.0.392-tem/bin/java -cp /tmp/sar-verifier-tom-group.LPCPlx edu.polytech.verifier.tom.tests.ApplicationEnvelopeCodecTest
```

Compilation exited 0 with no warnings under `-Xlint:all`. All four test
processes exited 0; neither external timeout expired:

```text
VerifierTomGroupTest: SUCCESSFUL; 3 members, 6 messages, 18 deliveries, identical histories
TomGroupTest: all tests passed
VerifierBaselineTest: 57 simulated-report test groups passed
ApplicationEnvelopeCodecTest: 225 checks passed
```

The new integration test asserts successful explicit closure, six confirmations,
zero provisional/unaccounted sends, no missing obligations, and each recipient's
exact expected message set, count and common order. Equal workload bytes from
different senders exercise distinct application identities. This is one finite
real-component no-failure trace, not a reusable scenario runner or failure
verifier. Normal observation closes after the expected deliveries; it does not
establish protocol-wide traffic exhaustion or the absence of future late events.
The unchanged `TomGroupTest` additionally runs its existing survivor scenario;
that does not validate verifier failure semantics. No periodic scheduling,
watchdog, reinsertion or dynamic-membership checking was added. New sources
remain Java 8 under `src`; classes stayed under `/tmp`, Eclipse configuration
was unchanged, and no new Eclipse UI validation was performed.

## Reusable fixed-group runner and sending periods — 6 November 2026

The scenario is now extracted into
`edu.polytech.verifier.tom.FixedGroupTomRunner(String runId, int periodMillis)`.
Each single-use runner forms three non-failing actors, sends two messages per
actor, and returns an immutable `VerificationResult` from `run()`. Invoke it
off the event pump and use distinct run IDs for separate groups. Negative
periods and blank run IDs are rejected. The runner does not terminate the JVM;
the integration test retains standalone-process cleanup, matching `TomGroupTest`.

Initial-view readiness, independent originals before multicast, confirmation
after normal live return, and receiver-bound decoding/reporting are preserved.
Each sender performs one send per posted runnable. Period 0 means no intentional
delay and yields between sends. A positive period delays that sender's successor
from completion of its previous send; actual scheduling may be later. A monotonic
elapsed-time check in each successive-send runnable verifies the minimum period.
There is no sleeping on the event pump. Bounded caller-side polling posts pump
observations while the executor keeps processing traffic and delayed sends.
Workload closure occurs only after every sender's final confirmation. On exit,
scheduled send callbacks are disabled; existing group resources remain until
the caller terminates its standalone process.

Exact commands, with generated classes outside the repository:

```sh
mktemp -d /tmp/sar-verifier-period.XXXXXX
# Returned /tmp/sar-verifier-period.zWvIQF
/home/abdallah/.sdkman/candidates/java/8.0.392-tem/bin/javac -encoding UTF-8 -Xlint:all -sourcepath src -d /tmp/sar-verifier-period.zWvIQF src/edu/polytech/verifier/tom/tests/VerifierTomGroupTest.java src/edu/polytech/verifier/tests/VerifierBaselineTest.java src/edu/polytech/verifier/tom/tests/ApplicationEnvelopeCodecTest.java src/edu/polytech/tom/tests/TomGroupTest.java
timeout 30s /home/abdallah/.sdkman/candidates/java/8.0.392-tem/bin/java -cp /tmp/sar-verifier-period.zWvIQF edu.polytech.verifier.tom.tests.VerifierTomGroupTest
timeout 30s /home/abdallah/.sdkman/candidates/java/8.0.392-tem/bin/java -cp /tmp/sar-verifier-period.zWvIQF edu.polytech.tom.tests.TomGroupTest
/home/abdallah/.sdkman/candidates/java/8.0.392-tem/bin/java -cp /tmp/sar-verifier-period.zWvIQF edu.polytech.verifier.tests.VerifierBaselineTest
/home/abdallah/.sdkman/candidates/java/8.0.392-tem/bin/java -cp /tmp/sar-verifier-period.zWvIQF edu.polytech.verifier.tom.tests.ApplicationEnvelopeCodecTest
```

Java 8 compilation with `-Xlint:all` exited 0 without warnings. All four test
processes exited 0; neither external timeout expired. Actual summaries:

```text
VerifierTomGroupTest: period=0 ms SUCCESSFUL; 3 members, 6 messages, 18 deliveries, identical histories
VerifierTomGroupTest: period=5 ms SUCCESSFUL; 3 members, 6 messages, 18 deliveries, identical histories
TomGroupTest: all tests passed
VerifierBaselineTest: 57 simulated-report test groups passed
ApplicationEnvelopeCodecTest: 225 checks passed
```

For each period the integration test asserts success, six confirmed messages,
18 valid deliveries, no unresolved accounting, the expected unique message set
at each of three recipients and identical histories. The two runs use separate
groups in the same JVM. This tests fixed finite workloads and minimum per-sender
spacing, not precise wall-clock timing, arbitrary workload configuration,
protocol-wide traffic exhaustion, watchdog behavior or failure/membership
extensions. The existing `TomGroupTest` survivor regression remains separate
from verifier failure semantics. Eclipse configuration was preserved; new
Eclipse UI validation and final integrated submission validation remain pending.

## One real death without reinsertion — 7 November 2026

Added a separate `SurvivorVerifier` final checker; `GroupVerifier` and the
fixed-group runner are unchanged. Originals are copied before invocation,
confirmation is explicit, and uncertain originals remain recorded as optional
all-or-none messages (with an uncertainty diagnostic). Confirmed surviving-sender
messages are mandatory at each survivor, including messages omitted everywhere.
Only application reports supply histories; dead actors' histories are excluded.
Final survivor histories must match exactly, with original content and no duplicates.
Missing mandatory deliveries remain incomplete; final disagreement/partial
all-or-none delivery is unsuccessful with established completion evidence.
Concrete integrity/observation violations take precedence over timeout.

`VerifierSurvivorGroupTest` forms three actual Actors through GroupManager and
TomMember. Actor 2 sends one independently registered message; that finite
pre-death traffic is drained before its explicit suicide. Actual `Actor.dead()`
is observed separately from removal views. Both survivors must install exactly
view members 1 and 3 before each sends two known post-removal messages. Planned
submission coverage is independently five invocations, not derived from reports.
The result requires all four required messages at both survivors, the dead
sender's all-or-none outcome, and identical final survivor histories.

Completion evidence in this controlled fixture comes from opaque TOM packet
transit counts at a delegating Group boundary. Every forwarded TOM send is
counted; every corresponding Group receipt decrements its source/destination
count before normal dispatch. No packet type, DATA reception, ACK decision or
payload is inspected for verification. Group routing and delivery are unchanged.
After actual death, outstanding packets addressed to the dead recipient cannot
produce further work and are excluded; all traffic toward survivors must drain.
Pre-death draining avoids unresolved dead-source packets in this particular
scenario. With finite invocations returned, no active dispatch at assessment,
all removal views observed, all expected application messages accounted for,
and zero surviving-target transit counts, the fixture establishes its finite
TOM observation boundary. A dropped or unreported surviving-target packet
prevents completion rather than being silently erased. This is scoped evidence
for this synchronous event-pump fixture, not a general Group quiescence API or
stall detector. Quiet time and empty verifier obligations alone are not used.

Exact Java 8 commands (classes outside the repository):

```sh
mktemp -d /tmp/sar-survivor.XXXXXX
# Returned /tmp/sar-survivor.h0R30g
/home/abdallah/.sdkman/candidates/java/8.0.392-tem/bin/javac -encoding UTF-8 -Xlint:all -sourcepath src -d /tmp/sar-survivor.h0R30g src/edu/polytech/verifier/tests/SurvivorVerifierTest.java src/edu/polytech/verifier/tom/tests/VerifierSurvivorGroupTest.java src/edu/polytech/verifier/tom/tests/VerifierTomGroupTest.java src/edu/polytech/verifier/tests/VerifierBaselineTest.java src/edu/polytech/verifier/tom/tests/ApplicationEnvelopeCodecTest.java src/edu/polytech/tom/tests/*Test.java
/home/abdallah/.sdkman/candidates/java/8.0.392-tem/bin/java -cp /tmp/sar-survivor.h0R30g edu.polytech.verifier.tests.SurvivorVerifierTest
timeout 30s /home/abdallah/.sdkman/candidates/java/8.0.392-tem/bin/java -cp /tmp/sar-survivor.h0R30g edu.polytech.verifier.tom.tests.VerifierSurvivorGroupTest
timeout 45s /home/abdallah/.sdkman/candidates/java/8.0.392-tem/bin/java -cp /tmp/sar-survivor.h0R30g edu.polytech.verifier.tom.tests.VerifierTomGroupTest
timeout 45s /home/abdallah/.sdkman/candidates/java/8.0.392-tem/bin/java -cp /tmp/sar-survivor.h0R30g edu.polytech.verifier.tests.VerifierBaselineTest
timeout 45s /home/abdallah/.sdkman/candidates/java/8.0.392-tem/bin/java -cp /tmp/sar-survivor.h0R30g edu.polytech.verifier.tom.tests.ApplicationEnvelopeCodecTest
timeout 45s /home/abdallah/.sdkman/candidates/java/8.0.392-tem/bin/java -cp /tmp/sar-survivor.h0R30g edu.polytech.tom.tests.TomGroupTest
timeout 45s /home/abdallah/.sdkman/candidates/java/8.0.392-tem/bin/java -cp /tmp/sar-survivor.h0R30g edu.polytech.tom.tests.TomMessageTest
timeout 45s /home/abdallah/.sdkman/candidates/java/8.0.392-tem/bin/java -cp /tmp/sar-survivor.h0R30g edu.polytech.tom.tests.TomProcessTest
timeout 45s /home/abdallah/.sdkman/candidates/java/8.0.392-tem/bin/java -cp /tmp/sar-survivor.h0R30g edu.polytech.tom.tests.TomMemberTest
timeout 45s /home/abdallah/.sdkman/candidates/java/8.0.392-tem/bin/java -cp /tmp/sar-survivor.h0R30g edu.polytech.tom.tests.TomRandomDeathsTest
# After the final survivor-check/test refinement, recompiled and reran both new mains:
/home/abdallah/.sdkman/candidates/java/8.0.392-tem/bin/javac -encoding UTF-8 -Xlint:all -sourcepath src -d /tmp/sar-survivor.h0R30g src/edu/polytech/verifier/tests/SurvivorVerifierTest.java src/edu/polytech/verifier/tom/tests/VerifierSurvivorGroupTest.java
/home/abdallah/.sdkman/candidates/java/8.0.392-tem/bin/java -cp /tmp/sar-survivor.h0R30g edu.polytech.verifier.tests.SurvivorVerifierTest
timeout 30s /home/abdallah/.sdkman/candidates/java/8.0.392-tem/bin/java -cp /tmp/sar-survivor.h0R30g edu.polytech.verifier.tom.tests.VerifierSurvivorGroupTest
```

Both compilations exited 0 with no `-Xlint:all` warnings. Every test process
exited 0; no external timeout expired. Final new-test summaries:

```text
SurvivorVerifierTest: 15 simulated-report cases passed
VerifierSurvivorGroupTest: SUCCESSFUL; actual death, both removal views, 4 required messages, identical survivor histories
```

Regressions passed: fixed-group integration at 0/5 ms (each six confirmations,
18 deliveries and identical histories), 57 verifier baseline groups, 225 codec
checks, and all five TOM mains (`TomMessageTest`, `TomProcessTest`,
`TomMemberTest`, `TomGroupTest`, `TomRandomDeathsTest`). The last reports 2,000
simulated random-death runs; that is TOM evidence, not random-failure verifier
integration. The new independent cases cover optional all/none/partial outcomes,
mandatory omission by everyone (both missing receiver/message pairs), final
order disagreement, uncertainty, unresolved provisional state, dead-history
exclusion, defensive original copying, corruption, duplicates, missing removal,
timeout and reporting gaps. Invalid traces pass their tests only when rejected.

Limits: exactly one declared death and nonempty fixed survivors; no reinsertion,
voluntary membership changes, random actor failure or failure under sustained
load is implemented here. The real scenario exercises the dead message's
all-delivered branch; none/partial branches use simulated reports. Lifecycle and
completion observations are trusted adapter inputs to the independent checker.
The fixture retains TomGroupTest's bounded waits and standalone-process cleanup.
No teammate sources or Eclipse configuration changed. Fresh Eclipse UI validation
of these additions and final integrated submission validation remain pending.

## Real death and reinsertion — 7 November 2026

Added `VerifierReinsertionGroupTest`, using the real three-actor Group/TOM setup
and envelope reporting from the existing integration fixtures. Its independent
send-side oracle records eight originals and explicit recipient incarnations:
three before-death messages for the original group, two absent-phase messages
for survivors 1 and 3, and three post-return messages for the admitted group.
Original bytes and recipient sets are copied before multicast; normal live return
confirms each invocation. Callbacks bind the actual receiving incarnation at
listener installation, validate sender metadata, and check eligibility, content
and duplicates. Expected identities and obligations never come from deliveries.
This oracle is local test infrastructure, not a general dynamic-membership verifier.

Each phase waits for its declared application obligations. Actual suicide is
observed separately from both survivors' exact removal views. Resurrection must
produce a greater incarnation; all three installed views must then contain that
new incarnation before post-return sends begin. At final assessment, both
survivors have identical full eight-message histories, and the returning
incarnation's three-message history equals the survivors' projection onto the
independently declared post-return IDs. Earlier messages are forbidden for the
new incarnation even if the old incarnation previously delivered them.

Listeners remain installed throughout and after all required deliveries, including
an additional pump-scheduled 100 ms observation interval followed by final
assessment on the pump. They are not replaced with no-op listeners or disabled
when expected counts are reached. Bounded future waits and polling fail the test
on missing transition evidence, missing deliveries or callback errors. Standalone
process exit follows `TomGroupTest` because Executor has no shutdown API.

A focused simulated negative case uses the same eligibility/report/assessment
code: a correctly framed absent-phase application message is reported to the
returning incarnation. It must record `FORBIDDEN_REPLAY` with receiver/message
context, refuse an eligible history entry, and make final assessment throw.
The negative case passes only when that replay is rejected.

Exact commands (installed Temurin Java 8; class files outside the repository):

```sh
mktemp -d /tmp/sar-reinsertion.XXXXXX
# Returned /tmp/sar-reinsertion.VllLts
/home/abdallah/.sdkman/candidates/java/8.0.392-tem/bin/javac -encoding UTF-8 -Xlint:all -sourcepath src -d /tmp/sar-reinsertion.VllLts src/edu/polytech/verifier/tom/tests/*Test.java src/edu/polytech/verifier/tests/*Test.java src/edu/polytech/tom/tests/*Test.java
timeout 30s /home/abdallah/.sdkman/candidates/java/8.0.392-tem/bin/java -cp /tmp/sar-reinsertion.VllLts edu.polytech.verifier.tom.tests.VerifierReinsertionGroupTest
timeout 45s /home/abdallah/.sdkman/candidates/java/8.0.392-tem/bin/java -cp /tmp/sar-reinsertion.VllLts edu.polytech.verifier.tom.tests.VerifierTomGroupTest
timeout 45s /home/abdallah/.sdkman/candidates/java/8.0.392-tem/bin/java -cp /tmp/sar-reinsertion.VllLts edu.polytech.verifier.tom.tests.VerifierSurvivorGroupTest
timeout 45s /home/abdallah/.sdkman/candidates/java/8.0.392-tem/bin/java -cp /tmp/sar-reinsertion.VllLts edu.polytech.verifier.tests.SurvivorVerifierTest
timeout 45s /home/abdallah/.sdkman/candidates/java/8.0.392-tem/bin/java -cp /tmp/sar-reinsertion.VllLts edu.polytech.verifier.tests.VerifierBaselineTest
timeout 45s /home/abdallah/.sdkman/candidates/java/8.0.392-tem/bin/java -cp /tmp/sar-reinsertion.VllLts edu.polytech.verifier.tom.tests.ApplicationEnvelopeCodecTest
timeout 45s /home/abdallah/.sdkman/candidates/java/8.0.392-tem/bin/java -cp /tmp/sar-reinsertion.VllLts edu.polytech.tom.tests.TomGroupTest
timeout 45s /home/abdallah/.sdkman/candidates/java/8.0.392-tem/bin/java -cp /tmp/sar-reinsertion.VllLts edu.polytech.tom.tests.TomMessageTest
timeout 45s /home/abdallah/.sdkman/candidates/java/8.0.392-tem/bin/java -cp /tmp/sar-reinsertion.VllLts edu.polytech.tom.tests.TomProcessTest
timeout 45s /home/abdallah/.sdkman/candidates/java/8.0.392-tem/bin/java -cp /tmp/sar-reinsertion.VllLts edu.polytech.tom.tests.TomMemberTest
timeout 45s /home/abdallah/.sdkman/candidates/java/8.0.392-tem/bin/java -cp /tmp/sar-reinsertion.VllLts edu.polytech.tom.tests.TomRandomDeathsTest
```

Compilation exited 0 with no `-Xlint:all` warnings. All test processes exited 0,
with no external timeouts. New test output:

```text
VerifierReinsertionGroupTest: simulated forbidden-replay negative case passed
VerifierReinsertionGroupTest: SUCCESSFUL; death/removal/new incarnation observed; survivors 8 each, returning 3, no earlier replay
```

Regressions passed: both fixed-group periods (0/5 ms), real-death survivor
integration, 15 independent survivor cases, 57 baseline groups, 225 codec checks,
and all five TOM mains (`TomGroupTest`, `TomMessageTest`, `TomProcessTest`,
`TomMemberTest`, `TomRandomDeathsTest`; the latter reports 2,000 simulated runs).
Existing verifier, runner and teammate sources are unchanged.

Observation limits: this finite controlled trace exercises explicit death and
reinsertion between completed application workloads, following TOM v3's stated
no-earlier-message contract. It does not settle in-flight membership boundaries,
random failure, concurrent joins/departures or state transfer. The extra 100 ms
is an explicit observation window, not proof of protocol-wide traffic exhaustion
or an unlimited no-replay guarantee. Success here covers the stated scenario
and window; future/late replay outside that window is not established absent
by this test. No new quiescence API or recovery mechanism was added. Java 8 and
Eclipse configuration are preserved; no new Eclipse UI validation was performed.
Final integrated fresh-workspace validation remains required.

## Runtime-triggered random actor failure — 7 November 2026

Added a separate `VerifierRandomFailureGroupTest`; the explicit-suicide test
remains unchanged. Each of two runs forms three real Group/TOM actors with
runtime failure probability zero. Only actor 2 is constructed fail-capable;
the driver and actors 1 and 3 are protected. After all initial views and
incarnations are checked, actor 2 sends one independently recorded message and
that finite traffic drains. Only then the fixture enables the actual runtime
failure mechanism with **seed 123 and probability 0.35**. An actor-owned eligible
probe posts bounded successive callbacks (at most 100 executions, separated by
1 ms), letting Executor's seeded sampling trigger failure before a callback.
No suicide, manual kill, artificial exception or alternate failure detector is
used. The probe's failure listener runs on the protected driver and must report
`IllegalStateException("Simulated task failure")`; `Actor.dead()` must also be
true. In both observed runs, two probe callbacks executed before runtime failure.

Actual death is recorded separately from each survivor's installed removal view.
Both exact survivor views must be observed before post-removal traffic starts.
Each survivor then sends two messages at the configured period (0 ms or 5 ms).
The first send is posted immediately; every successor uses a separate runnable,
yielding at 0 ms and delaying at least 5 ms after the preceding send completes
at 5 ms. A monotonic elapsed-time assertion checks positive pacing. Expectations
are send-side originals registered before multicast, followed by confirmation
only on normal live return. Five returned submissions are required independently
of callbacks; four are mandatory survivor messages. Receiver-bound envelope
reports go through the existing `SurvivorVerifier` for content, duplicates,
mandatory deliveries, final full-history equality and dead-sender all-or-none.

Completion uses the same opaque transit accounting as the explicit-death fixture,
with no DATA-reception evidence or protocol decision changes. Survivor-target
traffic must drain, all paced sends must finish, and all mandatory deliveries
must arrive. Missing death/removal evidence or timeout fails the test process;
none is accepted as a successful scenario. The runtime failure configuration
is reset to probability zero in `finally`, using the synchronized runtime API
without relying on the pump; an outer final reset also covers failures between
runs. Pacing callbacks are disabled during cleanup. Standalone process exit
matches the existing integration fixtures.

Exact Java 8 commands, with generated classes outside the repository:

```sh
mktemp -d /tmp/sar-random-verifier.XXXXXX
# Returned /tmp/sar-random-verifier.gItXTu
/home/abdallah/.sdkman/candidates/java/8.0.392-tem/bin/javac -encoding UTF-8 -Xlint:all -sourcepath src -d /tmp/sar-random-verifier.gItXTu src/edu/polytech/verifier/tom/tests/*Test.java src/edu/polytech/verifier/tests/*Test.java src/edu/polytech/tom/tests/*Test.java
timeout 30s /home/abdallah/.sdkman/candidates/java/8.0.392-tem/bin/java -cp /tmp/sar-random-verifier.gItXTu edu.polytech.verifier.tom.tests.VerifierRandomFailureGroupTest
timeout 45s /home/abdallah/.sdkman/candidates/java/8.0.392-tem/bin/java -cp /tmp/sar-random-verifier.gItXTu edu.polytech.verifier.tom.tests.VerifierTomGroupTest
timeout 45s /home/abdallah/.sdkman/candidates/java/8.0.392-tem/bin/java -cp /tmp/sar-random-verifier.gItXTu edu.polytech.verifier.tom.tests.VerifierSurvivorGroupTest
timeout 45s /home/abdallah/.sdkman/candidates/java/8.0.392-tem/bin/java -cp /tmp/sar-random-verifier.gItXTu edu.polytech.verifier.tom.tests.VerifierReinsertionGroupTest
timeout 45s /home/abdallah/.sdkman/candidates/java/8.0.392-tem/bin/java -cp /tmp/sar-random-verifier.gItXTu edu.polytech.verifier.tests.SurvivorVerifierTest
timeout 45s /home/abdallah/.sdkman/candidates/java/8.0.392-tem/bin/java -cp /tmp/sar-random-verifier.gItXTu edu.polytech.verifier.tests.VerifierBaselineTest
timeout 45s /home/abdallah/.sdkman/candidates/java/8.0.392-tem/bin/java -cp /tmp/sar-random-verifier.gItXTu edu.polytech.verifier.tom.tests.ApplicationEnvelopeCodecTest
timeout 45s /home/abdallah/.sdkman/candidates/java/8.0.392-tem/bin/java -cp /tmp/sar-random-verifier.gItXTu edu.polytech.tom.tests.TomGroupTest
timeout 45s /home/abdallah/.sdkman/candidates/java/8.0.392-tem/bin/java -cp /tmp/sar-random-verifier.gItXTu edu.polytech.tom.tests.TomMessageTest
timeout 45s /home/abdallah/.sdkman/candidates/java/8.0.392-tem/bin/java -cp /tmp/sar-random-verifier.gItXTu edu.polytech.tom.tests.TomProcessTest
timeout 45s /home/abdallah/.sdkman/candidates/java/8.0.392-tem/bin/java -cp /tmp/sar-random-verifier.gItXTu edu.polytech.tom.tests.TomMemberTest
timeout 45s /home/abdallah/.sdkman/candidates/java/8.0.392-tem/bin/java -cp /tmp/sar-random-verifier.gItXTu edu.polytech.tom.tests.TomRandomDeathsTest
```

Compilation exited 0 with no `-Xlint:all` warnings. All test processes exited 0;
no external timeout expired. New test output:

```text
VerifierRandomFailureGroupTest: period=0 ms seed=123 probability=0.35 probeExecutions=2 SUCCESSFUL; runtime death, both removal views, 4 required messages, identical survivor histories
VerifierRandomFailureGroupTest: period=5 ms seed=123 probability=0.35 probeExecutions=2 SUCCESSFUL; runtime death, both removal views, 4 required messages, identical survivor histories
```

All regressions passed: fixed-group integration at 0/5 ms, separate explicit
suicide integration, reinsertion integration/replay negative case, 15 independent
survivor cases, 57 baseline groups, 225 codec checks and all five TOM test mains.
`TomRandomDeathsTest` reports 2,000 simulated random-death runs; it remains
component evidence separate from the new actual-runtime scenario.

Limits: one seed, one fail-capable actor, one real death per run, and finite
post-removal workloads. The probe exercises random runtime failure after readiness,
not failure during a multicast or randomized membership choice. Dead-sender
traffic is completed before enabling failures, so this real scenario exercises
all-delivered; none/partial outcomes remain independent simulated cases. A seed
does not guarantee identical event scheduling across environments. The bounded
probe can exhaust or wait out the budget without a death, which fails rather
than substituting an explicit kill. No reinsertion, concurrent membership action,
production watchdog or new runtime/TOM API is added. Existing verifier and
teammate sources and Eclipse configuration are unchanged. No new Eclipse UI
validation was performed; final fresh-workspace validation remains required.

## Limitations and remaining integration

- Simulated reports validate the checker against supplied evidence, not the
  faithfulness/completeness of future real reporting adapters.
- Independent baseline timeout/interruption cases supply close reasons
  explicitly. Runner integration tests exercise bounded waiting and periods
  0/5 ms, without a watchdog or a general configurable execution deadline.
- The runner connects the core and codec to real Group/TOM components for
  a fixed no-failure workload at a configurable period. It adds no general observation adapter,
  task-clock integration or dynamic-membership checker. A separate single-death
  survivor checker is now tested as recorded above.
- The core requires sequential calls. No concurrent-use guarantee or monitor
  adapter is provided. Memory grows with actual registrations, delivery reports
  and diagnostics; snapshots copy observed evidence. This is not an unbounded
  stress or exhaustive correctness proof.
- The packet regression covers DATA/ACK/LEFTOVER representations. It establishes
  no multicast ordering, failure-detector or no-replay guarantee.
- Command-line validation generated classes outside the repository. The separate
  manual Eclipse check above validates import/build and the two stated entry
  points at `fc7e574`; final integrated submission verification remains pending.

Further work includes a general reviewed observation adapter and broader scenario
configuration, task/TOM clock integration where required, and concrete protocol-wide
completion evidence. Initial Group readiness and queue transport are exercised
by the new test rather than remaining absent connections. Verifier failure and
reinsertion boundaries remain outside this baseline integration result.
