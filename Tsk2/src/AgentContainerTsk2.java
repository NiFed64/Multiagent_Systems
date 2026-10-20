import jade.core.ProfileImpl;
import jade.core.Runtime;
import jade.wrapper.AgentContainer;
import jade.wrapper.AgentController;

public class AgentContainerTsk2 {
    public static void main(String[] args) {
        try {
            Runtime runtime = Runtime.instance();

            ProfileImpl profile = new ProfileImpl();

            // Подключаемся к главному контейнеру Tsk2
            profile.setParameter(ProfileImpl.MAIN_HOST, "localhost");
            profile.setParameter(ProfileImpl.MAIN_PORT, "1098");

            // Имя периферийного контейнера. Будет видно в RMA.
            profile.setParameter(ProfileImpl.CONTAINER_NAME, "GraphContainer-1");

            AgentContainer agentContainer = runtime.createAgentContainer(profile);

            System.out.println("==================================================");
            System.out.println("[AGENT-TSK2] Peripheral container 'GraphContainer-1' joined the platform.");
            System.out.println("[AGENT-TSK2] Launching cyclic graph agents ...");
            System.out.println("==================================================");

            // ============================================================
            // Циклический граф:
            //
            //      NodeA
            //     /     \
            //  NodeB   NodeD
            //     \     /
            //      NodeC
            //
            // Фактически кольцо:
            // NodeA <-> NodeB <-> NodeC <-> NodeD <-> NodeA
            // ============================================================

            AgentController nodeA = agentContainer.createNewAgent(
                    "NodeA",
                    "agents.GraphNodeAgent",
                    new Object[]{"NodeB", "NodeD"}
            );

            AgentController nodeB = agentContainer.createNewAgent(
                    "NodeB",
                    "agents.GraphNodeAgent",
                    new Object[]{"NodeA", "NodeC"}
            );

            AgentController nodeC = agentContainer.createNewAgent(
                    "NodeC",
                    "agents.GraphNodeAgent",
                    new Object[]{"NodeB", "NodeD"}
            );

            AgentController nodeD = agentContainer.createNewAgent(
                    "NodeD",
                    "agents.GraphNodeAgent",
                    new Object[]{"NodeC", "NodeA"}
            );

            nodeA.start();
            nodeB.start();
            nodeC.start();
            nodeD.start();

            AgentController client = agentContainer.createNewAgent(
                    "GraphClient",
                    "agents.GraphClientAgent",
                    new Object[]{}
            );

            client.start();

            System.out.println("[AGENT-TSK2] All cyclic graph agents launched in GraphContainer-1.");
            System.out.println("[AGENT-TSK2] Use THIS terminal for commands like:");
            System.out.println("[AGENT-TSK2]   ROUTE NodeC NodeA");
            System.out.println("[AGENT-TSK2]   test-cycle");
            System.out.println("[AGENT-TSK2]   exit");

            // Держим JVM живой, иначе контейнер отвалится от платформы.
            Thread.currentThread().join();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}