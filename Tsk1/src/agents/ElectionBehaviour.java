package agents;

import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.Behaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.StringJoiner;
import java.util.concurrent.ThreadLocalRandom;

/**
 * ЗАДАЧА 1ж:
 * Отдельное поведение выборов координатора(ов).
 *
 * Чтобы не ломать задачу 1г, выбирается не один координатор,
 * а пул координаторов: top-K узлов по случайному приоритету.
 */
public class ElectionBehaviour extends Behaviour {

    private static final String LANGUAGE = "election";

    private static final int STATE_COLLECT = 0;
    private static final int STATE_WAIT_ELECTED = 1;
    private static final int STATE_DONE = 2;

    private final String myName;
    private final int requestedCoordinators;

    private final List<AID> peers = new ArrayList<>();
    private final Map<String, Candidate> candidates = new HashMap<>();
    private final Random random = new Random();

    private final MessageTemplate mt = MessageTemplate.MatchLanguage(LANGUAGE);

    private long priority;
    private int desiredCoordinators;

    private int state = STATE_COLLECT;
    private long deadline;

    private boolean finished = false;
    private boolean spawned = false;

    private String winnerNames = "";

    private static class Candidate {
        final String name;
        final AID aid;
        final long priority;

        Candidate(String name, AID aid, long priority) {
            this.name = name;
            this.aid = aid;
            this.priority = priority;
        }
    }

    public ElectionBehaviour(Agent a, String[] peerNames, int requestedCoordinators) {
        super(a);
        this.myName = a.getLocalName();
        this.requestedCoordinators = Math.max(1, requestedCoordinators);

        if (peerNames != null) {
            for (String peer : peerNames) {
                if (peer != null && !peer.isEmpty() && !peer.equals(myName)) {
                    peers.add(new AID(peer, AID.ISLOCALNAME));
                }
            }
        }
    }

    @Override
    public void onStart() {
        priority = ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_000_000_000L);

        candidates.put(myName, new Candidate(myName, myAgent.getAID(), priority));

        if (peers.isEmpty()) {
            discoverPeersFromDF();
        }

        int knownNodes = peers.size() + 1;
        desiredCoordinators = Math.min(requestedCoordinators, knownNodes);
        desiredCoordinators = Math.max(1, desiredCoordinators);

        state = STATE_COLLECT;
        deadline = System.currentTimeMillis() + 1500 + random.nextInt(800);

        broadcastHello();

        LogUtil.net(
                myName.toUpperCase(),
                "ELECTION START priority=" + priority +
                        " desiredCoordinators=" + desiredCoordinators +
                        " peers=" + peersLog()
        );
    }

    @Override
    public void action() {
        ACLMessage msg = myAgent.receive(mt);

        if (msg != null) {
            handleMessage(msg);
        }

        if (finished) {
            return;
        }

        long now = System.currentTimeMillis();

        if (state == STATE_COLLECT) {
            if (now >= deadline) {
                decide();
            } else {
                block(deadline - now);
            }
        } else if (state == STATE_WAIT_ELECTED) {
            if (now >= deadline) {
                emergencyDecide();
            } else {
                block(deadline - now);
            }
        }
    }

    @Override
    public boolean done() {
        return finished;
    }

    private void handleMessage(ACLMessage msg) {
        String content = msg.getContent();

        if (content == null || content.isEmpty()) {
            return;
        }

        String[] parts = content.split(";");

        if (parts.length < 2) {
            return;
        }

        String type = parts[0];

        if ("HELLO".equals(type) && parts.length >= 3) {
            try {
                long p = Long.parseLong(parts[1].trim());
                String name = parts[2].trim();

                if (!candidates.containsKey(name)) {
                    candidates.put(name, new Candidate(name, msg.getSender(), p));

                    LogUtil.debug(
                            myName.toUpperCase(),
                            "ELECTION HELLO from " + name.toUpperCase() + " priority=" + p
                    );

                    // Ответим отправителю нашим приоритетом, чтобы ускорить сходимость.
                    if (state == STATE_COLLECT) {
                        sendHello(msg.getSender());
                    }
                }

            } catch (Exception e) {
                LogUtil.err(
                        myName.toUpperCase(),
                        "ELECTION bad HELLO: " + e.getMessage()
                );
            }

        } else if ("ELECTED".equals(type)) {
            winnerNames = parts[1].trim();

            if (isWinner(winnerNames, myName) && !spawned) {
                spawnCoordinator();
            }

            finished = true;
            state = STATE_DONE;

            LogUtil.net(
                    myName.toUpperCase(),
                    "ELECTION ELECTED received: " + winnerNames.toUpperCase()
            );
        }
    }

    private void decide() {
        List<Candidate> winners = chooseWinners();
        winnerNames = joinNames(winners);

        LogUtil.net(
                myName.toUpperCase(),
                "ELECTION DECIDE winners=" + winnerNames.toUpperCase()
        );

        if (isWinner(winnerNames, myName)) {
            if (spawnCoordinator()) {
                sendElected();
                finished = true;
                state = STATE_DONE;
            } else {
                // Если не удалось создать координатора, подождём других победителей.
                state = STATE_WAIT_ELECTED;
                deadline = System.currentTimeMillis() + 2500 + random.nextInt(1000);

                LogUtil.err(
                        myName.toUpperCase(),
                        "ELECTION spawn failed. Waiting for other winners."
                );
            }
        } else {
            state = STATE_WAIT_ELECTED;
            deadline = System.currentTimeMillis() + 2500 + random.nextInt(1000);

            LogUtil.net(
                    myName.toUpperCase(),
                    "ELECTION waiting for ELECTED message. Expected winners=" + winnerNames.toUpperCase()
            );
        }
    }

    private void emergencyDecide() {
        if (hasAnyCoordinatorInDF()) {
            finished = true;
            state = STATE_DONE;

            LogUtil.net(
                    myName.toUpperCase(),
                    "ELECTION timeout, but coordinator(s) already exist in DF. Finish."
            );

            return;
        }

        LogUtil.net(
                myName.toUpperCase(),
                "ELECTION timeout and no coordinator in DF. Emergency self-appointment."
        );

        spawnCoordinator();
        sendElected();

        finished = true;
        state = STATE_DONE;
    }

    private List<Candidate> chooseWinners() {
        List<Candidate> list = new ArrayList<>(candidates.values());

        // Shuffle нужен для случайного разрешения ничьих по приоритету.
        Collections.shuffle(list, random);

        // Stable sort: при равных приоритетах сохранится случайный порядок после shuffle.
        list.sort((a, b) -> Long.compare(b.priority, a.priority));

        int k = Math.min(desiredCoordinators, list.size());
        return new ArrayList<>(list.subList(0, k));
    }

    private boolean spawnCoordinator() {
        if (spawned) {
            return true;
        }

        String coordinatorName = "coord-" + myName;

        if (hasCoordinatorNamed(coordinatorName)) {
            spawned = true;

            LogUtil.cn(
                    myName.toUpperCase(),
                    "ELECTION coordinator " + coordinatorName.toUpperCase() + " already exists."
            );

            return true;
        }

        try {
            myAgent.getContainerController()
                    .createNewAgent(coordinatorName, "agents.CoordinatorAgent", new Object[]{})
                    .start();

            spawned = true;

            LogUtil.cn(
                    myName.toUpperCase(),
                    "ELECTION won. Spawned coordinator " + coordinatorName.toUpperCase()
            );

            return true;

        } catch (Exception e) {
            LogUtil.err(
                    myName.toUpperCase(),
                    "ELECTION coordinator spawn failed: " + e.getMessage()
            );

            return false;
        }
    }

    private void broadcastHello() {
        if (peers.isEmpty()) {
            return;
        }

        ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
        msg.setLanguage(LANGUAGE);
        msg.setContent("HELLO;" + priority + ";" + myName);

        for (AID peer : peers) {
            msg.addReceiver(peer);
        }

        myAgent.send(msg);
    }

    private void sendHello(AID target) {
        if (target == null) {
            return;
        }

        String targetName = target.getLocalName();

        if (targetName == null || targetName.equals(myName)) {
            return;
        }

        ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
        msg.setLanguage(LANGUAGE);
        msg.setContent("HELLO;" + priority + ";" + myName);
        msg.addReceiver(target);

        myAgent.send(msg);
    }

    private void sendElected() {
        if (winnerNames == null || winnerNames.isEmpty()) {
            return;
        }

        ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
        msg.setLanguage(LANGUAGE);
        msg.setContent("ELECTED;" + winnerNames);

        boolean hasReceiver = false;

        for (Candidate c : candidates.values()) {
            if (!c.name.equals(myName)) {
                msg.addReceiver(c.aid);
                hasReceiver = true;
            }
        }

        if (hasReceiver) {
            myAgent.send(msg);

            LogUtil.net(
                    myName.toUpperCase(),
                    "ELECTION ELECTED sent: " + winnerNames.toUpperCase()
            );
        }
    }

    private boolean isWinner(String winnersCsv, String name) {
        if (winnersCsv == null || winnersCsv.isEmpty()) {
            return false;
        }

        for (String w : winnersCsv.split(",")) {
            if (w.trim().equalsIgnoreCase(name)) {
                return true;
            }
        }

        return false;
    }

    private String joinNames(List<Candidate> winners) {
        StringJoiner sj = new StringJoiner(",");

        for (Candidate c : winners) {
            sj.add(c.name);
        }

        return sj.toString();
    }

    private void discoverPeersFromDF() {
        DFAgentDescription template = new DFAgentDescription();

        ServiceDescription sd = new ServiceDescription();
        sd.setType("sum-calculation");
        template.addServices(sd);

        try {
            DFAgentDescription[] result = DFService.search(myAgent, template);

            if (result == null) {
                return;
            }

            for (DFAgentDescription dfd : result) {
                AID aid = dfd.getName();

                if (aid == null) {
                    continue;
                }

                String name = aid.getLocalName();

                if (name == null || name.equals(myName)) {
                    continue;
                }

                boolean already = false;

                for (AID peer : peers) {
                    if (name.equals(peer.getLocalName())) {
                        already = true;
                        break;
                    }
                }

                if (!already) {
                    peers.add(aid);
                }
            }

        } catch (Exception e) {
            LogUtil.err(
                    myName.toUpperCase(),
                    "ELECTION DF peer discovery failed: " + e.getMessage()
            );
        }
    }

    private boolean hasAnyCoordinatorInDF() {
        return findCoordinatorsInDF().length > 0;
    }

    private boolean hasCoordinatorNamed(String coordinatorName) {
        DFAgentDescription[] coordinators = findCoordinatorsInDF();

        for (DFAgentDescription dfd : coordinators) {
            AID aid = dfd.getName();

            if (aid != null && coordinatorName.equalsIgnoreCase(aid.getLocalName())) {
                return true;
            }
        }

        return false;
    }

    private DFAgentDescription[] findCoordinatorsInDF() {
        DFAgentDescription template = new DFAgentDescription();

        ServiceDescription sd = new ServiceDescription();
        sd.setType("coordination-service");
        template.addServices(sd);

        try {
            DFAgentDescription[] result = DFService.search(myAgent, template);
            return result == null ? new DFAgentDescription[0] : result;

        } catch (Exception e) {
            return new DFAgentDescription[0];
        }
    }

    private String peersLog() {
        StringBuilder sb = new StringBuilder("[");

        for (int i = 0; i < peers.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }

            sb.append(peers.get(i).getLocalName().toUpperCase());
        }

        sb.append("]");

        return sb.toString();
    }
}