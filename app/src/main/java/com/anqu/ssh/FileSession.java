package com.anqu.ssh;

import java.io.IOException;

final class FileSession implements AutoCloseable {
    interface Task<T> { T run(RemoteFiles files, String directory) throws Exception; }

    private final ServerProfiles.Profile profile;
    private final ServerProfiles.Credentials credentials;
    private final SshTransport.Trust trust;
    private volatile RemoteFiles active;
    private volatile boolean closed;
    private String currentDirectory;

    FileSession(ServerProfiles.Profile profile, ServerProfiles.Credentials credentials, SshTransport.Trust trust) {
        this.profile = profile;
        this.credentials = credentials;
        this.trust = trust;
    }

    <T> T execute(String target, boolean repeatRead, Task<T> task) throws Exception {
        RemoteFiles files = active;
        boolean reused = files != null && files.connected();
        try {
            return run(target, task);
        } catch (Exception error) {
            RemoteFiles failed = active;
            boolean lost = failed != null && failed.lost(error);
            if (lost) drop(failed);
            if (reused && lost && repeatRead && !closed) {
                try {
                    return run(target, task);
                } catch (Exception retryError) {
                    failed = active;
                    if (failed != null && failed.lost(retryError)) drop(failed);
                    throw retryError;
                }
            }
            throw error;
        }
    }

    private <T> T run(String target, Task<T> task) throws Exception {
        RemoteFiles files = active;
        if (files == null || !files.connected()) {
            if (files != null) drop(files);
            files = RemoteFiles.create(profile.protocol());
            synchronized (this) {
                if (closed) {
                    files.close();
                    throw new IOException("连接已关闭");
                }
                active = files;
            }
            try {
                files.connect(profile, credentials, trust);
                currentDirectory = files.changeDirectory(target);
            } catch (Exception error) {
                drop(files);
                throw error;
            }
        } else if (currentDirectory == null || (target != null && !target.equals(currentDirectory))) {
            // CWD may succeed before PWD fails.
            currentDirectory = null;
            currentDirectory = files.changeDirectory(target);
        }
        return task.run(files, currentDirectory);
    }

    private void drop(RemoteFiles files) {
        synchronized (this) {
            if (active != files) return;
            active = null;
        }
        currentDirectory = null;
        files.close();
    }

    @Override public void close() {
        RemoteFiles files;
        synchronized (this) {
            closed = true;
            files = active;
            active = null;
        }
        if (files != null) files.close();
    }
}
