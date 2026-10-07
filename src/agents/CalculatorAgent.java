import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;

public class CalculatorAgent extends Agent {

    @Override
    protected void setup() {
        String name = getLocalName().toUpperCase();
        System.out.println("[SYSTEM] " + name + ": Initialized and registered in DF.");

        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());
        ServiceDescription sd = new ServiceDescription();
        sd.setType("sum-calculation");
        sd.setName("calculator-service");
        
        dfd.addServices(sd);
        
        try {
            DFService.register(this, dfd);
        } catch (Exception e) {
            System.err.println("\n[ERROR] " + name + ": DF registration failed: " + e.getMessage());
        }

        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                MessageTemplate mt = MessageTemplate.and(
                        MessageTemplate.MatchPerformative(ACLMessage.REQUEST),
                        MessageTemplate.MatchLanguage("sum")
                );

                ACLMessage msg = receive(mt);
                if (msg == null) {
                    block();
                } else {
                    try {
                        String[] parts = msg.getContent().split(",");
                        int A = Integer.parseInt(parts[0].trim());
                        int B = Integer.parseInt(parts[1].trim());

                        long sum = 0;
                        int start = Math.min(A, B);
                        int end = Math.max(A, B);
                        for (int i = start; i <= end; i++) sum += i;

                        System.out.println("[INFO] " + name + ": Calculated sum [" + start + "," + end + "] = " + sum);

                        ACLMessage reply = msg.createReply();
                        reply.setPerformative(ACLMessage.CONFIRM);
                        reply.setLanguage("sum");
                        reply.setContent(String.valueOf(sum));
                        send(reply);

                    } catch (Exception e) {
                        System.err.println("\n[ERROR] " + name + ": Error processing message: " + e.getMessage());
                    }
                }
            }
        });
    }

    @Override
    protected void takeDown() {
        try {
            DFService.deregister(this);
            System.out.println("[SYSTEM] " + getLocalName().toUpperCase() + ": Deregistered and terminated.");
        } catch (Exception e) {
            System.err.println("\n[ERROR] " + getLocalName().toUpperCase() + ": DF deregistration failed.");
        }
    }
}