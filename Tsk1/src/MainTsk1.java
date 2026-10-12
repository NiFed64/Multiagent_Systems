
import jade.core.ProfileImpl;
import jade.core.Runtime;
import jade.wrapper.AgentContainer;
import jade.wrapper.AgentController;

public class MainTsk1 {
    public static void main(String[] args) {
        try {
            Runtime runtime = Runtime.instance();
            ProfileImpl profile = new ProfileImpl(false);
            profile.setParameter(ProfileImpl.GUI, "true");
            
            AgentContainer container = runtime.createMainContainer(profile);
            
            // 1. Запускаем 3 Вычислителей
            AgentController calc1 = container.createNewAgent("calc1", "agents.CalculatorAgent", new Object[]{});
            AgentController calc2 = container.createNewAgent("calc2", "agents.CalculatorAgent", new Object[]{});
            AgentController calc3 = container.createNewAgent("calc3", "agents.CalculatorAgent", new Object[]{});
            calc1.start(); calc2.start(); calc3.start();
            
            // 2. Запускаем ДВУХ Координаторов (для демонстрации параллельной работы по Заданию 1г)
            AgentController coord1 = container.createNewAgent("coord1", "agents.CoordinatorAgent", new Object[]{});
            AgentController coord2 = container.createNewAgent("coord2", "agents.CoordinatorAgent", new Object[]{});
            coord1.start(); coord2.start();

            // 3. Запускаем Клиента (для ручного ввода, будет общаться с coord1)
            AgentController client = container.createNewAgent("client", "agents.ClientAgent", new Object[]{});
            client.start();
            
            // 4. Запускаем Заказчика (найдет обоих координаторов через DF и нагрузит их)
            AgentController customer = container.createNewAgent("customer", "agents.CustomerAgent", new Object[]{});
            customer.start();
            
            System.out.println(" Task 1г launched. 2 Coordinators are ready for parallel stress-test.");
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}