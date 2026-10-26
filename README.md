# SAR Project - Fault-Tolerant Totally-Ordered Multicast

Polytech Grenoble, 2026

## Group

| Member | University email | Role |
| --- | --- | --- |
| Arhan UNAY | arhan.unay@etu.univ-grenoble-alpes.fr | Contact member |
| Adam TAWFIK | adam.tawfik@etu.univ-grenoble-alpes.fr | Group member |
| Youssef KIRLOS | youssef.kirlos@etu.univ-grenoble-alpes.fr | Group member |
| Abdallah ABDELSADEK | abdallah.abdelsadek@etu.univ-grenoble-alpes.fr | Group member |

We will use Arhan UNAY's existing SAR course repository:
[UNAY_Arhan_Parallel_Distributed_Systems](https://github.com/arhis222/UNAY_Arhan_Parallel_Distributed_Systems).

## Branches and submission

- Default and shared development branch: `project-master`.
- Final submission branch: `master`.
- Earlier coursework: `lesson-arhan-personal-master`.
- Deadline stated in the course email: 8 November 2026, before midnight.

Members will merge their work into `project-master`. The completed and tested
project will then be merged into `master` before the deadline. The professor
should use `master` to evaluate the final submission, as required by the PDF.
For now, `master` contains the initial project setup; it is not a finished
submission.

`lesson-arhan-personal-master` was Arhan UNAY's original `master` branch for
individual SAR coursework before the group project. It preserves that work
separately from the group's project branch.

Each member will also work on a personal branch and contribute commits and
merges under their own Git identity.

| Member | Personal branch | GitHub username |
| --- | --- | --- |
| Arhan UNAY | `arhan-master` | `arhis222` |
| Adam TAWFIK | `adam-master` | `adamtawfik2-creator` |
| Youssef KIRLOS | `youssef-master` | `youssefkirlos` |
| Abdallah ABDELSADEK | `abdallah-master` | `abdallahwalid` |

Each member will use their personal branch and merge their work into
`project-master`. GitHub usernames are listed above; Git commit author names
will also be recorded as members contribute. Arhan's current Git author name
is `arhis222`.

## Project brief

The original assignment is in [project.pdf](docs/project.pdf).

We will build the multicast on the existing `QueueBroker` and `MessageQueue`
platform, using the supplied circular buffers. All actors and tasks will run
in the same JVM.

The work will cover:

1. Per-task logical clocks and task termination.
2. Actors, group creation, membership notifications and failure simulation.
3. Totally-ordered multicast without failures.
4. Actor failures without reinsertion.
5. Resurrection and reinsertion.
6. Voluntary joins and departures in a dynamic group.

A group verifier will check delivered messages, their content and their order.
An actor will not receive messages missed while it was dead after reinsertion.
Tests will support explicit and random failures, with an adjustable sending
period down to 0 ms for burst mode.

## Repository layout and documentation

- `src/`: Java sources and existing course tests.
- `docs/project.pdf`: original project assignment.
- `.project`, `.classpath`, `.settings/`: Eclipse project configuration.
- `Teacher Tests.launch`, `Queue Tests.launch`: existing Eclipse test launchers.
- [lesson-only-arhans-personal/](lesson-only-arhans-personal/): Arhan's earlier
  individual coursework, including lecture PDFs, specifications and designs.
  These files are reference material, not the group's final project documents.
- [Coursework TOM specification](lesson-only-arhans-personal/specs_tom.txt)
  and [design](lesson-only-arhans-personal/design_tom.txt): starting references
  for the project.
- `docs/`: project assignment and future group specification/design documents.

The coursework TOM documents are preliminary. They must be adapted to the
project brief, especially the local task runtime, dynamic membership and the
rule that messages missed during a failure are never replayed to that actor.

## Eclipse project and tests

The repository root is the Eclipse project directory. Its current Eclipse
name is `Arhan_UNAY_Channels`, named after the contact member.

Import it through **File > Import > General > Existing Projects into Workspace**
and select the repository root. The final submission must be checked in a
fresh Eclipse workspace without additional setup steps.

For the existing course tests, open `Teacher Tests.launch` or
`Queue Tests.launch` and choose **Run As > Java Application**. Both launchers
enable assertions. These are channel and queue tests, not the project TOM tests.

| Test suite | Current status |
| --- | --- |
| Existing channel and queue tests | Not rerun for this project setup |
| Logical clocks | Not implemented yet |
| Groups and membership | Not implemented yet |
| TOM without failures | Not implemented yet |
| TOM with failures, no reinsertion | Not implemented yet |
| TOM with resurrection and reinsertion | Not implemented yet |
| TOM with dynamic membership | Not implemented yet |
| Fresh Eclipse workspace import | To be verified for the final project |

Project test commands, sending-period settings and pass/fail results will be
added here as the corresponding tests are implemented.

## Contributions

### Work already completed

Arhan UNAY completed the initial preparation for the group project:

- Provided his existing SAR course repository and the channel and message
  queue implementations developed during the course as the starting code.
- Prepared the earlier specification and design documents, including the
  initial TOM design, and organised these references in
  `lesson-only-arhans-personal/`.
- Set up the Eclipse project configuration and the existing channel and queue
  test launchers. The final project still needs a fresh-workspace import check.
- Initialised the group project, added the assignment in `docs/project.pdf`
  and prepared the README with group members and submission instructions.
- Organised the shared development branch, personal branches and final
  submission branch, while preserving the original coursework branch.
- Prepared the initial task allocation and integration plan for the group.

### Project coordination

Arhan UNAY is the group's contact member and repository maintainer. Alongside
his implementation tasks below, he will coordinate communication with the
professor, track progress with the group and organise integration and final
submission. Each member remains responsible for their own code, tests,
documentation, commits and merges.

### Planned project responsibilities

The following is our task allocation, not a record of completed work. Each
member is responsible for the implementation, specification/design notes and
tests for their part. We will share the failure and recovery work because it
affects both membership and message ordering.

| Member / branch | Main implementation | Tests and documentation |
| --- | --- | --- |
| Arhan UNAY / `arhan-master` | Task runtime and Actor lifecycle: per-task logical clocks, cancelling pending runnables, actor suicide, random failures and the per-task failure flag. Stop all tasks belonging to a failed actor and create fresh tasks on resurrection. | Clock and termination tests, including pending callbacks after death. Runtime/Actor specification and design. Coordinate integration and verify the final Eclipse import. |
| Adam TAWFIK / `adam-master` | Group layer: initial N-member formation through the group manager, peer links, perfect failure detection and membership listeners. Drop traffic to/from dead members. Manage join, leave and reinsertion requests. | Group formation and membership tests, including delayed traffic from dead actors. Group specification and design. Implement agreed membership transitions jointly with Youssef. |
| Youssef KIRLOS / `youssef-master` | TOM ordering: message identities, Lamport timestamps, ordered pending queue, acknowledgements and delivery. First implement the no-failure version, then adapt ordering and pending-message handling to actor failures. | No-failure and failure-without-reinsertion tests. Ordering specification and correctness argument. Define safe delivery boundaries for membership transitions jointly with Adam. |
| Abdallah ABDELSADEK / `abdallah-master` | Group Verifier and scenario runner: direct-method delivery/lifecycle reports, order/content checks and membership-aware expected deliveries. Configurable periodic sends, 0 ms burst mode and repeatable failure scenarios. | Integration scenarios for resurrection/reinsertion and voluntary joins/leaves, with both explicit and random failures. Check that missed messages are never replayed. Document test commands and pass/fail results. |

### Integration and shared work

- Before coding, all four members will agree on the Actor, Group, TOM and
  Verifier interfaces, message identities and lifecycle notifications.
- Arhan will provide runtime failure events; Adam will turn them into group
  notifications. Adam and Youssef will jointly define when a membership
  change takes effect and which pending messages may be delivered. Removing
  a dead member from an ACK list alone is not a complete recovery protocol.
- For resurrection and dynamic membership, Arhan owns task recreation, Adam
  owns membership admission/removal, Youssef owns ordering across the change,
  and Abdallah owns verification of the resulting delivery intervals. No
  returning actor may receive messages missed while it was dead.
- Abdallah maintains the common test runner, but each member writes their
  component tests and fixes defects in their own part. All four members will
  run and review the integration scenarios.
- We will integrate in the assignment's order: no failures; failures without
  reinsertion; resurrection/reinsertion; voluntary joins and departures.
- Each member commits and merges under their own identity. Work is integrated
  into `project-master`; the tested final version is merged into `master`.
  Completed contributions and test results will be recorded here as we finish.

