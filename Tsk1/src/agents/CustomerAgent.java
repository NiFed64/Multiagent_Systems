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
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class CustomerAgent extends Agent {

    private int coordinatorIndex = 0;
    private final Set<String> internalTestConvIds = new HashSet<>();

    @Override
    protected void setup() {
        LogUtil.sys("CUSTOMER", "Started as dispatcher.");

        // 1. Обычные задачи от Клиента
        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                MessageTemplate mt = MessageTemplate.and(
                        MessageTemplate.MatchPerformative(ACLMessage.REQUEST),
                        MessageTemplate.MatchLanguage("sum")
                );

                ACLMessage msg = receive(mt);
                if (msg != null) {
                    dispatchTask(msg);
                } else {
                    block();
                }
            }
        });

        // 2. Тест REFUSE
        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                MessageTemplate mt = MessageTemplate.and(
                        MessageTemplate.MatchPerformative(ACLMessage.REQUEST),
                        MessageTemplate.MatchLanguage("test-refuse")
                );

                ACLMessage msg = receive(mt);
                if (msg != null) {
                    runRefuseTest(msg);
                } else {
                    block();
                }
            }
        });

        // 3. Тест параллельной работы
        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                MessageTemplate mt = MessageTemplate.and(
                        MessageTemplate.MatchPerformative(ACLMessage.REQUEST),
                        MessageTemplate.MatchLanguage("test-parallel")
                );

                ACLMessage msg = receive(mt);
                if (msg != null) {
                    runParallelTest(msg);
                } else {
                    block();
                }
            }
        });

        // 4. Ответы от Координаторов
        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                MessageTemplate mt = MessageTemplate.and(
                        MessageTemplate.MatchLanguage("sum"),
                        MessageTemplate.or(
                                MessageTemplate.MatchPerformative(ACLMessage.CONFIRM),
                                MessageTemplate.MatchPerformative(ACLMessage.REFUSE)
                        )
                );

                ACLMessage reply = receive(mt);
                if (reply == null) {
                    block();
                    return;
                }

                String convId = reply.getConversationId();
                String perf = ACLMessage.getPerformative(reply.getPerformative());

                // Внутренние результаты parallel-теста не отправляем клиенту.
                if (convId != null && internalTestConvIds.contains(convId)) {
                    internalTestConvIds.remove(convId);

                    LogUtil.debug("CUSTOMER",
                            "INTERNAL RESULT from " +
                                    reply.getSender().getLocalName().toUpperCase() +
                                    " " + perf +
                                    " value=" + reply.getContent());
                    return;
                }

                ACLMessage toClient = new ACLMessage(reply.getPerformative());
                toClient.addReceiver(new AID("client", AID.ISLOCALNAME));
                toClient.setLanguage("sum");
                toClient.setConversationId(reply.getConversationId());
                toClient.setContent(reply.getContent());
                send(toClient);

                LogUtil.net("CUSTOMER",
                        "-> CLIENT " + perf + " value=" + reply.getContent());
            }
        });
    }

    private void dispatchTask(ACLMessage clientRequest) {
        List<AID> coordinators = findCoordinators();
        if (coordinators.isEmpty()) {
            return;
        }

        AID target = coordinators.get(coordinatorIndex % coordinators.size());
        coordinatorIndex++;

        LogUtil.net("CUSTOMER",
                "-> " + target.getLocalName().toUpperCase() +
                        " task " + clientRequest.getContent());

        ACLMessage request = new ACLMessage(ACLMessage.REQUEST);
        request.addReceiver(target);
        request.setLanguage("sum");
        request.setContent(clientRequest.getContent());
        request.setConversationId(clientRequest.getConversationId());
        send(request);
    }

    private void runRefuseTest(ACLMessage clientRequest) {
        List<AID> coordinators = findCoordinators();
        if (coordinators.isEmpty()) {
            return;
        }

        AID target = coordinators.get(coordinatorIndex % coordinators.size());

        String[] tasks = clientRequest.getContent().split(";");

        LogUtil.net("CUSTOMER",
                "-> " + target.getLocalName().toUpperCase() +
                        " test-refuse tasks=" + Arrays.toString(tasks));

        String convId = clientRequest.getConversationId();
        if (convId == null || convId.isEmpty()) {
            convId = UUID.randomUUID().toString();
        }

        for (String task : tasks) {
            ACLMessage request = new ACLMessage(ACLMessage.REQUEST);
            request.addReceiver(target);
            request.setLanguage("sum");
            request.setContent(task);
            request.setConversationId(convId);
            send(request);
        }
    }

    private void runParallelTest(ACLMessage clientRequest) {
        List<AID> coordinators = findCoordinators();
        if (coordinators.isEmpty()) {
            return;
        }

        String[] tasks = clientRequest.getContent().split(";");

        LogUtil.net("CUSTOMER",
                "test-parallel tasks=" + Arrays.toString(tasks) +
                        " coordinators=" + coordinators.size());

        int used = Math.min(tasks.length, coordinators.size());

        for (int i = 0; i < used; i++) {
            String internalConvId = UUID.randomUUID().toString();
            internalTestConvIds.add(internalConvId);

            ACLMessage request = new ACLMessage(ACLMessage.REQUEST);
            request.addReceiver(coordinators.get(i));
            request.setLanguage("sum");
            request.setContent(tasks[i]);
            request.setConversationId(internalConvId);
            send(request);

            LogUtil.net("CUSTOMER",
                    "-> " + coordinators.get(i).getLocalName().toUpperCase() +
                            " parallel task " + tasks[i] +
                            " (internal)");
        }

        coordinatorIndex += used;
    }

    private List<AID> findCoordinators() {
        DFAgentDescription template = new DFAgentDescription();

        ServiceDescription sd = new ServiceDescription();
        sd.setType("coordination-service");
        template.addServices(sd);

        try {
            DFAgentDescription[] result = DFService.search(this, template);

            List<AID> list = new ArrayList<>();
            for (DFAgentDescription dfd : result) {
                list.add(dfd.getName());
            }

            return list;

        } catch (Exception e) {
            LogUtil.err("CUSTOMER", "DF search failed: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    @Override
    protected void takeDown() {
        LogUtil.sys("CUSTOMER", "Terminated.");
    }
}