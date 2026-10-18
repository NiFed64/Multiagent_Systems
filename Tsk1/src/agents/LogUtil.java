package agents;

public final class LogUtil {

    // Если нужно вернуть подробные логи для отладки:
    // LogUtil.verbose = true;
    public static volatile boolean verbose = false;

    private static final Object LOCK = new Object();

    private LogUtil() {
    }

    public static String shortId(String id) {
        if (id == null || id.isEmpty()) {
            return "null";
        }
        return id.length() <= 8 ? id : id.substring(0, 8);
    }

    public static void sys(String who, String msg) {
        if (ClientAgent.monitorMode) {
            println("[SYS] " + who + ": " + msg);
        }
    }

    public static void net(String who, String msg) {
        if (ClientAgent.monitorMode) {
            println("[NET] " + who + ": " + msg);
        }
    }

    public static void cn(String who, String msg) {
        if (ClientAgent.monitorMode) {
            println("[CN] " + who + ": " + msg);
        }
    }

    public static void work(String who, String msg) {
        if (ClientAgent.monitorMode) {
            println("[WORK] " + who + ": " + msg);
        }
    }

    public static void debug(String who, String msg) {
        if (ClientAgent.monitorMode && verbose) {
            println("[DBG] " + who + ": " + msg);
        }
    }

    public static void err(String who, String msg) {
        println("[ERR] " + who + ": " + msg);
    }

    public static void client(String msg) {
        println("[CLIENT] " + msg);
    }

    public static void resultConfirm(String value) {
        println(" RESULT: Final sum = " + value);
    }

    public static void resultRefuse(String value) {
        println(" RESULT: " + value);
    }

    private static void println(String s) {
        synchronized (LOCK) {
            System.out.println(s);
        }
    }
}