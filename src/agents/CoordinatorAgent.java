import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.TickerBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class CoordinatorAgent extends Agent {

    private List<AID> calculators = new ArrayList<>();
    private Map<String, TaskContext> activeTasks = new HashMap<>();
    
    // Счетчик для отслеживания ИЗМЕНЕНИЯ количества вычислителей
    private int previousCalculatorsCount = -1; 

    private class TaskContext {
        AID originalSender;
        int expectedReplies;
        long currentSum;

        TaskContext(AID sender, int replies) {
            this.originalSender = sender;
            this.expectedReplies = replies;
            this.currentSum = 0;
        }
    }

    @Override
    protected void setup() {
        System.out.println("[SYSTEM] " + getLocalName().toUpperCase() + ": Started. Searching DF...");

        updateCalculatorsFromDF();

        addBehaviour(new TickerBehaviour(this, 5000) {
            @Override
            protected void onTick() {
                updateCalculatorsFromDF();
            }
        });

        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                MessageTemplate mt = MessageTemplate.and(
                        MessageTemplate.MatchPerformative(ACLMessage.REQUEST),
                        MessageTemplate.MatchLanguage("sum")
                );
                ACLMessage msg = receive(mt);
                if (msg == null) block();
                else handleExternalRequest(msg);
            }
        });

        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.CONFIRM);
                ACLMessage msg = receive(mt);
                if (msg == null) block();
                else handleInternalReply(msg);
            }
        });
    }

    private void updateCalculatorsFromDF() {
        DFAgentDescription template = new DFAgentDescription();
        ServiceDescription sd = new ServiceDescription();
        sd.setType("sum-calculation");
        template.addServices(sd);

        try {
            DFAgentDescription[] result = DFService.search(this, template);
            List<AID> foundCalculators = new ArrayList<>();
            for (DFAgentDescription dfd : result) {
                foundCalculators.add(dfd.getName());
            }

            if (!foundCalculators.isEmpty()) {
                int limit = Math.min(3, foundCalculators.size());
                calculators.clear();
                for (int i = 0; i < limit; i++) {
                    calculators.add(foundCalculators.get(i));
                }
                
                // ВАЖНО: Выводим сообщение ТОЛЬКО при изменении количества
                if (calculators.size() != previousCalculatorsCount) {
                    System.out.println("\n[SYSTEM] " + getLocalName().toUpperCase() + ": Calculators count CHANGED. Active: " + calculators.size());
                    previousCalculatorsCount = calculators.size();
                }
            } else {
                if (previousCalculatorsCount != 0) {
                    System.out.println("\n[SYSTEM] " + getLocalName().toUpperCase() + ": No calculators found in DF.");
                    previousCalculatorsCount = 0;
                }
            }
        } catch (Exception e) {
            System.err.println("\n[ERROR] " + getLocalName().toUpperCase() + ": DF search failed. " + e.getMessage());
        }
    }

    private void handleExternalRequest(ACLMessage msg) {
        if (calculators.isEmpty()) {
            System.err.println("\n[ERROR] " + getLocalName().toUpperCase() + ": Cannot process request. No calculators available.");
            return; 
        }

        try {
            String[] parts = msg.getContent().split(",");
            int A = Integer.parseInt(parts[0].trim());
            int B = Integer.parseInt(parts[1].trim());

            String conversationId = UUID.randomUUID().toString();
            int workersCount = calculators.size(); 
            TaskContext context = new TaskContext(msg.getSender(), workersCount);
            activeTasks.put(conversationId, context);

            System.out.println("\n[SYSTEM] " + getLocalName().toUpperCase() + ": Received task [" + A + ", " + B + "]. Splitting between " + workersCount + " workers.");

            int totalNumbers = B - A + 1;
            int baseSize = totalNumbers / workersCount;
            int remainder = totalNumbers % workersCount;
            int currentStart = A;

            for (int i = 0; i < workersCount; i++) {
                int currentSize = baseSize + (i < remainder ? 1 : 0);
                int currentEnd = currentStart + currentSize - 1;

                ACLMessage request = new ACLMessage(ACLMessage.REQUEST);
                request.addReceiver(calculators.get(i));
                request.setLanguage("sum");
                request.setContent(currentStart + "," + currentEnd);
                request.setConversationId(conversationId); 
                
                send(request);
                // Имена вычислителей в CAPS LOCK
                System.out.println("   -> Sent to " + calculators.get(i).getLocalName().toUpperCase() + ": [" + currentStart + ", " + currentEnd + "]");
                currentStart = currentEnd + 1;
            }
        } catch (Exception e) {
            System.err.println("\n[ERROR] " + getLocalName().toUpperCase() + ": Error parsing external request. " + e.getMessage());
        }
    }

    private void handleInternalReply(ACLMessage msg) {
        String convId = msg.getConversationId();
        if (convId != null && activeTasks.containsKey(convId)) {
            TaskContext context = activeTasks.get(convId);
            try {
                long partialSum = Long.parseLong(msg.getContent().trim());
                context.currentSum += partialSum;
                context.expectedReplies--;

                System.out.println("[INFO] " + getLocalName().toUpperCase() + ": Received " + partialSum + " from " + 
                        msg.getSender().getLocalName().toUpperCase() + ". Remaining: " + context.expectedReplies);

                if (context.expectedReplies == 0) {
                    System.out.println("[SUCCESS] " + getLocalName().toUpperCase() + ": All answers received! Final Result: " + context.currentSum);
                    ACLMessage finalReply = new ACLMessage(ACLMessage.CONFIRM);
                    finalReply.addReceiver(context.originalSender);
                    finalReply.setLanguage("sum");
                    finalReply.setContent(String.valueOf(context.currentSum));
                    finalReply.setConversationId(convId);
                    send(finalReply);
                    activeTasks.remove(convId);
                }
            } catch (Exception e) {
                System.err.println("\n[ERROR] " + getLocalName().toUpperCase() + ": Error parsing answer. " + e.getMessage());
            }
        }
    }
}