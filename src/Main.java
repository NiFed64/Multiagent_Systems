import jade.core.ProfileImpl;
import jade.core.Runtime;
import jade.wrapper.AgentContainer;
import jade.wrapper.AgentController;

public class Main {
    public static void main(String[] args) {
        try {
            Runtime runtime = Runtime.instance();
            ProfileImpl profile = new ProfileImpl(false); 
            profile.setParameter(ProfileImpl.GUI, "true"); 
            
            AgentContainer container = runtime.createMainContainer(profile);
            
            // Запускаем вычислителей (они сами зарегистрируются в DF)
            AgentController calc1 = container.createNewAgent("calc1", "CalculatorAgent", new Object[]{});
            AgentController calc2 = container.createNewAgent("calc2", "CalculatorAgent", new Object[]{});
            AgentController calc3 = container.createNewAgent("calc3", "CalculatorAgent", new Object[]{});
            
            calc1.start();
            calc2.start();
            calc3.start();
            
            // Запускаем координатора БЕЗ аргументов. Он сам найдёт вычислителей в DF.
            AgentController coord = container.createNewAgent("coordinator", "CoordinatorAgent", new Object[]{});
            coord.start();

            // Запускаем клиента для ввода из консоли
            AgentController client = container.createNewAgent("client", "ClientAgent", new Object[]{});
            client.start();
            
            System.out.println("✅ All agents launched. DF (Yellow Pages) is active.");
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}