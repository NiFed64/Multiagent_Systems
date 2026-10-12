package agents;

import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.OneShotBehaviour;
import jade.core.behaviours.ThreadedBehaviourFactory;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;

public class CalculatorAgent extends Agent {

    private ThreadedBehaviourFactory tbf = new ThreadedBehaviourFactory();

    @Override
    protected void setup() {
        String name = getLocalName().toUpperCase();
        if (ClientAgent.monitorMode) System.out.println("[SYSTEM] " + name + ": Initialized and registered in DF.");

        // Регистрация в DF
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());
        ServiceDescription sd = new ServiceDescription();
        sd.setType("sum-calculation");
        sd.setName("calculator-service");
        
        dfd.addServices(sd);
        try { DFService.register(this, dfd); } catch (Exception e) { e.printStackTrace(); }

        // ЗАДАЧА 1г: Циклическое поведение, которое порождает потоки для каждого запроса
        addBehaviour(new CyclicBehaviour(this) {
            private MessageTemplate mt = MessageTemplate.and(
                    MessageTemplate.MatchPerformative(ACLMessage.REQUEST),
                    MessageTemplate.MatchLanguage("sum")
            );

            @Override
            public void action() {
                ACLMessage msg = receive(mt);
                if (msg != null) {
                    if (ClientAgent.monitorMode) {
                        System.out.println("[INFO] " + getLocalName().toUpperCase() + ": Received request. Spawning threaded OneShotBehaviour...");
                    }
                    // Порождаем одноразовое поведение в отдельном потоке
                    addBehaviour(tbf.wrap(new CalculationTask(msg)));
                } else {
                    block();
                }
            }
        });
    }

    // ОДНОРАЗОВОЕ ПОВЕДЕНИЕ (ЗАДАЧА 1г)
    private class CalculationTask extends OneShotBehaviour {
        private ACLMessage msg;

        public CalculationTask(ACLMessage msg) {
            super(CalculatorAgent.this);
            this.msg = msg;
        }

        @Override
        public void action() {
            String[] parts = msg.getContent().split(",");
            int A = Integer.parseInt(parts[0].trim());
            int B = Integer.parseInt(parts[1].trim());

            if (ClientAgent.monitorMode) {
                System.out.println("[INFO] " + getLocalName().toUpperCase() + " (Thread: " + Thread.currentThread().getName() + "): Started calculation from " + A + " to " + B);
            }

            // Эмуляция вычисления
            long sum = 0;
            int start = Math.min(A, B);
            int end = Math.max(A, B);
            
            for (int i = 0; i < 5; i++) {
                try { Thread.sleep(300); } catch (InterruptedException e) { break; }
            }

            for (int i = start; i <= end; i++) sum += i;

            // Отправка ответа
            ACLMessage reply = msg.createReply();
            reply.setPerformative(ACLMessage.CONFIRM);
            reply.setLanguage("sum");
            reply.setConversationId(msg.getConversationId());
            reply.setContent(String.valueOf(sum));
            send(reply);
            
            if (ClientAgent.monitorMode) System.out.println("[SUCCESS] " + getLocalName().toUpperCase() + ": Finished. Sum = " + sum);
        }
    }

    @Override
    protected void takeDown() {
        try { DFService.deregister(this); } catch (Exception e) {}
        if (ClientAgent.monitorMode) System.out.println("[SYSTEM] " + getLocalName().toUpperCase() + ": Terminated.");
    }
}