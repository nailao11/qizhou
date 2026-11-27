package com.anqu.ssh;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class ServerProfiles {
    enum Protocol {
        SSH("SSH / SFTP", 22), FTP("FTP", 21), FTPS("FTPS 显式", 21), IMPLICIT_FTPS("FTPS 隐式", 990);

        final String label;
        final int port;

        Protocol(String label, int port) {
            this.label = label;
            this.port = port;
        }
    }

    record Profile(String id, String name, String host, int port, String user, Protocol protocol,
                   boolean restore, int heartbeat, boolean remember, String keyName, String sealed) {
        String address() { return (host.contains(":") ? "[" + host + "]" : host) + ":" + port; }
        String title() { return name.isEmpty() ? host : name; }
        boolean hasSavedLogin() { return !sealed.isEmpty(); }
        boolean sameLogin(Profile other) {
            return other != null && host.equals(other.host) && port == other.port
                && user.equals(other.user) && protocol == other.protocol;
        }
    }

    record Credentials(byte[] password, byte[] key) implements AutoCloseable {
        SshTransport.Config ssh(Profile profile) {
            return new SshTransport.Config(profile.host(), profile.port(), profile.user(),
                profile.restore(), profile.heartbeat(), key == null ? null : key.clone(), password.clone());
        }

        @Override public void close() {
            Arrays.fill(password, (byte) 0);
            if (key != null) Arrays.fill(key, (byte) 0);
        }
    }

    private static final String ALIAS = "qizhou-profile-login-v1";
    private final SharedPreferences prefs;
    private final ArrayList<Profile> profiles = new ArrayList<>();

    ServerProfiles(Context context) {
        prefs = context.getSharedPreferences("servers", Context.MODE_PRIVATE);
        if (!prefs.contains("profiles")) migrate(context);
        load();
    }

    List<Profile> all() { return new ArrayList<>(profiles); }

    Profile find(String id) {
        for (Profile item : profiles) if (item.id().equals(id)) return item;
        return null;
    }

    void save(Profile profile, Credentials credentials, boolean remember) throws Exception {
        String sealed = remember && credentials != null ? encrypt(profile, credentials)
            : remember ? profile.sealed() : "";
        Profile entry = new Profile(profile.id(), profile.name(), profile.host(), profile.port(),
            profile.user(), profile.protocol(), profile.restore(), profile.heartbeat(), remember,
            sealed.isEmpty() ? "" : profile.keyName(), sealed);
        ArrayList<Profile> next = new ArrayList<>(profiles);
        Profile existing = find(profile.id());
        if (existing == null) next.add(entry);
        else next.set(next.indexOf(existing), entry);
        commit(next);
    }

    void remove(Profile profile) throws Exception {
        ArrayList<Profile> next = new ArrayList<>(profiles);
        next.removeIf(p -> p.id().equals(profile.id()));
        commit(next);
    }

    Credentials unlock(Profile profile) throws Exception {
        if (!profile.hasSavedLogin()) return null;
        byte[] blob = Base64.decode(profile.sealed(), Base64.NO_WRAP);
        if (blob.length < 28) throw new IOException("Invalid credentials");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(128, blob, 0, 12));
        cipher.updateAAD(associatedData(profile));
        byte[] clear = cipher.doFinal(blob, 12, blob.length - 12);
        try {
            ByteBuffer data = ByteBuffer.wrap(clear);
            int passwordSize = data.getInt();
            if (passwordSize < 0 || passwordSize > data.remaining()) throw new IOException("Invalid credentials");
            byte[] password = new byte[passwordSize];
            data.get(password);
            byte[] privateKey = data.hasRemaining() ? new byte[data.remaining()] : null;
            if (privateKey != null) data.get(privateKey);
            return new Credentials(password, privateKey);
        } finally {
            Arrays.fill(clear, (byte) 0);
        }
    }

    private String encrypt(Profile profile, Credentials credentials) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key(true));
        cipher.updateAAD(associatedData(profile));
        byte[] key = credentials.key();
        ByteBuffer data = ByteBuffer.allocate(4 + credentials.password().length + (key == null ? 0 : key.length));
        data.putInt(credentials.password().length).put(credentials.password());
        if (key != null) data.put(key);
        try {
            byte[] encrypted = cipher.doFinal(data.array());
            byte[] iv = cipher.getIV();
            if (iv.length != 12) throw new IOException("Unexpected encryption IV");
            return Base64.encodeToString(ByteBuffer.allocate(iv.length + encrypted.length)
                .put(iv).put(encrypted).array(), Base64.NO_WRAP);
        } finally {
            Arrays.fill(data.array(), (byte) 0);
        }
    }

    private byte[] associatedData(Profile p) {
        return (p.id() + "\n" + p.protocol().name() + "\n" + p.address() + "\n" + p.user())
            .getBytes(StandardCharsets.UTF_8);
    }

    private SecretKey key(boolean create) throws Exception {
        KeyStore keys = KeyStore.getInstance("AndroidKeyStore");
        keys.load(null);
        SecretKey key = (SecretKey) keys.getKey(ALIAS, null);
        if (key != null) return key;
        if (!create) throw new IOException("Keystore entry unavailable");
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
            KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }

    private void load() {
        try {
            JSONArray list = new JSONArray(prefs.getString("profiles", "[]"));
            for (int i = 0; i < list.length(); i++) {
                JSONObject p = list.optJSONObject(i);
                if (p == null) continue;
                try {
                    profiles.add(new Profile(p.getString("id"), p.optString("name"), p.getString("host"),
                        p.getInt("port"), p.getString("user"), Protocol.valueOf(p.getString("protocol")),
                        p.optBoolean("restore"), p.optInt("heartbeat", 5), p.optBoolean("remember", true),
                        p.optString("key"), p.optString("sealed")));
                } catch (org.json.JSONException | IllegalArgumentException malformed) {
                    android.util.Log.w("QizhouProfiles", "Skipped malformed saved profile");
                }
            }
        } catch (org.json.JSONException malformed) {
            android.util.Log.w("QizhouProfiles", "Could not read saved profiles");
        }
    }

    private void commit(List<Profile> next) throws Exception {
        JSONArray list = new JSONArray();
        for (Profile p : next) {
            list.put(new JSONObject().put("id", p.id()).put("name", p.name()).put("host", p.host())
                .put("port", p.port()).put("user", p.user()).put("protocol", p.protocol().name())
                .put("restore", p.restore()).put("heartbeat", p.heartbeat()).put("remember", p.remember())
                .put("key", p.keyName()).put("sealed", p.sealed()));
        }
        if (!prefs.edit().putString("profiles", list.toString()).commit()) {
            throw new IOException("Could not save profiles");
        }
        profiles.clear();
        profiles.addAll(next);
    }

    private void migrate(Context context) {
        SharedPreferences old = context.getSharedPreferences("connection", Context.MODE_PRIVATE);
        String host = old.getString("host", "");
        if (host.isEmpty()) return;
        try {
            int legacyInterval = switch (old.getInt("heartbeat", 0)) {
                case 1 -> 30;
                case 2 -> 60;
                default -> 5;
            };
            commit(Collections.singletonList(new Profile(UUID.randomUUID().toString(), "", host,
                Integer.parseInt(old.getString("port", "22")), old.getString("user", ""), Protocol.SSH,
                old.getBoolean("tmux", false), old.getInt("heartbeat_seconds", legacyInterval), true, "", "")));
            profiles.clear();
        } catch (Exception error) {
            android.util.Log.w("QizhouProfiles", "Could not migrate the previous connection");
        }
    }
}
