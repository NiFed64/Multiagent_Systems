package agents;

import jade.core.Agent;
import jade.core.behaviours.FSMBehaviour;
import jade.core.behaviours.SimpleBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;

public class CalculatorAgent extends Agent {

    // Общие переменные для передачи данных между состояниями FSM
    private ACLMessage currentRequest;
    
    @Override
    protected void setup() {
        String name = getLocalName().toUpperCase();
        System.out.println("[SYSTEM] " + name + ": Initialized and registered in DF.");

        // Регистрация в DF (из Задачи 1б)
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());
        ServiceDescription sd = new ServiceDescription();
        sd.setType("sum-calculation");
        sd.setName("calculator-service");
        
        dfd.addServices(sd);
        try { DFService.register(this, dfd); } catch (Exception e) { e.printStackTrace(); }

        // === НОВАЯ ЛОГИКА: Конечный Автомат (FSM) ===
        FSMBehaviour fsm = new FSMBehaviour(this);
        
        // Регистрируем состояния
        fsm.registerFirstState(new WaitingState(), "WAITING");
        fsm.registerState(new BusyState(), "BUSY");
        
        // Регистрируем переходы
        fsm.registerTransition("WAITING", "BUSY", 1); // Если WaitingState вернет 1 -> идем в BUSY
        fsm.registerTransition("BUSY", "WAITING", 0); // Если BusyState вернет 0 -> идем в WAITING
        
        addBehaviour(fsm);
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
                currentRequest = msg; // Сохраняем запрос для следующего состояния
                finished = true;
                System.out.println("[FSM] " + getLocalName().toUpperCase() + ": Request received. Transitioning WAITING -> BUSY.");
            } else {
                block(); // Ждем сообщения
            }
        }

        @Override
        public boolean done() { return finished; }
        
        @Override
        public int onEnd() { return 1; } // Код перехода в BUSY
    }

    // ================= СОСТОЯНИЕ 2: ВЫЧИСЛЕНИЕ =================
    private class BusyState extends SimpleBehaviour {
        private int step = 0;
        private int totalSteps = 5; // Эмулируем 5 "шагов" вычисления
        private int A, B;

        @Override
        public void onStart() {
            String[] parts = currentRequest.getContent().split(",");
            A = Integer.parseInt(parts[0].trim());
            B = Integer.parseInt(parts[1].trim());
            System.out.println("[INFO] " + getLocalName().toUpperCase() + ": Started calculation from " + A + " to " + B);
        }

        @Override
        public void action() {
            // 1. Проверяем, не пришли ли НОВЫЕ запросы, пока мы заняты
            MessageTemplate mtReq = MessageTemplate.and(
                    MessageTemplate.MatchPerformative(ACLMessage.REQUEST),
                    MessageTemplate.MatchLanguage("sum")
            );
            ACLMessage msg = receive(mtReq);
            if (msg != null) {
                // Отказываем, так как мы заняты
                ACLMessage refuse = msg.createReply();
                refuse.setPerformative(ACLMessage.REFUSE);
                refuse.setContent("I am busy calculating the previous request.");
                send(refuse);
                System.out.println("[REFUSE] " + getLocalName().toUpperCase() + " refused request from " + msg.getSender().getLocalName().toUpperCase());
            }

            // 2. Эмулируем работу
            if (step < totalSteps) {
                step++;
                System.out.println("[INFO] " + getLocalName().toUpperCase() + ": Calculating... step " + step + "/" + totalSteps);
                block(500); // "Спим" 500мс, эмулируя нагрузку и давая другим поведениям шанс сработать
            } else {
                // 3. Вычисление завершено
                long sum = 0;
                int start = Math.min(A, B);
                int end = Math.max(A, B);
                for (int i = start; i <= end; i++) sum += i;

                ACLMessage reply = currentRequest.createReply();
                reply.setPerformative(ACLMessage.CONFIRM);
                reply.setLanguage("sum");
                reply.setContent(String.valueOf(sum));
                send(reply);
                System.out.println("[SUCCESS] " + getLocalName().toUpperCase() + ": Finished. Sum = " + sum);
            }
        }

        @Override
        public boolean done() { return step >= totalSteps; }
        
        @Override
        public int onEnd() { 
            System.out.println("[FSM] " + getLocalName().toUpperCase() + ": Calculation done. Transitioning BUSY -> WAITING.");
            return 0; // Код перехода обратно в WAITING
        }
    }

    @Override
    protected void takeDown() {
        try { DFService.deregister(this); } catch (Exception e) {}
        System.out.println("[SYSTEM] " + getLocalName().toUpperCase() + ": Terminated.");
    }
}