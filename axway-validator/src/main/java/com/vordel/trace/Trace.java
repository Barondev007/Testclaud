package com.vordel.trace;

/**
 * Stub class for Axway Trace.
 * This is only used for compilation - the actual class is provided by Axway runtime.
 */
public class Trace {

    public static void debug(String message) {
        System.out.println("[DEBUG] " + message);
    }

    public static void info(String message) {
        System.out.println("[INFO] " + message);
    }

    public static void error(String message) {
        System.err.println("[ERROR] " + message);
    }

    public static void error(String message, Throwable t) {
        System.err.println("[ERROR] " + message);
        if (t != null) {
            t.printStackTrace(System.err);
        }
    }

    public static boolean isDebugEnabled() {
        return true;
    }
}
