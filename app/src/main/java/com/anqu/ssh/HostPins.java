package com.anqu.ssh;

import android.content.SharedPreferences;
import android.util.Base64;

final class HostPins {
    private HostPins() {}

    static String fingerprint(byte[] digest) {
        return "SHA256:" + Base64.encodeToString(digest, Base64.NO_PADDING | Base64.NO_WRAP);
    }

    static synchronized boolean confirm(SharedPreferences pins, String host, String fingerprint) {
        String previous = pins.getString(host, null);
        if (fingerprint.equals(previous)) return true;
        return previous == null && pins.edit().putString(host, fingerprint).commit();
    }
}
