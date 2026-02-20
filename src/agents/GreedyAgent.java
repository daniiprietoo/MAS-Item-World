package agents;

import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.OneShotBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAException;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.lang.acl.MessageTemplate;
import jade.lang.acl.UnreadableException;
import jade.lang.acl.ACLMessage;
import helper.Map;
import helper.MapNavigator;
import helper.Position;
import helper.SimulationState;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Set;
import java.util.HashMap;


public class GreedyAgent extends Agent {
    private AID simulatorAgent;
    private int commitment;
    private SimulationState myState = null;

    private MapNavigator navigator;

    private LinkedList<Position> currentPlan = new LinkedList<>();
    private Position currentTarget; 

    protected void setup() {
        super.setup();
        System.out.println("GreedyAgent " + getAID().getName() + " is ready.");
        navigator = new MapNavigator();
        Object[] args = getArguments();
        
        if (args == null || args.length == 0) {
            System.out.println("No commitment level provided. Defaulting to 1.");
            commitment = 1;
        } else {
            commitment = Integer.parseInt((String) args[0]);
        }

        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        addBehaviour(new OneShotBehaviour() {

            public void action() {
                DFAgentDescription template = new DFAgentDescription();
                ServiceDescription sd = new ServiceDescription();

                sd.setType("SimulatorService");

                template.addServices(sd);

                try {
                    DFAgentDescription[] simulators = DFService.search(myAgent, template);
                    simulatorAgent = simulators[0].getName();

                    System.out.println("Found Simulator Agent" + simulatorAgent);
                } catch (FIPAException fe){
                    fe.printStackTrace();
                }

                if (simulatorAgent != null) {
                    addBehaviour(new RequestJoinBehavior());
                }       
            }     
        });
    }
    // Greedy decision-making
    protected Position makeDecision() {
        Position currentPosition = myState.getPosition();
        Map currentMap = myState.getMap();

        LinkedList<Position> items = currentMap.getItemPositions();
        LinkedList<Position> traps = currentMap.getTrapsPositions();
        Set<Position> trapSet = new HashSet<>(traps);


        boolean shouldReplan = currentPlan.isEmpty()
                || currentTarget == null
                || !items.contains(currentTarget)
                || (!currentPlan.isEmpty() && trapSet.contains(currentPlan.getFirst()));

        if (shouldReplan) {
            currentPlan.clear();
            currentTarget = null;

            LinkedList<Position> bestPath = null;
            int bestDistance = Integer.MAX_VALUE;

            for (Position itemPos: items) {
                LinkedList<Position> path = bfs(currentMap, currentPosition, itemPos, trapSet);

                if (path != null && path.size() < bestDistance) {
                    bestDistance = path.size();
                    bestPath = path;
                    currentTarget = itemPos;
                }
            }

            if (bestPath != null && bestPath.size() > 1) {
                bestPath.removeFirst();
                currentPlan = new LinkedList<>(bestPath);
            }
        }

        if (!currentPlan.isEmpty()) {
            Position next = currentPlan.removeFirst();
            LinkedList<Position> validMoves = navigator.getNextPossiblePositions(currentMap, currentPosition);

            if (validMoves.contains(next)) {
                if (items.contains(next)) {
                    currentMap.clearPosition(next);
                    currentTarget = null;
                    currentPlan.clear();
                }
                return next;
            } else {
                currentPlan.clear();
                currentTarget = null;
            }
        }
        return currentPosition;
    }

    private LinkedList<Position> bfs(Map map, Position start, Position goal, Set<Position> trapSet) {
        if (start.equals(goal)) {
            LinkedList<Position> single = new LinkedList<>();
            single.add(start);
            return single;
        }

        // Use a map to store the parent of each position for path reconstruction
        java.util.Map<Position, Position> cameFrom = new HashMap<>();
        Queue<Position> queue = new ArrayDeque<>();
        Set<Position> visited = new HashSet<>();

        queue.add(start);
        visited.add(start);
        cameFrom.put(start, null);


        while (!queue.isEmpty()) {
            Position current = queue.poll();

            if (current.equals(goal)) {
                // Reconstruct path
                LinkedList<Position> path = new LinkedList<>();
                Position step = goal;
                while (step != null) {
                    path.addFirst(step);
                    step = cameFrom.get(step);
                }
                return path;
            }

            for (Position neighbor : navigator.getNextPossiblePositions(map, current)) {
                if (visited.contains(neighbor) || trapSet.contains(neighbor)) {
                    continue;
                }

                visited.add(neighbor);
                cameFrom.put(neighbor, current);
                queue.add(neighbor);
            }
        }

        return null;
    }


    private class RequestJoinBehavior extends OneShotBehaviour {

        /*
        Participants must send:
            performative = REQUEST
            conversationId = "join-simulation-request"
            content = <integer commitment>
        Simulator Agent: RegisterParticipantsBehaviour.action replies:
            AGREE + contentObject = initial SimulationState if there’s space
            or REFUSE if full
        
        */
        private MessageTemplate mt;

        public void action() {
            ACLMessage req = new ACLMessage(ACLMessage.REQUEST);
            req.addReceiver(simulatorAgent);
            req.setContent(Integer.toString(commitment));
            req.setConversationId("join-simulation-request");
            req.setReplyWith("req" + System.currentTimeMillis());
            myAgent.send(req);

            mt = MessageTemplate.and(MessageTemplate.MatchConversationId("join-simulation-request"),
                    MessageTemplate.MatchInReplyTo(req.getReplyWith()));

            ACLMessage reply = myAgent.blockingReceive(mt);

            if (reply != null) {
                if (reply.getPerformative() == ACLMessage.AGREE) {
                    System.out.println("Joined simulation successfully with commitment: " + commitment);

                    // get initial simulation state from contentObject
                    try {
                        SimulationState contentObject = (SimulationState) reply.getContentObject();
                        System.out.println("Received initial simulation state: \n" + contentObject.toString());
                        myState = contentObject;
                    } catch (Exception e) {
                        e.printStackTrace();
                    }

                    addBehaviour(new GameLoopBehavior());

                } else if (reply.getPerformative() == ACLMessage.REFUSE) {
                    System.out.println("Failed to join simulation: " + reply.getContent());
                }
            } else {
                System.out.println("No response received for join request.");
            }
        }
    }


    private class GameLoopBehavior extends CyclicBehaviour {

        public void action() {

            if (myState == null) {
                block();
                return;
            } 

            MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.REQUEST);

            mt = MessageTemplate.or(mt, MessageTemplate.MatchPerformative(ACLMessage.INFORM));
            ACLMessage msg = myAgent.receive(mt);
            if (msg != null) {
                try {
                    switch (msg.getPerformative()) {
                        case ACLMessage.REQUEST:
                            try {
                                Position nextPosition = makeDecision();
                                ACLMessage rep = msg.createReply();
                                rep.setPerformative(ACLMessage.PROPOSE);
                                rep.setContentObject(nextPosition);
                                myAgent.send(rep);
                                System.out.println(getLocalName() + ": Proposed move to " + nextPosition);

                            } catch (Exception e) {
                                System.err.println(getLocalName() + ": Error in makeDecision: " + e.getMessage());
                                e.printStackTrace();
                                // Send current position as fallback
                                Position fallback = myState.getPosition();
                                ACLMessage rep = msg.createReply();
                                rep.setPerformative(ACLMessage.PROPOSE);
                                rep.setContentObject(fallback);
                                myAgent.send(rep);
                            }
                            break;
                    
                        case ACLMessage.INFORM:
                            if ("update-state".equals(msg.getConversationId())) {
                                SimulationState contentObject = (SimulationState) msg.getContentObject();
                                myState = contentObject;
                            } else if ("simulation-complete".equals(msg.getConversationId())) {
                                System.out.println(getLocalName() + ": Game Over.");
                                myAgent.doDelete();
                            }
                            break;
                    }
                } catch (UnreadableException | IOException e) {
                    e.printStackTrace();
                }
            } else {
                block();
            }
        }
    }

}
