package agents;

import jade.core.Agent;
import jade.core.ContainerID;
import jade.core.Location;
import jade.core.behaviours.Behaviour;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * ЗАДАЧА 1и: поведение миграции из главного контейнера в агентный.
 *
 * ВАЖНО:
 * doMove() в JADE асинхронный. Он не бросает исключение, если назначение
 * временно отсутствует. Мобильный сервис может откатить агента обратно
 * в исходный контейнер. Поэтому успех миграции нельзя определять просто
 * по факту возврата из doMove() или по переходу в state == MOVE.
 *
 * Слабая миграция (weak migration): переносится код и данные, но не
 * программный счётчик. Поэтому state сериализуется вместе с агентом.
 *
 * Состояния:
 *   CHECK   -> готовим ContainerID и вызываем doMove().
 *   MOVE    -> проверяем фактическое имя контейнера.
 *              Если агент реально в targetContainerName -> успех.
 *              Если агент всё ещё в старом контейнере -> асинхронный откат,
 *              возвращаемся в CHECK и повторяем.
 *   ARRIVED -> поведение завершено.
 *
 * AMS/reflection оставлены как попытка получить корректный ContainerID
 * с адресом. Если AMS в вашей сборке недоступен, используется fallback
 * ContainerID только по имени + контроль отката через getContainerName().
 */
public class MigrationBehaviour extends Behaviour {

    private static final int CHECK = 0;
    private static final int MOVE = 1;
    private static final int ARRIVED = 2;

    private static final long POLL_INTERVAL = 1500L;
    private static final long INITIAL_DELAY = 3000L;

    private final String targetContainerName;

    private int state = CHECK;
    private boolean finished = false;

    private int attempts = 0;
    private boolean initialWaitDone = false;
    private boolean tracePrinted = false;

    private String hardcodedAddress = null;

    public MigrationBehaviour(Agent a, String targetContainerName) {
        super(a);
        this.targetContainerName = targetContainerName;
    }

    public void setHardcodedAddress(String addr) {
        this.hardcodedAddress = addr;
    }

    @Override
    public void onStart() {
        LogUtil.net(
                myAgent.getLocalName().toUpperCase(),
                "MIGRATION start. Target container = " + targetContainerName.toUpperCase()
                        + ". Success will be verified by actual container name after async doMove()."
        );
    }

    @Override
    public void action() {
        String name = myAgent.getLocalName().toUpperCase();

        switch (state) {
            case CHECK:
                handleCheck(name);
                break;

            case MOVE:
                handleMove(name);
                break;

            case ARRIVED:
                finished = true;
                break;

            default:
                finished = true;
                break;
        }
    }

    @Override
    public boolean done() {
        return finished;
    }

    // ================================================================
    // CHECK: пытаемся мигрировать
    // ================================================================

    private void handleCheck(String name) {
        if (!initialWaitDone) {
            initialWaitDone = true;
            LogUtil.debug(name, "MIGRATION initial wait " + INITIAL_DELAY + " ms before first doMove().");
            block(INITIAL_DELAY);
            return;
        }

        attempts++;

        ContainerID destination = findTargetContainer(name);

        if (destination == null) {
            LogUtil.err(name, "MIGRATION cannot build destination ContainerID. Retry.");
            block(POLL_INTERVAL);
            return;
        }

        state = MOVE;

        LogUtil.debug(
                name,
                "MIGRATION attempt #" + attempts + ": doMove -> " + destination.getName()
                        + " address=" + addressToString(destination)
        );

        try {
            myAgent.doMove(destination);

        } catch (Exception e) {
            // Синхронная ошибка, например mobility service недоступен.
            state = CHECK;

            String reason = e.getClass().getName() + ": " + e.getMessage();
            LogUtil.err(name, "MIGRATION doMove threw synchronously: " + reason + ". Retry.");

            if (!tracePrinted) {
                tracePrinted = true;
                System.err.println("[MIGRATION-TRACE] first synchronous doMove failure stacktrace:");
                e.printStackTrace();
            }

            block(POLL_INTERVAL);
        }
    }

    // ================================================================
    // MOVE: проверяем, реально ли агент прибыл
    // ================================================================

    private void handleMove(String name) {
        String currentContainer = getCurrentContainerName(name);

        if (targetContainerName.equals(currentContainer)) {
            state = ARRIVED;

            LogUtil.sys(
                    name,
                    "MIGRATION SUCCESS: now in " + currentContainer.toUpperCase()
                            + " after " + attempts + " attempt(s). Running post-migration setup."
            );

            if (myAgent instanceof MigratableAgent) {
                ((MigratableAgent) myAgent).postMigrationSetup();
            } else {
                LogUtil.err(name, "MIGRATION agent does not implement MigratableAgent!");
            }

            finished = true;
            return;
        }

        state = CHECK;

        LogUtil.net(
                name,
                "MIGRATION rollback detected: current container = "
                        + (currentContainer == null ? "UNKNOWN" : currentContainer.toUpperCase())
                        + ", target = " + targetContainerName.toUpperCase()
                        + ". Retry #" + attempts + "."
        );

        if (attempts == 20) {
            LogUtil.err(
                    name,
                    "MIGRATION still cannot reach '" + targetContainerName
                            + "' after " + attempts + " attempts."
                            + " CHECK: (1) exact container name and case in AgentContainerTsk1 CONTAINER_NAME"
                            + " and in MainContainerTsk1 agent args[0];"
                            + " (2) mobility service is not disabled by explicit -services;"
                            + " (3) AgentContainerTsk1 terminal shows 'Agent container Container-1@... is ready'."
            );
        }

        block(POLL_INTERVAL);
    }

    private String getCurrentContainerName(String name) {
        try {
            return myAgent.getContainerController().getContainerName();
        } catch (Exception e) {
            LogUtil.err(name, "MIGRATION cannot read current container name: " + e.getMessage());
            return null;
        }
    }

    // ================================================================
    // Поиск ContainerID: AMS через reflection + fallback
    // ================================================================

    private ContainerID findTargetContainer(String name) {
        /*
         * 1. Пробуем современный/альтернативный AMS-путь:
         *    AMSService.queryLocations(Agent) -> Location[]
         */
        ContainerID cid = tryQueryLocations(name);
        if (cid != null) {
            LogUtil.debug(name, "MIGRATION AMS path succeeded: queryLocations.");
            return cid;
        }

        cid = tryExecuteAction(name);
        if (cid != null) {
            LogUtil.debug(name, "MIGRATION AMS path succeeded: execute(QueryPlatformLocationsAction).");
            return cid;
        }

        /*
         * 3. Если задан hardcodedAddress, строим ContainerID с адресом вручную.
         */
        cid = manualWithAddress(name);
        if (cid != null) {
            LogUtil.debug(name, "MIGRATION using hardcoded address.");
            return cid;
        }

        return nameOnlyFallback(name);
    }

    private ContainerID tryQueryLocations(String name) {
        try {
            Class<?> ams = Class.forName("jade.domain.AMSService");
            Method m = ams.getMethod("queryLocations", Agent.class);

            Object locations = m.invoke(null, myAgent);

            ContainerID cid = pickFrom(locations);
            if (cid != null) {
                return cid;
            }

        } catch (Throwable t) {
            LogUtil.debug(
                    name,
                    "MIGRATION AMS queryLocations unavailable: "
                            + t.getClass().getName() + ": " + t.getMessage()
            );
        }

        return null;
    }

    private ContainerID tryExecuteAction(String name) {
        try {
            Class<?> ams = Class.forName("jade.domain.AMSService");

            Class<?> actionCls;
            try {
                actionCls = Class.forName("jade.domain.JADEAgentManagement.QueryPlatformLocationsAction");
            } catch (ClassNotFoundException e1) {
                try {
                    actionCls = Class.forName("jade.domain.FIPAAgentManagement.QueryPlatformLocationsAction");
                } catch (ClassNotFoundException e2) {
                    LogUtil.debug(name, "MIGRATION QueryPlatformLocationsAction class not found.");
                    return null;
                }
            }

            Object action = actionCls.getDeclaredConstructor().newInstance();

            Method exec = findExecuteMethod(ams, actionCls);
            if (exec == null) {
                LogUtil.debug(name, "MIGRATION AMSService.execute(Agent, QueryPlatformLocationsAction) not found.");
                return null;
            }

            exec.invoke(null, myAgent, action);

            Object result = readActionResult(action);

            return pickFrom(result);

        } catch (Throwable t) {
            LogUtil.debug(
                    name,
                    "MIGRATION AMS execute action unavailable: "
                            + t.getClass().getName() + ": " + t.getMessage()
            );
        }

        return null;
    }

    private ContainerID manualWithAddress(String name) {
        if (hardcodedAddress == null || hardcodedAddress.isEmpty()) {
            return null;
        }

        ContainerID cid = new ContainerID();
        cid.setName(targetContainerName);

        try {
            Method set = cid.getClass().getMethod("setAddresses", String[].class);
            set.invoke(cid, (Object) new String[]{hardcodedAddress});
            return cid;
        } catch (Throwable t1) {
            try {
                Method add = cid.getClass().getMethod("addAddress", String.class);
                add.invoke(cid, hardcodedAddress);
                return cid;
            } catch (Throwable t2) {
                LogUtil.debug(
                        name,
                        "MIGRATION cannot set hardcoded address: "
                                + t1.getMessage() + " / " + t2.getMessage()
                );
            }
        }

        return null;
    }

    private ContainerID nameOnlyFallback(String name) {
        LogUtil.debug(
                name,
                "MIGRATION AMS did not return target container. Using name-only ContainerID "
                        + "and relying on async rollback verification."
        );

        ContainerID cid = new ContainerID();
        cid.setName(targetContainerName);
        return cid;
    }

    // ================================================================
    // Reflection helpers
    // ================================================================

    private Method findExecuteMethod(Class<?> ams, Class<?> actionCls) {
        for (Method m : ams.getMethods()) {
            if (!"execute".equals(m.getName())) {
                continue;
            }

            if (m.getParameterCount() != 2) {
                continue;
            }

            if (!Modifier.isStatic(m.getModifiers())) {
                continue;
            }

            Class<?>[] params = m.getParameterTypes();

            if (Agent.class.isAssignableFrom(params[0])
                    && params[1].isAssignableFrom(actionCls)) {
                return m;
            }
        }

        return null;
    }

    private Object readActionResult(Object action) {
        Object r = callNoArg(action, "getLocations");
        if (r != null) {
            return r;
        }

        r = callNoArg(action, "getElements");
        return r;
    }

    private Object callNoArg(Object obj, String methodName) {
        try {
            Method m = obj.getClass().getMethod(methodName);
            return m.invoke(obj);
        } catch (Throwable t) {
            return null;
        }
    }

    private ContainerID pickFrom(Object holder) {
        if (holder == null) {
            return null;
        }

        if (holder instanceof Location[]) {
            for (Location loc : (Location[]) holder) {
                ContainerID cid = matchLocation(loc);
                if (cid != null) {
                    return cid;
                }
            }
            return null;
        }

        /*
         * Пытаемся вызвать iterator(), если это List:
         * java.util.List или jade.util.leap.List.
         */
        try {
            Method iteratorMethod = holder.getClass().getMethod("iterator");
            Object iterator = iteratorMethod.invoke(holder);

            ContainerID cid = iterateAndMatch(iterator);
            if (cid != null) {
                return cid;
            }

        } catch (Throwable ignore) {
            // Возможно, holder уже является Iterator.
        }

        return iterateAndMatch(holder);
    }

    private ContainerID iterateAndMatch(Object iterator) {
        if (iterator == null) {
            return null;
        }

        try {
            Method hasNext = iterator.getClass().getMethod("hasNext");
            Method next = iterator.getClass().getMethod("next");

            while (Boolean.TRUE.equals(hasNext.invoke(iterator))) {
                Object obj = next.invoke(iterator);

                if (obj instanceof Location) {
                    ContainerID cid = matchLocation((Location) obj);
                    if (cid != null) {
                        return cid;
                    }
                }
            }

        } catch (Throwable ignore) {
            // Не Iterator или метод недоступен.
        }

        return null;
    }

    private ContainerID matchLocation(Location loc) {
        if (loc instanceof ContainerID) {
            ContainerID cid = (ContainerID) loc;

            if (targetContainerName.equals(cid.getName())) {
                return cid;
            }
        }

        return null;
    }

    private String addressToString(ContainerID cid) {
        try {
            Method m = cid.getClass().getMethod("getAddresses");
            Object addr = m.invoke(cid);

            if (addr instanceof String[]) {
                String[] arr = (String[]) addr;
                if (arr.length == 0) {
                    return "none";
                }

                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < arr.length; i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append(arr[i]);
                }
                return sb.toString();
            }

            return String.valueOf(addr);

        } catch (Throwable t) {
            try {
                Method m = cid.getClass().getMethod("getAddress");
                return String.valueOf(m.invoke(cid));
            } catch (Throwable ignore) {
                return "unknown";
            }
        }
    }
}