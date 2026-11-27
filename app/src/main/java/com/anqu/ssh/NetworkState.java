package com.anqu.ssh;

import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.SystemClock;

final class NetworkState extends ConnectivityManager.NetworkCallback {
    private Network current;
    private String path = "未知";
    private boolean blocked;
    private long blockedAt = -1;
    private long changedAt = -1;
    private boolean visible;
    private long visibilityChangedAt = SystemClock.elapsedRealtime();

    @Override public synchronized void onAvailable(Network network) {
        if (network.equals(current)) return;
        if (current != null) changedAt = SystemClock.elapsedRealtime();
        current = network;
        path = "未知";
        blocked = false;
    }

    @Override public synchronized void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
        if (!network.equals(current)) return;
        String next = transportName(caps);
        if (!"未知".equals(path) && !next.equals(path)) changedAt = SystemClock.elapsedRealtime();
        path = next;
    }

    @Override public synchronized void onBlockedStatusChanged(Network network, boolean blocked) {
        if (!network.equals(current)) return;
        this.blocked = blocked;
        if (blocked) blockedAt = SystemClock.elapsedRealtime();
    }

    @Override public synchronized void onLost(Network network) {
        if (!network.equals(current)) return;
        current = null;
        path = "不可用";
        blocked = false;
        changedAt = SystemClock.elapsedRealtime();
    }

    synchronized void updateVisibility(boolean visible) {
        if (this.visible == visible) return;
        this.visible = visible;
        visibilityChangedAt = SystemClock.elapsedRealtime();
    }

    synchronized String describeLoss(String error, long connectedAt) {
        long now = SystemClock.elapsedRealtime();
        StringBuilder result = new StringBuilder(error)
            .append("\n应用：").append(visible ? "前台" : "后台")
            .append("（").append(Math.max(0, now - visibilityChangedAt) / 1000).append(" 秒）")
            .append("\n默认网络：").append(path);
        if (blocked || recentlyObserved(blockedAt, connectedAt, now)) {
            result.append("\n安卓报告应用联网受限");
        }
        if (recentlyObserved(changedAt, connectedAt, now)) {
            result.append("\n掉线前默认网络发生变化");
        }
        return result.toString();
    }

    private static boolean recentlyObserved(long event, long connectedAt, long now) {
        return event >= connectedAt && now - event <= 5_000;
    }

    private static String transportName(NetworkCapabilities caps) {
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return "VPN";
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "Wi-Fi";
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "移动数据";
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "有线网络";
        return "其他";
    }
}
