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
    
    // Флаг для отслеживания занятости агента вычислением
    private volatile boolean isCalculating = false;

    @Override
    protected void setup() {
        String name = getLocalName().toUpperCase();
        if (ClientAgent.monitorMode) System.out.println("[SYSTEM] " + name + ": Initialized and registered in DF.");

        // Регистрация в DF (Задача 1б)
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());
        ServiceDescription sd = new ServiceDescription();
        sd.setType("sum-calculation");
        sd.setName("calculator-service");
        
        dfd.addServices(sd);
        try { 
            DFService.register(this, dfd); 
        } catch (Exception e) { 
            e.printStackTrace(); 
        }

        // Инициализация FSM (Задача 1в)
        FSMBehaviour fsm = new FSMBehaviour(this);
        fsm.registerFirstState(new WaitingState(), "WAITING");
        
        // ЗАДАЧА 1г: Вычисление реализуется поведением, запущенным в отдельном потоке.
        // Мы оборачиваем BusyState в ThreadedBehaviourFactory.
        // Само поведение спроектировано как одноразовое (done() возвращает true после выполнения).
        fsm.registerState(tbf.wrap(new BusyState()), "BUSY");
        
        fsm.registerTransition("WAITING", "BUSY", 1);
        fsm.registerDefaultTransition("BUSY", "WAITING", new String[]{"WAITING", "BUSY"});
        
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
        public void onStart() {
            super.onStart();
            finished = false;
        }

        @Override
        public void action() {
            ACLMessage msg = receive(mt);
            if (msg != null) {
                if (isCalculating) {
                    // Агент уже считает, отвергаем новый запрос (Задача 1в + 1г)
                    ACLMessage refuse = msg.createReply();
                    refuse.setPerformative(ACLMessage.REFUSE);
                    refuse.setContent("I am currently processing another request in a separate thread.");
                    send(refuse);
                    if (ClientAgent.monitorMode) {
                        System.out.println("[REFUSE] " + getLocalName().toUpperCase() + " rejected new request (already calculating).");
                    }
                    } else {
                    currentRequest = msg;
                    isCalculating = true; 
                    finished = true;      
                    
                    if (ClientAgent.monitorMode) {
                        System.out.println("[FSM] " + getLocalName().toUpperCase() + ": Request accepted. Transitioning WAITING -> BUSY.");
                    }
                }
            } else {
                block(); 
            }
        }

        @Override
        public boolean done() { 
            return finished; 
        }
        
        @Override
        public int onEnd() { 
            return 1; 
        }
    }

    // ================= СОСТОЯНИЕ 2: ВЫЧИСЛЕНИЕ (ОДНОРАЗОВОЕ, В ОТДЕЛЬНОМ ПОТОКЕ) =================
    private class BusyState extends SimpleBehaviour {
        private boolean isDone = false;
        private int A, B;

        @Override
        public void onStart() {
            super.onStart();
            isDone = false;
            String[] parts = currentRequest.getContent().split(",");
            A = Integer.parseInt(parts[0].trim());
            B = Integer.parseInt(parts[1].trim());
            
            if (ClientAgent.monitorMode) {
                System.out.println("[INFO] " + getLocalName().toUpperCase() + ": Started calculation (Thread: " + Thread.currentThread().getName() + ") from " + A + " to " + B);
            }
        }

        @Override
        public void action() {
            // 1. Эмуляция длительных вычислений 
            long sum = 0;
            int start = Math.min(A, B);
            int end = Math.max(A, B);
            
            for (int i = 0; i < 5; i++) {
                if (ClientAgent.monitorMode) {
                    System.out.println("[INFO] " + getLocalName().toUpperCase() + ": Calculating... step " + (i+1) + "/5");
                }
                try { 
                    Thread.sleep(300); 
                } catch (InterruptedException e) { 
                    Thread.currentThread().interrupt();
                    break; 
                }
            }

            // 2. Непосредственное вычисление
            for (int i = start; i <= end; i++) {
                sum += i;
            }

            // 3. Отправка ответа с явным указанием ConversationId
            ACLMessage reply = currentRequest.createReply();
            reply.setPerformative(ACLMessage.CONFIRM);
            reply.setLanguage("sum");
            reply.setConversationId(currentRequest.getConversationId()); 
            reply.setContent(String.valueOf(sum));
            send(reply);
            
            if (ClientAgent.monitorMode) {
                System.out.println("[SUCCESS] " + getLocalName().toUpperCase() + ": Finished. Sum = " + sum);
            }

            // 4. Освобождаем агент для новых задач
            isCalculating = false;
            isDone = true; 
        }

        @Override
        public boolean done() { 
            return isDone; // Возвращает true после одного полного цикла выполнения (одноразовое поведение)
        }
        
        @Override
        public int onEnd() { 
            if (ClientAgent.monitorMode) {
                System.out.println("[FSM] " + getLocalName().toUpperCase() + ": One-shot behaviour finished. Transitioning BUSY -> WAITING.");
            }
            return 0; 
        }
    }

    @Override
    protected void takeDown() {
        try { 
            DFService.deregister(this); 
        } catch (Exception e) {}
        
        if (ClientAgent.monitorMode) {
            System.out.println("[SYSTEM] " + getLocalName().toUpperCase() + ": Terminated.");
        }
    }
}