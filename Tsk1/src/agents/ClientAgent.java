package agents;

import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;

import java.util.Scanner;

public class ClientAgent extends Agent {

    @Override
    protected void setup() {
        System.out.println("========================================");
        System.out.println("  CLIENT: Ready!");
        System.out.println("Commands:");
        System.out.println("  '1,10'  - Send range request");
        System.out.println("  'test'  - Auto-test REFUSE logic");
        System.out.println("  'exit'  - Quit");
        System.out.println("========================================");

        // 1. Слушаем ответы (теперь и CONFIRM, и REFUSE)
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
                if (reply != null) {
                    if (reply.getPerformative() == ACLMessage.CONFIRM) {
                        System.out.println("\n CONFIRM RECEIVED: Final sum = " + reply.getContent());
                    } else if (reply.getPerformative() == ACLMessage.REFUSE) {
                        System.out.println("\n REFUSE RECEIVED: " + reply.getContent());
                    }
                } else {
                    block();
                }
            }
        });

        // 2. Чтение консоли
        final Agent self = this; 
        Thread consoleReader = new Thread(() -> {
            Scanner scanner = new Scanner(System.in);
            while (true) {
                System.out.print("\n[CLIENT] Enter command: ");
                String input = scanner.nextLine().trim();

                if ("exit".equalsIgnoreCase(input)) {
                    self.doDelete();
                    break;
                }

                if ("test".equalsIgnoreCase(input)) {
                    System.out.println("[TEST] Sending two rapid requests to trigger REFUSE...");
                    sendRequest("1,10");
                    try { Thread.sleep(200); } catch (Exception e) {} // Небольшая задержка
                    sendRequest("11,20"); // Этот запрос должен застать агентов в состоянии BUSY
                    continue;
                }

                if (input.matches("\\d+,\\d+")) {
                    sendRequest(input);
                } else {
                    System.out.println("[INFO] Invalid format. Use 'A,B', 'test' or 'exit'.");
                }
            }
            scanner.close();
        });
        consoleReader.start();
    }

    private void sendRequest(String content) {
        ACLMessage request = new ACLMessage(ACLMessage.REQUEST);
        request.addReceiver(new AID("coordinator", AID.ISLOCALNAME));
        request.setLanguage("sum");
        request.setContent(content);
        send(request);
        System.out.println("[INFO] Request '" + content + "' sent to Coordinator.");
    }
}