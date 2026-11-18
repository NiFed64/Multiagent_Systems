package agents;

import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;

import java.util.Scanner;
import java.util.UUID;

public class ClientAgent extends Agent implements MigratableAgent {

    private static final long RESPONSE_TIMEOUT = 15000L;
    private static final String KILL_LANG = "kill-switch";

    public static volatile boolean monitorMode = true;

    private String targetContainerName = "Container-1";

    private int mode = 2;
    private String currentConvId = null;
    private long requestDeadline = 0L;

    @Override
    protected void setup() {
        String name = getLocalName().toUpperCase();

        Object[] args = getArguments();
        if (args != null && args.length > 0 && args[0] != null) {
            targetContainerName = args[0].toString();
        }

        LogUtil.sys(name, "Born in MAIN container. Will migrate to "
                + targetContainerName.toUpperCase() + ".");

        addBehaviour(new MigrationBehaviour(this, targetContainerName));
    }

    @Override
    public void postMigrationSetup() {
        String name = getLocalName().toUpperCase();
        LogUtil.sys(name, "Post-migration setup on " + targetContainerName.toUpperCase() + ".");

        // 1. Приём ответов (CONFIRM / REFUSE) с таймаутом (Задача 1з)
        addBehaviour(new CyclicBehaviour(this) {
            private final MessageTemplate baseMt = MessageTemplate.and(
                    MessageTemplate.MatchLanguage("sum"),
                    MessageTemplate.or(
                            MessageTemplate.MatchPerformative(ACLMessage.CONFIRM),
                            MessageTemplate.MatchPerformative(ACLMessage.REFUSE)
                    )
            );

            @Override
            public void action() {
                ACLMessage reply;

                if (currentConvId != null) {
                    MessageTemplate mt = MessageTemplate.and(
                            baseMt,
                            MessageTemplate.MatchConversationId(currentConvId)
                    );
                    long remaining = requestDeadline - System.currentTimeMillis();
                    if (remaining <= 0) {
                        LogUtil.resultRefuse("TIMEOUT: coordinator did not answer in time (possibly killed).");
                        currentConvId = null;
                        block();
                        return;
                    }
                    reply = blockingReceive(mt, Math.min(remaining, 2000));
                    if (reply == null) {
                        block(200);
                        return;
                    }
                } else {
                    reply = receive(baseMt);
                    if (reply == null) {
                        block();
                        return;
                    }
                }

                if (reply.getPerformative() == ACLMessage.CONFIRM) {
                    LogUtil.resultConfirm(reply.getContent());
                } else if (reply.getPerformative() == ACLMessage.REFUSE) {
                    LogUtil.resultRefuse(reply.getContent());
                }
                currentConvId = null;
            }
        });

        // 2. Console-поток (читает System.in уже в агентном контейнере)
        final Agent self = this;

        Thread consoleReader = new Thread(() -> {
            Scanner scanner = new Scanner(System.in);

            System.out.println("========================================");
            System.out.println(" Initial Mode: MONITOR (System logs)");
            System.out.println(" Commands:");
            System.out.println("  '1' -> Switch to CLIENT MODE");
            System.out.println("  'test-refuse' -> Test FSM (Task 1v)");
            System.out.println("  'test-parallel' -> Test Threads (Task 1g)");
            System.out.println("  'kill <coordName>' -> Kill-switch (Task 1z)");
            System.out.println("========================================");

            while (true) {
                String input;

                if (mode == 2) {
                    System.out.print("\n[MONITOR MODE] Type '1', 'test-refuse', 'test-parallel' or 'kill <name>': ");
                    input = scanner.nextLine().trim();

                    if (input.equals("1")) {
                        mode = 1;
                        monitorMode = false;
                        System.out.println("\n>>> Switched to CLIENT MODE.");
                        continue;
                    }
                    if (input.equalsIgnoreCase("test-refuse") || input.equalsIgnoreCase("test-parallel")) {
                        sendTestCommand(input);
                        continue;
                    }
                    if (input.toLowerCase().startsWith("kill ")) {
                        sendKillCommand(input.substring("kill ".length()).trim());
                        continue;
                    }
                } else {
                    System.out.print("\n[CLIENT MODE] Enter range (A,B), '2', 'kill <name>', 'test-*', or 'exit': ");
                    input = scanner.nextLine();

                    if (input.trim().equals("2")) {
                        mode = 2;
                        monitorMode = true;
                        System.out.println("\n>>> Switched to MONITOR MODE.");
                        continue;
                    }
                    if (input.trim().equalsIgnoreCase("exit")) {
                        self.doDelete();
                        break;
                    }
                    if (input.trim().equalsIgnoreCase("test-refuse") || input.trim().equalsIgnoreCase("test-parallel")) {
                        sendTestCommand(input.trim());
                        continue;
                    }
                    if (input.trim().toLowerCase().startsWith("kill ")) {
                        sendKillCommand(input.trim().substring("kill ".length()).trim());
                        continue;
                    }

                    String cleanInput = input.replaceAll("\\s+", "");
                    if (cleanInput.matches("\\d+,\\d+")) {
                        sendRequest(cleanInput);
                        continue;
                    }
                    System.out.println("[INFO] Invalid format.");
                }
            }
            scanner.close();
        });

        consoleReader.start();
    }

    private void sendRequest(String content) {
        ACLMessage request = new ACLMessage(ACLMessage.REQUEST);
        request.addReceiver(new AID("customer", AID.ISLOCALNAME));
        request.setLanguage("sum");
        request.setContent(content);

        currentConvId = UUID.randomUUID().toString();
        request.setConversationId(currentConvId);
        requestDeadline = System.currentTimeMillis() + RESPONSE_TIMEOUT;
        send(request);

        if (!ClientAgent.monitorMode) {
            LogUtil.client("Request '" + content + "' sent to CUSTOMER. Waiting for result...");
        }
    }

    private void sendTestCommand(String command) {
        ACLMessage request = new ACLMessage(ACLMessage.REQUEST);
        request.addReceiver(new AID("customer", AID.ISLOCALNAME));
        request.setLanguage(command);

        if (command.equalsIgnoreCase("test-refuse")) {
            request.setContent("1,10;11,20");
        } else if (command.equalsIgnoreCase("test-parallel")) {
            request.setContent("1,100;200,300");
        } else {
            return;
        }

        currentConvId = UUID.randomUUID().toString();
        request.setConversationId(currentConvId);
        requestDeadline = System.currentTimeMillis() + RESPONSE_TIMEOUT;
        send(request);

        LogUtil.client("Sent '" + command + "' command to CUSTOMER.");
    }

    private void sendKillCommand(String coordName) {
        if (coordName == null || coordName.isEmpty()) {
            System.out.println("[ERROR] Usage: kill <coordName>  (e.g. kill coord-calc1)");
            return;
        }
        ACLMessage kill = new ACLMessage(ACLMessage.REQUEST);
        kill.addReceiver(new AID(coordName, AID.ISLOCALNAME));
        kill.setLanguage(KILL_LANG);
        kill.setContent(coordName);
        send(kill);
        LogUtil.client("KILL-SWITCH sent to " + coordName.toUpperCase() + ". Expect re-election.");
    }

    @Override
    protected void takeDown() {
        if (monitorMode) {
            LogUtil.sys("CLIENT", "Successfully terminated.");
        }
    }
}