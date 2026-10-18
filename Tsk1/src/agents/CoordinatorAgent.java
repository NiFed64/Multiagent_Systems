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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class CoordinatorAgent extends Agent {

    private final List<AID> calculators = new ArrayList<>();

    private ACLMessage currentExternalRequest;
    private AID originalSender;
    private String currentConvId;
    private String currentTaskId = "";

    private long currentSum = 0;

    private volatile boolean isBusy = false;

    private final Map<String, ContractContext> contracts = new HashMap<>();
    private final Map<String, Integer> calculatorLoad = new HashMap<>();

    private int subtasksRemaining = 0;
    private int informsPending = 0;

    private final StringBuilder awardSummary = new StringBuilder();

    private static class Proposal {
        final AID sender;
        final long cost;

        Proposal(AID sender, long cost) {
            this.sender = sender;
            this.cost = cost;
        }
    }

    private static class ContractContext {
        final String subTaskId;
        final int start;
        final int end;

        int expectedProposals;

        boolean resolved = false;
        boolean accepted = false;
        boolean informed = false;

        final Map<String, Proposal> proposals = new HashMap<>();

        ContractContext(String subTaskId, int start, int end, int expectedProposals) {
            this.subTaskId = subTaskId;
            this.start = start;
            this.end = end;
            this.expectedProposals = expectedProposals;
        }
    }

    @Override
    protected void setup() {
        String name = getLocalName().toUpperCase();
        LogUtil.sys(name, "Started. Registered in DF as Contract Net Initiator.");

        // Регистрация Координатора в DF для Заказчика
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());

        ServiceDescription sd = new ServiceDescription();
        sd.setType("coordination-service");
        sd.setName("coordinator-service");
        dfd.addServices(sd);

        try {
            DFService.register(this, dfd);
        } catch (Exception e) {
            LogUtil.err(name, "DF registration failed: " + e.getMessage());
        }

        FSMBehaviour fsm = new FSMBehaviour(this);
        fsm.registerFirstState(new WaitingState(), "WAITING");
        fsm.registerState(new BusyState(), "BUSY");

        fsm.registerTransition("WAITING", "BUSY", 1);
        fsm.registerDefaultTransition("BUSY", "WAITING", new String[]{"WAITING", "BUSY"});

        addBehaviour(fsm);
    }

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
            LogUtil.err(getLocalName().toUpperCase(), "DF search failed: " + e.getMessage());
        }
    }

    private class WaitingState extends SimpleBehaviour {
        private boolean finished = false;

        private final MessageTemplate mt = MessageTemplate.and(
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
                originalSender = msg.getSender();
                currentConvId = msg.getConversationId();

                if (currentConvId == null || currentConvId.isEmpty()) {
                    currentConvId = UUID.randomUUID().toString();
                }

                finished = true;
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
            isBusy = true;
            return 1;
        }
    }

    private class BusyState extends SimpleBehaviour {
        private boolean taskStarted = false;

        @Override
        public void onStart() {
            super.onStart();

            String name = getLocalName().toUpperCase();

            taskStarted = false;

            contracts.clear();
            calculatorLoad.clear();
            subtasksRemaining = 0;
            informsPending = 0;
            currentSum = 0;
            awardSummary.setLength(0);

            currentTaskId = LogUtil.shortId(currentConvId);

            findCalculators();

            if (calculators.isEmpty()) {
                LogUtil.err(name, "No calculators available in DF.");
                taskStarted = true;
                return;
            }

            try {
                String content = currentExternalRequest.getContent();
                if (content == null || content.isEmpty()) {
                    throw new IllegalArgumentException("Empty external request content.");
                }

                String[] parts = content.split(",");
                int A = Integer.parseInt(parts[0].trim());
                int B = Integer.parseInt(parts[1].trim());

                int low = Math.min(A, B);
                int high = Math.max(A, B);
                int totalNumbers = high - low + 1;

                int partsCount = Math.min(3, calculators.size());
                partsCount = Math.min(partsCount, totalNumbers);

                if (partsCount < 1) {
                    partsCount = 1;
                }

                LogUtil.cn(name,
                        "TASK START " + currentTaskId +
                                " [" + low + "," + high + "]" +
                                " subtasks=" + partsCount +
                                " responders=" + calculators.size());

                int baseSize = totalNumbers / partsCount;
                int remainder = totalNumbers % partsCount;
                int currentStart = low;

                for (int i = 0; i < partsCount; i++) {
                    int currentSize = baseSize + (i < remainder ? 1 : 0);
                    int currentEnd = currentStart + currentSize - 1;

                    String subTaskId = currentTaskId + "/p" + i;

                    ContractContext ctx = new ContractContext(
                            subTaskId,
                            currentStart,
                            currentEnd,
                            calculators.size()
                    );

                    contracts.put(subTaskId, ctx);
                    subtasksRemaining++;

                    ACLMessage cfp = new ACLMessage(ACLMessage.CFP);
                    cfp.setLanguage("sum-cn");
                    cfp.setConversationId(currentConvId);
                    cfp.setContent(subTaskId + ";" + currentStart + ";" + currentEnd);

                    for (AID calculator : calculators) {
                        cfp.addReceiver(calculator);
                    }

                    send(cfp);

                    currentStart = currentEnd + 1;
                }

                LogUtil.cn(name, "CFP SENT " + currentTaskId + " parts=0.." + (partsCount - 1));

                taskStarted = true;

            } catch (Exception e) {
                LogUtil.err(name, "Failed to start Contract Net: " + e.getMessage());
                subtasksRemaining = 0;
                informsPending = 0;
                taskStarted = true;
            }
        }

        @Override
        public void action() {
            if (!taskStarted) {
                return;
            }

            String name = getLocalName().toUpperCase();

            // 1. Отбиваем новые внешние запросы, пока Координатор занят.
            MessageTemplate mtExt = MessageTemplate.and(
                    MessageTemplate.MatchPerformative(ACLMessage.REQUEST),
                    MessageTemplate.MatchLanguage("sum")
            );

            ACLMessage extMsg = receive(mtExt);
            if (extMsg != null) {
                ACLMessage refuse = extMsg.createReply();
                refuse.setPerformative(ACLMessage.REFUSE);
                refuse.setLanguage("sum");
                refuse.setConversationId(extMsg.getConversationId());
                refuse.setContent("Coordinator is busy processing a previous task.");
                send(refuse);

                LogUtil.net(name, "REFUSE -> " + extMsg.getSender().getLocalName().toUpperCase());
            }

            // 2. Обрабатываем внутренние Contract Net сообщения.
            MessageTemplate mtCn = MessageTemplate.and(
                    MessageTemplate.MatchLanguage("sum-cn"),
                    MessageTemplate.MatchConversationId(currentConvId)
            );

            ACLMessage cnMsg = receive(mtCn);
            if (cnMsg != null) {
                handleCnMessage(cnMsg);
            }

            if (extMsg == null && cnMsg == null) {
                block(100);
            }
        }

        @Override
        public boolean done() {
            return taskStarted && subtasksRemaining == 0 && informsPending == 0;
        }

        @Override
        public int onEnd() {
            String name = getLocalName().toUpperCase();

            if (currentExternalRequest != null) {
                ACLMessage finalReply = currentExternalRequest.createReply();
                finalReply.setPerformative(ACLMessage.CONFIRM);
                finalReply.setLanguage("sum");
                finalReply.setConversationId(currentConvId);
                finalReply.setContent(String.valueOf(currentSum));
                send(finalReply);
            }

            LogUtil.cn(name,
                    "TASK END " + currentTaskId +
                            " sum=" + currentSum +
                            " -> " + (originalSender != null ? originalSender.getLocalName().toUpperCase() : "UNKNOWN"));

            isBusy = false;
            return 0;
        }
    }

    private void handleCnMessage(ACLMessage msg) {
        int performative = msg.getPerformative();

        if (performative == ACLMessage.PROPOSE) {
            handlePropose(msg);
        } else if (performative == ACLMessage.INFORM) {
            handleInform(msg);
        } else if (performative == ACLMessage.REFUSE || performative == ACLMessage.FAILURE) {
            handleNegativeProposal(msg);
        }
    }

    private void handlePropose(ACLMessage msg) {
        try {
            String content = msg.getContent();
            if (content == null || content.isEmpty()) {
                return;
            }

            String[] parts = content.split(";");
            if (parts.length < 2) {
                return;
            }

            String subTaskId = parts[0];
            long cost = Long.parseLong(parts[1].trim());

            ContractContext ctx = contracts.get(subTaskId);
            if (ctx == null || ctx.resolved) {
                return;
            }

            String senderName = msg.getSender().getLocalName().toUpperCase();
            ctx.proposals.put(senderName, new Proposal(msg.getSender(), cost));
            ctx.expectedProposals--;

            LogUtil.debug(getLocalName().toUpperCase(),
                    "PROPOSE " + subTaskId + " from " + senderName + " cost=" + cost);

            if (ctx.expectedProposals <= 0) {
                resolveContract(ctx);
            }

        } catch (Exception e) {
            LogUtil.err(getLocalName().toUpperCase(), "Failed to process PROPOSE: " + e.getMessage());
        }
    }

    private void handleNegativeProposal(ACLMessage msg) {
        try {
            String content = msg.getContent();
            if (content == null || content.isEmpty()) {
                return;
            }

            String subTaskId = content.split(";")[0];

            ContractContext ctx = contracts.get(subTaskId);
            if (ctx == null || ctx.resolved) {
                return;
            }

            ctx.expectedProposals--;

            LogUtil.debug(getLocalName().toUpperCase(),
                    "NEGATIVE " + subTaskId + " from " + msg.getSender().getLocalName().toUpperCase());

            if (ctx.expectedProposals <= 0) {
                resolveContract(ctx);
            }

        } catch (Exception e) {
            LogUtil.err(getLocalName().toUpperCase(), "Failed to process negative response: " + e.getMessage());
        }
    }

    private void resolveContract(ContractContext ctx) {
        if (ctx.resolved) {
            return;
        }

        String name = getLocalName().toUpperCase();

        ctx.resolved = true;
        subtasksRemaining--;

        // Никто не предложил — контракт провален, INFORM не придёт, удаляем сразу.
        if (ctx.proposals.isEmpty()) {
            LogUtil.cn(name, "NO PROPOSALS " + ctx.subTaskId);
            contracts.remove(ctx.subTaskId);
            checkAwardsCompleted(name);
            return;
        }

        Proposal best = null;
        String bestName = null;
        int bestLoad = Integer.MAX_VALUE;

        for (Proposal p : ctx.proposals.values()) {
            String candidateName = p.sender.getLocalName().toUpperCase();
            int load = calculatorLoad.getOrDefault(candidateName, 0);

            boolean better = false;

            if (best == null) {
                better = true;
            } else if (p.cost < best.cost) {
                better = true;
            } else if (p.cost == best.cost) {
                if (load < bestLoad) {
                    better = true;
                } else if (load == bestLoad) {
                    if (candidateName.compareTo(bestName) < 0) {
                        better = true;
                    }
                }
            }

            if (better) {
                best = p;
                bestName = candidateName;
                bestLoad = load;
            }
        }

        if (best == null) {
            contracts.remove(ctx.subTaskId);
            checkAwardsCompleted(name);
            return;
        }

        // ACCEPT-PROPOSAL выбранному вычислителю
        ACLMessage accept = new ACLMessage(ACLMessage.ACCEPT_PROPOSAL);
        accept.addReceiver(best.sender);
        accept.setLanguage("sum-cn");
        accept.setConversationId(currentConvId);
        accept.setContent(ctx.subTaskId + ";" + ctx.start + ";" + ctx.end);
        send(accept);

        // REJECT-PROPOSAL остальным
        StringBuilder rejects = new StringBuilder();

        for (Proposal p : ctx.proposals.values()) {
            String candidateName = p.sender.getLocalName().toUpperCase();

            if (!candidateName.equals(bestName)) {
                ACLMessage reject = new ACLMessage(ACLMessage.REJECT_PROPOSAL);
                reject.addReceiver(p.sender);
                reject.setLanguage("sum-cn");
                reject.setConversationId(currentConvId);
                reject.setContent(ctx.subTaskId);
                send(reject);

                if (rejects.length() > 0) {
                    rejects.append(",");
                }
                rejects.append(candidateName);
            }
        }

        ctx.accepted = true;
        informsPending++;

        Integer oldLoad = calculatorLoad.get(bestName);
        calculatorLoad.put(bestName, oldLoad == null ? 1 : oldLoad + 1);

        if (awardSummary.length() > 0) {
            awardSummary.append("; ");
        }
        awardSummary.append(ctx.subTaskId)
                .append("->")
                .append(bestName)
                .append("(")
                .append(best.cost)
                .append(")");

        LogUtil.debug(name,
                "AWARD " + ctx.subTaskId + " -> " + bestName +
                        " cost=" + best.cost +
                        (rejects.length() > 0 ? " rejects=" + rejects : ""));

        // ВАЖНО: контракт НЕ удаляем здесь — он нужен handleInform для учёта INFORM.
        // Удаляем только после получения INFORM (см. handleInform).
        checkAwardsCompleted(name);
    }

    private void checkAwardsCompleted(String name) {
        if (subtasksRemaining == 0 && awardSummary.length() > 0) {
            LogUtil.cn(name, "AWARDS " + currentTaskId + " " + awardSummary);
        }
    }

    private void handleInform(ACLMessage msg) {
        String name = getLocalName().toUpperCase();

        try {
            String content = msg.getContent();
            if (content == null || content.isEmpty()) {
                return;
            }

            String[] parts = content.split(";");
            if (parts.length < 2) {
                return;
            }

            String subTaskId = parts[0];
            long sum = Long.parseLong(parts[1].trim());

            ContractContext ctx = contracts.get(subTaskId);
            if (ctx == null || !ctx.accepted || ctx.informed) {
                return;
            }

            ctx.informed = true;
            currentSum += sum;
            informsPending--;

            contracts.remove(subTaskId);   // ← удаление теперь ТОЛЬКО здесь

            LogUtil.cn(name,
                    "INFORM " + subTaskId +
                            " <- " + msg.getSender().getLocalName().toUpperCase() +
                            " sum=" + sum +
                            " total=" + currentSum +
                            " pending=" + informsPending);

        } catch (Exception e) {
            LogUtil.err(name, "Failed to process INFORM: " + e.getMessage());
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