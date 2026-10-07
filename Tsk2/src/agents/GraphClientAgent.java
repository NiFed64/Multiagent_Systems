package agents; 

import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;

import java.util.Scanner;
import java.util.UUID;

public class GraphClientAgent extends Agent {

    @Override
    protected void setup() {
        System.out.println("========================================");
        System.out.println("🖥  GRAPH CLIENT: Ready to route!");
        System.out.println("Format: ROUTE <TargetNode> <StartNode>");
        System.out.println("Example: ROUTE NodeD NodeA");
        System.out.println("Type 'exit' to quit.");
        System.out.println("========================================");

        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                MessageTemplate mt = MessageTemplate.and(
                        MessageTemplate.MatchLanguage("graph-routing"),
                        MessageTemplate.or(
                            MessageTemplate.MatchPerformative(ACLMessage.CONFIRM),
                            MessageTemplate.MatchPerformative(ACLMessage.DISCONFIRM)
                        )
                );
                
                ACLMessage reply = receive(mt);
                if (reply != null) {
                    if (reply.getPerformative() == ACLMessage.CONFIRM) {
                        String path = reply.getContent().split(";")[1];
                        String formattedPath = path.replace(",", "\n"); 
                        System.out.println("\n✅ ROUTE FOUND:\n" + formattedPath);
                    } else {
                        System.out.println("\n❌ ROUTE NOT FOUND (DISCONFIRM).");
                    }
                    System.out.print("\n[CLIENT] Enter command: ");
                } else {
                    block();
                }
            }
        });

        final Agent self = this;
        Thread consoleReader = new Thread(() -> {
            Scanner scanner = new Scanner(System.in);
            while (true) {
                System.out.print("[CLIENT] Enter command: ");
                String input = scanner.nextLine().trim();

                if ("exit".equalsIgnoreCase(input)) {
                    self.doDelete();
                    break;
                }

                if (input.toUpperCase().startsWith("ROUTE ")) {
                    String[] parts = input.split(" ");
                    if (parts.length != 3) {
                        System.out.println("[ERROR] Invalid format. Use: ROUTE <Target> <Start>");
                        continue;
                    }
                    
                    String target = parts[1];
                    String startNode = parts[2];
                    String searchId = UUID.randomUUID().toString();

                    ACLMessage request = new ACLMessage(ACLMessage.REQUEST);
                    request.addReceiver(new AID(startNode, AID.ISLOCALNAME));
                    request.setLanguage("graph-routing");
                    request.setContent(searchId + ";" + target + ";");
                    request.setConversationId(searchId);
                    
                    send(request);
                    System.out.println("[INFO] Route request sent to " + startNode.toUpperCase() + ". Searching for " + target.toUpperCase() + "...");
                } else {
                    System.out.println("[INFO] Unknown command. Try 'ROUTE <Target> <Start>' or 'exit'.");
                }
            }
            scanner.close();
        });
        consoleReader.start();
    }
}