package agents;

import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.TickerBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class CustomerAgent extends Agent {

    private int coordinatorIndex = 0;

    private final Set<String> internalTestConvIds = new HashSet<>();
    private final List<PendingRequest> pending = new ArrayList<>();

    private static class PendingRequest {
        final String type;
        final String content;
        final String convId;

        PendingRequest(String type, String content, String convId) {
            this.type = type;
            this.content = content;
            this.convId = convId;
        }
    }

    @Override
    protected void setup() {
        LogUtil.sys("CUSTOMER", "Started as dispatcher with election-aware retry.");

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
                    handleIncoming(msg, "sum");
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
                    handleIncoming(msg, "test-refuse");
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
                    handleIncoming(msg, "test-parallel");
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

                    LogUtil.debug(
                            "CUSTOMER",
                            "INTERNAL RESULT from " +
                                    reply.getSender().getLocalName().toUpperCase() +
                                    " " + perf +
                                    " value=" + reply.getContent()
                    );

                    return;
                }

                ACLMessage toClient = new ACLMessage(reply.getPerformative());
                toClient.addReceiver(new AID("client", AID.ISLOCALNAME));
                toClient.setLanguage("sum");
                toClient.setConversationId(reply.getConversationId());
                toClient.setContent(reply.getContent());

                send(toClient);

                LogUtil.net(
                        "CUSTOMER",
                        "-> CLIENT " + perf + " value=" + reply.getContent()
                );
            }
        });

        // 5. Периодическая обработка очереди, если координатор ещё не выбран
        addBehaviour(new TickerBehaviour(this, 1000) {
            @Override
            protected void onTick() {
                processPending();
            }
        });
    }

    private void handleIncoming(ACLMessage msg, String type) {
        String convId = msg.getConversationId();

        if (convId == null || convId.isEmpty()) {
            convId = UUID.randomUUID().toString();
        }

        PendingRequest pr = new PendingRequest(type, msg.getContent(), convId);

        List<AID> coordinators = findCoordinators();
        int required = requiredCoordinatorsFor(type, pr.content);

        if (coordinators.size() < required) {
            pending.add(pr);

            LogUtil.net(
                    "CUSTOMER",
                    "Not enough coordinators in DF (" +
                            coordinators.size() + "/" + required +
                            "). Queued " + type
            );

        } else {
            dispatchPending(pr, coordinators);
        }
    }

    private void processPending() {
        if (pending.isEmpty()) {
            return;
        }

        List<AID> coordinators = findCoordinators();

        if (coordinators.isEmpty()) {
            return;
        }

        java.util.Iterator<PendingRequest> it = pending.iterator();

        while (it.hasNext()) {
            PendingRequest pr = it.next();
            int required = requiredCoordinatorsFor(pr.type, pr.content);

            if (coordinators.size() >= required) {
                it.remove();
                dispatchPending(pr, coordinators);
            }
        }
    }

    private int requiredCoordinatorsFor(String type, String content) {
        if ("test-parallel".equals(type)) {
            int tasks = 0;

            if (content != null && !content.isEmpty()) {
                tasks = content.split(";").length;
            }

            return Math.max(1, tasks);
        }

        return 1;
    }

    private void dispatchPending(PendingRequest pr, List<AID> coordinators) {
        if (coordinators.isEmpty()) {
            pending.add(pr);
            return;
        }

        switch (pr.type) {
            case "sum":
                dispatchNormal(pr, coordinators);
                break;

            case "test-refuse":
                dispatchRefuseTest(pr, coordinators);
                break;

            case "test-parallel":
                dispatchParallelTest(pr, coordinators);
                break;

            default:
                LogUtil.err("CUSTOMER", "Unknown pending type: " + pr.type);
                break;
        }
    }

    private void dispatchNormal(PendingRequest pr, List<AID> coordinators) {
        AID target = coordinators.get(coordinatorIndex % coordinators.size());
        coordinatorIndex++;

        ACLMessage request = new ACLMessage(ACLMessage.REQUEST);
        request.addReceiver(target);
        request.setLanguage("sum");
        request.setContent(pr.content);
        request.setConversationId(pr.convId);

        send(request);

        LogUtil.net(
                "CUSTOMER",
                "-> " + target.getLocalName().toUpperCase() +
                        " task " + pr.content
        );
    }

    private void dispatchRefuseTest(PendingRequest pr, List<AID> coordinators) {
        AID target = coordinators.get(coordinatorIndex % coordinators.size());

        String[] tasks = pr.content.split(";");

        for (String task : tasks) {
            ACLMessage request = new ACLMessage(ACLMessage.REQUEST);
            request.addReceiver(target);
            request.setLanguage("sum");
            request.setContent(task);
            request.setConversationId(pr.convId);

            send(request);
        }

        LogUtil.net(
                "CUSTOMER",
                "-> " + target.getLocalName().toUpperCase() +
                        " test-refuse tasks=" + Arrays.toString(tasks)
        );
    }

    private void dispatchParallelTest(PendingRequest pr, List<AID> coordinators) {
        String[] tasks = pr.content.split(";");

        int used = Math.min(tasks.length, coordinators.size());

        for (int i = 0; i < used; i++) {
            String internalConvId = UUID.randomUUID().toString();
            internalTestConvIds.add(internalConvId);

            AID target = coordinators.get((coordinatorIndex + i) % coordinators.size());

            ACLMessage request = new ACLMessage(ACLMessage.REQUEST);
            request.addReceiver(target);
            request.setLanguage("sum");
            request.setContent(tasks[i]);
            request.setConversationId(internalConvId);

            send(request);

            LogUtil.net(
                    "CUSTOMER",
                    "-> " + target.getLocalName().toUpperCase() +
                            " parallel task " + tasks[i] +
                            " (internal)"
            );
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

            if (result != null) {
                for (DFAgentDescription dfd : result) {
                    list.add(dfd.getName());
                }
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