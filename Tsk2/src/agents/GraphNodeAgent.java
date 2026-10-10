package agents;

import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GraphNodeAgent extends Agent {

    private List<AID> neighbors = new ArrayList<>();
    private Map<String, SearchContext> activeSearches = new HashMap<>();

    private class SearchContext {
        AID requester;
        int expectedReplies;
        String foundPath;

        SearchContext(AID requester) {
            this.requester = requester;
            this.expectedReplies = 0;
            this.foundPath = null;
        }
    }

    @Override
    protected void setup() {
        String name = getLocalName().toUpperCase();
        System.out.println("[SYSTEM] " + name + ": Initialized.");

        Object[] args = getArguments();
        if (args != null) {
            for (Object arg : args) {
                neighbors.add(new AID((String) arg, AID.ISLOCALNAME));
            }
        }
        System.out.println("[INFO] " + name + ": My neighbors are " + neighbors);

        // 1. Поведение для обработки входящих REQUEST (поиск маршрута)
        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                MessageTemplate mt = MessageTemplate.and(
                        MessageTemplate.MatchPerformative(ACLMessage.REQUEST),
                        MessageTemplate.MatchLanguage("graph-routing")
                );
                ACLMessage msg = receive(mt);
                if (msg == null) {
                    block();
                } else {
                    handleRouteRequest(msg);
                }
            }
        });

        // 2. Поведение для обработки ответов CONFIRM и DISCONFIRM от соседей (ИСПРАВЛЕНО)
        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                // Создаем корректный MessageTemplate вместо лямбда-выражения
                MessageTemplate mt = MessageTemplate.and(
                        MessageTemplate.or(
                                MessageTemplate.MatchPerformative(ACLMessage.CONFIRM),
                                MessageTemplate.MatchPerformative(ACLMessage.DISCONFIRM)
                        ),
                        MessageTemplate.MatchLanguage("graph-routing")
                );
                
                ACLMessage msg = receive(mt);
                
                if (msg == null) {
                    block();
                } else {
                    // Дополнительная проверка: относится ли сообщение к нашим активным поискам
                    if (msg.getConversationId() != null && activeSearches.containsKey(msg.getConversationId())) {
                        handleRouteReply(msg);
                    } else {
                        // Игнорируем сообщения с неизвестным ConversationId (защита от мусора)
                        System.out.println("[INFO] " + getLocalName().toUpperCase() + ": Ignored message with unknown ConversationId.");
                    }
                }
            }
        });
    }

    private void handleRouteRequest(ACLMessage msg) {
        String myName = getLocalName();
        String content = msg.getContent();
        String[] parts = content.split(";");
        
        String searchId = parts[0];
        String destination = parts[1];
        String pathSoFar = parts.length > 2 ? parts[2] : "";

        System.out.println("[INFO] " + myName.toUpperCase() + ": Received route request to " + destination + ". Path: [" + pathSoFar + "]");

        SearchContext ctx = new SearchContext(msg.getSender());
        activeSearches.put(searchId, ctx);

        // Сценарий 1: Мы - конечная цель!
        if (destination.equals(myName)) {
            String finalPath = pathSoFar.isEmpty() ? myName : pathSoFar + "," + myName;
            System.out.println("[SUCCESS] " + myName.toUpperCase() + ": I am the destination! Replying to " + msg.getSender().getLocalName().toUpperCase());
            
            ACLMessage reply = msg.createReply();
            reply.setPerformative(ACLMessage.CONFIRM);
            reply.setLanguage("graph-routing");
            reply.setContent(searchId + ";" + finalPath);
            send(reply);
            
            activeSearches.remove(searchId);
            return;
        }

        // Сценарий 2: Защита от зацикливания (точная проверка по словам)
        if (!pathSoFar.isEmpty()) {
            List<String> pathNodes = Arrays.asList(pathSoFar.split(","));
            if (pathNodes.contains(myName)) {
                System.out.println("[INFO] " + myName.toUpperCase() + ": Loop detected. Replying DISCONFIRM.");
                ACLMessage reply = msg.createReply();
                reply.setPerformative(ACLMessage.DISCONFIRM);
                reply.setContent(searchId);
                send(reply);
                activeSearches.remove(searchId);
                return;
            }
        }

        // Сценарий 3: Пересылаем запрос соседям
        String newPath = pathSoFar.isEmpty() ? myName : pathSoFar + "," + myName;
        
        for (AID neighbor : neighbors) {
            List<String> pathNodes = Arrays.asList(newPath.split(","));
            if (!pathNodes.contains(neighbor.getLocalName())) {
                ACLMessage fwd = new ACLMessage(ACLMessage.REQUEST);
                fwd.addReceiver(neighbor);
                fwd.setLanguage("graph-routing");
                fwd.setContent(searchId + ";" + destination + ";" + newPath);
                fwd.setConversationId(searchId);
                send(fwd);
                ctx.expectedReplies++;
                System.out.println("[INFO] " + myName.toUpperCase() + ": Forwarded to " + neighbor.getLocalName().toUpperCase());
            }
        }

        // Если некому пересылать (тупик), сразу отвечаем отказом
        if (ctx.expectedReplies == 0) {
            System.out.println("[INFO] " + myName.toUpperCase() + ": Dead end. Replying DISCONFIRM.");
            ACLMessage reply = msg.createReply();
            reply.setPerformative(ACLMessage.DISCONFIRM);
            reply.setContent(searchId);
            send(reply);
            activeSearches.remove(searchId);
        }
    }

    private void handleRouteReply(ACLMessage msg) {
        String myName = getLocalName();
        String searchId = msg.getConversationId();
        SearchContext ctx = activeSearches.get(searchId);

        if (ctx == null) return;

        if (msg.getPerformative() == ACLMessage.CONFIRM) {
            String path = msg.getContent().split(";")[1];
            System.out.println("[SUCCESS] " + myName.toUpperCase() + ": Received CONFIRM from " + msg.getSender().getLocalName().toUpperCase() + ". Path: " + path);
            
            // Если мы еще не находили путь, сохраняем и пересылаем дальше "вверх"
            if (ctx.foundPath == null) {
                ctx.foundPath = path;
                ACLMessage reply = new ACLMessage(ACLMessage.CONFIRM);
                reply.addReceiver(ctx.requester);
                reply.setLanguage("graph-routing");
                reply.setContent(searchId + ";" + path);
                reply.setConversationId(searchId);
                send(reply);
            }
        } else { // DISCONFIRM
            System.out.println("[INFO] " + myName.toUpperCase() + ": Received DISCONFIRM from " + msg.getSender().getLocalName().toUpperCase());
        }

        ctx.expectedReplies--;

        // Если все соседи ответили, а путь так и не найден -> отправляем DISCONFIRM "вверх"
        if (ctx.expectedReplies == 0 && ctx.foundPath == null) {
            System.out.println("[ERROR] " + myName.toUpperCase() + ": All branches failed. Replying DISCONFIRM to " + ctx.requester.getLocalName().toUpperCase());
            ACLMessage reply = new ACLMessage(ACLMessage.DISCONFIRM);
            reply.addReceiver(ctx.requester);
            reply.setLanguage("graph-routing");
            reply.setContent(searchId);
            reply.setConversationId(searchId);
            send(reply);
            activeSearches.remove(searchId);
        } else if (ctx.expectedReplies == 0) {
            // Путь найден, все ответы собраны, очищаем память
            activeSearches.remove(searchId);
        }
    }

    @Override
    protected void takeDown() {
        System.out.println("[SYSTEM] " + getLocalName().toUpperCase() + ": Terminated.");
    }
}