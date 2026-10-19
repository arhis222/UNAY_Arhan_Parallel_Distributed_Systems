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

| Member | Work |
| --- | --- |
| Arhan UNAY | Existing course repository, channel and queue work, initial documentation and project setup |
| Adam TAWFIK | Project tasks to be assigned |
| Youssef KIRLOS | Project tasks to be assigned |
| Abdallah ABDELSADEK | Project tasks to be assigned |

