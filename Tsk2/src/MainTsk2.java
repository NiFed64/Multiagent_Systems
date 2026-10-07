// ВНИМАНИЕ: Здесь НЕТ строки package agents;

import jade.core.ProfileImpl;
import jade.core.Runtime;
import jade.wrapper.AgentContainer;
import jade.wrapper.AgentController;

public class MainTsk2 {
    public static void main(String[] args) {
        try {
            Runtime runtime = Runtime.instance();
            ProfileImpl profile = new ProfileImpl(false);
            profile.setParameter(ProfileImpl.GUI, "true");
            
            AgentContainer container = runtime.createMainContainer(profile);
            
            // Создаем узлы графа (указываем пакет "agents.")
            AgentController nodeA = container.createNewAgent("NodeA", "agents.GraphNodeAgent", new Object[]{"NodeB", "NodeC"});
            AgentController nodeB = container.createNewAgent("NodeB", "agents.GraphNodeAgent", new Object[]{"NodeA", "NodeD"});
            AgentController nodeC = container.createNewAgent("NodeC", "agents.GraphNodeAgent", new Object[]{"NodeA", "NodeD"});
            AgentController nodeD = container.createNewAgent("NodeD", "agents.GraphNodeAgent", new Object[]{"NodeB", "NodeC"});
            
            nodeA.start(); nodeB.start(); nodeC.start(); nodeD.start();

            // Создаем клиента графа (указываем пакет "agents.")
            AgentController client = container.createNewAgent("GraphClient", "agents.GraphClientAgent", new Object[]{});
            client.start();
            
            System.out.println(" Task 2 (P2P Graph) launched.");
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}