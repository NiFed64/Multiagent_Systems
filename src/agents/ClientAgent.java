import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;

import java.util.Scanner;
import java.util.UUID;

public class ClientAgent extends Agent {
    private String entryNode;

    @Override
    protected void setup() {
        Object[] args = getArguments();
        entryNode = (args != null && args.length > 0) ? (String) args[0] : "A";
        
        System.out.println("\n[SYSTEM] CLIENT: Started. Entry node: " + entryNode.toUpperCase());

        // 1. Прослушивание ответов
        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                MessageTemplate mt = MessageTemplate.and(
                    MessageTemplate.or(
                        MessageTemplate.MatchPerformative(ACLMessage.CONFIRM),
                        MessageTemplate.MatchPerformative(ACLMessage.DISCONFIRM)
                    ),
                    MessageTemplate.MatchLanguage("find-route")
                );
                
                ACLMessage reply = receive(mt);
                if (reply != null) {
                    if (reply.getPerformative() == ACLMessage.CONFIRM) {
                        System.out.println("\n[SUCCESS] CLIENT: Route found!\n" + reply.getContent().replace("\n", " -> "));
                    } else {
                        System.out.println("\n[ERROR] CLIENT: Route not found (DISCONFIRM).");
                    }
                } else {
                    block();
                }
            }
        });

        // 2. Ввод из консоли
        final Agent self = this;
        Thread consoleReader = new Thread(() -> {
            Scanner scanner = new Scanner(System.in);
            int mode = 2; 
            
            System.out.println("========================================");
            System.out.println(" Initial Mode: MONITOR (System logs)");
            System.out.println(" Type '1' -> Switch to CLIENT MODE");
            System.out.println(" Type '2' -> Switch to MONITOR MODE");
            System.out.println("========================================");

            while (true) {
                if (mode == 1) {
                    System.out.print("\n[CLIENT MODE] Enter target node (or 'exit'): ");
                } else {
                    System.out.print("\n[MONITOR MODE] System logs active. Type '1' or '2': ");
                }

                String input = scanner.nextLine().trim();

                if (input.equals("1")) { mode = 1; System.out.println("\n>>> Switched to CLIENT MODE."); continue; }
                if (input.equals("2")) { mode = 2; System.out.println("\n>>> Switched to MONITOR MODE."); continue; }

                if (mode == 1) {
                    if ("exit".equalsIgnoreCase(input)) { self.doDelete(); break; }
                    if (input.isEmpty()) continue;

                    String convId = UUID.randomUUID().toString();
                    ACLMessage request = new ACLMessage(ACLMessage.REQUEST);
                    request.addReceiver(new AID(entryNode, AID.ISLOCALNAME));
                    request.setLanguage("find-route");
                    request.setContent(input);
                    request.setConversationId(convId);
                    
                    self.send(request);
                    System.out.println("[CLIENT] Request sent to " + entryNode.toUpperCase() + " to find " + input.toUpperCase() + ".");
                } else {
                    System.out.println("[INFO] You are in MONITOR MODE. Type '1' to switch.");
                }
            }
            scanner.close();
        });
        consoleReader.start();
    }

    @Override
    protected void takeDown() {
        System.out.println("\n[SYSTEM] CLIENT: Terminated.");
    }
}