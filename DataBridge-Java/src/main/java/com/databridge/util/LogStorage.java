package com.databridge.util;

import java.time.LocalDateTime;
import java.util.*;

public class LogStorage {
    private static final List<Map<String, String>> logs = new ArrayList<>();

    public static synchronized void addLog(String level, String message) {
        Map<String, String> logEntry = new HashMap<>();
        logEntry.put("level", level);
        logEntry.put("message", message);
        logEntry.put("timestamp", LocalDateTime.now().toString());
        logs.add(logEntry);
    }

    public static synchronized List<Map<String, String>> getLogs() {
        return new ArrayList<>(logs);
    }

    public static synchronized void clearLogs() {
        logs.clear();
    }
}
