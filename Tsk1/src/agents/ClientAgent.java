package agents;

import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;

import java.util.Scanner;
import java.util.UUID;

public class ClientAgent extends Agent {

    public static volatile boolean monitorMode = true; 
    private int mode = 2; 
    private String currentConvId = null; 

    @Override
    protected void setup() {
        if (monitorMode) System.out.println("\n[SYSTEM] CLIENT: Started and ready.");

        // 1. Поведение для прослушивания ответов
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
                
                if (currentConvId != null) {
                    mt = MessageTemplate.and(mt, MessageTemplate.MatchConversationId(currentConvId));
                }
                
                ACLMessage reply = receive(mt);
                if (reply != null) {
                    if (reply.getPerformative() == ACLMessage.CONFIRM) {
                        System.out.println("\n✅ RESULT: Final sum = " + reply.getContent());
                    } else if (reply.getPerformative() == ACLMessage.REFUSE) {
                        System.out.println("\n❌ RESULT: " + reply.getContent());
                    }
                    currentConvId = null; 
                } else {
                    block();
                }
            }
        });

        final Agent self = this; 
        
        Thread consoleReader = new Thread(() -> {
            Scanner scanner = new Scanner(System.in);
            
            System.out.println("========================================");
            System.out.println(" Initial Mode: MONITOR (System logs)");
            System.out.println(" Commands:");
            System.out.println(" '1' -> Switch to CLIENT MODE");
            System.out.println(" 'test-refuse' -> Test FSM (Task 1v)");
            System.out.println(" 'test-parallel' -> Test Threads (Task 1g)");
            System.out.println("========================================");

            while (true) {
                String input;
                
                if (mode == 2) {
                    System.out.print("\n[MONITOR MODE] Type '1', 'test-refuse', or 'test-parallel': ");
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
                } else {
                    System.out.print("\n[CLIENT MODE] Enter range (A,B), '2' for MONITOR, 'test-refuse', 'test-parallel', or 'exit': ");
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
        
        send(request);
        if (!ClientAgent.monitorMode) {
            System.out.println("[CLIENT] Request '" + content + "' sent to CUSTOMER. Waiting for result...");
        }
    }

    // ИСПРАВЛЕНИЕ: Разный контент для разных тестов
    private void sendTestCommand(String command) {
        ACLMessage request = new ACLMessage(ACLMessage.REQUEST);
        request.addReceiver(new AID("customer", AID.ISLOCALNAME));
        request.setLanguage(command); 
        
        if (command.equalsIgnoreCase("test-refuse")) {
            // Для REFUSE нужно две задачи, чтобы Customer отправил их по очереди на одного координатора
            request.setContent("1,10;11,20"); 
        } else {
            // Для PARALLEL нужна одна задача, которую Customer разошлет обоим координаторам одновременно
            request.setContent("1,10"); 
        }
        
        currentConvId = UUID.randomUUID().toString();
        request.setConversationId(currentConvId);
        
        send(request);
        System.out.println("[CLIENT] Sent '" + command + "' command to CUSTOMER.");
    }

    @Override
    protected void takeDown() {
        if (monitorMode) System.out.println("\n[SYSTEM] CLIENT: Successfully terminated.");
    }
}