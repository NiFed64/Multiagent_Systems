import jade.core.ProfileImpl;
import jade.core.Runtime;
import jade.wrapper.AgentContainer;

public class MainContainerTsk1 {
    public static void main(String[] args) {
        try {
            Runtime runtime = Runtime.instance();

            // false => контейнер НЕ изолирован, доступен извне для подключения периферийных
            ProfileImpl profile = new ProfileImpl(false);
            profile.setParameter(ProfileImpl.GUI, "true");      // RMA в этом процессе
            profile.setParameter(ProfileImpl.MAIN_HOST, "localhost");
            profile.setParameter(ProfileImpl.MAIN_PORT, "1099"); // порт RMI-registry по умолчанию

            AgentContainer mainContainer = runtime.createMainContainer(profile);

            System.out.println("==================================================");
            System.out.println("[MAIN] Main container is UP.");
            System.out.println("[MAIN] AMS + DF hosted here. RMA window opened.");
            System.out.println("[MAIN] Waiting for agent containers on localhost:1099 ...");
            System.out.println("[MAIN] Now start AgentContainerTsk1 in a SECOND terminal.");
            System.out.println("==================================================");

            // Держим процесс живым: main-контейнер сам по себе не завершается,
            // но явный wait страховка от преждевременного выхода JVM в некоторых средах.
            Thread.currentThread().join();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}