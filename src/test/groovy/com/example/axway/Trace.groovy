package com.example.axway

/**
 * Mock Axway Trace class for logging outside Axway environment.
 */
class Trace {
    static boolean enabled = true
    static List<String> logs = []

    static void error(String message) {
        def logMessage = "[ERROR] ${message}"
        logs.add(logMessage)
        if (enabled) println logMessage
    }

    static void warn(String message) {
        def logMessage = "[WARN] ${message}"
        logs.add(logMessage)
        if (enabled) println logMessage
    }

    static void info(String message) {
        def logMessage = "[INFO] ${message}"
        logs.add(logMessage)
        if (enabled) println logMessage
    }

    static void debug(String message) {
        def logMessage = "[DEBUG] ${message}"
        logs.add(logMessage)
        if (enabled) println logMessage
    }

    static void clearLogs() {
        logs.clear()
    }

    static List<String> getLogs() {
        return logs
    }
}
