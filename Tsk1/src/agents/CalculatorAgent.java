package agents;

import jade.core.Agent;
import jade.core.behaviours.FSMBehaviour;
import jade.core.behaviours.SimpleBehaviour;
import jade.core.behaviours.ThreadedBehaviourFactory;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;

public class CalculatorAgent extends Agent {

    private ACLMessage currentRequest;
    private ThreadedBehaviourFactory tbf = new ThreadedBehaviourFactory();

    @Override
    protected void setup() {
        String name = getLocalName().toUpperCase();
        if (ClientAgent.monitorMode) System.out.println("[SYSTEM] " + name + ": Initialized and registered in DF.");

        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());
        ServiceDescription sd = new ServiceDescription();
        sd.setType("sum-calculation");
        sd.setName("calculator-service");
        
        dfd.addServices(sd);
        try { DFService.register(this, dfd); } catch (Exception e) { e.printStackTrace(); }

        FSMBehaviour fsm = new FSMBehaviour(this);
        fsm.registerFirstState(new WaitingState(), "WAITING");
        
        // Оборачиваем BusyState в отдельный поток, чтобы он не блокировал агента
        fsm.registerState(tbf.wrap(new BusyState()), "BUSY");
        
        fsm.registerTransition("WAITING", "BUSY", 1);
        fsm.registerDefaultTransition("BUSY", "WAITING", new String[]{"WAITING", "BUSY"});
        addBehaviour(fsm);
    }

    private class WaitingState extends SimpleBehaviour {
        private boolean finished = false;
        private MessageTemplate mt = MessageTemplate.and(
                MessageTemplate.MatchPerformative(ACLMessage.REQUEST),
                MessageTemplate.MatchLanguage("sum")
        );

        @Override
        public void onStart() {
            super.onStart();
            finished = false; // Сброс при каждом входе
        }

        @Override
        public void action() {
            ACLMessage msg = receive(mt);
            if (msg != null) {
                currentRequest = msg;
                finished = true;
                if (ClientAgent.monitorMode) System.out.println("[FSM] " + getLocalName().toUpperCase() + ": Request received. Transitioning WAITING -> BUSY.");
            } else {
                block();
            }
        }

        @Override
        public boolean done() { return finished; }
        @Override
        public int onEnd() { return 1; }
    }

    private class BusyState extends SimpleBehaviour {
        private int A, B;

        @Override
        public void onStart() {
            super.onStart();
            String[] parts = currentRequest.getContent().split(",");
            A = Integer.parseInt(parts[0].trim());
            B = Integer.parseInt(parts[1].trim());
            if (ClientAgent.monitorMode) System.out.println("[INFO] " + getLocalName().toUpperCase() + ": Started calculation from " + A + " to " + B);
        }

        @Override
        public void action() {
            // 1. Проверяем новые запросы, чтобы ответить REFUSE
            MessageTemplate mtReq = MessageTemplate.and(
                    MessageTemplate.MatchPerformative(ACLMessage.REQUEST),
                    MessageTemplate.MatchLanguage("sum")
            );
            ACLMessage newReq = receive(mtReq);
            if (newReq != null) {
                ACLMessage refuse = newReq.createReply();
                refuse.setPerformative(ACLMessage.REFUSE);
                refuse.setContent("I am busy calculating the previous request.");
                send(refuse);
                if (ClientAgent.monitorMode) System.out.println("[REFUSE] " + getLocalName().toUpperCase() + " refused request from " + newReq.getSender().getLocalName().toUpperCase());
            }

            // 2. Эмуляция вычисления (в отдельном потоке, поэтому sleep безопасен)
            long sum = 0;
            int start = Math.min(A, B);
            int end = Math.max(A, B);
            
            for (int i = 0; i < 5; i++) {
                if (ClientAgent.monitorMode) System.out.println("[INFO] " + getLocalName().toUpperCase() + ": Calculating... step " + (i+1) + "/5");
                try { Thread.sleep(300); } catch (InterruptedException e) { break; }
            }

            for (int i = start; i <= end; i++) sum += i;

            // 3. Отправка ответа с ЯВНЫМ указанием ConversationId
            ACLMessage reply = currentRequest.createReply();
            reply.setPerformative(ACLMessage.CONFIRM);
            reply.setLanguage("sum");
            reply.setConversationId(currentRequest.getConversationId()); // КРИТИЧЕСКИ ВАЖНО
            reply.setContent(String.valueOf(sum));
            send(reply);
            
            if (ClientAgent.monitorMode) System.out.println("[SUCCESS] " + getLocalName().toUpperCase() + ": Finished. Sum = " + sum);
        }

        @Override
        public boolean done() { return true; } // Одноразовое поведение в потоке
        
        @Override
        public int onEnd() { 
            if (ClientAgent.monitorMode) System.out.println("[FSM] " + getLocalName().toUpperCase() + ": Calculation done. Transitioning BUSY -> WAITING.");
            return 0; 
        }
    }

    @Override
    protected void takeDown() {
        try { DFService.deregister(this); } catch (Exception e) {}
        if (ClientAgent.monitorMode) System.out.println("[SYSTEM] " + getLocalName().toUpperCase() + ": Terminated.");
    }
}