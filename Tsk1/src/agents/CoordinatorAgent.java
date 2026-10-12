package agents;

import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.FSMBehaviour;
import jade.core.behaviours.SimpleBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class CoordinatorAgent extends Agent {

    private List<AID> calculators = new ArrayList<>();
    
    private ACLMessage currentExternalRequest;
    private AID originalSender;
    private int expectedReplies;
    private long currentSum;
    private String currentConvId;
    
    private volatile boolean isBusy = false;

    @Override
    protected void setup() {
        if (ClientAgent.monitorMode) System.out.println("[SYSTEM] " + getLocalName().toUpperCase() + ": Started. Registered in DF.");

        // Регистрация Координатора в DF для Customer
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());
        ServiceDescription sd = new ServiceDescription();
        sd.setType("coordination-service");
        sd.setName("coordinator-service");
        dfd.addServices(sd);
        try { DFService.register(this, dfd); } catch (Exception e) { e.printStackTrace(); }

        FSMBehaviour fsm = new FSMBehaviour(this);
        fsm.registerFirstState(new WaitingState(), "WAITING");
        fsm.registerState(new BusyState(), "BUSY");
        
        fsm.registerTransition("WAITING", "BUSY", 1);
        fsm.registerDefaultTransition("BUSY", "WAITING", new String[]{"WAITING", "BUSY"});
        addBehaviour(fsm);
    }

    // Метод для поиска Вычислителей (вызывается только при необходимости)
    private void findCalculators() {
        DFAgentDescription template = new DFAgentDescription();
        ServiceDescription sd = new ServiceDescription();
        sd.setType("sum-calculation");
        template.addServices(sd);
        try {
            DFAgentDescription[] result = DFService.search(this, template);
            calculators.clear();
            for (DFAgentDescription dfd : result) {
                calculators.add(dfd.getName());
            }
        } catch (Exception e) { 
            System.err.println("[ERROR] " + getLocalName().toUpperCase() + ": DF search failed.");
        }
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
            finished = false;
        }

        @Override
        public void action() {
            ACLMessage msg = receive(mt);
            if (msg != null) {
                currentExternalRequest = msg;
                finished = true;
                if (ClientAgent.monitorMode) System.out.println("[FSM] " + getLocalName().toUpperCase() + ": Request received from " + msg.getSender().getLocalName().toUpperCase() + ". Transitioning WAITING -> BUSY.");
            } else {
                block();
            }
        }

        @Override
        public boolean done() { return finished; }
        @Override
        public int onEnd() { isBusy = true; return 1; }
    }

    private class BusyState extends SimpleBehaviour {
        private boolean taskStarted = false;

        @Override
        public void onStart() {
            super.onStart();
            taskStarted = false;
            
            // Ищем вычислителей только сейчас
            findCalculators();
            
            if (calculators.isEmpty()) {
                if (ClientAgent.monitorMode) System.err.println("[ERROR] " + getLocalName().toUpperCase() + ": No calculators available!");
                expectedReplies = 0;
                taskStarted = true;
                return;
            }

            String[] parts = currentExternalRequest.getContent().split(",");
            int A = Integer.parseInt(parts[0].trim());
            int B = Integer.parseInt(parts[1].trim());
            originalSender = currentExternalRequest.getSender();
            expectedReplies = calculators.size();
            currentSum = 0;
            currentConvId = currentExternalRequest.getConversationId();

            if (ClientAgent.monitorMode) System.out.println("[INFO] " + getLocalName().toUpperCase() + ": Splitting task [" + A + "," + B + "] between " + expectedReplies + " workers. ConvID: " + currentConvId);
            
            int totalNumbers = B - A + 1;
            int baseSize = totalNumbers / expectedReplies;
            int remainder = totalNumbers % expectedReplies;
            int currentStart = A;

            for (int i = 0; i < expectedReplies; i++) {
                int currentSize = baseSize + (i < remainder ? 1 : 0);
                int currentEnd = currentStart + currentSize - 1;
                ACLMessage req = new ACLMessage(ACLMessage.REQUEST);
                req.addReceiver(calculators.get(i));
                req.setLanguage("sum");
                req.setContent(currentStart + "," + currentEnd);
                req.setConversationId(currentConvId);
                send(req);
                currentStart = currentEnd + 1;
            }
            taskStarted = true;
        }

        @Override
        public void action() {
            if (!taskStarted) return;
            
            // 1. Отбиваем внешние запросы (REFUSE)
            MessageTemplate mtExt = MessageTemplate.and(
                    MessageTemplate.MatchPerformative(ACLMessage.REQUEST),
                    MessageTemplate.MatchLanguage("sum")
            );
            ACLMessage extMsg = receive(mtExt);
            if (extMsg != null) {
                ACLMessage refuse = extMsg.createReply();
                refuse.setPerformative(ACLMessage.REFUSE);
                refuse.setContent("Coordinator is busy processing a previous task.");
                send(refuse);
                if (ClientAgent.monitorMode) System.out.println("[REFUSE] " + getLocalName().toUpperCase() + " refused external request.");
            }

            // 2. Собираем ответы от Вычислителей
            MessageTemplate mtInt = MessageTemplate.and(
                    MessageTemplate.and(
                            MessageTemplate.MatchPerformative(ACLMessage.CONFIRM),
                            MessageTemplate.MatchLanguage("sum")
                    ),
                    MessageTemplate.MatchConversationId(currentConvId)
            );
            ACLMessage intMsg = receive(mtInt);
            if (intMsg != null) {
                long partialSum = Long.parseLong(intMsg.getContent().trim());
                currentSum += partialSum;
                expectedReplies--;
                if (ClientAgent.monitorMode) System.out.println("[INFO] " + getLocalName().toUpperCase() + ": Received " + partialSum + " from " + intMsg.getSender().getLocalName().toUpperCase() + ". Remaining: " + expectedReplies);
            }
            
            if (intMsg == null && extMsg == null) {
                block(100);
            }
        }

        @Override
        public boolean done() { return taskStarted && expectedReplies == 0; }

        @Override
        public int onEnd() {
            // Отправляем ответ Customer (originalSender)
            ACLMessage finalReply = currentExternalRequest.createReply();
            finalReply.setPerformative(ACLMessage.CONFIRM);
            finalReply.setLanguage("sum");
            finalReply.setConversationId(currentConvId);
            finalReply.setContent(String.valueOf(currentSum));
            send(finalReply);
            
            if (ClientAgent.monitorMode) {
                System.out.println("[SUCCESS] " + getLocalName().toUpperCase() + ": All workers finished. Final sum = " + currentSum + ". Replying to " + originalSender.getLocalName().toUpperCase());
                System.out.println("[FSM] " + getLocalName().toUpperCase() + ": Transitioning BUSY -> WAITING.");
            }
            
            isBusy = false;
            return 0;
        }
    }

    @Override
    protected void takeDown() {
        try { DFService.deregister(this); } catch (Exception e) {}
        if (ClientAgent.monitorMode) System.out.println("[SYSTEM] " + getLocalName().toUpperCase() + ": Terminated.");
    }
}