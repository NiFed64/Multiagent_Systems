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
    
    // Переменные для передачи данных между состояниями FSM
    private ACLMessage currentExternalRequest;
    private AID originalSender;
    private int expectedReplies;
    private long currentSum;

    @Override
    protected void setup() {
        System.out.println("[SYSTEM] " + getLocalName().toUpperCase() + ": Started. Searching DF...");
        updateCalculatorsFromDF();

        // Периодическое обновление DF (остается как фоновое поведение)
        addBehaviour(new TickerBehaviour(this, 5000) {
            @Override
            protected void onTick() { updateCalculatorsFromDF(); }
        });

        // === НОВАЯ ЛОГИКА: Конечный Автомат (FSM) ===
        FSMBehaviour fsm = new FSMBehaviour(this);
        fsm.registerFirstState(new WaitingState(), "WAITING");
        fsm.registerState(new BusyState(), "BUSY");
        
        fsm.registerTransition("WAITING", "BUSY", 1);
        fsm.registerTransition("BUSY", "WAITING", 0);
        
        addBehaviour(fsm);
    }

    private void updateCalculatorsFromDF() {
        // ... (Логика поиска в DF остается без изменений) ...
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
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    // ================= СОСТОЯНИЕ 1: ОЖИДАНИЕ =================
    private class WaitingState extends SimpleBehaviour {
        private boolean finished = false;
        private MessageTemplate mt = MessageTemplate.and(
                MessageTemplate.MatchPerformative(ACLMessage.REQUEST),
                MessageTemplate.MatchLanguage("sum")
        );

        @Override
        public void action() {
            ACLMessage msg = receive(mt);
            if (msg != null) {
                currentExternalRequest = msg;
                finished = true;
                System.out.println("[FSM] " + getLocalName().toUpperCase() + ": External request received. Transitioning WAITING -> BUSY.");
            } else {
                block();
            }
        }

        @Override
        public boolean done() { return finished; }
        @Override
        public int onEnd() { return 1; }
    }

    // ================= СОСТОЯНИЕ 2: РАСПРЕДЕЛЕНИЕ И СБОР =================
    private class BusyState extends SimpleBehaviour {
        
        @Override
        public void onStart() {
            // Парсим запрос и рассылаем задачи (как в предыдущих версиях)
            String[] parts = currentExternalRequest.getContent().split(",");
            int A = Integer.parseInt(parts[0].trim());
            int B = Integer.parseInt(parts[1].trim());
            originalSender = currentExternalRequest.getSender();
            expectedReplies = calculators.size();
            currentSum = 0;
            String convId = UUID.randomUUID().toString();

            System.out.println("[INFO] " + getLocalName().toUpperCase() + ": Splitting task [" + A + "," + B + "] between " + expectedReplies + " workers.");
            
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
                req.setConversationId(convId);
                send(req);
                currentStart = currentEnd + 1;
            }
        }

        @Override
        public void action() {
            // 1. Отбиваем новые ВНЕШНИЕ запросы (REFUSE)
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
                System.out.println("[REFUSE] " + getLocalName().toUpperCase() + " refused external request from " + extMsg.getSender().getLocalName().toUpperCase());
            }

            // 2. Собираем ВНУТРЕННИЕ ответы от вычислителей
            MessageTemplate mtInt = MessageTemplate.MatchPerformative(ACLMessage.CONFIRM);
            ACLMessage intMsg = receive(mtInt);
            if (intMsg != null) {
                long partialSum = Long.parseLong(intMsg.getContent().trim());
                currentSum += partialSum;
                expectedReplies--;
                System.out.println("[INFO] " + getLocalName().toUpperCase() + ": Received " + partialSum + " from " + intMsg.getSender().getLocalName().toUpperCase() + ". Remaining: " + expectedReplies);
            }
            
            if (expectedReplies > 0 && intMsg == null && extMsg == null) {
                block(); // Ждем новых сообщений
            }
        }

        @Override
        public boolean done() { return expectedReplies == 0; }

        @Override
        public int onEnd() {
            // Отправляем итоговый ответ
            ACLMessage finalReply = new ACLMessage(ACLMessage.CONFIRM);
            finalReply.addReceiver(originalSender);
            finalReply.setLanguage("sum");
            finalReply.setContent(String.valueOf(currentSum));
            send(finalReply);
            
            System.out.println("[SUCCESS] " + getLocalName().toUpperCase() + ": All workers finished. Final sum = " + currentSum);
            System.out.println("[FSM] " + getLocalName().toUpperCase() + ": Transitioning BUSY -> WAITING.");
            return 0;
        }
    }
}