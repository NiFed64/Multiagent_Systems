import jade.core.ProfileImpl;
import jade.core.Runtime;
import jade.wrapper.AgentContainer;
import jade.wrapper.AgentController;

public class AgentContainerTsk1 {
    public static void main(String[] args) {
        try {
            Runtime runtime = Runtime.instance();

            ProfileImpl profile = new ProfileImpl();
            profile.setParameter(ProfileImpl.MAIN_HOST, "localhost");
            profile.setParameter(ProfileImpl.MAIN_PORT, "1099");
            profile.setParameter(ProfileImpl.CONTAINER_NAME, "Container-1");

            AgentContainer agentContainer = runtime.createAgentContainer(profile);

            System.out.println("==================================================");
            System.out.println("[AGENT] Peripheral container 'Container-1' joined the platform.");
            System.out.println("[AGENT] Launching calculators WITHOUT predefined coordinators.");
            System.out.println("[AGENT] Calculators will elect a coordinator pool.");
            System.out.println("[AGENT] Default pool size: 2 coordinators.");
            System.out.println("==================================================");

            // Вычислители знают только друг друга.
            // Координаторы будут созданы победителями выборов.
            AgentController calc1 = agentContainer.createNewAgent(
                    "calc1",
                    "agents.CalculatorAgent",
                    new Object[]{"calc2", "calc3"}
            );

            AgentController calc2 = agentContainer.createNewAgent(
                    "calc2",
                    "agents.CalculatorAgent",
                    new Object[]{"calc1", "calc3"}
            );

            AgentController calc3 = agentContainer.createNewAgent(
                    "calc3",
                    "agents.CalculatorAgent",
                    new Object[]{"calc1", "calc2"}
            );

            calc1.start();
            calc2.start();
            calc3.start();

            AgentController client = agentContainer.createNewAgent(
                    "client",
                    "agents.ClientAgent",
                    new Object[]{}
            );

            AgentController customer = agentContainer.createNewAgent(
                    "customer",
                    "agents.CustomerAgent",
                    new Object[]{}
            );

            client.start();
            customer.start();

            System.out.println("[AGENT] Base agents launched.");
            System.out.println("[AGENT] Wait a few seconds for election.");
            System.out.println("[AGENT] Then use THIS terminal:");
            System.out.println("[AGENT]   test-refuse");
            System.out.println("[AGENT]   test-parallel");
            System.out.println("[AGENT]   1");
            System.out.println("[AGENT]   2");
            System.out.println("[AGENT]   A,B");

            Thread.currentThread().join();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}