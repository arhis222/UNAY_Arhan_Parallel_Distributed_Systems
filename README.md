# Name: Arhan UNAY
---
## School mail: arhan.unay@etu.univ-grenoble-alpes.fr
---
## Personal mail: arhanyo@gmail.com

## Running the project

The project uses Java 8 or newer and has no external dependencies.

In Eclipse, use File > Import > Existing Projects into Workspace and select
this folder. Run `Teacher Tests.launch` for channels or `Queue Tests.launch`
for message queues. Configure a compatible JDK for the JavaSE-1.8 environment.

In IntelliJ IDEA, open this folder, select an installed JDK, and run the
`Teacher Tests` or `Queue Tests` configuration.

The test entry points are `edu.polytech.channels.tests.Test` and
`edu.polytech.queues.tests.Test`. Both configurations enable assertions.

Channel implementations are in `edu.polytech.channels.empty`;
`edu.polytech.channels.local.Boot` provides the default test entry point.
Queue implementations are in `edu.polytech.queues.local`.

The design documents are `design.txt` and `design_queues.txt`.
The supplied specifications are `specs.pdf` and `specs_queue.pdf`.
The supplied APIs, utilities and tests retain their original notices.
