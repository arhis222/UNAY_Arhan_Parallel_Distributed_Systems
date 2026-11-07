# SAR Project - Fault-Tolerant Totally-Ordered Multicast

Polytech Grenoble, 2026

## Group

| Member | University email | Role |
| --- | --- | --- |
| Arhan UNAY | arhan.unay@etu.univ-grenoble-alpes.fr | Contact member |
| Adam TAWFIK | adam.tawfik@etu.univ-grenoble-alpes.fr | Group member |
| Youssef KIRLOS | youssef.kirlos@etu.univ-grenoble-alpes.fr | Group member |
| Abdallah ABDELSADEK | abdallah.abdelsadek@etu.univ-grenoble-alpes.fr | Group member |

The project uses Arhan UNAY's existing SAR course repository:
[UNAY_Arhan_Parallel_Distributed_Systems](https://github.com/arhis222/UNAY_Arhan_Parallel_Distributed_Systems).

## Branches and submission

- Default and shared development branch: `project-master`.
- Final submission branch: `master`.
- Earlier coursework: `lesson-arhan-personal-master`.
- Deadline stated in the course email: 8 November 2026, before midnight.

Development was integrated on `project-master`. The tested version is also on
`master`, which is the branch to use for the final submission as required by
the assignment.

`lesson-arhan-personal-master` was Arhan UNAY's original `master` branch for
individual SAR coursework before the group project. It preserves that work
separately from the group's project branch.

Each member worked on a personal branch and contributed commits and merges
under their own Git identity.

| Member | Personal branch | GitHub username |
| --- | --- | --- |
| Arhan UNAY | `arhan-master` | `arhis222` |
| Adam TAWFIK | `adam-master` | `adamtawfik2-creator` |
| Youssef KIRLOS | `youssef-master` | `youssefkirlos` |
| Abdallah ABDELSADEK | `abdallah-master` | `abdallahwalid` |

Work from the personal branches was merged into `project-master`. GitHub
usernames are listed above; Arhan's Git author name is `arhis222`.

## Project brief

The original assignment is in [project.pdf](docs/project.pdf).

The multicast is built on the existing `QueueBroker` and `MessageQueue`
platform, using the supplied circular buffers. All actors and tasks will run
in the same JVM.

The project covers:

1. Per-task logical clocks and task termination.
2. Actors, group creation, membership notifications and failure simulation.
3. Totally-ordered multicast without failures.
4. Actor failures without reinsertion.
5. Resurrection and reinsertion.
6. Voluntary joins and departures in a dynamic group.

A group verifier checks delivered messages, their content and their order.
An actor will not receive messages missed while it was dead after reinsertion.
Tests support explicit and random failures, with an adjustable sending
period down to 0 ms for burst mode.

## Repository layout and documentation

- `src/`: Java sources and existing course tests.
- `docs/project.pdf`: original project assignment.
- `.project`, `.classpath`, `.settings/`: Eclipse project configuration.
- `Teacher Tests.launch`, `Queue Tests.launch`: existing channel and queue tests.
- `Runtime Tests.launch`: task clock, termination and Actor lifecycle tests.
- `Group Tests.launch`, `Group Dynamic Tests.launch`: group formation,
  failures and dynamic membership tests.
- [Runtime specification](docs/runtime_spec.md) and
  [design](docs/runtime_design.md): Arhan's runtime and Actor contribution.
- [lesson-only-arhans-personal/](lesson-only-arhans-personal/): Arhan's earlier
  individual coursework, including lecture PDFs, specifications and designs.
  These files are reference material, not the group's final project documents.
- [Coursework TOM specification](lesson-only-arhans-personal/specs_tom.txt)
  and [design](lesson-only-arhans-personal/design_tom.txt): starting references
  for the project.
- `docs/group_spec.md`, `docs/group_design.md`: Group specification and design.
- `docs/totally-ordered-multicast/`: TOM specifications and test guide.
- `docs/verifier_spec.md`, `docs/verifier_design.md`, `docs/verifier_tests.md`:
  Verifier specification, design and recorded test results.

The files under `lesson-only-arhans-personal/` are course references. The
project documentation is under `docs/`.

## Eclipse project and tests

The repository root is the Eclipse project directory. Its Eclipse
name is `Arhan_UNAY_Channels`, named after the contact member.

Import the repository with **File > Import > General > Existing Projects into
Workspace**. Eclipse detects the project as `Arhan_UNAY_Channels`. Test classes
can be opened and run with **Run As > Java Application**. The five `.launch`
files at the repository root can also be opened directly.

| Test stage | Test classes | Status on 7 November 2026 |
| --- | --- | --- |
| Existing platform | `ComplianceTests`, `Teacher Tests.launch`, `Queue Tests.launch` | Passed, including 4 clients × 5 channel connections × 32768 bytes and 8 clients × 10 queue connections × 65536 bytes |
| Runtime and Actors | `RuntimeTest` | Passed; deliberate task termination prints expected stack traces before `RuntimeTest: PASS` |
| Group | `GroupTest`, `GroupDynamicTest` | Passed; explicit and random failures, reinsertion, join and leave, at 5 ms and 0 ms |
| TOM protocol | `TomMessageTest`, `TomProcessTest`, `TomRandomDeathsTest` | Passed; includes 2000 random-death runs |
| TOM with real Group/Actors | `TomMemberTest`, `TomGroupTest` | Passed; no failure, failures, reinsertion and dynamic membership |
| Verifier unit tests | `ApplicationEnvelopeCodecTest`, `VerifierBaselineTest`, `SurvivorVerifierTest` | Passed; 225 codec checks, 57 baseline groups and 15 survivor cases |
| Verifier integration | `VerifierTomGroupTest`, `VerifierSurvivorGroupTest`, `VerifierRandomFailureGroupTest`, `VerifierReinsertionGroupTest`, `VerifierJoinLeaveGroupTest` | Passed; 0/5 ms traffic, explicit and random failure, reinsertion with no replay, join and leave |
| Eclipse | Fresh workspace import and `VerifierJoinLeaveGroupTest` from **Run As > Java Application** | Passed; 0 build errors, 26 warnings in existing course/test sources |

The integration tests use finite workloads and explicit completion checks. A
0 ms period is burst mode. `VerifierRandomFailureGroupTest` uses a fixed seed,
and `VerifierReinsertionGroupTest` runs both explicit suicide and a runtime
random failure before resurrection.

Useful package names for Eclipse's **Open Type** dialog are:

- `edu.polytech.actors.tests`
- `edu.polytech.groups.tests`
- `edu.polytech.tom.tests`
- `edu.polytech.verifier.tests`
- `edu.polytech.verifier.tom.tests`

## Contributions

### Arhan UNAY

Arhan's runtime contribution adds per-task logical clocks, pending-event
cancellation, actor-wide failure, configurable random failures and fresh
Actor incarnations. The runtime/Actor implementation and component tests are
complete on `arhan-master` and integrated into the project. Tests include
failure-flag changes and suicide after normal task completion.

Arhan UNAY completed the initial preparation for the group project:

- Provided his existing SAR course repository and the channel and message
  queue implementations developed during the course as the starting code.
- Prepared the earlier specification and design documents, including the
  initial TOM design, and organised these references in
  `lesson-only-arhans-personal/`.
- Set up the Eclipse project configuration and the channel, queue, runtime and
  group test launchers.
- Initialised the group project, added the assignment in `docs/project.pdf`
  and prepared the README with group members and submission instructions.
- Organised the shared development branch, personal branches and final
  submission branch, while preserving the original coursework branch.
- Prepared the initial task allocation and integration plan for the group.

### Adam TAWFIK

Adam implemented the Group layer: initial group formation through the manager,
peer links over message queues, common views, failure detection, reinsertion,
voluntary join and leave. `GroupTest` and `GroupDynamicTest` cover formation,
packet draining, failures, membership changes and burst traffic. The related
documents are `docs/group_spec.md` and `docs/group_design.md`.

### Youssef KIRLOS

Totally-ordered multicast, developed on `youssef-master` and merged into `project-master`.

What was done:

- The packets DATA, ACK and LEFTOVER, with the identity of a message (sender, incarnation, sequence).
- The multicast of one member: logical clock, ordered queue, ACKs and delivery, and members that die.
- The link with the Group and the Actors: members can die, leave, join and come back.
- The identity of a message for the verifier, and a logical clock that follows the clocks of the tasks.

Where the files are:

- Code: `src/edu/polytech/tom/` (`TotalOrderMulticast`, `TomMessage`, `TomProcess`, `TomMember`, `TaskClock`).
- Tests: `src/edu/polytech/tom/tests/` (`TomMessageTest`, `TomProcessTest`, `TomRandomDeathsTest`,
  `TomMemberTest`, `TomGroupTest`). Each one is run with Run As > Java Application.
- Documents: `docs/totally-ordered-multicast/` (`README.md` with the list of files, `tom_spec.md`,
  `tom_process_spec.md`, `tom_version2_spec.md`, `tom_version3_spec.md`).

### Abdallah ABDELSADEK

Abdallah implemented the application envelope, fixed-group and survivor
verifiers, and the integration scenarios that connect the Verifier to real
Actors, Group, queues and TOM. The tests cover no failure, explicit failure,
seeded random failure, resurrection/reinsertion, forbidden replay, voluntary
join and leave, and both 0 ms and 5 ms sending periods. The documentation is in
`docs/verifier_spec.md`, `docs/verifier_design.md` and `docs/verifier_tests.md`.

### Shared integration

Adam and Youssef connected membership views to TOM periods. Arhan's task clocks
are used through `TaskClock`. Abdallah's integration runner records multicast
identities and deliveries through direct verifier calls. All members contributed
commits and merges on their personal branches and `project-master`.

