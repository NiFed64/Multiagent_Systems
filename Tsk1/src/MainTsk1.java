// ВНИМАНИЕ: Здесь НЕТ строки package agents;

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
            
            // Создаем вычислителей (указываем пакет "agents.")
            AgentController calc1 = container.createNewAgent("calc1", "agents.CalculatorAgent", new Object[]{});
            AgentController calc2 = container.createNewAgent("calc2", "agents.CalculatorAgent", new Object[]{});
            AgentController calc3 = container.createNewAgent("calc3", "agents.CalculatorAgent", new Object[]{});
            
            calc1.start(); calc2.start(); calc3.start();
            
            // Создаем координатора (указываем пакет "agents.")
            AgentController coord = container.createNewAgent("coordinator", "agents.CoordinatorAgent", new Object[]{});
            coord.start();

            // Создаем клиента (указываем пакет "agents.")
            AgentController client = container.createNewAgent("client", "agents.ClientAgent", new Object[]{});
            client.start();
            
            System.out.println(" Task 1 launched. Agents are registered in DF.");
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}