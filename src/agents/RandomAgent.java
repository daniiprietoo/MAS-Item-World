package agents;

import jade.core.Agent;

public class RandomAgent extends Agent {

    protected void setup() {
        super.setup();
        System.out.println("RandomAgent " + getAID().getName() + " is ready.");
    }
    
}
