package com.anqu.ssh;

import android.content.Context;
import android.content.SharedPreferences;

final class AppPreferences {
    enum FileOrder {
        NAME("名称"), SIZE("大小");

        final String label;

        FileOrder(String label) { this.label = label; }
    }

    static final int[] TERMINAL_SIZES = {12, 13, 15, 17, 19, 21};
    static final int[] HEARTBEATS = {5, 15, 30, 60};

    private final SharedPreferences values;

    AppPreferences(Context context) {
        values = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
    }

    int terminalSize() {
        return allowed(values.getInt("terminal_size", 13), TERMINAL_SIZES, 13);
    }

    void terminalSize(int size) {
        values.edit().putInt("terminal_size", allowed(size, TERMINAL_SIZES, 13)).apply();
    }

    boolean shortcutBar() { return values.getBoolean("shortcut_bar", true); }

    void shortcutBar(boolean show) { values.edit().putBoolean("shortcut_bar", show).apply(); }

    FileOrder fileOrder() {
        return "SIZE".equals(values.getString("file_order", "")) ? FileOrder.SIZE : FileOrder.NAME;
    }

    void fileOrder(FileOrder order) { values.edit().putString("file_order", order.name()).apply(); }

    int defaultHeartbeat() {
        return allowed(values.getInt("default_heartbeat", 5), HEARTBEATS, 5);
    }

    void defaultHeartbeat(int seconds) {
        values.edit().putInt("default_heartbeat", allowed(seconds, HEARTBEATS, 5)).apply();
    }

    boolean rememberDefault() { return values.getBoolean("default_remember", true); }

    void rememberDefault(boolean remember) {
        values.edit().putBoolean("default_remember", remember).apply();
    }

    private static int allowed(int value, int[] choices, int fallback) {
        for (int choice : choices) if (value == choice) return value;
        return fallback;
    }
}
