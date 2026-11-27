package com.anqu.ssh;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class SshService extends Service {
    private static final String NOTIFICATIONS = "ssh-session";
    static final String SHOW_TERMINAL = "com.anqu.ssh.SHOW_TERMINAL";
    private static final String STOP = "com.anqu.ssh.STOP";
    private static final int NOTICE = 42;
    private static final long BACKGROUND_WAKE_MS = 3 * 60_000L;

    enum Phase { IDLE, CONNECTING, VERIFYING, ONLINE, RETRYING, ENDED }
    record Status(Phase phase, String title, String detail) {}
    record Verify(String host, String fingerprint, CompletableFuture<Boolean> reply) {}

    final class LocalBinder extends Binder {
        SshService service() { return SshService.this; }
    }

    private final LocalBinder binder = new LocalBinder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final NetworkState networkState = new NetworkState();
    private final TerminalState terminal = new TerminalState(this::send);
    private volatile SshTransport.Size size = new SshTransport.Size(80, 24, 640, 432);
    private Status status = new Status(Phase.IDLE, "未连接", "");
    private SharedPreferences pins;
    private Run run;
    private Verify verification;
    private boolean foreground;
    private boolean activityVisible;
    private PowerManager.WakeLock wakeLock;
    private String lastLoss = "";
    private String profileId = "";
    private String profileTitle = "";
    private Runnable observer;

    @Override public void onCreate() {
        super.onCreate();
        pins = getSharedPreferences("host_keys", MODE_PRIVATE);
        getSystemService(ConnectivityManager.class).registerDefaultNetworkCallback(networkState, main);
        wakeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
            "AnquSSH:BackgroundTerminal");
        wakeLock.setReferenceCounted(false);
        NotificationChannel channel = new NotificationChannel(
            NOTIFICATIONS, "SSH 会话", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("切到其他应用后保留 SSH 会话状态");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) stopConnection();
        return START_NOT_STICKY;
    }

    void observe(Runnable listener) {
        observer = listener;
        notifyObserver();
    }

    void unobserve(Runnable listener) {
        if (observer == listener) observer = null;
    }

    Status status() { return status; }

    Verify verification() { return verification; }

    TerminalState terminal() { return terminal; }

    boolean active() { return run != null; }

    String lastLoss() { return lastLoss; }

    String profileId() { return profileId; }

    String profileTitle() { return profileTitle; }

    boolean matches(ServerProfiles.Profile profile) {
        return run != null && profile != null && profileId.equals(profile.id())
            && profile.protocol() == ServerProfiles.Protocol.SSH
            && profile.port() == run.config.port()
            && profile.host().equals(run.config.host())
            && profile.user().equals(run.config.user());
    }

    void setActivityVisible(boolean visible) {
        if (activityVisible == visible) return;
        activityVisible = visible;
        networkState.updateVisibility(visible);
        if (visible) releaseWakeLock();
        else if (foreground && run != null) wakeLock.acquire(BACKGROUND_WAKE_MS);
    }

    private void releaseWakeLock() {
        if (wakeLock.isHeld()) wakeLock.release();
    }

    void begin(String profileId, String profileTitle, SshTransport.Config config) {
        if (run != null) {
            config.clearSecrets();
            return;
        }
        terminal.reset();
        this.profileId = profileId;
        this.profileTitle = profileTitle;
        lastLoss = "";
        run = new Run(config);
        status = new Status(Phase.CONNECTING, "正在连接", config.address());
        try {
            startForeground(NOTICE, notification());
            foreground = true;
            run.thread.start();
        } catch (RuntimeException error) {
            run.config.clearSecrets();
            run = null;
            status = new Status(Phase.ENDED, "无法启动后台连接", "请在应用打开时连接。");
            stopSelf();
        }
        notifyObserver();
    }

    void stopConnection() {
        if (run != null) {
            Run old = run;
            run = null;
            if (verification != null) verification.reply().complete(false);
            verification = null;
            old.cancel();
            status = new Status(Phase.ENDED, "已断开", old.config.tmux()
                ? "服务器上的 tmux 会话仍可在下次连接时打开。" : "");
        }
        finishForeground();
        notifyObserver();
    }

    @Override public void onDestroy() {
        observer = null;
        stopConnection();
        getSystemService(ConnectivityManager.class).unregisterNetworkCallback(networkState);
        super.onDestroy();
    }

    void resize(SshTransport.Size value) {
        if (size.equals(value)) return;
        size = value;
        terminal.resize(value);
        if (run != null && status.phase() == Phase.ONLINE) {
            run.submit(link -> link.resize(size));
        }
    }

    void send(byte[] input) {
        if (run != null && (status.phase() == Phase.ONLINE)) {
            run.submit(link -> link.write(input));
        }
    }

    private void notifyObserver() {
        if (observer != null) observer.run();
    }

    private void update(Status next) {
        status = next;
        if (foreground) getSystemService(NotificationManager.class).notify(NOTICE, notification());
        notifyObserver();
    }

    private void finishForeground() {
        releaseWakeLock();
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            foreground = false;
        }
        stopSelf();
    }

    private Notification notification() {
        Intent show = new Intent(this, MainActivity.class).setAction(SHOW_TERMINAL)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        Intent disconnect = new Intent(this, SshService.class).setAction(STOP);
        int flags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        Notification.Builder notice = new Notification.Builder(this, NOTIFICATIONS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("栖舟")
            .setContentText(status.title())
            .setContentIntent(PendingIntent.getActivity(this, 0, show, flags))
            .addAction(new Notification.Action.Builder(null, "断开",
                PendingIntent.getService(this, 1, disconnect, flags)).build())
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(true)
            .setOngoing(true);
        if (Build.VERSION.SDK_INT >= 31) {
            notice.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return notice.build();
    }

    private void finish(Run owner, Status ended) {
        main.post(() -> {
            if (run != owner) return;
            run = null;
            verification = null;
            finishForeground();
            update(ended);
        });
    }

    @FunctionalInterface private interface Transmission {
        void apply(SshTransport link) throws IOException;
    }

    private final class Run {
        final SshTransport.Config config;
        final Thread thread = new Thread(this::work, "ssh-connection");
        final ThreadPoolExecutor writer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(128), task -> new Thread(task, "ssh-input"));
        final Semaphore outputSlots = new Semaphore(12);
        volatile SshTransport transport;
        volatile boolean cancelled;
        volatile String trustError;

        Run(SshTransport.Config config) { this.config = config; }

        void cancel() {
            cancelled = true;
            thread.interrupt();
            writer.shutdownNow();
            SshTransport link = transport;
            if (link != null) new Thread(link::disconnect, "ssh-close").start();
        }

        void submit(Transmission work) {
            SshTransport target = transport;
            if (cancelled || target == null) return;
            try {
                writer.execute(() -> {
                    if (cancelled || transport != target) return;
                    try {
                        work.apply(target);
                    } catch (IOException connectionLost) {
                        // The reader owns reconnection.
                    }
                });
            } catch (RejectedExecutionException full) {
                if (!cancelled && status.phase() == Phase.ONLINE) {
                    update(new Status(Phase.ONLINE, "已连接", "发送队列已满，请稍后重试。"));
                }
            }
        }

        private void postStatus(Status next) {
            main.post(() -> {
                if (run == this) update(next);
            });
        }

        private boolean verify(String address, byte[] digest) {
            if (cancelled) return false;
            String fingerprint = HostPins.fingerprint(digest);
            String saved = pins.getString(address, null);
            if (fingerprint.equals(saved)) return true;
            if (saved != null) {
                trustError = "服务器指纹已变化，连接已停止。请向服务器管理员核对。";
                return false;
            }

            Verify prompt = new Verify(address, fingerprint, new CompletableFuture<>());
            main.post(() -> {
                if (run != this) return;
                verification = prompt;
                update(new Status(Phase.VERIFYING, "等待确认服务器", address));
            });
            try {
                if (prompt.reply().get(90, TimeUnit.SECONDS) && !cancelled) {
                    if (HostPins.confirm(pins, address, fingerprint)) return true;
                    trustError = "无法保存或确认服务器指纹。";
                    return false;
                }
                trustError = "未信任此服务器指纹。";
            } catch (TimeoutException expired) {
                trustError = "服务器指纹确认已超时，请重新连接。";
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException error) {
                trustError = "服务器指纹验证失败。";
            } finally {
                main.post(() -> {
                    if (run != this || verification != prompt) return;
                    verification = null;
                    update(new Status(Phase.CONNECTING, "正在连接", address));
                });
            }
            return false;
        }

        private void work() {
            boolean previouslyOpened = false;
            int retrySeconds = 3;
            String previousLoss = "";
            try {
                while (!cancelled) {
                    boolean resume = previouslyOpened;
                    String lastDisconnect = previousLoss;
                    trustError = null;
                    long connectedAt = -1;
                    SshTransport link = new SshTransport();
                    try (link) {
                        transport = link;
                        link.open(config, size, resume, this::verify);
                        if (cancelled) return;
                        previouslyOpened = true;
                        connectedAt = SystemClock.elapsedRealtime();
                        retrySeconds = 3;
                        main.post(() -> {
                            if (run != this) return;
                            terminal.reset();
                            if (transport == link) submit(connected -> connected.resize(size));
                            String detail = config.tmux() ? (resume ? "已重新接入服务器终端" : "正在使用服务器 tmux")
                                : (resume ? "已打开新的终端" : "直接连接");
                            if (resume && !lastDisconnect.isEmpty()) detail += " · 上次：" + lastDisconnect;
                            update(new Status(Phase.ONLINE, "已连接", detail));
                        });
                        receive(link);
                        int exit = link.awaitExitStatus();
                        if (exit >= 0 || link.endedBySignal()) {
                            String detail = exit == SshTransport.MISSING_TMUX && config.tmux()
                                ? "服务器没有 tmux。安装后再连接，或在连接设置中关闭会话恢复。"
                                : exit > 0 ? "远程终端已退出（状态 " + exit + "）。" : "服务器已结束终端。";
                            finish(this, new Status(Phase.ENDED, "会话已结束", detail));
                            return;
                        }
                        previousLoss = recordLoss(link, connectedAt);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    } catch (Exception error) {
                        if (cancelled) return;
                        String detail = connectionError(error, previouslyOpened);
                        if (detail != null) {
                            finish(this, new Status(Phase.ENDED, "连接未成功", detail));
                            return;
                        }
                        if (connectedAt >= 0) previousLoss = recordLoss(link, connectedAt);
                    } finally {
                        transport = null;
                        writer.getQueue().clear();
                    }

                    if (cancelled) return;
                    String detail = previousLoss + " · " + retrySeconds + " 秒后尝试重新连接";
                    postStatus(new Status(Phase.RETRYING, "连接中断，等待重连", detail));
                    Thread.sleep(retrySeconds * 1_000L);
                    retrySeconds = Math.min(30, retrySeconds * 2);
                    postStatus(new Status(Phase.CONNECTING, "正在重连", config.address()));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                config.clearSecrets();
                writer.shutdownNow();
            }
        }

        private String recordLoss(SshTransport link, long connectedAt) {
            String description = link.failure().description();
            String snapshot = networkState.describeLoss(description, connectedAt);
            main.post(() -> {
                if (run == this) lastLoss = snapshot;
            });
            return description;
        }

        private String connectionError(Exception error, boolean previouslyOpened) {
            if (trustError != null) return trustError;
            if (error instanceof SshTransport.AuthenticationFailed) return "登录验证失败，请检查用户名和凭据。";
            if (error instanceof SshTransport.InvalidKey) return "无法读取私钥，请检查文件和口令。";
            return previouslyOpened ? null : "无法建立 SSH 连接，请检查地址、端口和网络。";
        }

        private void receive(SshTransport link) throws Exception {
            byte[] buffer = new byte[8_192];
            int size;
            while (!cancelled && (size = link.read(buffer)) != -1) {
                outputSlots.acquire();
                byte[] chunk = Arrays.copyOf(buffer, size);
                main.post(() -> {
                    try {
                        if (run == this) {
                            terminal.append(chunk);
                            notifyObserver();
                        }
                    } finally {
                        outputSlots.release();
                    }
                });
            }
        }
    }
}
