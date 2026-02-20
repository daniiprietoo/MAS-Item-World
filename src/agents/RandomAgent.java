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
import java.util.LinkedList;
import java.util.Random;

import config.Config;
 
public class RandomAgent extends Agent {
    private AID simulatorAgent;
    private int commitment;
    private SimulationState myState;

    private MapNavigator navigator;
    private Random rand;

    protected void setup() {
        super.setup();
        System.out.println("RandomAgent " + getAID().getName() + " is ready.");
        navigator = new MapNavigator();
        if (Config.USE_SEED) {
            rand = new Random(Config.SEED);
        } else {
            rand = new Random();
        }
        Object[] args = getArguments();

        if (args != null && args.length > 0) {
            commitment = Integer.parseInt((String) args[0]);
        } else {
            System.out.println("No commitment level provided. Defaulting to 1.");
            commitment = 1;
        }

        try {
            Thread.sleep(1500);
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

    protected Position makeDecision(SimulationState currentState) {
        Map map = currentState.getMap();
        Position position = currentState.getPosition();

        LinkedList<Position> candidates = navigator.getNextPossiblePositions(map, position);

        if (candidates.isEmpty()) return position;

        return candidates.get(rand.nextInt(candidates.size()));
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
