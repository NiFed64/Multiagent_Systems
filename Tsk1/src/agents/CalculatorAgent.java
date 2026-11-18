package agents;

import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.OneShotBehaviour;
import jade.core.behaviours.ThreadedBehaviourFactory;
import jade.core.behaviours.TickerBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class CalculatorAgent extends Agent implements MigratableAgent {

    // Тайминги живости/перевыборов (Задача 1з)
    private static final long HEARTBEAT_TIMEOUT = 5000L;
    private static final long MONITOR_INTERVAL = 2000L;
    private static final long REELECTION_COOLDOWN = 3000L;
    private static final String HEARTBEAT_LANG = "coordination-heartbeat";

    // Размер пула координаторов (Задача 1ж + сохранение 1г)
    private static final int DESIRED_COORDINATORS = 2;

    // transient: пересоздаём на назначении (weak migration не гарантирует сериализацию фабрики)
    private transient ThreadedBehaviourFactory tbf;

    // Аргументы, прочитанные до миграции
    private String targetContainerName = "Container-1";
    private String[] peerNamesArray = new String[0];

    // Состояние выборов/мониторинга (Задача 1ж/1з)
    private ElectionBehaviour currentElection;
    private final Set<String> knownCoordinators = new HashSet<>();
    private final Map<String, Long> lastHeartbeat = new HashMap<>();
    private long lastReelectionTime = 0L;

    @Override
    protected void setup() {
        String name = getLocalName().toUpperCase();

        // ЗАДАЧА 1и: читаем аргументы. args[0] — целевой контейнер, args[1..] — peers.
        Object[] args = getArguments();
        List<String> peers = new ArrayList<>();
        if (args != null && args.length > 0) {
            if (args[0] != null) {
                targetContainerName = args[0].toString();
            }
            for (int i = 1; i < args.length; i++) {
                if (args[i] != null) {
                    peers.add(args[i].toString());
                }
            }
        }
        peerNamesArray = peers.toArray(new String[0]);

        LogUtil.sys(name, "Born in MAIN container. Will migrate to "
                + targetContainerName.toUpperCase() + ".");

        // Единственное поведение до миграции — миграционный FSM.
        addBehaviour(new MigrationBehaviour(this, targetContainerName));
    }

    @Override
    public void postMigrationSetup() {
        String name = getLocalName().toUpperCase();
        LogUtil.sys(name, "Post-migration setup on " + targetContainerName.toUpperCase() + ".");

        tbf = new ThreadedBehaviourFactory();

        // Регистрация в DF как вычислитель (Задача 1б) — уже на назначении.
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());

        ServiceDescription sd = new ServiceDescription();
        sd.setType("sum-calculation");
        sd.setName("calculator-service");
        dfd.addServices(sd);

        try {
            DFService.register(this, dfd);
        } catch (Exception e) {
            LogUtil.err(name, "DF registration failed: " + e.getMessage());
        }

        // ЗАДАЧА 1ж/1з: выборы пула координаторов (отдельное поведение).
        currentElection = new ElectionBehaviour(this, peerNamesArray, DESIRED_COORDINATORS);
        addBehaviour(currentElection);

        // ЗАДАЧА 1з: слушатель heartbeat / death-signal.
        addBehaviour(new CyclicBehaviour(this) {
            private final MessageTemplate mt = MessageTemplate.MatchLanguage(HEARTBEAT_LANG);

            @Override
            public void action() {
                ACLMessage msg = receive(mt);
                if (msg == null) {
                    block();
                } else {
                    handleHeartbeatMessage(msg);
                }
            }
        });

        // ЗАДАЧА 1з: монитор живости координаторов + триггер перевыборов.
        addBehaviour(new TickerBehaviour(this, MONITOR_INTERVAL) {
            @Override
            protected void onTick() {
                monitorCoordinators();
            }
        });

        // Прямой режим REQUEST/CONFIRM на языке "sum" (Задача 1а/1г).
        addBehaviour(new CyclicBehaviour(this) {
            private final MessageTemplate mt = MessageTemplate.and(
                    MessageTemplate.MatchPerformative(ACLMessage.REQUEST),
                    MessageTemplate.MatchLanguage("sum")
            );

            @Override
            public void action() {
                ACLMessage msg = receive(mt);
                if (msg == null) {
                    block();
                    return;
                }
                LogUtil.debug(getLocalName().toUpperCase(), "Legacy REQUEST accepted");
                addBehaviour(tbf.wrap(new CalculationTask(msg)));
            }
        });

        // Contract Net Responder на языке "sum-cn" (Задача 1д).
        addBehaviour(new CyclicBehaviour(this) {
            private final MessageTemplate mt = MessageTemplate.MatchLanguage("sum-cn");

            @Override
            public void action() {
                ACLMessage msg = receive(mt);
                if (msg == null) {
                    block();
                    return;
                }
                switch (msg.getPerformative()) {
                    case ACLMessage.CFP:
                        handleCfp(msg);
                        break;
                    case ACLMessage.ACCEPT_PROPOSAL:
                        handleAcceptProposal(msg);
                        break;
                    case ACLMessage.REJECT_PROPOSAL:
                        handleRejectProposal(msg);
                        break;
                    default:
                        break;
                }
            }
        });
    }

    // ================================================================
    // ЗАДАЧА 1з: heartbeat / death / мониторинг / перевыборы (без изменений)
    // ================================================================

    private void handleHeartbeatMessage(ACLMessage msg) {
        String content = msg.getContent();
        if (content == null || content.isEmpty()) {
            return;
        }
        String[] parts = content.split(";");
        if (parts.length < 2) {
            return;
        }
        String type = parts[0];
        String coordName = parts[1].trim();
        long now = System.currentTimeMillis();

        if ("HEARTBEAT".equals(type)) {
            lastHeartbeat.put(coordName, now);
            knownCoordinators.add(coordName);
            LogUtil.debug(getLocalName().toUpperCase(), "HEARTBEAT from " + coordName.toUpperCase());
        } else if ("COORDINATOR_DIED".equals(type)) {
            lastHeartbeat.remove(coordName);
            knownCoordinators.remove(coordName);
            LogUtil.net(getLocalName().toUpperCase(),
                    "COORDINATOR_DIED from " + coordName.toUpperCase() + ". Re-election.");
            triggerReelection(true);
        }
    }

    private void monitorCoordinators() {
        String name = getLocalName().toUpperCase();
        long now = System.currentTimeMillis();

        Set<String> currentFromDF = findCoordinatorNamesFromDF();
        for (String c : currentFromDF) {
            lastHeartbeat.putIfAbsent(c, now);
        }
        knownCoordinators.clear();
        knownCoordinators.addAll(currentFromDF);

        List<String> dead = new ArrayList<>();
        for (String c : currentFromDF) {
            Long last = lastHeartbeat.get(c);
            if (last != null && (now - last) > HEARTBEAT_TIMEOUT) {
                dead.add(c);
            }
        }
        for (String c : dead) {
            lastHeartbeat.remove(c);
            knownCoordinators.remove(c);
            LogUtil.net(name, "HEARTBEAT TIMEOUT for " + c.toUpperCase() + ". Marked dead.");
        }

        if (knownCoordinators.size() < DESIRED_COORDINATORS) {
            LogUtil.debug(name, "Coordinators in DF = " + knownCoordinators.size()
                    + " < desired = " + DESIRED_COORDINATORS);
            triggerReelection(false);
        }
    }

    private void triggerReelection(boolean force) {
        String name = getLocalName().toUpperCase();
        long now = System.currentTimeMillis();

        if (currentElection != null && !currentElection.done()) {
            LogUtil.debug(name, "Re-election skipped: election in progress.");
            return;
        }
        if (!force && (now - lastReelectionTime) < REELECTION_COOLDOWN) {
            return;
        }
        lastReelectionTime = now;

        if (currentElection != null) {
            removeBehaviour(currentElection);
        }
        currentElection = new ElectionBehaviour(this, peerNamesArray, DESIRED_COORDINATORS);
        addBehaviour(currentElection);

        LogUtil.net(name, "REELECTION triggered (force=" + force
                + "). Known coordinators = " + knownCoordinators);
    }

    private Set<String> findCoordinatorNamesFromDF() {
        DFAgentDescription template = new DFAgentDescription();
        ServiceDescription sd = new ServiceDescription();
        sd.setType("coordination-service");
        template.addServices(sd);

        Set<String> names = new HashSet<>();
        try {
            DFAgentDescription[] result = DFService.search(this, template);
            if (result != null) {
                for (DFAgentDescription dfd : result) {
                    AID aid = dfd.getName();
                    if (aid != null && aid.getLocalName() != null) {
                        names.add(aid.getLocalName());
                    }
                }
            }
        } catch (Exception e) {
            LogUtil.err(getLocalName().toUpperCase(), "Coordinator DF search failed: " + e.getMessage());
        }
        return names;
    }

    // ================================================================
    // Contract Net Responder (Задача 1д) — без изменений
    // ================================================================

    private void handleCfp(ACLMessage cfp) {
        String name = getLocalName().toUpperCase();
        try {
            String content = cfp.getContent();
            if (content == null || content.isEmpty()) return;
            String[] parts = content.split(";");
            if (parts.length < 3) return;

            String subTaskId = parts[0];
            int A = Integer.parseInt(parts[1].trim());
            int B = Integer.parseInt(parts[2].trim());
            int start = Math.min(A, B);
            int end = Math.max(A, B);
            long cost = end - start + 1L;

            ACLMessage propose = cfp.createReply();
            propose.setPerformative(ACLMessage.PROPOSE);
            propose.setLanguage("sum-cn");
            propose.setConversationId(cfp.getConversationId());
            propose.setContent(subTaskId + ";" + cost);
            send(propose);

            LogUtil.debug(name, "PROPOSE " + subTaskId + " cost=" + cost);
        } catch (Exception e) {
            LogUtil.err(name, "CFP processing error: " + e.getMessage());
        }
    }

    private void handleAcceptProposal(ACLMessage accept) {
        String name = getLocalName().toUpperCase();
        LogUtil.debug(name, "ACCEPTED " + extractSubTaskId(accept));
        addBehaviour(tbf.wrap(new CalculationTaskCN(accept)));
    }

    private void handleRejectProposal(ACLMessage reject) {
        // Молча: отказ виден в сводке координатора.
    }

    private String extractSubTaskId(ACLMessage msg) {
        if (msg == null || msg.getContent() == null || msg.getContent().isEmpty()) return "?";
        String[] parts = msg.getContent().split(";");
        return parts.length > 0 ? parts[0] : "?";
    }

    private void emulateWork() {
        for (int i = 0; i < 5; i++) {
            LogUtil.debug(getLocalName().toUpperCase(), "Calculating... step " + (i + 1) + "/5");
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private class CalculationTask extends OneShotBehaviour {
        private final ACLMessage msg;
        CalculationTask(ACLMessage msg) {
            super(CalculatorAgent.this);
            this.msg = msg;
        }
        @Override
        public void action() {
            String name = getLocalName().toUpperCase();
            try {
                String[] parts = msg.getContent().split(",");
                int A = Integer.parseInt(parts[0].trim());
                int B = Integer.parseInt(parts[1].trim());
                emulateWork();
                long sum = 0;
                int start = Math.min(A, B);
                int end = Math.max(A, B);
                for (int i = start; i <= end; i++) sum += i;

                ACLMessage reply = msg.createReply();
                reply.setPerformative(ACLMessage.CONFIRM);
                reply.setLanguage("sum");
                reply.setConversationId(msg.getConversationId());
                reply.setContent(String.valueOf(sum));
                send(reply);
                LogUtil.debug(name, "LEGACY DONE sum=" + sum);
            } catch (Exception e) {
                LogUtil.err(name, "Legacy calculation error: " + e.getMessage());
            }
        }
    }

    private class CalculationTaskCN extends OneShotBehaviour {
        private final ACLMessage accept;
        CalculationTaskCN(ACLMessage accept) {
            super(CalculatorAgent.this);
            this.accept = accept;
        }
        @Override
        public void action() {
            String name = getLocalName().toUpperCase();
            try {
                String content = accept.getContent();
                if (content == null || content.isEmpty()) return;
                String[] parts = content.split(";");
                if (parts.length < 3) return;

                String subTaskId = parts[0];
                int A = Integer.parseInt(parts[1].trim());
                int B = Integer.parseInt(parts[2].trim());
                int start = Math.min(A, B);
                int end = Math.max(A, B);

                LogUtil.debug(name, "CN START " + subTaskId + " [" + start + "," + end + "]");
                emulateWork();
                long sum = 0;
                for (int i = start; i <= end; i++) sum += i;

                ACLMessage inform = accept.createReply();
                inform.setPerformative(ACLMessage.INFORM);
                inform.setLanguage("sum-cn");
                inform.setConversationId(accept.getConversationId());
                inform.setContent(subTaskId + ";" + sum);
                send(inform);
                LogUtil.debug(name, "CN DONE " + subTaskId + " sum=" + sum);
            } catch (Exception e) {
                LogUtil.err(name, "CN calculation error: " + e.getMessage());
            }
        }
    }

    @Override
    protected void takeDown() {
        try {
            DFService.deregister(this);
        } catch (Exception e) {
            // ignore
        }
        LogUtil.sys(getLocalName().toUpperCase(), "Terminated.");
    }
}