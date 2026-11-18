import jade.core.ProfileImpl;
import jade.core.Runtime;
import jade.wrapper.AgentContainer;

public class AgentContainerTsk1 {
    public static void main(String[] args) {
        try {
            Runtime runtime = Runtime.instance();

            ProfileImpl profile = new ProfileImpl();
            profile.setParameter(ProfileImpl.MAIN_HOST, "localhost");
            profile.setParameter(ProfileImpl.MAIN_PORT, "1099");
            profile.setParameter(ProfileImpl.CONTAINER_NAME, "Container-1");
            // SERVICES не задаём: mobility + notification по умолчанию.

            AgentContainer agentContainer = runtime.createAgentContainer(profile);

            System.out.println("==================================================");
            System.out.println("[AGENT-TSK1] Peripheral container 'Container-1' joined the platform.");
            System.out.println("[AGENT-TSK1] NO agents created here.");
            System.out.println("[AGENT-TSK1] Waiting for incoming migrations from Main-Container ...");
            System.out.println("[AGENT-TSK1] After migration completes, use THIS terminal for commands:");
            System.out.println("[AGENT-TSK1]   test-refuse / test-parallel / 1 / 2 / A,B / kill <name>");
            System.out.println("==================================================");

            Thread.currentThread().join();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}