# Important

To compile the project, compile into the classes folder:

```bash
javac -d classes -cp "lib/jade.jar:src" src/helper/*.java src/agents/*.java # on Mac/Linux
javac -d classes -cp "lib\jade.jar;src" src/helper/*.java src/agents/*.java # on Windows
```

To run the proejct:

```bash
java -cp "lib/jade.jar:classes" jade.Boot -agents "Simulator:helper.SimulatorAgent;Random_1:agents.RandomAgent({commitment});Random_2:agents.RandomAgent({commitment})" # on Mac/Linux

java -cp "lib\jade.jar;classes" jade.Boot -agents "Simulator:helper.SimulatorAgent;Random_1:agents.RandomAgent({commitment});Random_2:agents.RandomAgent({commitment})" # on Windows
```

Where `{commitment}` is the commitment level of the agent, it is a postive integer that controls how often does the agent receive an update of the _SimulationState_ from the _SimulatorAgent_. The less the commitment level, the more often the agent receives an update. For instance commitment level 1 means that the agent receives an update every turn, commitment level 2 means that the agent receives an update every 2 turns, and so on.
