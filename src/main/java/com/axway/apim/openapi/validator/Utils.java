package com.axway.apim.openapi.validator;

import com.vordel.mime.HeaderSet;
import com.vordel.trace.Trace;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;

/**
 * Utility class for OpenAPI Validator operations.
 */
public class Utils {

    public enum TraceLevel {
        DEBUG, INFO, WARN, ERROR
    }

    /**
     * Trace a message to the Axway trace log.
     */
    public static void traceMessage(String message, TraceLevel level) {
        switch (level) {
            case DEBUG:
                Trace.debug(message);
                break;
            case INFO:
                Trace.info(message);
                break;
            case WARN:
                Trace.info("[WARN] " + message);
                break;
            case ERROR:
                Trace.error(message);
                break;
        }
    }

    /**
     * Trace a message with exception to the Axway trace log.
     */
    public static void traceMessage(String message, Exception e, TraceLevel level) {
        String fullMessage = message + " - " + e.getMessage();
        traceMessage(fullMessage, level);
        if (level == TraceLevel.ERROR || level == TraceLevel.DEBUG) {
            Trace.error(e.toString());
        }
    }

    /**
     * Get header values from a HeaderSet by name.
     */
    @SuppressWarnings("unchecked")
    public static Collection<String> getHeaderValues(HeaderSet headers, String name) {
        if (headers == null) {
            return Collections.emptyList();
        }
        ArrayList<String> values = headers.getHeaderValues(name);
        return (values == null) ? Collections.emptyList() : values;
    }

    /**
     * Remove Content-Type header from HeaderSet.
     */
    public static void removeContentTypeHeader(HeaderSet headers) {
        if (headers == null) return;
        try {
            headers.remove("Content-Type");
        } catch (Exception e) {
            // Ignore if header doesn't exist
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
     */
    @SuppressWarnings("unchecked")
    public static Collection<String> getHeaderNames(HeaderSet headers) {
        if (headers == null) {
            return Collections.emptyList();
        }
        try {
            Collection<String> headerSet = headers.getHeaderSet();
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
