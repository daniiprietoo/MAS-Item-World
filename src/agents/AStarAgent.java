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
import java.util.Collections;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;




public class AStarAgent extends Agent {
    private AID simulatorAgent;
    private int commitment;
    private int timeToCommitment;
    private SimulationState myState;

    private MapNavigator navigator;
    private Random rand;
    private LinkedList<Position> currentPlan;
    private Position currentTarget; 

    private static class Node {
        Position pos;
        int score;

        Node(Position pos, int score) {
            this.pos = pos;
            this.score = score;
        }
    }

    protected void setup() {
        super.setup();
        System.out.println("RandomAgent " + getAID().getName() + " is ready.");
        navigator = new MapNavigator();
        rand = new Random();
        currentPlan = new LinkedList<>();
        Object[] args = getArguments();

        if (args != null && args.length > 0) {
            commitment = Integer.parseInt((String) args[0]);
        } else {
            System.out.println("No commitment level provided. Defaulting to 1.");
            commitment = 1;
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

                    System.out.println("Found Simulator Agent " + simulatorAgent);
                } catch (FIPAException fe){
                    fe.printStackTrace();
                }

                if (simulatorAgent != null) {
                    addBehaviour(new RequestJoinBehavior());
                }       
            }     
        });
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
            // Send CFP (Call for Proposal) to all sellers
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

    public int manhattanDistance(Position pos1, Position pos2) {
        return Math.abs(pos1.x - pos2.x) + Math.abs(pos1.y - pos2.y);
    }


    private int heuristic(Position current, Position goal) {
        return manhattanDistance(current, goal);
    }

    private LinkedList<Position> reconstructPath(HashMap<Position, Position> cameFrom, Position current){
        LinkedList<Position> total_path = new LinkedList<>();
        total_path.add(current);
        while (cameFrom.containsKey(current)){
            current = cameFrom.get(current);
            total_path.add(current);
        }
        Collections.reverse(total_path);
        return total_path;
    }    


    private LinkedList<Position> aStar(Position initialPosition, Position goalPosition, SimulationState currentState) {
        Map map = currentState.getMap();
        Position position = currentState.getPosition();
        LinkedList<Position> traps = map.getTrapsPositions();
        Set<Position> trapSet = new HashSet<>(traps);
        PriorityQueue<Node> openSet = new PriorityQueue<>((a, b) -> Integer.compare(a.score, b.score));
        openSet.add(new Node(position, 0));
        HashMap<Position, Position> cameFrom = new HashMap<>();
        HashMap<Position, Integer> gScore = new HashMap<>();
        gScore.put(initialPosition, 0);
        HashMap<Position, Integer> fScore = new HashMap<>();
        fScore.put(initialPosition, heuristic(position, goalPosition));
        while (openSet.size() > 0) {
            Position current = openSet.poll().pos;
            if (current.equals(goalPosition)) {
                return reconstructPath(cameFrom, current);
            }
            LinkedList<Position> candidates = navigator.getNextPossiblePositions(map, current);
            for (Position neighbor : candidates) {
                if (trapSet.contains(neighbor)){
                    continue;
                }
                int tentativeScore = gScore.get(current) + 1;
                if (gScore.get(neighbor) == null){
                    gScore.put(neighbor, 1000000000);
                }
                if (tentativeScore < gScore.get(neighbor)) {
                    cameFrom.put(neighbor, current);
                    gScore.put(neighbor, tentativeScore);
                    fScore.put(neighbor, tentativeScore + heuristic(current, goalPosition));
                    Node nodeNeighbor = new Node(neighbor, tentativeScore + heuristic(current, goalPosition));  
                    if (!openSet.contains(nodeNeighbor)) {
                        openSet.add(nodeNeighbor);
                    }
                }
            }
        }
        return null;
    }

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
                LinkedList<Position> path = aStar(currentPosition, itemPos, currentState);

                if (path != null && path.size() < bestDistance) {
                    bestDistance = path.size();
                    bestPath = path;
                    currentTarget = itemPos;
                }
            }

            if (bestPath != null && bestPath.size() > 1) {
                // Remove current position from path
                currentPlan = bestPath;
            }
        }

        if (!currentPlan.isEmpty()) {
            Position next = currentPlan.removeFirst();
            return next;
        }

        return currentPosition;
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
