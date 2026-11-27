package com.anqu.ssh;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPConnectionClosedException;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;
import org.apache.commons.net.ftp.FTPSClient;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocket;

abstract class RemoteFiles implements AutoCloseable {
    record Entry(String name, boolean directory, boolean link, long bytes) {}
    record Folder(String path, List<Entry> entries) {}

    private static final Comparator<Entry> BY_NAME = Comparator.comparing(Entry::name,
        String.CASE_INSENSITIVE_ORDER).thenComparing(Entry::name);
    private static final Comparator<Entry> FOLDERS_FIRST = Comparator.comparing(Entry::directory).reversed();
    static final Comparator<Entry> LARGEST_FIRST = FOLDERS_FIRST
        .thenComparing(Comparator.comparingLong((Entry entry) -> entry.directory() ? 0 : entry.bytes()).reversed())
        .thenComparing(BY_NAME);

    static RemoteFiles create(ServerProfiles.Protocol protocol) throws Exception {
        return protocol == ServerProfiles.Protocol.SSH ? new Sftp() : new Ftp(protocol);
    }

    abstract void connect(ServerProfiles.Profile profile, ServerProfiles.Credentials credentials,
                           SshTransport.Trust trust) throws Exception;
    abstract boolean connected();
    abstract String changeDirectory(String path) throws Exception;
    abstract List<Entry> entries() throws Exception;
    abstract void makeDirectory(String name) throws Exception;
    abstract void move(String from, String to) throws Exception;
    abstract void remove(String name, boolean directory) throws Exception;
    abstract void download(String name, OutputStream output) throws Exception;
    abstract void writeFile(String name, InputStream input) throws Exception;
    @Override public abstract void close();

    final Folder list(String path) throws Exception {
        return readFolder(changeDirectory(path));
    }

    final Folder readFolder(String canonical) throws Exception {
        return readFolder(canonical, FOLDERS_FIRST.thenComparing(BY_NAME));
    }

    final Folder readFolder(String canonical, Comparator<Entry> order) throws Exception {
        List<Entry> entries = entries();
        entries.removeIf(item -> !validName(item.name()));
        entries.sort(order);
        return new Folder(canonical, entries);
    }

    final void upload(String name, InputStream input) throws Exception {
        ensureAvailable(name);
        String temporary = ".qizhou-" + UUID.randomUUID() + ".part";
        boolean published = false;
        try {
            writeFile(temporary, input);
            ensureAvailable(name);
            move(temporary, name);
            published = true;
        } finally {
            if (!published) {
                try {
                    remove(temporary, false);
                } catch (Exception ignored) {
                }
            }
        }
    }

    final void ensureAvailable(String name) throws Exception {
        if (!validName(name)) throw new IOException("文件名不能包含路径分隔符或控制字符。");
        if (exists(name)) throw new IOException("目标目录已存在同名文件或文件夹。");
    }

    boolean exists(String name) throws Exception {
        for (Entry item : entries()) if (item.name().equals(name)) return true;
        return false;
    }

    final boolean lost(Exception error) {
        if (!connected()) return true;
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof FTPConnectionClosedException || cause instanceof SocketException
                || cause instanceof SocketTimeoutException || cause instanceof EOFException
                || cause instanceof SSLException) return true;
            if (cause.getCause() == cause) break;
        }
        return false;
    }

    static boolean validName(String name) {
        return name != null && !name.isEmpty() && !name.equals(".") && !name.equals("..")
            && name.indexOf('/') < 0 && name.codePoints().noneMatch(Character::isISOControl);
    }

    static String child(String path, String name) {
        return path.endsWith("/") ? path + name : path + "/" + name;
    }

    static String parent(String path) {
        int slash = path.lastIndexOf('/');
        if (slash < 0) return null;
        return slash == 0 ? "/" : path.substring(0, slash);
    }

    private static final class Sftp extends RemoteFiles {
        private final SshTransport transport = new SshTransport();
        private ChannelSftp channel;

        @Override void connect(ServerProfiles.Profile profile, ServerProfiles.Credentials credentials,
                               SshTransport.Trust trust) throws Exception {
            SshTransport.Config config = credentials.ssh(profile);
            try {
                channel = transport.openSftp(config, trust);
            } finally {
                config.clearSecrets();
            }
        }

        @Override boolean connected() { return channel != null && channel.isConnected(); }

        @Override boolean exists(String name) throws SftpException {
            try {
                channel.lstat(escaped(name));
                return true;
            } catch (SftpException error) {
                if (error.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) return false;
                throw error;
            }
        }

        @Override String changeDirectory(String path) throws SftpException {
            if (path != null) channel.cd(escaped(path));
            return channel.pwd();
        }

        @Override List<Entry> entries() throws SftpException {
            List<Entry> results = new ArrayList<>();
            for (ChannelSftp.LsEntry item : channel.ls(".")) {
                SftpATTRS attributes = item.getAttrs();
                results.add(new Entry(item.getFilename(), attributes.isDir(), attributes.isLink(),
                    attributes.getSize()));
            }
            return results;
        }

        @Override void makeDirectory(String name) throws SftpException { channel.mkdir(name); }

        @Override void move(String from, String to) throws SftpException {
            channel.rename(escaped(from), escaped(to));
        }

        @Override void remove(String name, boolean directory) throws SftpException {
            if (directory) channel.rmdir(escaped(name));
            else channel.rm(escaped(name));
        }

        @Override void download(String name, OutputStream output) throws SftpException {
            channel.get(escaped(name), output);
        }

        @Override void writeFile(String name, InputStream input) throws SftpException {
            channel.put(input, escaped(name));
        }

        @Override public void close() { transport.close(); }

        private static String escaped(String path) {
            return path.replace("\\", "\\\\").replace("*", "\\*").replace("?", "\\?");
        }
    }

    private static final class Ftp extends RemoteFiles {
        private final FTPClient client;

        Ftp(ServerProfiles.Protocol protocol) throws Exception {
            if (protocol == ServerProfiles.Protocol.FTP) {
                client = new FTPClient();
            } else {
                SSLContext context = SSLContext.getInstance("TLS");
                context.init(null, null, null);
                client = new VerifiedFtps(protocol == ServerProfiles.Protocol.IMPLICIT_FTPS, context);
            }
            client.setConnectTimeout(12_000);
            client.setDefaultTimeout(20_000);
            client.setDataTimeout(Duration.ofSeconds(30));
            client.setControlKeepAliveTimeout(Duration.ofSeconds(15));
            client.setControlEncoding("UTF-8");
            client.setUseEPSVwithIPv4(true);
            client.setListHiddenFiles(true);
            client.setIpAddressFromPasvResponse(false);
        }

        @Override void connect(ServerProfiles.Profile profile, ServerProfiles.Credentials credentials,
                               SshTransport.Trust trust) throws IOException {
            String password = new String(credentials.password(), StandardCharsets.UTF_8);
            if (password.indexOf('\r') >= 0 || password.indexOf('\n') >= 0
                || profile.user().codePoints().anyMatch(Character::isISOControl)) {
                throw new IOException("FTP 凭据包含无效字符。");
            }
            client.connect(profile.host(), profile.port());
            require(FTPReply.isPositiveCompletion(client.getReplyCode()), "连接文件服务失败");
            require(client.login(profile.user(), password), "登录验证失败");
            if (client instanceof FTPSClient tls) {
                tls.execPBSZ(0);
                tls.execPROT("P");
            }
            client.enterLocalPassiveMode();
            require(client.setFileType(FTP.BINARY_FILE_TYPE), "无法切换到二进制传输");
        }

        @Override boolean connected() { return client.isConnected() && client.getReplyCode() != 421; }

        @Override String changeDirectory(String path) throws IOException {
            if (path != null) {
                if (path.codePoints().anyMatch(Character::isISOControl)) throw new IOException("无效的目录路径。");
                require(client.changeWorkingDirectory(path), "无法打开目录");
            }
            String directory = client.printWorkingDirectory();
            require(directory != null, "无法获取当前目录");
            return directory;
        }

        @Override List<Entry> entries() throws IOException {
            FTPFile[] listing = client.hasFeature("MLST") ? client.mlistDir() : client.listFiles();
            if (commandUnsupported(client.getReplyCode())) listing = client.listFiles();
            if (commandUnsupported(client.getReplyCode()) && client.getListHiddenFiles()) {
                client.setListHiddenFiles(false);
                listing = client.listFiles();
            }
            require(FTPReply.isPositiveCompletion(client.getReplyCode()), "无法读取目录");
            List<Entry> results = new ArrayList<>();
            for (FTPFile item : listing) {
                if (item != null && item.isValid() && item.getName() != null) {
                    results.add(new Entry(item.getName(), item.isDirectory(), item.isSymbolicLink(), item.getSize()));
                }
            }
            return results;
        }

        @Override void makeDirectory(String name) throws IOException {
            require(client.makeDirectory(name), "无法新建文件夹");
        }

        @Override void move(String from, String to) throws IOException {
            require(client.rename(from, to), "无法重命名");
        }

        @Override void remove(String name, boolean directory) throws IOException {
            require(directory ? client.removeDirectory(name) : client.deleteFile(name), "无法删除");
        }

        @Override void download(String name, OutputStream output) throws IOException {
            boolean transferred;
            try {
                transferred = client.retrieveFile(name, output);
            } catch (IOException error) {
                close();
                throw error;
            }
            require(transferred, "下载失败");
        }

        @Override void writeFile(String name, InputStream input) throws IOException {
            boolean transferred;
            try {
                transferred = client.storeFile(name, input);
            } catch (IOException error) {
                close();
                throw error;
            }
            require(transferred, "上传失败");
        }

        private static boolean commandUnsupported(int reply) {
            return reply == 500 || reply == 501 || reply == 502 || reply == 504;
        }

        private void require(boolean ok, String message) throws IOException {
            if (!ok) throw new IOException(message + "（FTP " + client.getReplyCode() + "）");
        }

        @Override public void close() {
            if (!client.isConnected()) return;
            try {
                client.disconnect();
            } catch (IOException ignored) {
            }
        }
    }

    private static final class VerifiedFtps extends FTPSClient {
        VerifiedFtps(boolean implicit, SSLContext context) {
            super(implicit, context);
            setEndpointCheckingEnabled(true);
        }

        @Override protected Socket _openDataConnection_(String command, String path) throws IOException {
            Socket data = super._openDataConnection_(command, path);
            if (data == null) return null;
            try {
                if (data instanceof SSLSocket encrypted && _socket_ instanceof SSLSocket control
                    && encrypted.getSession().getPeerCertificates()[0].equals(
                        control.getSession().getPeerCertificates()[0])) return data;
                throw new SSLPeerUnverifiedException("FTPS 数据连接证书与控制连接不一致。");
            } catch (IOException | RuntimeException error) {
                data.close();
                throw error;
            }
        }
    }
}
