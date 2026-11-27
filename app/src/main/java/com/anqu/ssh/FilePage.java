package com.anqu.ssh;

import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.MimeTypeMap;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.SftpException;

import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLException;

final class FilePage implements AutoCloseable {
    static final int UPLOAD = 201;
    static final int DOWNLOAD = 202;
    static final int DOWNLOAD_BATCH = 203;

    private final MainActivity activity;
    private final ServerProfiles.Credentials credentials;
    private final FileSession session;
    private final AppPreferences.FileOrder fileOrder;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final LinearLayout folderBar;
    private final TextView pathLabel;
    private final TextView status;
    private final TextView empty;
    private final ProgressBar progress;
    private final LinearLayout toolbar;
    private final Button selectionButton;
    private final LinearLayout selectionBar;
    private final TextView selectionCount;
    private final Button selectAll;
    private final Button batchDelete;
    private final Button batchDownload;
    private final ListView rows;
    private final FolderAdapter adapter = new FolderAdapter();
    private final Map<String, RemoteFiles.Entry> selected = new LinkedHashMap<>();
    private List<RemoteFiles.Entry> entries = Collections.emptyList();
    private volatile boolean closed;
    private boolean busy;
    private boolean changing;
    private boolean selecting;
    private String path;
    private RemoteFiles.Entry downloading;
    private List<RemoteFiles.Entry> downloadQueue = Collections.emptyList();
    private QizhouDialog trustDialog;
    private volatile CompletableFuture<Boolean> trustReply;
    private String trustError;
    private long nextProgress;

    FilePage(MainActivity activity, LinearLayout root, ServerProfiles.Profile profile,
             ServerProfiles.Credentials credentials, AppPreferences.FileOrder fileOrder) {
        this.activity = activity;
        this.credentials = credentials;
        this.fileOrder = fileOrder;
        session = new FileSession(profile, credentials, this::verify);
        LinearLayout title = activity.pageHeader(root, "文件管理", activity::leaveFiles);
        folderBar = activity.column();
        folderBar.setPadding(activity.dp(13), activity.dp(9), activity.dp(13), activity.dp(10));
        folderBar.setBackground(activity.surface(0xff213a34, MainActivity.BORDER, 14));
        TextView server = activity.text(profile.title() + " · " +
            (profile.protocol() == ServerProfiles.Protocol.SSH ? "SFTP" : profile.protocol().label),
            12, MainActivity.ACCENT, true);
        server.setSingleLine(true);
        server.setEllipsize(TextUtils.TruncateAt.END);
        activity.add(folderBar, server, 0);
        pathLabel = activity.text("正在连接", 15, MainActivity.TEXT, true);
        pathLabel.setSingleLine(true);
        pathLabel.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        folderBar.setOnClickListener(view -> openPath());
        folderBar.setContentDescription("当前目录");
        LinearLayout pathRow = new LinearLayout(activity);
        pathRow.setGravity(Gravity.CENTER_VERTICAL);
        pathRow.addView(pathLabel, new LinearLayout.LayoutParams(0, -2, 1));
        TextView jump = activity.text("跳转", 12, MainActivity.ACCENT, true);
        jump.setPadding(activity.dp(10), 0, 0, 0);
        pathRow.addView(jump);
        activity.add(folderBar, pathRow, 3);
        activity.add(title, folderBar, 12);

        toolbar = new LinearLayout(activity);
        activity.addTool(toolbar, UiIcon.Kind.UP, "上级", () -> {
            if (path != null && !path.equals("/")) reload(RemoteFiles.parent(path));
        });
        activity.addTool(toolbar, UiIcon.Kind.REFRESH, "刷新", () -> reload(path));
        activity.addTool(toolbar, UiIcon.Kind.PLUS, "新建", () -> changeName(null));
        activity.addTool(toolbar, UiIcon.Kind.UPLOAD, "上传", this::pickUpload);
        selectionButton = activity.addTool(toolbar, UiIcon.Kind.SELECT, "多选", () -> setSelecting(!selecting));
        activity.add(title, toolbar, 10);

        progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressPlace = new LinearLayout.LayoutParams(-1, activity.dp(4));
        progressPlace.topMargin = activity.dp(5);
        title.addView(progress, progressPlace);
        status = activity.text("", 13, MainActivity.MUTED, false);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setMaxLines(3);
        activity.add(title, status, 6);

        rows = new ListView(activity);
        rows.setDivider(null);
        rows.setDividerHeight(activity.dp(5));
        rows.setClipToPadding(false);
        rows.setPadding(activity.dp(14), activity.dp(3), activity.dp(14), activity.dp(10));
        root.addView(rows, new LinearLayout.LayoutParams(-1, 0, 1));
        rows.setAdapter(adapter);
        empty = activity.text("", 15, MainActivity.MUTED, false);
        empty.setGravity(Gravity.CENTER);
        root.addView(empty, new LinearLayout.LayoutParams(-1, 0, 1));
        rows.setEmptyView(empty);

        selectionBar = activity.column();
        selectionBar.setPadding(activity.dp(16), activity.dp(9), activity.dp(16), activity.dp(11));
        selectionBar.setBackgroundColor(0xff20362f);
        selectionCount = activity.text("", 13, MainActivity.ACCENT, true);
        activity.add(selectionBar, selectionCount, 0);
        LinearLayout buttons = new LinearLayout(activity);
        selectAll = activity.addTool(buttons, "全选", this::toggleAll);
        batchDownload = activity.addTool(buttons, "下载", this::pickBatchDownload);
        batchDelete = activity.addTool(buttons, "删除", () -> delete(selection()));
        batchDelete.setTextColor(MainActivity.DANGER);
        activity.add(selectionBar, buttons, 4);
        root.addView(selectionBar);
        selectionBar.setVisibility(View.GONE);
        reload(null);
    }

    void onFilePicked(int request, Intent data) {
        if (closed || data == null || busy || path == null) return;
        if (request == UPLOAD) {
            Set<Uri> picked = new LinkedHashSet<>();
            if (data.getData() != null) picked.add(data.getData());
            ClipData clip = data.getClipData();
            if (clip != null) {
                for (int i = 0; i < clip.getItemCount(); i++) {
                    Uri uri = clip.getItemAt(i).getUri();
                    if (uri != null) picked.add(uri);
                }
            }
            if (!picked.isEmpty()) batch("上传", new ArrayList<>(picked), true, this::upload);
        } else if (request == DOWNLOAD && data.getData() != null && downloading != null) {
            RemoteFiles.Entry item = downloading;
            Uri target = data.getData();
            downloading = null;
            start("正在下载", path, false, (files, directory) -> {
                download(files, item, target);
                return new Result(null, "已保存到手机");
            }, " · 本地文件可能不完整");
        } else if (request == DOWNLOAD_BATCH && data.getData() != null && !downloadQueue.isEmpty()) {
            Uri tree = data.getData();
            List<RemoteFiles.Entry> queue = downloadQueue;
            downloadQueue = Collections.emptyList();
            batch("下载", queue, false, (files, item) -> downloadToFolder(files, item, tree));
        }
    }

    private void upload(RemoteFiles files, Uri uri) throws Exception {
        String fileName = activity.fileName(uri);
        if (!RemoteFiles.validName(fileName)) throw new IOException("所选文件的名称无法上传。");
        try (InputStream source = activity.getContentResolver().openInputStream(uri)) {
            if (source == null) throw new IOException("无法读取所选文件。");
            files.upload(fileName, new FilterInputStream(source) {
                long bytes;
                @Override public int read(byte[] b, int offset, int length) throws IOException {
                    int count = in.read(b, offset, length);
                    if (count > 0) reportProgress("上传 · " + fileName, bytes += count);
                    return count;
                }

                @Override public int read() throws IOException {
                    int value = in.read();
                    if (value >= 0) reportProgress("上传 · " + fileName, ++bytes);
                    return value;
                }
            });
        }
    }

    private void download(RemoteFiles files, RemoteFiles.Entry item, Uri uri) throws Exception {
        try (OutputStream target = activity.getContentResolver().openOutputStream(uri, "wt")) {
            if (target == null) throw new IOException("无法写入所选位置。");
            files.download(item.name(), new FilterOutputStream(target) {
                long bytes;
                @Override public void write(byte[] b, int offset, int count) throws IOException {
                    out.write(b, offset, count);
                    reportProgress("下载 · " + item.name(), bytes += count);
                }

                @Override public void write(int value) throws IOException {
                    out.write(value);
                    reportProgress("下载 · " + item.name(), ++bytes);
                }
            });
        }
    }

    private void downloadToFolder(RemoteFiles files, RemoteFiles.Entry item, Uri tree) throws Exception {
        String mime = mimeType(item.name());
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        Uri created = DocumentsContract.createDocument(activity.getContentResolver(), parent, mime, item.name());
        if (created == null) throw new IOException("手机无法新建文件。");
        try {
            download(files, item, created);
        } catch (Exception error) {
            try {
                DocumentsContract.deleteDocument(activity.getContentResolver(), created);
            } catch (Exception ignored) {
            }
            throw error;
        }
    }

    private static String mimeType(String name) {
        int dot = name.lastIndexOf('.');
        String mime = dot < 0 ? null : MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase(Locale.ROOT));
        return mime == null ? "application/octet-stream" : mime;
    }

    private void pickUpload() {
        if (busy || path == null) return;
        activity.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), UPLOAD);
    }

    private void pickDownload(RemoteFiles.Entry item) {
        if (busy || item.directory()) return;
        downloading = item;
        activity.startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE).setType(mimeType(item.name()))
            .putExtra(Intent.EXTRA_TITLE, item.name()), DOWNLOAD);
    }

    private void pickBatchDownload() {
        if (busy) return;
        List<RemoteFiles.Entry> queue = selection();
        if (queue.isEmpty() || queue.stream().anyMatch(RemoteFiles.Entry::directory)) return;
        downloadQueue = queue;
        activity.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), DOWNLOAD_BATCH);
    }

    private void render(RemoteFiles.Folder directory) {
        boolean different = !directory.path().equals(path);
        path = directory.path();
        pathLabel.setText(path);
        folderBar.setContentDescription("当前目录 " + path + "，点按跳转");
        entries = directory.entries();
        empty.setText("此文件夹为空");
        if (different) {
            selecting = false;
            selected.clear();
        } else {
            selected.keySet().retainAll(entries.stream()
                .map(RemoteFiles.Entry::name).collect(java.util.stream.Collectors.toSet()));
            for (RemoteFiles.Entry item : entries) selected.replace(item.name(), item);
            if (selected.isEmpty() && entries.isEmpty()) selecting = false;
        }
        adapter.notifyDataSetChanged();
        if (different) rows.setSelection(0);
    }

    private final class FolderAdapter extends BaseAdapter {
        @Override public int getCount() { return entries.size(); }
        @Override public RemoteFiles.Entry getItem(int index) { return entries.get(index); }
        @Override public long getItemId(int index) { return index; }
        @Override public View getView(int index, View convertView, ViewGroup parent) {
            FileRow row = convertView instanceof FileRow existing ? existing : new FileRow();
            row.bind(getItem(index));
            return row;
        }
    }

    private final class FileRow extends LinearLayout {
        private final ImageView fileIcon;
        private final TextView name;
        private final TextView detail;
        private final ImageButton menu;

        FileRow() {
            super(activity);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(activity.dp(9), activity.dp(7), activity.dp(6), activity.dp(7));
            setMinimumHeight(activity.dp(60));
            fileIcon = new ImageView(activity);
            fileIcon.setPadding(activity.dp(10), activity.dp(10), activity.dp(10), activity.dp(10));
            fileIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            LinearLayout.LayoutParams iconPlace = new LinearLayout.LayoutParams(activity.dp(40), activity.dp(40));
            iconPlace.rightMargin = activity.dp(10);
            addView(fileIcon, iconPlace);
            LinearLayout labels = activity.column();
            addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
            name = activity.text("", 15, MainActivity.TEXT, true);
            name.setMaxLines(2);
            name.setEllipsize(TextUtils.TruncateAt.END);
            activity.add(labels, name, 0);
            detail = activity.text("", 12, MainActivity.MUTED, false);
            activity.add(labels, detail, 2);
            menu = activity.iconButton(UiIcon.Kind.MORE, "操作", null);
            menu.setFocusable(false);
            addView(menu, new LinearLayout.LayoutParams(activity.dp(44), activity.dp(44)));
        }

        void bind(RemoteFiles.Entry item) {
            boolean checked = selected.containsKey(item.name());
            UiIcon.Kind kind = checked ? UiIcon.Kind.CHECK : item.directory() ? UiIcon.Kind.FOLDER
                : item.link() ? UiIcon.Kind.LINK : UiIcon.Kind.FILE;
            fileIcon.setImageDrawable(new UiIcon(kind, checked ? 0xff10261f : MainActivity.ACCENT));
            fileIcon.setBackground(activity.surface(checked ? MainActivity.ACCENT : 0xff233a32, 0xff314940, 11));
            setBackground(activity.surface(checked ? 0xff233d34 : MainActivity.PANEL,
                checked ? 0xff629e85 : MainActivity.BORDER, 12));
            name.setText(item.name());
            detail.setText(item.directory() ? "文件夹" : item.link() ? "链接" : size(item.bytes()));
            setOnClickListener(view -> {
                if (busy) return;
                if (selecting) toggle(item);
                else if (item.directory()) reload(RemoteFiles.child(path, item.name()));
                else openMenu(item);
            });
            setOnLongClickListener(view -> {
                if (busy) return false;
                if (!selecting) setSelecting(true);
                toggle(item);
                return true;
            });
            menu.setVisibility(selecting ? View.GONE : View.VISIBLE);
            menu.setContentDescription(item.name() + "的操作");
            menu.setOnClickListener(view -> openMenu(item));
        }
    }

    private void setSelecting(boolean value) {
        if (busy) return;
        selecting = value;
        if (!value) selected.clear();
        updateSelection();
    }

    private void toggle(RemoteFiles.Entry item) {
        if (selected.putIfAbsent(item.name(), item) != null) selected.remove(item.name());
        updateSelection();
    }

    private void toggleAll() {
        if (selected.size() == entries.size()) selected.clear();
        else for (RemoteFiles.Entry item : entries) selected.put(item.name(), item);
        updateSelection();
    }

    private List<RemoteFiles.Entry> selection() {
        return new ArrayList<>(selected.values());
    }

    private void updateSelection() {
        selectionBar.setVisibility(selecting ? View.VISIBLE : View.GONE);
        selectionButton.setText(selecting ? "完成" : "多选");
        int count = selected.size();
        selectionCount.setText("已选 " + count + " / " + entries.size() + " 项");
        selectAll.setText(count > 0 && count == entries.size() ? "取消全选" : "全选");
        selectAll.setEnabled(!busy && !entries.isEmpty());
        batchDelete.setEnabled(!busy && count > 0);
        boolean folders = count > 0 && selected.values().stream().anyMatch(RemoteFiles.Entry::directory);
        batchDownload.setEnabled(!busy && count > 0 && !folders);
        batchDownload.setContentDescription(folders ? "下载，仅支持选中的文件" : "下载所选文件");
        adapter.notifyDataSetChanged();
    }

    private void openMenu(RemoteFiles.Entry item) {
        if (busy) return;
        new QizhouDialog(activity, item.name())
            .option(item.directory() ? "打开文件夹" : "下载到手机",
                item.directory() ? UiIcon.Kind.FOLDER : UiIcon.Kind.DOWNLOAD, () -> {
                    if (item.directory()) reload(RemoteFiles.child(path, item.name()));
                    else pickDownload(item);
                })
            .option("重命名", UiIcon.Kind.EDIT, () -> changeName(item))
            .option("删除", UiIcon.Kind.TRASH, () -> delete(Collections.singletonList(item)))
            .action("关闭", false, null).show();
    }

    private void openPath() {
        if (busy || path == null) return;
        LinearLayout form = activity.column();
        EditText input = activity.field(form, "目录路径", "/", InputType.TYPE_CLASS_TEXT, path);
        QizhouDialog dialog = new QizhouDialog(activity, "跳转目录").content(form);
        dialog.action("取消", false, null).keepOpen("跳转", () -> {
            String destination = input.getText().toString();
            if (!destination.startsWith("/") || destination.codePoints().anyMatch(Character::isISOControl)) {
                input.setError("请输入有效的绝对路径");
                return;
            }
            dialog.dismiss();
            reload(destination);
        }).show();
    }

    private void changeName(RemoteFiles.Entry item) {
        if (busy || path == null) return;
        LinearLayout form = activity.column();
        EditText input = activity.field(form, "名称", "文件夹名称", InputType.TYPE_CLASS_TEXT,
            item == null ? "" : item.name());
        QizhouDialog dialog = new QizhouDialog(activity, item == null ? "新建文件夹" : "重命名").content(form);
        dialog.action("取消", false, null).keepOpen("保存", () -> {
            String name = input.getText().toString();
            if (!RemoteFiles.validName(name)) {
                input.setError("请输入有效名称");
                return;
            }
            dialog.dismiss();
            if (item != null && name.equals(item.name())) return;
            start(item == null ? "正在新建" : "正在重命名", path, false, (files, directory) -> {
                files.ensureAvailable(name);
                if (item == null) files.makeDirectory(name);
                else files.move(item.name(), name);
                return new Result(readFolder(files, directory), "操作完成");
            }, " · 刷新可核对服务器状态");
        }).show();
    }

    private void delete(List<RemoteFiles.Entry> targets) {
        if (busy || targets.isEmpty()) return;
        boolean hasFolder = targets.stream().anyMatch(RemoteFiles.Entry::directory);
        String heading = targets.size() == 1 ? "删除此项目？" : "删除选中的 " + targets.size() + " 项？";
        String note = targets.size() == 1 ? targets.get(0).name() + "\n" : "";
        note += hasFolder ? "服务器上的文件和空文件夹会被删除。非空文件夹会跳过。" : "选中的文件会从服务器删除。";
        new QizhouDialog(activity, heading).message(note)
            .action("取消", false, null)
            .destructive("确认删除", () -> batch("删除", targets, true,
                (files, item) -> files.remove(item.name(), item.directory())))
            .show();
    }

    private <T> void batch(String label, List<T> targets, boolean refresh, ItemAction<T> action) {
        start("正在" + label, path, false, (files, directory) -> {
            FileBatch.Report report = FileBatch.run(files, targets, () -> closed,
                count -> showProgress(label + " · " + count), this::describe, item -> action.run(files, item));
            RemoteFiles.Folder folder = null;
            if (refresh) {
                try {
                    folder = readFolder(files, directory);
                } catch (Exception error) {
                    throw new FileBatch.Interrupted(report.summary() + "\n目录未能刷新，请手动刷新。", error);
                }
            }
            return new Result(folder, report.summary());
        }, "");
    }

    private void reload(String directory) {
        start("正在读取目录", directory, true,
            (files, canonical) -> new Result(readFolder(files, canonical), ""), "");
    }

    private void start(String message, String directory, boolean readOnly, FileSession.Task<Result> work,
                       String suffix) {
        if (closed || busy) return;
        busy = true;
        changing = !readOnly;
        updateControls();
        setStatus(message);
        nextProgress = 0;
        worker.execute(() -> {
            trustError = null;
            try {
                Result result = session.execute(directory, readOnly, work);
                activity.runOnUiThread(() -> {
                    if (closed) return;
                    if (result.folder() != null) render(result.folder());
                    end(result.status());
                });
            } catch (Exception error) {
                String detail = describe(error) + suffix;
                activity.runOnUiThread(() -> {
                    if (closed) return;
                    end(detail);
                });
            }
        });
    }

    private RemoteFiles.Folder readFolder(RemoteFiles files, String directory) throws Exception {
        return fileOrder == AppPreferences.FileOrder.SIZE
            ? files.readFolder(directory, RemoteFiles.LARGEST_FIRST) : files.readFolder(directory);
    }

    private void end(String message) {
        busy = false;
        changing = false;
        setStatus(message);
        updateControls();
    }

    private void setStatus(String message) {
        status.setText(message);
        status.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private String describe(Exception error) {
        if (trustError != null) return trustError;
        if (error instanceof FileBatch.Interrupted) return error.getMessage();
        if (error instanceof SSLException) return "FTPS 验证或加密连接失败，请检查证书和 TLS 模式。";
        if (error instanceof SshTransport.AuthenticationFailed) return "登录失败，请检查用户名和密码。";
        if (error instanceof SshTransport.InvalidKey) return "无法读取私钥，请检查文件和口令。";
        if (error instanceof SftpException sftp) {
            return switch (sftp.id) {
                case ChannelSftp.SSH_FX_NO_SUCH_FILE -> "文件或目录已不存在。";
                case ChannelSftp.SSH_FX_PERMISSION_DENIED -> "服务器拒绝了该文件操作。";
                case ChannelSftp.SSH_FX_OP_UNSUPPORTED -> "服务器不支持此操作。";
                default -> "SFTP 操作失败（状态 " + sftp.id + "）。";
            };
        }
        if (error instanceof IOException && error.getMessage() != null
            && error.getMessage().matches("^[\\u4e00-\\u9fff].*")) return error.getMessage();
        return "文件连接或操作失败，请检查网络后刷新。";
    }

    private boolean verify(String host, byte[] digest) {
        if (closed) return false;
        String fp = HostPins.fingerprint(digest);
        SharedPreferences pins = activity.getSharedPreferences("host_keys", android.content.Context.MODE_PRIVATE);
        String saved = pins.getString(host, null);
        if (fp.equals(saved)) return true;
        if (saved != null) {
            trustError = "服务器指纹已变化，连接已停止。";
            return false;
        }
        CompletableFuture<Boolean> reply = new CompletableFuture<>();
        trustReply = reply;
        activity.runOnUiThread(() -> {
            if (closed || reply.isDone()) return;
            trustDialog = new QizhouDialog(activity, "核对服务器指纹")
                .message(host + "\n\n" + fp + "\n\n请和服务器管理员提供的 SHA-256 指纹核对。")
                .action("取消", false, () -> reply.complete(false))
                .action("信任", true, () -> reply.complete(true));
            trustDialog.setOnCancelListener(dialog -> reply.complete(false));
            trustDialog.show();
        });
        try {
            if (reply.get(90, TimeUnit.SECONDS) && !closed) {
                if (HostPins.confirm(pins, host, fp)) return true;
                trustError = "无法保存或确认服务器指纹。";
                return false;
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
        } finally {
            reply.complete(false);
            activity.runOnUiThread(() -> {
                if (trustDialog != null) trustDialog.dismiss();
                trustDialog = null;
            });
            trustReply = null;
        }
        trustError = "未确认服务器指纹。";
        return false;
    }

    private void updateControls() {
        for (int i = 0; i < toolbar.getChildCount(); i++) {
            boolean usable = !busy && (i == 1 || path != null) && (i != 0 || !"/".equals(path));
            if (i == 4) usable &= selecting || !entries.isEmpty();
            toolbar.getChildAt(i).setEnabled(usable);
        }
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        updateSelection();
    }

    private void reportProgress(String prefix, long bytes) {
        long now = System.nanoTime();
        if (now < nextProgress) return;
        nextProgress = now + TimeUnit.MILLISECONDS.toNanos(250);
        showProgress(prefix + " · " + size(bytes));
    }

    private void showProgress(String message) {
        activity.runOnUiThread(() -> { if (!closed && busy) setStatus(message); });
    }

    boolean handleBack(Runnable leave) {
        if (busy && changing) {
            new QizhouDialog(activity, "结束文件操作？")
                .message("已完成的操作会保留。关闭连接可能中断当前项目。")
                .action("继续操作", false, null).destructive("结束并离开", leave).show();
            return true;
        }
        if (selecting) {
            selecting = false;
            selected.clear();
            updateSelection();
            return true;
        }
        return false;
    }

    private static String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
        return String.format(Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        CompletableFuture<Boolean> reply = trustReply;
        if (reply != null) reply.complete(false);
        if (trustDialog != null) trustDialog.dismiss();
        new Thread(session::close, "file-connection-close").start();
        worker.execute(credentials::close);
        worker.shutdown();
    }

    private record Result(RemoteFiles.Folder folder, String status) {}
    private interface ItemAction<T> { void run(RemoteFiles files, T item) throws Exception; }
}
