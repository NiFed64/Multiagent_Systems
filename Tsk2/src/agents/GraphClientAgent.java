package agents;

import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;

import java.util.Map;
import java.util.Scanner;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class GraphClientAgent extends Agent {

    // searchId -> человекочитаемая подпись запроса
    private final Map<String, String> pendingRoutes = new ConcurrentHashMap<>();

    @Override
    protected void setup() {
        System.out.println("========================================");
        System.out.println("GRAPH CLIENT: Ready to route in cyclic graph.");
        System.out.println("Commands:");
        System.out.println("  ROUTE <TargetNode> <StartNode>");
        System.out.println("  test-cycle");
        System.out.println("  exit");
        System.out.println("Example:");
        System.out.println("  ROUTE NodeC NodeA");
        System.out.println("========================================");

        // ============================================================
        // Приём ответов от графа
        // ============================================================
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

                if (reply == null) {
                    block();
                    return;
                }

                String searchId = reply.getConversationId();
                String label = pendingRoutes.remove(searchId);

                if (label == null) {
                    label = "unknown";
                }

                if (reply.getPerformative() == ACLMessage.CONFIRM) {
                    String[] parts = reply.getContent().split(";");
                    String path = parts.length > 1 ? parts[1] : "";

                    System.out.println("\n ROUTE FOUND [" + label + "]:");
                    System.out.println(path.replace(",", "\n"));
                } else {
                    System.out.println("\n ROUTE NOT FOUND [" + label + "]");
                }
            }
        });

        // ============================================================
        // Чтение команд из консоли
        // ============================================================
        final Agent self = this;

        Thread consoleReader = new Thread(() -> {
            Scanner scanner = new Scanner(System.in);

            while (true) {
                System.out.print("\n[CLIENT] Command: ");
                String input = scanner.nextLine().trim();

                if (input.isEmpty()) {
                    continue;
                }

                if ("exit".equalsIgnoreCase(input)) {
                    self.doDelete();
                    break;
                }

                if ("test-cycle".equalsIgnoreCase(input)) {
                    runCycleTest();
                    continue;
                }

                if (input.toUpperCase().startsWith("ROUTE ")) {
                    String[] parts = input.split("\\s+");

                    if (parts.length != 3) {
                        System.out.println("[ERROR] Invalid format. Use: ROUTE <Target> <Start>");
                        continue;
                    }

                    String target = parts[1];
                    String start = parts[2];

                    sendRoute(target, start);
                    continue;
                }

                System.out.println("[INFO] Unknown command. Try: ROUTE <Target> <Start>, test-cycle, exit.");
            }

            scanner.close();
        });

        consoleReader.start();
    }

    private void sendRoute(String target, String start) {
        String searchId = UUID.randomUUID().toString();

        pendingRoutes.put(searchId, start + " -> " + target);

        ACLMessage request = new ACLMessage(ACLMessage.REQUEST);
        request.addReceiver(new AID(start, AID.ISLOCALNAME));
        request.setLanguage("graph-routing");
        request.setConversationId(searchId);

        // Формат: searchId;destination;pathSoFar
        // Для стартового узла путь пока пустой.
        request.setContent(searchId + ";" + target + ";");

        send(request);

        System.out.println("[CLIENT] Sent ROUTE " + target.toUpperCase()
                + " from " + start.toUpperCase()
                + " (id=" + shortId(searchId) + ")");
    }

    private void runCycleTest() {
        System.out.println("\n[TEST-CYCLE] Starting cyclic graph demonstration...");

        // В кольцевом графе между этими узлами есть два разных пути.
        sendRoute("NodeC", "NodeA");

        sleep(300);

        sendRoute("NodeD", "NodeB");

        sleep(300);

        // Несуществующий узел: должен вернуться DISCONFIRM,
        // но система не должна зависнуть.
        sendRoute("NodeX", "NodeA");

        System.out.println("[TEST-CYCLE] Requests sent. Waiting for answers...");
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String shortId(String id) {
        if (id == null || id.isEmpty()) {
            return "null";
        }
        return id.length() <= 8 ? id : id.substring(0, 8);
    }
}