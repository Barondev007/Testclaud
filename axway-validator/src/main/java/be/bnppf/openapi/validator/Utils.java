package be.bnppf.openapi.validator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;

/**
 * Utility class for OpenAPI Validator operations.
 * Works both in Axway environment (with Vordel classes) and standalone (CLI).
 */
public class Utils {

    // Flag to track if we're running in Axway environment
    private static final boolean AXWAY_AVAILABLE;
    private static Object traceClass = null;

    static {
        boolean axwayAvailable = false;
        try {
            // Try to load Vordel Trace class
            Class<?> clazz = Class.forName("com.vordel.trace.Trace");
            traceClass = clazz;
            axwayAvailable = true;
        } catch (ClassNotFoundException e) {
            // Vordel classes not available - running standalone
            axwayAvailable = false;
        }
        AXWAY_AVAILABLE = axwayAvailable;
    }

    public enum TraceLevel {
        DEBUG, INFO, WARN, ERROR
    }

    /**
     * Check if running in Axway environment.
     */
    public static boolean isAxwayEnvironment() {
        return AXWAY_AVAILABLE;
    }

    /**
     * Trace a message. Uses Axway Trace if available, otherwise prints to stdout/stderr.
     */
    public static void traceMessage(String message, TraceLevel level) {
        if (AXWAY_AVAILABLE) {
            traceToAxway(message, level);
        } else {
            traceToStdout(message, level);
        }
    }

    /**
     * Trace to Axway using reflection to avoid compile-time dependency.
     */
    private static void traceToAxway(String message, TraceLevel level) {
        try {
            Class<?> traceClazz = (Class<?>) traceClass;
            java.lang.reflect.Method method;
            switch (level) {
                case DEBUG:
                    method = traceClazz.getMethod("debug", String.class);
                    method.invoke(null, message);
                    break;
                case INFO:
                    method = traceClazz.getMethod("info", String.class);
                    method.invoke(null, message);
                    break;
                case WARN:
                    method = traceClazz.getMethod("info", String.class);
                    method.invoke(null, "[WARN] " + message);
                    break;
                case ERROR:
                    method = traceClazz.getMethod("error", String.class);
                    method.invoke(null, message);
                    break;
            }
        } catch (Exception e) {
            // Fallback to stdout if reflection fails
            traceToStdout(message, level);
        }
    }

    /**
     * Trace to standard output (for CLI/standalone usage).
     */
    private static void traceToStdout(String message, TraceLevel level) {
        // Only output in verbose/debug scenarios - CLI handles its own output
        // Uncomment below for debug purposes:
        // String prefix = "[" + level.name() + "] ";
        // if (level == TraceLevel.ERROR) {
        //     System.err.println(prefix + message);
        // } else {
        //     System.out.println(prefix + message);
        // }
    }

    /**
     * Trace a message with exception.
     */
    public static void traceMessage(String message, Exception e, TraceLevel level) {
        String fullMessage = message + " - " + e.getMessage();
        traceMessage(fullMessage, level);
    }

    /**
     * Get header values from a HeaderSet by name.
     * Only works in Axway environment.
     */
    @SuppressWarnings("unchecked")
    public static Collection<String> getHeaderValues(Object headers, String name) {
        if (headers == null) {
            return Collections.emptyList();
        }
        try {
            // Use reflection to call getHeaderValues
            java.lang.reflect.Method method = headers.getClass().getMethod("getHeaderValues", String.class);
            ArrayList<String> values = (ArrayList<String>) method.invoke(headers, name);
            return (values == null) ? Collections.emptyList() : values;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /**
     * Remove Content-Type header from HeaderSet.
     * Only works in Axway environment.
     */
    public static void removeContentTypeHeader(Object headers) {
        if (headers == null) return;
        try {
            java.lang.reflect.Method method = headers.getClass().getMethod("remove", String.class);
            method.invoke(headers, "Content-Type");
        } catch (Exception e) {
            // Ignore if header doesn't exist or method not available
        }
    }

    /**
     * Get the beginning of content for logging purposes.
     */
    public static String getContentStart(String content, int maxLength, boolean escape) {
        if (content == null) return "null";
        if (content.isEmpty()) return "(empty)";

        String result = content.length() > maxLength
            ? content.substring(0, maxLength) + "..."
            : content;

        if (escape) {
            result = result.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
        }
        return result;
    }

    /**
     * Extract all header names from a HeaderSet.
     * Only works in Axway environment.
     */
    @SuppressWarnings("unchecked")
    public static Collection<String> getHeaderNames(Object headers) {
        if (headers == null) {
            return Collections.emptyList();
        }
        try {
            java.lang.reflect.Method method = headers.getClass().getMethod("getHeaderSet");
            Collection<String> headerSet = (Collection<String>) method.invoke(headers);
            return (headerSet == null) ? Collections.emptyList() : headerSet;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /**
     * Safely parse an integer with a default value.
     */
    public static int parseInt(String value, int defaultValue) {
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Check if a string is null or empty.
     */
    public static boolean isEmpty(String str) {
        return str == null || str.trim().isEmpty();
    }

    /**
     * Check if a string is not null and not empty.
     */
    public static boolean isNotEmpty(String str) {
        return str != null && !str.trim().isEmpty();
    }
}
