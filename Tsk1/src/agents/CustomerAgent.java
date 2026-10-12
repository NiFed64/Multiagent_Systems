package agents;

import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;

import java.util.ArrayList;
import java.util.List;

public class CustomerAgent extends Agent {

    private int coordinatorIndex = 0; // Для Round-Robin обычных задач

    @Override
    protected void setup() {
        if (ClientAgent.monitorMode) System.out.println("[SYSTEM] CUSTOMER: Started. Acting as Smart Dispatcher.");

        // 1. Обычные задачи (Load Balancer)
        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                MessageTemplate mt = MessageTemplate.and(
                        MessageTemplate.MatchPerformative(ACLMessage.REQUEST),
                        MessageTemplate.MatchLanguage("sum")
                );
                ACLMessage msg = receive(mt);
                if (msg != null) dispatchTask(msg, true);
                else block();
            }
        });

        // 2. Тест на REFUSE (Задача 1в) - отправка на ОДНОГО координатора
        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                MessageTemplate mt = MessageTemplate.and(
                        MessageTemplate.MatchPerformative(ACLMessage.REQUEST),
                        MessageTemplate.MatchLanguage("test-refuse")
                );
                ACLMessage msg = receive(mt);
                if (msg != null) runRefuseTest(msg);
                else block();
            }
        });

        // 3. Тест на ПАРАЛЛЕЛИЗМ (Задача 1г) - отправка на ВСЕХ координаторов
        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                MessageTemplate mt = MessageTemplate.and(
                        MessageTemplate.MatchPerformative(ACLMessage.REQUEST),
                        MessageTemplate.MatchLanguage("test-parallel")
                );
                ACLMessage msg = receive(mt);
                if (msg != null) runParallelTest(msg);
                else block();
            }
        });

        // 4. Сбор ответов от Координаторов и пересылка Клиенту
        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                MessageTemplate mt = MessageTemplate.and(
                        MessageTemplate.MatchPerformative(ACLMessage.CONFIRM),
                        MessageTemplate.MatchLanguage("sum")
                );
                ACLMessage reply = receive(mt);
                if (reply != null) {
                    ACLMessage toClient = new ACLMessage(ACLMessage.CONFIRM);
                    toClient.addReceiver(new AID("client", AID.ISLOCALNAME));
                    toClient.setLanguage("sum");
                    toClient.setConversationId(reply.getConversationId()); // Пробрасываем ID Клиента
                    toClient.setContent(reply.getContent());
                    send(toClient);

                    if (ClientAgent.monitorMode) {
                        System.out.println("[INFO] CUSTOMER: Forwarded result from " + reply.getSender().getLocalName().toUpperCase() + " to Client.");
                    }
                } else {
                    block();
                }
            }
        });
    }

    // --- ЛОГИКА ОБЫЧНЫХ ЗАДАЧ ---
    private void dispatchTask(ACLMessage clientRequest, boolean incrementIndex) {
        List<AID> coordinators = findCoordinators();
        if (coordinators.isEmpty()) return;

        AID target = coordinators.get(coordinatorIndex % coordinators.size());
        if (incrementIndex) coordinatorIndex++;

        if (ClientAgent.monitorMode) System.out.println("[INFO] CUSTOMER: Assigning task to " + target.getLocalName().toUpperCase());

        ACLMessage request = new ACLMessage(ACLMessage.REQUEST);
        request.addReceiver(target);
        request.setLanguage("sum");
        request.setContent(clientRequest.getContent());
        request.setConversationId(clientRequest.getConversationId());
        send(request);
    }

    // --- ЛОГИКА ТЕСТА REFUSE (Задача 1в) ---
    private void runRefuseTest(ACLMessage clientRequest) {
        List<AID> coordinators = findCoordinators();
        if (coordinators.isEmpty()) return;

        // Берем только ОДНОГО координатора
        AID target = coordinators.get(coordinatorIndex % coordinators.size());
        
        if (ClientAgent.monitorMode) System.out.println("\n[TEST-REFUSE] CUSTOMER: Sending 2 rapid requests to " + target.getLocalName().toUpperCase() + " (Task 1v)");

        String[] tasks = clientRequest.getContent().split(";");
        for (String task : tasks) {
            ACLMessage request = new ACLMessage(ACLMessage.REQUEST);
            request.addReceiver(target);
            request.setLanguage("sum");
            request.setContent(task);
            request.setConversationId(clientRequest.getConversationId()); // Один ID для обоих, чтобы Клиент их принял
            send(request);
            if (ClientAgent.monitorMode) System.out.println("[TEST-REFUSE] Sent task: " + task);
        }
        // Индекс не двигаем, чтобы следующий обычный запрос ушел на другого
    }

    // --- ЛОГИКА ТЕСТА ПАРАЛЛЕЛИЗМА (Задача 1г) ---
    private void runParallelTest(ACLMessage clientRequest) {
        List<AID> coordinators = findCoordinators();
        if (coordinators.isEmpty()) return;

        if (ClientAgent.monitorMode) System.out.println("\n[TEST-PARALLEL] CUSTOMER: Broadcasting tasks to ALL " + coordinators.size() + " Coordinators (Task 1g)");

        // Отправляем задачу КАЖДОМУ координатору одновременно
        for (AID coord : coordinators) {
            ACLMessage request = new ACLMessage(ACLMessage.REQUEST);
            request.addReceiver(coord);
            request.setLanguage("sum");
            request.setContent(clientRequest.getContent());
            request.setConversationId(clientRequest.getConversationId());
            send(request);
            if (ClientAgent.monitorMode) System.out.println("[TEST-PARALLEL] Sent task to " + coord.getLocalName().toUpperCase());
        }
        coordinatorIndex += coordinators.size(); // Сдвигаем индекс на количество использованных
    }

    // Вспомогательный метод поиска
    private List<AID> findCoordinators() {
        DFAgentDescription template = new DFAgentDescription();
        ServiceDescription sd = new ServiceDescription();
        sd.setType("coordination-service");
        template.addServices(sd);
        try {
            DFAgentDescription[] result = DFService.search(this, template);
            List<AID> list = new ArrayList<>();
            for (DFAgentDescription dfd : result) list.add(dfd.getName());
            return list;
        } catch (Exception e) {
            System.err.println("[ERROR] CUSTOMER: DF search failed.");
            return new ArrayList<>();
        }
    }

    @Override
    protected void takeDown() {
        if (ClientAgent.monitorMode) System.out.println("[SYSTEM] CUSTOMER: Terminated.");
    }
}