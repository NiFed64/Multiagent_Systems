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
    
    // Храним ID текущего активного запроса для фильтрации ответов
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
                
                // Если есть активный запрос, фильтруем только его
                if (currentConvId != null) {
                    mt = MessageTemplate.and(mt, MessageTemplate.MatchConversationId(currentConvId));
                }
                
                ACLMessage reply = receive(mt);
                if (reply != null) {
                    if (reply.getPerformative() == ACLMessage.CONFIRM) {
                        System.out.println("\n RESULT: Final sum = " + reply.getContent());
                    } else if (reply.getPerformative() == ACLMessage.REFUSE) {
                        System.out.println("\n RESULT: " + reply.getContent());
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
            System.out.println(" Type '1' -> Switch to CLIENT MODE");
            System.out.println(" Type 'test' -> Trigger FSM REFUSE test");
            System.out.println("========================================");

            while (true) {
                String input;
                
                if (mode == 2) {
                    System.out.print("\n[MONITOR MODE] Type '1' for CLIENT, or 'test' to trigger FSM: ");
                    input = scanner.nextLine().trim();

                    if (input.equals("1")) {
                        mode = 1;
                        monitorMode = false;
                        System.out.println("\n>>> Switched to CLIENT MODE. Internal logs hidden.");
                        continue;
                    }
                    if (input.equalsIgnoreCase("test")) {
                        System.out.println("\n[TEST] Sending two rapid requests to demonstrate FSM REFUSE logic...");
                        sendRequest("1,10");
                        try { Thread.sleep(200); } catch (Exception e) {}
                        sendRequest("11,20");
                        continue;
                    }
                } else {
                    System.out.print("\n[CLIENT MODE] Enter range (A,B), '2' for MONITOR, or 'exit': ");
                    input = scanner.nextLine();

                    if (input.trim().equals("2")) {
                        mode = 2;
                        monitorMode = true;
                        System.out.println("\n>>> Switched to MONITOR MODE. Internal logs visible.");
                        continue;
                    }
                    if (input.trim().equalsIgnoreCase("exit")) {
                        self.doDelete();
                        break;
                    }
                    
                    String cleanInput = input.replaceAll("\\s+", "");
                    if (cleanInput.matches("\\d+,\\d+")) {
                        sendRequest(cleanInput);
                        continue;
                    }
                    
                    System.out.println("[INFO] Invalid format. Use 'A,B' (spaces allowed), '2' or 'exit'.");
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
        
        // ГЕНЕРИРУЕМ УНИКАЛЬНЫЙ ID ДЛЯ ЭТОГО ЗАПРОСА
        currentConvId = UUID.randomUUID().toString();
        request.setConversationId(currentConvId);
        
        send(request);
        
        if (monitorMode) {
            System.out.println("[INFO] Request '" + content + "' sent with ConvID: " + currentConvId);
        } else {
            System.out.println("[CLIENT] Request '" + content + "' sent. Waiting for result...");
        }
    }

    @Override
    protected void takeDown() {
        if (monitorMode) {
            System.out.println("\n[SYSTEM] CLIENT: Successfully terminated.");
        }
    }
}