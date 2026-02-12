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
 
public class GreedyAgent extends Agent {
    private AID simulatorAgent;
    private int commitment;
    private SimulationState myState;

    private MapNavigator navigator;

    private LinkedList<Position> currentPlan;
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
    protected Position makeDecision(SimulationState currentState) {
        Position currentPosition = currentState.getPosition();
        Map currentMap = currentState.getMap();

        LinkedList<Position> items = currentMap.getItemPositions();
        LinkedList<Position> traps = currentMap.getTrapsPositions();
        Set<Position> trapSet = new HashSet<>(traps);

        // 1. if there is no current plan or target
        // 2. current plan is invalid (no object in destination)
        // 3. current plan is invalid (trap in the way)
        boolean shouldReplan = currentPlan.isEmpty()
                || currentTarget == null
                || !items.contains(currentTarget)
                || (!currentPlan.isEmpty() && trapSet.contains(currentPlan.getFirst()));

        if (shouldReplan) {
            currentPlan.clear();
            currentTarget = null;

            // Find nearest REACHABLE
            LinkedList<Position> bestPath = new LinkedList<>();
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
                // Remove current position from path
                bestPath.removeFirst();
                currentPlan = bestPath;
            }
        }

        if (!currentPlan.isEmpty()) {
            Position next = currentPlan.removeFirst();

            // Check if still valid move
            LinkedList<Position> validMoves = navigator.getNextPossiblePositions(currentMap, currentPosition);

            if (validMoves.contains(next)) return next;

            currentPlan.clear();
            currentTarget = null;
        }

        return currentPosition;
    }

    private LinkedList<Position> bfs(Map map, Position start, Position goal, Set<Position> trapSet) {
        if (start.equals(goal)) {
            LinkedList<Position> single = new LinkedList<>();
            single.add(start);
            return single;
        }

        Queue<LinkedList<Position>> queue = new ArrayDeque<>();

        Set<Position> visitedPositions = new HashSet<>();

        LinkedList<Position> initial = new LinkedList<>();
        initial.add(start);
        queue.add(initial);
        visitedPositions.add(start);

        while (!queue.isEmpty()) {
            LinkedList<Position> path = queue.poll();
            Position current = path.getLast();

            for (Position neigh: navigator.getNextPossiblePositions(map, current)) {
                if (visitedPositions.contains(neigh)) continue;

                boolean isTrap = trapSet.contains(neigh);
                if (isTrap) continue;

                LinkedList<Position> newPath = new LinkedList<>(path);
                newPath.add(neigh);

                if (neigh.equals(goal)) return newPath;
                
                visitedPositions.add(neigh);
                queue.add(newPath);
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
            MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.REQUEST);

            mt = MessageTemplate.or(mt, MessageTemplate.MatchPerformative(ACLMessage.INFORM));
            ACLMessage msg = myAgent.receive(mt);
            if (msg != null) {
                try {
                    switch (msg.getPerformative()) {
                        case ACLMessage.REQUEST:
                            Position nexPosition = makeDecision(myState);
                            ACLMessage rep = msg.createReply();
                            rep.setPerformative(ACLMessage.PROPOSE);
                            rep.setContentObject(nexPosition);
                            myAgent.send(rep);
                            break;
                    
                        case ACLMessage.INFORM:
                            if ("update-state".equals(msg.getConversationId())) {
                                myState = (SimulationState) msg.getContentObject();
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
