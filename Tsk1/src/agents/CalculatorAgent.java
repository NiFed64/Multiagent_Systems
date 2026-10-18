package agents;

import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.OneShotBehaviour;
import jade.core.behaviours.ThreadedBehaviourFactory;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;

public class CalculatorAgent extends Agent {

    private final ThreadedBehaviourFactory tbf = new ThreadedBehaviourFactory();

    @Override
    protected void setup() {
        String name = getLocalName().toUpperCase();
        LogUtil.sys(name, "Initialized and registered in DF.");

        // Регистрация в DF
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

        // Старый прямой режим REQUEST/CONFIRM на языке "sum"
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

        // Contract Net Responder на языке "sum-cn"
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

    private void handleCfp(ACLMessage cfp) {
        String name = getLocalName().toUpperCase();

        try {
            String content = cfp.getContent();
            if (content == null || content.isEmpty()) {
                return;
            }

            String[] parts = content.split(";");
            if (parts.length < 3) {
                return;
            }

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
        String subTaskId = extractSubTaskId(accept);

        LogUtil.debug(name, "ACCEPTED " + subTaskId);
        addBehaviour(tbf.wrap(new CalculationTaskCN(accept)));
    }

    private void handleRejectProposal(ACLMessage reject) {
        // Молча: отказ виден в сводке координатора.
    }

    private String extractSubTaskId(ACLMessage msg) {
        if (msg == null || msg.getContent() == null || msg.getContent().isEmpty()) {
            return "?";
        }

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

    // Старое одноразовое вычисление для REQUEST/CONFIRM
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

                for (int i = start; i <= end; i++) {
                    sum += i;
                }

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

    // Contract Net одноразовое вычисление
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
                if (content == null || content.isEmpty()) {
                    return;
                }

                String[] parts = content.split(";");
                if (parts.length < 3) {
                    return;
                }

                String subTaskId = parts[0];
                int A = Integer.parseInt(parts[1].trim());
                int B = Integer.parseInt(parts[2].trim());

                int start = Math.min(A, B);
                int end = Math.max(A, B);

                LogUtil.debug(name, "CN START " + subTaskId + " [" + start + "," + end + "]");

                emulateWork();

                long sum = 0;
                for (int i = start; i <= end; i++) {
                    sum += i;
                }

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