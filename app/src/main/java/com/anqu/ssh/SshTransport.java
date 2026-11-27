package com.anqu.ssh;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.JSchSessionDisconnectException;
import com.jcraft.jsch.Logger;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SocketFactory;
import com.jcraft.jsch.UserInfo;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

final class SshTransport implements AutoCloseable {
    static final int MISSING_TMUX = 73;
    private static final String TMUX_NAME = "anqu-ssh";
    private static final int CONNECT_TIMEOUT_MS = 12_000;

    record Size(int columns, int rows, int width, int height) {}

    record Config(String host, int port, String user, boolean tmux, int heartbeat,
                  byte[] key, byte[] secret) {
        String address() {
            return (host.contains(":") ? "[" + host + "]" : host) + ":" + port;
        }

        void clearSecrets() {
            Arrays.fill(secret, (byte) 0);
            if (key != null) Arrays.fill(key, (byte) 0);
        }
    }

    static final class AuthenticationFailed extends IOException {
        AuthenticationFailed(Throwable cause) { super(cause); }
    }

    static final class InvalidKey extends IOException {
        InvalidKey(Throwable cause) { super(cause); }
    }

    interface Trust {
        boolean verify(String host, byte[] sha256);
    }

    enum Loss {
        HEARTBEAT("服务器长时间未响应 SSH 心跳"),
        REMOTE_DISCONNECT("服务器发送了断开通知"),
        TCP_CLOSED("网络连接已关闭"),
        NETWORK_ERROR("网络连接异常"),
        CHANNEL_ENDED("远程终端通道已结束"),
        SSH_ERROR("SSH 连接读取异常");

        final String text;

        Loss(String text) { this.text = text; }
    }

    private final JSch jsch = new JSch();
    private final TrackedSocket socket = new TrackedSocket();
    record Failure(Loss category, String detail) {
        String description() {
            return detail.isEmpty() ? category.text : category.text + "（" + detail + "）";
        }
    }

    private final AtomicReference<Failure> loss = new AtomicReference<>();
    private ScheduledThreadPoolExecutor probes;
    private volatile Session session;
    private volatile Channel channel;
    private volatile boolean closed;
    private InputStream input;
    private OutputStream output;

    private Session authenticate(Config config, Trust trust) throws Exception {
        if (config.key() != null) {
            byte[] key = config.key().clone();
            try {
                jsch.addIdentity("Android file picker", key, null, config.secret());
            } catch (JSchException e) {
                throw new InvalidKey(e);
            } finally {
                Arrays.fill(key, (byte) 0);
            }
        }

        Session next = jsch.getSession(config.user(), config.host(), config.port());
        session = next;
        checkOpen();
        next.setHostKeyAlias(config.address());
        next.setHostKeyRepository(new PinnedHost(trust));
        next.setConfig("StrictHostKeyChecking", "yes");
        next.setConfig("host_certificate_to_key_fallback", "no");
        next.setConfig("PreferredAuthentications", config.key() == null ? "password" : "publickey");
        next.setConfig("MaxAuthTries", "1");
        if (config.key() == null) next.setPassword(config.secret());
        next.setSocketFactory(socket);
        next.setLogger(new Logger() {
            @Override public boolean isEnabled(int level) { return level == INFO; }

            @Override public void log(int level, String message) {}

            @Override public void log(int level, String message, Throwable cause) {
                if (closed) return;
                if (cause instanceof JSchSessionDisconnectException remote) {
                    remember(Loss.REMOTE_DISCONNECT, "SSH " + remote.getReasonCode());
                } else if (cause instanceof SocketException) {
                    remember(Loss.NETWORK_ERROR, networkError(cause));
                } else if (socket.inputEnded) {
                    remember(Loss.TCP_CLOSED, "");
                } else if (cause instanceof IOException) {
                    remember(Loss.SSH_ERROR, "");
                }
            }
        });
        try {
            next.connect(CONNECT_TIMEOUT_MS);
        } catch (JSchException error) {
            String message = error.getMessage();
            if (message != null && (message.startsWith("Auth fail") || message.startsWith("Auth cancel"))) {
                throw new AuthenticationFailed(error);
            }
            throw error;
        }

        return next;
    }

    com.jcraft.jsch.ChannelSftp openSftp(Config config, Trust trust) throws Exception {
        Session next = authenticate(config, trust);
        com.jcraft.jsch.ChannelSftp files = (com.jcraft.jsch.ChannelSftp) next.openChannel("sftp");
        channel = files;
        checkOpen();
        files.setBulkRequests(48);
        files.connect(CONNECT_TIMEOUT_MS);
        startProbes(next, config.heartbeat());
        return files;
    }

    void open(Config config, Size size, boolean resume, Trust trust) throws Exception {
        Session next = authenticate(config, trust);
        if (config.tmux()) {
            ChannelExec exec = (ChannelExec) next.openChannel("exec");
            String tmux = resume
                ? "attach-session -t =" + TMUX_NAME
                : "new-session -A -s " + TMUX_NAME;
            exec.setCommand("if command -v tmux >/dev/null 2>&1; then exec tmux " + tmux
                + " 2>&1; else printf '\\n[栖舟] 服务器未安装 tmux。\\n'; exit "
                + MISSING_TMUX + "; fi");
            exec.setPty(true);
            exec.setPtyType("xterm-256color", size.columns(), size.rows(), size.width(), size.height());
            channel = exec;
        } else {
            ChannelShell shell = (ChannelShell) next.openChannel("shell");
            shell.setPty(true);
            shell.setPtyType("xterm-256color", size.columns(), size.rows(), size.width(), size.height());
            channel = shell;
        }

        checkOpen();
        input = channel.getInputStream();
        output = channel.getOutputStream();
        channel.connect(CONNECT_TIMEOUT_MS);
        checkOpen();
        startProbes(next, config.heartbeat());
    }

    int read(byte[] bytes) throws IOException {
        return input.read(bytes);
    }

    void write(byte[] bytes) throws IOException {
        checkOpen();
        try {
            output.write(bytes);
            output.flush();
        } catch (IOException error) {
            Channel current = channel;
            if (current != null && current.isConnected() && !current.isEOF()) {
                remember(error instanceof SocketException ? Loss.NETWORK_ERROR : Loss.SSH_ERROR, networkError(error));
                socket.close();
            }
            throw error;
        }
    }

    void resize(Size size) {
        Channel current = channel;
        if (current == null || !current.isConnected()) return;
        if (current instanceof ChannelExec exec) {
            exec.setPtySize(size.columns(), size.rows(), size.width(), size.height());
        } else if (current instanceof ChannelShell shell) {
            shell.setPtySize(size.columns(), size.rows(), size.width(), size.height());
        }
    }

    int exitStatus() {
        Channel current = channel;
        return current == null ? -1 : current.getExitStatus();
    }

    int awaitExitStatus() throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(350);
        while (!closed && channel.isConnected() && exitStatus() < 0 && channel.getExitSignal() == null
            && System.nanoTime() < until) {
            Thread.sleep(20);
        }
        return exitStatus();
    }

    boolean endedBySignal() { return channel != null && channel.getExitSignal() != null; }

    Failure failure() {
        Failure saved = loss.get();
        if (saved != null) return saved;
        Session current = session;
        Loss fallback = !socket.inputEnded && current != null && current.isConnected()
            ? Loss.CHANNEL_ENDED : Loss.TCP_CLOSED;
        return new Failure(fallback, "");
    }

    private void remember(Loss value, String detail) {
        loss.compareAndSet(null, new Failure(value, detail));
    }

    static String networkError(Throwable error) {
        String message = "";
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null) message += " " + cause.getMessage().toUpperCase(Locale.ROOT);
            if (cause.getCause() == cause) break;
        }
        if (message.contains("ECONNRESET") || message.contains("CONNECTION RESET")) return "连接被重置";
        if (message.contains("ECONNABORTED") || message.contains("CONNECTION ABORT")) return "连接被中止";
        if (message.contains("EPERM") || message.contains("EACCES") || message.contains("PERMISSION DENIED")
            || message.contains("OPERATION NOT PERMITTED")) return "系统拒绝网络操作";
        if (message.contains("ENETUNREACH") || message.contains("EHOSTUNREACH")
            || message.contains("UNREACHABLE")) return "网络不可达";
        if (message.contains("ETIMEDOUT") || message.contains("TIMED OUT")) return "网络超时";
        if (message.contains("EPIPE") || message.contains("BROKEN PIPE")) return "写入时连接已关闭";
        if (message.contains("SOCKET CLOSED") || message.contains("EBADF")) return "套接字已关闭";
        return "";
    }

    private synchronized void startProbes(Session connected, int interval) throws IOException {
        checkOpen();
        long deadline = TimeUnit.SECONDS.toNanos(Math.max(60, interval * 4L));
        socket.lastRead = System.nanoTime();
        probes = new ScheduledThreadPoolExecutor(2, task -> new Thread(task, "ssh-keepalive"));
        // A read timeout can split an SSH packet.
        probes.scheduleWithFixedDelay(() -> {
            if (closed || !connected.isConnected()) return;
            try {
                connected.sendKeepAliveMsg();
            } catch (Exception error) {
                if (closed) return;
                remember(Loss.NETWORK_ERROR, networkError(error));
                socket.close();
            }
        }, interval, interval, TimeUnit.SECONDS);
        probes.scheduleWithFixedDelay(() -> {
            if (closed || !connected.isConnected()) return;
            if (System.nanoTime() - socket.lastRead >= deadline) {
                remember(Loss.HEARTBEAT, "");
                socket.close();
            }
        }, interval, Math.min(interval, 5), TimeUnit.SECONDS);
    }

    void disconnect() {
        synchronized (this) {
            closed = true;
            if (probes != null) probes.shutdownNow();
        }
        socket.close();
        Channel oldChannel = channel;
        Session oldSession = session;
        if (oldChannel != null) oldChannel.disconnect();
        if (oldSession != null) oldSession.disconnect();
    }

    @Override public void close() {
        disconnect();
        try {
            jsch.removeAllIdentity();
        } catch (JSchException ignored) {
        }
    }

    private void checkOpen() throws IOException {
        if (closed) throw new IOException("连接已关闭");
    }

    private static final class TrackedSocket implements SocketFactory {
        private Socket socket;
        private boolean closed;
        volatile long lastRead = System.nanoTime();
        volatile boolean inputEnded;

        @Override public Socket createSocket(String host, int port) throws IOException {
            Socket next = new Socket();
            synchronized (this) {
                if (closed) {
                    next.close();
                    throw new SocketException("SSH socket closed");
                }
                socket = next;
            }
            next.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            next.setKeepAlive(true);
            return next;
        }

        @Override public InputStream getInputStream(Socket connected) throws IOException {
            return new FilterInputStream(connected.getInputStream()) {
                @Override public int read() throws IOException {
                    int value = in.read();
                    noteRead(value == -1 ? -1 : 1);
                    return value;
                }

                @Override public int read(byte[] bytes, int offset, int count) throws IOException {
                    int size = in.read(bytes, offset, count);
                    noteRead(size);
                    return size;
                }
            };
        }

        @Override public OutputStream getOutputStream(Socket connected) throws IOException {
            return connected.getOutputStream();
        }

        private void noteRead(int bytes) {
            if (bytes > 0) lastRead = System.nanoTime();
            if (bytes < 0) inputEnded = true;
        }

        synchronized void close() {
            closed = true;
            if (socket == null) return;
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static final class PinnedHost implements HostKeyRepository {
        private final Trust trust;

        PinnedHost(Trust trust) {
            this.trust = trust;
        }

        @Override public int check(String host, byte[] sshKey) {
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(sshKey);
                return trust.verify(host, digest) ? OK : NOT_INCLUDED;
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override public String getKnownHostsRepositoryID() { return "pinned-hosts"; }
        @Override public HostKey[] getHostKey() { return new HostKey[0]; }
        @Override public HostKey[] getHostKey(String host, String type) { return getHostKey(); }
        @Override public void add(HostKey hostKey, UserInfo userInfo) {}
        @Override public void remove(String host, String type) {}
        @Override public void remove(String host, String type, byte[] key) {}
    }
}
