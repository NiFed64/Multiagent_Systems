package agents;

import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class GraphNodeAgent extends Agent {

    // Жёсткий предел длины пути.
    // Для цикла из 4 узлов достаточно 8, но можно увеличить для больших графов.
    private static final int MAX_HOPS = 8;

    private final List<AID> neighbors = new ArrayList<>();

    // Активные поиски, которые этот узел сейчас обрабатывает.
    private final Map<String, SearchContext> activeSearches = new HashMap<>();

    // Множество searchId, которые этот узел уже видел.
    // Нужно для циклических графов: один и тот же поиск может прийти
    // в узел двумя разными путями. Обрабатываем только первый приход.
    private final Set<String> seenSearchIds = new HashSet<>();

    private static class SearchContext {
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

        System.out.println("[INFO] " + name + ": Neighbors = " + neighborNames());

        // ============================================================
        // 1. Приём REQUEST от соседей или клиента
        // ============================================================
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

        // ============================================================
        // 2. Приём CONFIRM / DISCONFIRM от соседей
        // ============================================================
        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
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
                } else if (msg.getConversationId() != null
                        && activeSearches.containsKey(msg.getConversationId())) {
                    handleRouteReply(msg);
                }
            }
        });
    }

    private void handleRouteRequest(ACLMessage msg) {
        String myName = getLocalName();
        String upper = myName.toUpperCase();

        String content = msg.getContent();
        if (content == null || content.isEmpty()) {
            return;
        }

        String[] parts = content.split(";");
        if (parts.length < 2) {
            return;
        }

        String searchId = parts[0];
        String destination = parts[1];
        String pathSoFar = parts.length > 2 ? parts[2] : "";

        System.out.println("[INFO] " + upper + ": REQUEST to " + destination.toUpperCase()
                + " path=[" + pathSoFar + "]");

        // ============================================================
        // Защита от повторной обработки одного и того же поиска.
        // ============================================================
        if (!seenSearchIds.add(searchId)) {
            System.out.println("[CYCLE] " + upper + ": Duplicate searchId "
                    + shortId(searchId) + ". DISCONFIRM.");
            sendDisconfirm(msg, searchId);
            return;
        }

        List<String> visited = splitPath(pathSoFar);

        // ============================================================
        // Сценарий 1: мы и есть целевой узел.
        // ============================================================
        if (destination.equals(myName)) {
            String finalPath = appendPath(pathSoFar, myName);
            System.out.println("[SUCCESS] " + upper + ": Destination reached. Path=" + finalPath);
            sendConfirm(msg, searchId, finalPath);
            return;
        }

        // ============================================================
        // Сценарий 2: мы уже были в этом пути.
        // Это прямая защита от цикла.
        // ============================================================
        if (visited.contains(myName)) {
            System.out.println("[CYCLE] " + upper + ": Already in path [" + pathSoFar + "]. DISCONFIRM.");
            sendDisconfirm(msg, searchId);
            return;
        }

        // ============================================================
        // Сценарий 3: превышен максимальный размер пути.
        // Страховка от очень больших или некорректных графов.
        // ============================================================
        if (visited.size() >= MAX_HOPS) {
            System.out.println("[CYCLE] " + upper + ": Max hops reached. DISCONFIRM.");
            sendDisconfirm(msg, searchId);
            return;
        }

        // ============================================================
        // Сценарий 4: пересылаем запрос соседям,
        // которые ещё не были в пути.
        // ============================================================
        String newPath = appendPath(pathSoFar, myName);
        List<String> newPathVisited = splitPath(newPath);

        SearchContext ctx = new SearchContext(msg.getSender());

        for (AID neighbor : neighbors) {
            String neighborName = neighbor.getLocalName();

            if (newPathVisited.contains(neighborName)) {
                System.out.println("[CYCLE] " + upper + ": Skip neighbor "
                        + neighborName.toUpperCase() + " already in path.");
                continue;
            }

            ACLMessage fwd = new ACLMessage(ACLMessage.REQUEST);
            fwd.addReceiver(neighbor);
            fwd.setLanguage("graph-routing");
            fwd.setConversationId(searchId);
            fwd.setContent(searchId + ";" + destination + ";" + newPath);
            send(fwd);

            ctx.expectedReplies++;

            System.out.println("[INFO] " + upper + ": Forwarded to "
                    + neighborName.toUpperCase() + " path=[" + newPath + "]");
        }

        // ============================================================
        // Если пересылать некуда — тупик.
        // ============================================================
        if (ctx.expectedReplies == 0) {
            System.out.println("[CYCLE] " + upper + ": Dead end. DISCONFIRM.");
            sendDisconfirm(msg, searchId);
            return;
        }

        activeSearches.put(searchId, ctx);
    }

    private void handleRouteReply(ACLMessage msg) {
        String myName = getLocalName();
        String upper = myName.toUpperCase();

        String searchId = msg.getConversationId();
        SearchContext ctx = activeSearches.get(searchId);

        if (ctx == null) {
            return;
        }

        if (msg.getPerformative() == ACLMessage.CONFIRM) {
            String[] parts = msg.getContent().split(";");
            String path = parts.length > 1 ? parts[1] : "";

            System.out.println("[SUCCESS] " + upper + ": CONFIRM from "
                    + msg.getSender().getLocalName().toUpperCase()
                    + " path=" + path);

            // Отправляем CONFIRM вверх только один раз — первый найденный путь.
            if (ctx.foundPath == null) {
                ctx.foundPath = path;
                sendConfirmUp(ctx.requester, searchId, path);
            }
        } else {
            System.out.println("[INFO] " + upper + ": DISCONFIRM from "
                    + msg.getSender().getLocalName().toUpperCase());
        }

        ctx.expectedReplies--;

        // Когда все соседи ответили, очищаем контекст.
        if (ctx.expectedReplies == 0) {
            if (ctx.foundPath == null) {
                System.out.println("[ERROR] " + upper + ": All branches failed. DISCONFIRM to "
                        + ctx.requester.getLocalName().toUpperCase());
                sendDisconfirmUp(ctx.requester, searchId);
            }

            activeSearches.remove(searchId);
        }
    }

    // ================================================================
    // Вспомогательные методы отправки ответов
    // ================================================================

    private void sendConfirm(ACLMessage original, String searchId, String path) {
        ACLMessage reply = original.createReply();
        reply.setPerformative(ACLMessage.CONFIRM);
        reply.setLanguage("graph-routing");
        reply.setConversationId(searchId);
        reply.setContent(searchId + ";" + path);
        send(reply);
    }

    private void sendConfirmUp(AID receiver, String searchId, String path) {
        ACLMessage reply = new ACLMessage(ACLMessage.CONFIRM);
        reply.addReceiver(receiver);
        reply.setLanguage("graph-routing");
        reply.setConversationId(searchId);
        reply.setContent(searchId + ";" + path);
        send(reply);
    }

    private void sendDisconfirm(ACLMessage original, String searchId) {
        ACLMessage reply = original.createReply();
        reply.setPerformative(ACLMessage.DISCONFIRM);
        reply.setLanguage("graph-routing");
        reply.setConversationId(searchId);
        reply.setContent(searchId);
        send(reply);
    }

    private void sendDisconfirmUp(AID receiver, String searchId) {
        ACLMessage reply = new ACLMessage(ACLMessage.DISCONFIRM);
        reply.addReceiver(receiver);
        reply.setLanguage("graph-routing");
        reply.setConversationId(searchId);
        reply.setContent(searchId);
        send(reply);
    }

    // ================================================================
    // Работа с путём
    // ================================================================

    private List<String> splitPath(String path) {
        List<String> result = new ArrayList<>();

        if (path == null || path.isEmpty()) {
            return result;
        }

        for (String p : path.split(",")) {
            String trimmed = p.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }

        return result;
    }

    private String appendPath(String path, String node) {
        if (path == null || path.isEmpty()) {
            return node;
        }
        return path + "," + node;
    }

    private String neighborNames() {
        StringBuilder sb = new StringBuilder("[");

        for (int i = 0; i < neighbors.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(neighbors.get(i).getLocalName().toUpperCase());
        }

        sb.append("]");
        return sb.toString();
    }

    private String shortId(String id) {
        if (id == null || id.isEmpty()) {
            return "null";
        }
        return id.length() <= 8 ? id : id.substring(0, 8);
    }

    @Override
    protected void takeDown() {
        System.out.println("[SYSTEM] " + getLocalName().toUpperCase() + ": Terminated.");
    }
}