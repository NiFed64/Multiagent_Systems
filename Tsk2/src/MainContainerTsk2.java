import jade.core.ProfileImpl;
import jade.core.Runtime;
import jade.wrapper.AgentContainer;

public class MainContainerTsk2 {
    public static void main(String[] args) {
        try {
            Runtime runtime = Runtime.instance();

            ProfileImpl profile = new ProfileImpl(false);

            // GUI / RMA поднимаем именно в главном контейнере
            profile.setParameter(ProfileImpl.GUI, "true");

            // Хост и порт главного контейнера.
            // Используем 1098, чтобы не конфликтовать с Tsk1, если он использует 1099.
            profile.setParameter(ProfileImpl.MAIN_HOST, "localhost");
            profile.setParameter(ProfileImpl.MAIN_PORT, "1098");

            AgentContainer mainContainer = runtime.createMainContainer(profile);

            System.out.println("==================================================");
            System.out.println("[MAIN-TSK2] Main container is UP.");
            System.out.println("[MAIN-TSK2] AMS + DF are hosted here.");
            System.out.println("[MAIN-TSK2] RMA window should be opened.");
            System.out.println("[MAIN-TSK2] Waiting for AgentContainerTsk2 on localhost:1098 ...");
            System.out.println("[MAIN-TSK2] Do NOT enter graph commands here.");
            System.out.println("==================================================");

            // Держим JVM живой.
            Thread.currentThread().join();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}