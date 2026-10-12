package agents;

import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.FSMBehaviour;
import jade.core.behaviours.SimpleBehaviour;
import jade.core.behaviours.TickerBehaviour;
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
        if (ClientAgent.monitorMode) System.out.println("[SYSTEM] " + getLocalName().toUpperCase() + ": Started. Searching DF...");
        updateCalculatorsFromDF();

        addBehaviour(new TickerBehaviour(this, 5000) {
            @Override
            protected void onTick() {
                if (!isBusy) updateCalculatorsFromDF();
            }
        });

        FSMBehaviour fsm = new FSMBehaviour(this);
        fsm.registerFirstState(new WaitingState(), "WAITING");
        fsm.registerState(new BusyState(), "BUSY");
        
        fsm.registerTransition("WAITING", "BUSY", 1);
        fsm.registerDefaultTransition("BUSY", "WAITING", new String[]{"WAITING", "BUSY"});
        addBehaviour(fsm);
    }

    private void updateCalculatorsFromDF() {
        DFAgentDescription template = new DFAgentDescription();
        ServiceDescription sd = new ServiceDescription();
        sd.setType("sum-calculation");
        template.addServices(sd);
        try {
            DFAgentDescription[] result = DFService.search(this, template);
            List<AID> found = new ArrayList<>();
            for (DFAgentDescription dfd : result) found.add(dfd.getName());
            if (!found.isEmpty()) {
                int limit = Math.min(3, found.size());
                calculators.clear();
                for (int i = 0; i < limit; i++) calculators.add(found.get(i));
                if (ClientAgent.monitorMode) System.out.println("[INFO] " + getLocalName().toUpperCase() + ": DF updated. Calculators: " + calculators.size());
            }
        } catch (Exception e) { 
            if (ClientAgent.monitorMode) System.err.println("[ERROR] " + getLocalName().toUpperCase() + ": DF search failed.");
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
                if (ClientAgent.monitorMode) System.out.println("[FSM] " + getLocalName().toUpperCase() + ": External request received. Transitioning WAITING -> BUSY.");
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
            
            if (calculators.isEmpty()) {
                if (ClientAgent.monitorMode) System.err.println("[ERROR] No calculators available!");
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
            
            // 1. Отбиваем внешние запросы
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

            // 2. Собираем внутренние ответы 
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
                block(100); // Неблокирующая пауза
            }
        }

        @Override
        public boolean done() { return taskStarted && expectedReplies == 0; }

        @Override
        public int onEnd() {
            ACLMessage finalReply = currentExternalRequest.createReply();
            finalReply.setPerformative(ACLMessage.CONFIRM);
            finalReply.setLanguage("sum");
            finalReply.setConversationId(currentConvId); // ЯВНАЯ установка ID для клиента
            finalReply.setContent(String.valueOf(currentSum));
            send(finalReply);
            
            if (ClientAgent.monitorMode) {
                System.out.println("[SUCCESS] " + getLocalName().toUpperCase() + ": All workers finished. Final sum = " + currentSum);
                System.out.println("[FSM] " + getLocalName().toUpperCase() + ": Transitioning BUSY -> WAITING.");
            }
            
            isBusy = false;
            return 0;
        }
    }
}