import jade.core.ProfileImpl;
import jade.core.Runtime;
import jade.wrapper.AgentContainer;
import jade.wrapper.AgentController;

public class AgentContainerTsk1 {
    public static void main(String[] args) {
        try {
            Runtime runtime = Runtime.instance();

            // Профиль периферийного контейнера: указываем, К КАКОМУ главному подключаемся
            ProfileImpl profile = new ProfileImpl();
            profile.setParameter(ProfileImpl.MAIN_HOST, "localhost");
            profile.setParameter(ProfileImpl.MAIN_PORT, "1099");
            profile.setParameter(ProfileImpl.CONTAINER_NAME, "Container-1");
            // GUI здесь НЕ включаем — RMA уже есть в главном контейнере

            AgentContainer agentContainer = runtime.createAgentContainer(profile);

            System.out.println("==================================================");
            System.out.println("[AGENT] Peripheral container 'Container-1' joined the platform.");
            System.out.println("[AGENT] Launching agents ...");
            System.out.println("==================================================");

            // --- Вычислители (регистрируются в DF главного контейнера) ---
            AgentController calc1 = agentContainer.createNewAgent("calc1", "agents.CalculatorAgent", new Object[]{});
            AgentController calc2 = agentContainer.createNewAgent("calc2", "agents.CalculatorAgent", new Object[]{});
            AgentController calc3 = agentContainer.createNewAgent("calc3", "agents.CalculatorAgent", new Object[]{});
            calc1.start(); calc2.start(); calc3.start();

            // --- Координаторы (Contract Net Initiator, тоже в DF) ---
            AgentController coord1 = agentContainer.createNewAgent("coord1", "agents.CoordinatorAgent", new Object[]{});
            AgentController coord2 = agentContainer.createNewAgent("coord2", "agents.CoordinatorAgent", new Object[]{});
            coord1.start(); coord2.start();

            // --- Клиент (консольный ввод) ---
            AgentController client = agentContainer.createNewAgent("client", "agents.ClientAgent", new Object[]{});
            client.start();

            // --- Заказчик (диспетчер / агрегатор) ---
            AgentController customer = agentContainer.createNewAgent("customer", "agents.CustomerAgent", new Object[]{});
            customer.start();

            System.out.println("[AGENT] All agents launched in Container-1.");
            System.out.println("[AGENT] Type commands in THIS terminal (client lives here).");

            // Держим процесс живым, иначе JVM выйдет и контейнер отвалится от платформы
            Thread.currentThread().join();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}