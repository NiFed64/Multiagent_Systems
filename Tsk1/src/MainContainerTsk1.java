import jade.core.ProfileImpl;
import jade.core.Runtime;
import jade.wrapper.AgentContainer;
import jade.wrapper.AgentController;

public class MainContainerTsk1 {
    public static void main(String[] args) {
        try {
            Runtime runtime = Runtime.instance();

            ProfileImpl profile = new ProfileImpl(false);
            profile.setParameter(ProfileImpl.GUI, "true");          // RMA здесь
            profile.setParameter(ProfileImpl.MAIN_HOST, "localhost");
            profile.setParameter(ProfileImpl.MAIN_PORT, "1099");
            // SERVICES не задаём: AgentMobilityService включён по умолчанию (книга, 6.2/3.6).

            AgentContainer mainContainer = runtime.createMainContainer(profile);

            System.out.println("==================================================");
            System.out.println("[MAIN-TSK1] Main container is UP (AMS + DF + RMA).");
            System.out.println("[MAIN-TSK1] Launching agents HERE (they will migrate to Container-1).");
            System.out.println("[MAIN-TSK1] Now start AgentContainerTsk1 in a SECOND terminal.");
            System.out.println("==================================================");

            // Аргумент [0] — целевой контейнер миграции; далее — peers (для выборов).
            AgentController calc1 = mainContainer.createNewAgent(
                    "calc1", "agents.CalculatorAgent",
                    new Object[]{"Container-1", "calc2", "calc3"});
            AgentController calc2 = mainContainer.createNewAgent(
                    "calc2", "agents.CalculatorAgent",
                    new Object[]{"Container-1", "calc1", "calc3"});
            AgentController calc3 = mainContainer.createNewAgent(
                    "calc3", "agents.CalculatorAgent",
                    new Object[]{"Container-1", "calc1", "calc2"});

            AgentController client = mainContainer.createNewAgent(
                    "client", "agents.ClientAgent",
                    new Object[]{"Container-1"});

            AgentController customer = mainContainer.createNewAgent(
                    "customer", "agents.CustomerAgent",
                    new Object[]{"Container-1"});

            calc1.start(); calc2.start(); calc3.start();
            client.start(); customer.start();

            System.out.println("[MAIN-TSK1] Agents born in Main-Container. "
                    + "They are waiting for Container-1 to appear, then migrate.");
            System.out.println("[MAIN-TSK1] Watch the migration in RMA.");

            Thread.currentThread().join();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}