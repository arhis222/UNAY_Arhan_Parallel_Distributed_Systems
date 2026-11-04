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
check, not TOM integration. No teammate source was modified.

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

The review follow-up reran compilation and both suites on 4 November 2026.
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

## User-performed manual Eclipse validation

On 4 November 2026, **Abdallah ABDELSADEK** manually verified commit
`fc7e574` in Eclipse IDE for Java Developers, version **2024-12 (4.34.0)**,
build **20241128-0757**. This records the tester's supplied evidence; it is
not an Eclipse check performed by the assistant.

In the fresh workspace `sar-verification`, the tester used **File → Import →
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

The tester inspected all 25 warnings: raw `Class`/`Constructor` generic types,
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

## Limitations and remaining integration

- Simulated reports validate the checker against supplied evidence, not the
  faithfulness/completeness of future real reporting adapters.
- Timeout/interruption are supplied explicitly; no real timing or scheduling
  behavior is tested. Period/budget values are validated metadata only.
- No envelope, TOM transport adapter, runtime dependency, Group readiness,
  task-clock integration, watchdog, failure or dynamic-membership checker is
  implemented in stage 1.
- The core requires sequential calls. No concurrent-use guarantee or monitor
  adapter is provided. Memory grows with actual registrations, delivery reports
  and diagnostics; snapshots copy observed evidence. This is not an unbounded
  stress or exhaustive correctness proof.
- The packet regression covers DATA/ACK/LEFTOVER representations. It establishes
  no multicast ordering, failure-detector or no-replay guarantee.
- Command-line validation generated classes outside the repository. The separate
  manual Eclipse check above validates import/build and the two stated entry
  points at `fc7e574`; final integrated submission verification remains pending.

Next agreements remain Youssef's application identity/registration boundary,
Adam's Group readiness and transport, Arhan/Youssef's task/TOM clock connection,
and shared observation closure. Failure/reinsertion boundaries remain open and
do not block these independent baseline checks.
