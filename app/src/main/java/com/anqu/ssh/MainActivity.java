package com.anqu.ssh;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.IDN;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;

import com.anqu.ssh.ServerProfiles.Credentials;
import com.anqu.ssh.ServerProfiles.Profile;
import com.anqu.ssh.ServerProfiles.Protocol;

public final class MainActivity extends Activity {
    private static final int KEY_PICKER = 101;
    private static final int NOTIFICATION_PERMISSION = 102;
    private static final int MAX_KEY_BYTES = 131_072;
    static final int BG = 0xff111c1b;
    static final int PANEL = 0xff1b2a27;
    static final int BORDER = 0xff354943;
    static final int TEXT = 0xfff0f3e7;
    static final int MUTED = 0xffafc3b9;
    static final int ACCENT = 0xffa6e3c5;
    static final int DANGER = 0xffffb9ad;
    private static final int AMBER = 0xffffcd80;
    private static final int[] HEARTBEATS = AppPreferences.HEARTBEATS;
    private final Runnable observer = this::refresh;
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((SshService.LocalBinder) binder).service();
            service.setActivityVisible(visible);
            if (service.active() && filePage == null && !editing && !showingSettings) showTerminal();
            service.observe(observer);
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            service = null;
            if (showingTerminal) showHome();
        }
    };

    private SshService service;
    private boolean bound;
    private boolean resumed;
    private boolean visible;
    private boolean showingTerminal;
    private boolean showingSettings;
    private boolean settingsFromTerminal;
    private LinearLayout root;
    private SharedPreferences settings;
    private AppPreferences preferences;
    private ServerProfiles profiles;
    private Profile editingProfile;
    private boolean editing;
    private boolean removedKey;
    private FilePage filePage;
    private boolean returnToTerminal;
    private EditText displayName;
    private Spinner protocol;
    private CheckBox remember;
    private EditText host;
    private EditText port;
    private EditText user;
    private EditText secret;
    private CheckBox tmux;
    private Spinner heartbeat;
    private Button keyButton;
    private TextView formMessage;
    private SshService.Status lastStatus;
    private TextView statusTitle;
    private TextView statusDetail;
    private Button terminalAction;
    private Button pasteButton;
    private TerminalView terminalView;
    private Button homeResume;
    private byte[] keyBytes;
    private String keyName;
    private QizhouDialog fingerprintDialog;
    private SshService.Verify shownVerification;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setFocusableInTouchMode(true);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                int ime = insets.getInsets(WindowInsets.Type.ime()).bottom;
                view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime));
                return insets;
            });
        }
        setContentView(root);
        settings = getSharedPreferences("connection", MODE_PRIVATE);
        preferences = new AppPreferences(this);
        profiles = new ServerProfiles(this);
        showHome();
        bound = bindService(new Intent(this, SshService.class), connection, BIND_AUTO_CREATE);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (SshService.SHOW_TERMINAL.equals(intent.getAction()) && service != null
            && service.active() && filePage == null && !editing && !showingSettings) showTerminal();
    }

    @Override protected void onStart() {
        super.onStart();
        visible = true;
        if (service != null) service.setActivityVisible(true);
    }

    @Override protected void onStop() {
        visible = false;
        if (service != null) service.setActivityVisible(false);
        super.onStop();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        refresh();
    }

    @Override protected void onPause() {
        resumed = false;
        dismissFingerprint();
        super.onPause();
    }

    @Override protected void onDestroy() {
        dismissFingerprint();
        if (service != null) service.unobserve(observer);
        if (bound) unbindService(connection);
        clearKey();
        closeFiles();
        super.onDestroy();
    }

    private LinearLayout scrollPage() {
        root.removeAllViews();
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        LinearLayout page = column();
        page.setPadding(dp(20), dp(16), dp(20), dp(24));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        return page;
    }

    private void showHome() {
        closeFiles();
        clearKey();
        if (editing && secret != null) secret.setText("");
        editing = false;
        showingTerminal = false;
        showingSettings = false;
        terminalView = null;
        LinearLayout page = scrollPage();
        LinearLayout masthead = new LinearLayout(this);
        masthead.setGravity(Gravity.CENTER_VERTICAL);
        masthead.setPadding(dp(15), dp(12), dp(15), dp(12));
        masthead.setBackground(surface(0xff213831, 0xff38564b, 20));
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_terminal);
        icon.setBackground(surface(0xff205149, 0xff355950, 16));
        icon.setClipToOutline(true);
        masthead.addView(icon, new LinearLayout.LayoutParams(dp(65), dp(65)));
        TextView title = text("栖舟", 31, TEXT, true);
        title.setTypeface(Typeface.create("serif", Typeface.BOLD));
        title.setLetterSpacing(0.12f);
        LinearLayout.LayoutParams titlePlace = new LinearLayout.LayoutParams(0, -2, 1);
        titlePlace.leftMargin = dp(14);
        masthead.addView(title, titlePlace);
        masthead.addView(iconButton(UiIcon.Kind.SETTINGS, "应用设置", this::showSettings),
            new LinearLayout.LayoutParams(dp(46), dp(46)));
        add(page, masthead, 0);

        Button newServer = button("添加服务器", true);
        addButtonIcon(newServer, UiIcon.Kind.PLUS, 0xff10251d);
        newServer.setOnClickListener(view -> showEditor(null, false));
        add(page, newServer, 20);

        homeResume = button("", false);
        homeResume.setOnClickListener(view -> {
            if (service != null && service.active()) showTerminal();
        });
        add(page, homeResume, 8);
        refreshHomeResume();
        TextView servers = text("服务器", 19, TEXT, true);
        if (android.os.Build.VERSION.SDK_INT >= 28) servers.setAccessibilityHeading(true);
        add(page, servers, 23);
        if (profiles.all().isEmpty()) {
            TextView empty = text("暂无服务器", 15, MUTED, false);
            empty.setGravity(Gravity.CENTER);
            empty.setMinHeight(dp(84));
            empty.setBackground(surface(PANEL, BORDER, 15));
            add(page, empty, 12);
        }
        for (Profile profile : profiles.all()) add(page, serverCard(profile), 10);
        root.requestFocus();
    }

    private View serverCard(Profile profile) {
        LinearLayout card = column();
        card.setPadding(dp(13), dp(8), dp(13), dp(12));
        card.setBackground(surface(PANEL, BORDER, 15));
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout information = column();
        heading.addView(information, new LinearLayout.LayoutParams(0, -2, 1));
        add(information, text(profile.title(), 17, TEXT, true), 0);
        add(information, text(profile.user() + "@" + profile.address(), 13, MUTED, false), 3);
        add(information, text(profile.protocol().label + (profile.hasSavedLogin() ? " · 凭据已保存" : ""),
            12, ACCENT, false), 5);
        ImageButton menu = iconButton(UiIcon.Kind.MORE, profile.title() + "的设置", () ->
            new QizhouDialog(this, profile.title())
                .option("编辑服务器", UiIcon.Kind.EDIT, () -> showEditor(profile, false))
                .option("移除服务器", UiIcon.Kind.TRASH, () -> confirmRemove(profile))
                .action("关闭", false, null).show());
        heading.addView(menu, new LinearLayout.LayoutParams(dp(48), dp(44)));
        card.addView(heading);

        LinearLayout actions = new LinearLayout(this);
        if (profile.protocol() == Protocol.SSH) {
            addButtonIcon(addTool(actions, "终端", () -> openProfile(profile, false)), UiIcon.Kind.TERMINAL, ACCENT);
        }
        addButtonIcon(addTool(actions, "文件管理", () -> openProfile(profile, true)), UiIcon.Kind.FOLDER, ACCENT);
        add(card, actions, 10);
        return card;
    }

    private void confirmRemove(Profile profile) {
        new QizhouDialog(this, "移除「" + profile.title() + "」？")
            .message("手机上保存的信息和凭据会一起删除。")
            .action("取消", false, null).destructive("移除", () -> {
                try {
                    profiles.remove(profile);
                    if (service != null && profile.id().equals(service.profileId()) && service.active()) {
                        service.stopConnection();
                    }
                    showHome();
                } catch (Exception error) {
                    toast("无法移除服务器");
                }
            }).show();
    }

    private void showEditor(Profile profile, boolean openFilesAfterSaving) {
        closeFiles();
        clearKey();
        removedKey = false;
        editingProfile = profile;
        editing = true;
        showingTerminal = false;
        terminalView = null;
        LinearLayout page = scrollPage();
        pageHeader(page, profile == null ? "添加服务器" : "编辑服务器", this::showHome);
        LinearLayout card = column();
        card.setPadding(dp(15), dp(10), dp(15), dp(15));
        card.setBackground(surface(PANEL, BORDER, 15));
        add(page, card, 12);

        displayName = field(card, "显示名称", "例如 家用服务器（可留空）",
            InputType.TYPE_CLASS_TEXT, profile == null ? "" : profile.name());
        add(card, text("连接协议", 13, MUTED, true), 9);
        Protocol protocolValue = profile == null ? Protocol.SSH : profile.protocol();
        Protocol[] options = Protocol.values();
        String[] protocolLabels = new String[options.length];
        for (int i = 0; i < options.length; i++) protocolLabels[i] = options[i].label;
        protocol = selector(protocolLabels);
        protocol.setContentDescription("连接协议");
        protocol.setSelection(protocolValue.ordinal());
        card.addView(protocol, new LinearLayout.LayoutParams(-1, dp(44)));
        TextView ftpNote = text("FTP 会以明文传送登录信息和文件。", 13, AMBER, false);
        ftpNote.setVisibility(protocolValue == Protocol.FTP ? View.VISIBLE : View.GONE);
        add(card, ftpNote, 2);

        LinearLayout addressRow = new LinearLayout(this);
        card.addView(addressRow);
        LinearLayout hostField = column();
        LinearLayout portField = column();
        LinearLayout.LayoutParams hostPlace = new LinearLayout.LayoutParams(0, -2, 1);
        hostPlace.rightMargin = dp(10);
        addressRow.addView(hostField, hostPlace);
        addressRow.addView(portField, new LinearLayout.LayoutParams(dp(79), -2));
        host = field(hostField, "服务器地址", "IP 或域名",
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, profile == null ? "" : profile.host());
        port = field(portField, "端口", Integer.toString(protocolValue.port), InputType.TYPE_CLASS_NUMBER,
            Integer.toString(profile == null ? protocolValue.port : profile.port()));
        user = field(card, "用户名", "输入用户名", InputType.TYPE_CLASS_TEXT
            | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, profile == null ? "" : profile.user());
        secret = field(card, "密码", "输入密码", InputType.TYPE_CLASS_TEXT
            | InputType.TYPE_TEXT_VARIATION_PASSWORD, "");
        secret.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        keyButton = button("", false);
        keyButton.setOnClickListener(view -> {
            if (keyBytes != null || usesSavedKey()) {
                clearKey();
                removedKey = true;
                secret.setText("");
                updateKeyLabel();
                return;
            }
            Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            startActivityForResult(picker, KEY_PICKER);
        });
        add(card, keyButton, 8);
        updateKeyLabel();
        remember = new CheckBox(this);
        remember.setText("在此手机上加密保存登录凭据");
        remember.setTextColor(TEXT);
        remember.setChecked(profile == null ? preferences.rememberDefault() : profile.remember());
        add(card, remember, 8);

        LinearLayout sshOptions = column();
        sshOptions.setPadding(dp(13), dp(5), dp(13), dp(8));
        sshOptions.setBackground(surface(PANEL, BORDER, 15));
        add(page, sshOptions, 10);
        tmux = new CheckBox(this);
        tmux.setText("断线后恢复终端（服务器需已有 tmux）");
        tmux.setTextColor(TEXT);
        tmux.setChecked(profile != null && profile.restore());
        sshOptions.addView(tmux);

        LinearLayout interval = new LinearLayout(this);
        interval.setGravity(Gravity.CENTER_VERTICAL);
        interval.addView(text("SSH 心跳间隔", 14, TEXT, false), new LinearLayout.LayoutParams(0, -2, 1));
        String[] labels = new String[HEARTBEATS.length];
        for (int i = 0; i < labels.length; i++) labels[i] = HEARTBEATS[i] + " 秒";
        heartbeat = selector(labels);
        heartbeat.setContentDescription("SSH 心跳间隔");
        int currentHeartbeat = profile == null ? preferences.defaultHeartbeat() : profile.heartbeat();
        for (int i = 0; i < HEARTBEATS.length; i++) {
            if (HEARTBEATS[i] == currentHeartbeat) heartbeat.setSelection(i);
        }
        interval.addView(heartbeat, new LinearLayout.LayoutParams(dp(100), dp(43)));
        add(sshOptions, interval, 0);

        Button save = button("保存服务器", false);
        Button launch = button(openFilesAfterSaving ? "保存并打开文件" : "保存并连接", true);
        save.setOnClickListener(view -> saveProfile(false, false));
        launch.setOnClickListener(view -> saveProfile(true, openFilesAfterSaving));
        add(page, launch, 12);
        add(page, save, 8);
        formMessage = text("", 13, AMBER, false);
        formMessage.setVisibility(View.GONE);
        add(page, formMessage, 5);

        Protocol[] previous = {protocolValue};
        Runnable showOptions = () -> {
            Protocol mode = selectedProtocol();
            boolean ssh = mode == Protocol.SSH;
            if (mode != previous[0]) {
                if (port.getText().toString().equals(Integer.toString(previous[0].port))) {
                    port.setText(Integer.toString(mode.port));
                }
                secret.setText("");
            }
            previous[0] = mode;
            ftpNote.setVisibility(mode == Protocol.FTP ? View.VISIBLE : View.GONE);
            keyButton.setVisibility(ssh ? View.VISIBLE : View.GONE);
            sshOptions.setVisibility(ssh ? View.VISIBLE : View.GONE);
            updateKeyLabel();
        };
        for (EditText field : new EditText[]{host, port, user}) {
            field.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                    updateKeyLabel();
                }
                @Override public void afterTextChanged(android.text.Editable value) {}
            });
        }
        showOptions.run();
        protocol.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View row, int position, long id) {
                showOptions.run();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        root.requestFocus();
    }

    private Spinner selector(String[] labels) {
        Spinner selector = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        selector.setAdapter(adapter);
        return selector;
    }

    private Protocol selectedProtocol() { return Protocol.values()[protocol.getSelectedItemPosition()]; }

    private boolean sameFormLogin() {
        return editingProfile != null && selectedProtocol() == editingProfile.protocol()
            && host.getText().toString().trim().equalsIgnoreCase(editingProfile.host())
            && port.getText().toString().trim().equals(Integer.toString(editingProfile.port()))
            && user.getText().toString().trim().equals(editingProfile.user());
    }

    private boolean usesSavedKey() {
        return !removedKey && sameFormLogin() && !editingProfile.keyName().isEmpty()
            && selectedProtocol() == Protocol.SSH;
    }

    private void saveProfile(boolean launch, boolean files) {
        String hostname = host.getText().toString().trim();
        if (hostname.startsWith("[") && hostname.endsWith("]")) {
            hostname = hostname.substring(1, hostname.length() - 1);
        }
        try {
            if (hostname.indexOf(':') == -1) hostname = IDN.toASCII(hostname, IDN.USE_STD3_ASCII_RULES);
            hostname = hostname.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException error) {
            hostname = "";
        }
        if (hostname.isEmpty() || !hostname.matches("[A-Za-z0-9._:%-]+")
            || (hostname.contains(":") && hostname.chars().filter(c -> c == ':').count() < 2)) {
            host.setError("请输入 IP 地址或域名");
            host.requestFocus();
            return;
        }
        int serverPort;
        try {
            serverPort = Integer.parseInt(port.getText().toString().trim());
        } catch (NumberFormatException error) {
            serverPort = 0;
        }
        if (serverPort < 1 || serverPort > 65535) {
            port.setError("端口须在 1 到 65535 之间");
            port.requestFocus();
            return;
        }
        String login = user.getText().toString().trim();
        if (login.isEmpty() || login.codePoints().anyMatch(Character::isISOControl)) {
            user.setError("请输入有效用户名");
            user.requestFocus();
            return;
        }
        Protocol mode = selectedProtocol();
        Profile profile = new Profile(editingProfile == null ? UUID.randomUUID().toString() : editingProfile.id(),
            displayName.getText().toString().trim(), hostname, serverPort, login, mode, tmux.isChecked(),
            HEARTBEATS[heartbeat.getSelectedItemPosition()], remember.isChecked(), "", "");
        boolean canReuse = profile.sameLogin(editingProfile) && editingProfile.hasSavedLogin() && !removedKey;
        byte[] selectedKey = mode == Protocol.SSH ? keyBytes : null;
        boolean newLogin = selectedKey != null || secret.length() != 0
            || (mode == Protocol.FTP && "anonymous".equalsIgnoreCase(login) && !canReuse);
        Credentials credentials = null;
        try {
            String storedKeyName = "";
            if (selectedKey != null) storedKeyName = keyName;
            else if (canReuse) storedKeyName = editingProfile.keyName();
            if (newLogin) {
                byte[] privateKey = selectedKey == null ? null : selectedKey.clone();
                if (privateKey == null && !storedKeyName.isEmpty()) {
                    try (Credentials saved = profiles.unlock(editingProfile)) {
                        privateKey = saved.key().clone();
                    }
                }
                credentials = new Credentials(secret.getText().toString().getBytes(StandardCharsets.UTF_8),
                    privateKey);
            } else if (launch && canReuse) {
                credentials = profiles.unlock(editingProfile);
            }
            if (launch && credentials == null) {
                formError("请输入密码或选择该服务器的 SSH 私钥。");
                return;
            }
            profile = new Profile(profile.id(), profile.name(), profile.host(), profile.port(), profile.user(),
                profile.protocol(), profile.restore(), profile.heartbeat(), profile.remember(), storedKeyName,
                canReuse ? editingProfile.sealed() : "");
            profiles.save(profile, newLogin ? credentials : null, remember.isChecked());
            Profile saved = profiles.find(profile.id());
            if (launch) {
                Credentials ready = credentials;
                credentials = null;
                showHome();
                launch(saved, files || mode != Protocol.SSH, ready);
            } else {
                showHome();
            }
        } catch (Exception error) {
            formError("无法读取或保存登录凭据。请重新输入登录信息。");
        } finally {
            if (credentials != null) credentials.close();
        }
    }

    private void openProfile(Profile profile, boolean files) {
        if (!files && service != null && service.matches(profile)) {
            showTerminal();
            return;
        }
        boolean wasTerminal = showingTerminal;
        try {
            Credentials login = profiles.unlock(profile);
            if (login != null) {
                launch(profile, files, login);
                return;
            }
        } catch (Exception error) {
            showEditor(profile, files);
            formError("无法解锁保存的登录信息，请重新输入。");
            return;
        }
        showEditor(profile, files);
        if (wasTerminal) formError("请输入该服务器的登录信息以打开文件管理。");
    }

    private void launch(Profile profile, boolean files, Credentials credentials) {
        if (profile.protocol() == Protocol.FTP) {
            QizhouDialog warning = new QizhouDialog(this, "使用明文 FTP？")
                .message("用户名、密码和文件会以明文传输。")
                .action("取消", false, credentials::close)
                .action("连接", true, () -> openFiles(profile, credentials));
            warning.setOnCancelListener(dialog -> credentials.close());
            warning.show();
            return;
        }
        if (files) {
            openFiles(profile, credentials);
            return;
        }
        if (service == null) {
            credentials.close();
            toast("SSH 服务正在启动，请稍后重试");
            return;
        }
        if (service.active()) {
            QizhouDialog switching = new QizhouDialog(this, "切换终端？")
                .message("当前终端会断开。")
                .action("取消", false, credentials::close)
                .action("切换", true, () -> {
                    if (service == null) {
                        credentials.close();
                        return;
                    }
                    service.stopConnection();
                    startTerminal(profile, credentials);
                });
            switching.setOnCancelListener(dialog -> credentials.close());
            switching.show();
        } else {
            startTerminal(profile, credentials);
        }
    }

    private void startTerminal(Profile profile, Credentials login) {
        SshTransport.Config config;
        try (login) {
            config = login.ssh(profile);
        }
        try {
            startForegroundService(new Intent(this, SshService.class));
        } catch (IllegalStateException | SecurityException error) {
            config.clearSecrets();
            toast("系统阻止了后台服务启动，请检查应用的后台设置");
            return;
        }
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(root.getWindowToken(), 0);
        service.begin(profile.id(), profile.title(), config);
        showTerminal();

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED && !settings.getBoolean("asked_notifications", false)) {
            settings.edit().putBoolean("asked_notifications", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION);
        }
    }

    private void openFiles(Profile profile, Credentials credentials) {
        returnToTerminal = showingTerminal;
        showingTerminal = false;
        editing = false;
        closeFiles();
        clearKey();
        terminalView = null;
        root.removeAllViews();
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(root.getWindowToken(), 0);
        filePage = new FilePage(this, root, profile, credentials, preferences.fileOrder());
    }

    void leaveFiles() {
        if (filePage != null && filePage.handleBack(this::closeFileScreen)) return;
        closeFileScreen();
    }

    private void closeFileScreen() {
        closeFiles();
        if (returnToTerminal && service != null) showTerminal();
        else showHome();
    }

    private void closeFiles() {
        if (filePage == null) return;
        filePage.close();
        filePage = null;
    }

    @Override public void onBackPressed() {
        if (filePage != null) leaveFiles();
        else if (showingSettings) leaveSettings();
        else if (editing || showingTerminal) showHome();
        else super.onBackPressed();
    }

    private void showSettings() {
        settingsFromTerminal = showingTerminal;
        showingTerminal = false;
        showingSettings = true;
        terminalView = null;
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(root.getWindowToken(), 0);
        new SettingsPage(this, preferences, scrollPage(), this::leaveSettings, this::showBackgroundSettings);
        root.requestFocus();
    }

    private void leaveSettings() {
        showingSettings = false;
        if (settingsFromTerminal && service != null) showTerminal();
        else showHome();
    }

    void applySelectableBackground(View view) {
        android.util.TypedValue value = new android.util.TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackground, value, true)) {
            view.setBackgroundResource(value.resourceId);
        }
    }

    LinearLayout pageHeader(LinearLayout parent, String label, Runnable leave) {
        LinearLayout box = column();
        if (parent == root) box.setPadding(dp(18), dp(9), dp(18), dp(9));
        LinearLayout title = new LinearLayout(this);
        title.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton back = iconButton(UiIcon.Kind.BACK, "返回", leave);
        title.addView(back, new LinearLayout.LayoutParams(dp(46), dp(44)));
        TextView name = text(label, 23, TEXT, true);
        if (android.os.Build.VERSION.SDK_INT >= 28) name.setAccessibilityHeading(true);
        LinearLayout.LayoutParams namePlace = new LinearLayout.LayoutParams(0, -2, 1);
        namePlace.leftMargin = dp(12);
        title.addView(name, namePlace);
        box.addView(title);
        parent.addView(box);
        return box;
    }

    private void formError(String message) {
        formMessage.setText(message);
        formMessage.setVisibility(View.VISIBLE);
    }

    private void showTerminal() {
        if (service == null) return;
        closeFiles();
        editing = false;
        showingTerminal = true;
        showingSettings = false;
        lastStatus = null;
        root.removeAllViews();
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(14), dp(8), dp(12), dp(8));
        header.setBackgroundColor(PANEL);
        ImageButton back = iconButton(UiIcon.Kind.BACK, "服务器列表", () -> {
            getSystemService(InputMethodManager.class).hideSoftInputFromWindow(root.getWindowToken(), 0);
            showHome();
        });
        LinearLayout.LayoutParams backPlace = new LinearLayout.LayoutParams(dp(42), dp(44));
        backPlace.rightMargin = dp(8);
        header.addView(back, backPlace);
        LinearLayout status = column();
        header.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        TextView target = text(service.profileTitle(), 12, MUTED, true);
        target.setSingleLine(true);
        target.setEllipsize(android.text.TextUtils.TruncateAt.END);
        add(status, target, 0);
        statusTitle = text("", 17, ACCENT, true);
        statusDetail = text("", 12, MUTED, false);
        statusDetail.setMaxLines(2);
        statusDetail.setEllipsize(android.text.TextUtils.TruncateAt.END);
        statusDetail.setOnClickListener(view -> showBackgroundSettings());
        add(status, statusTitle, 0);
        add(status, statusDetail, 1);
        terminalAction = button("", false);
        terminalAction.setMinWidth(dp(58));
        terminalAction.setMinimumWidth(dp(58));
        terminalAction.setOnClickListener(view -> {
            if (service == null) return;
            if (service.active()) service.stopConnection();
            else {
                getSystemService(InputMethodManager.class)
                    .hideSoftInputFromWindow(root.getWindowToken(), 0);
                showHome();
            }
        });
        header.addView(terminalAction, new LinearLayout.LayoutParams(-2, dp(44)));
        root.addView(header);

        terminalView = new TerminalView(this, service, preferences.terminalSize());
        root.addView(terminalView, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout tools = new LinearLayout(this);
        tools.setPadding(dp(8), dp(3), dp(8), dp(4));
        tools.setBackgroundColor(PANEL);
        addTool(tools, "键盘", () -> terminalView.showKeyboard());
        pasteButton = addTool(tools, "粘贴", this::pasteClipboard);
        addTool(tools, "复制", this::showScreenText);
        Profile connected = profiles.find(service.profileId());
        if (service.matches(connected)) {
            addTool(tools, "文件", () -> openProfile(connected, true));
        }
        if (!preferences.shortcutBar()) {
            Button extra = addTool(tools, "按键", this::showExtraKeys);
            extra.setPadding(dp(3), 0, dp(3), 0);
        }
        root.addView(tools);
        if (preferences.shortcutBar()) addShortcutBar();
        root.requestFocus();
        refresh();
    }

    private void addShortcutBar() {
        HorizontalScrollView keys = new HorizontalScrollView(this);
        keys.setHorizontalScrollBarEnabled(false);
        keys.setBackgroundColor(BG);
        LinearLayout strip = new LinearLayout(this);
        strip.setPadding(dp(8), dp(2), dp(8), dp(5));
        keys.addView(strip, new HorizontalScrollView.LayoutParams(-2, -2));
        key(strip, "更多键", this::showExtraKeys);
        Button ctrl = key(strip, "Ctrl", () -> terminalView.toggleCtrl());
        Button alt = key(strip, "Alt", () -> terminalView.toggleAlt());
        terminalView.onModifiersChanged(() -> {
            ctrl.setSelected(terminalView.ctrl());
            alt.setSelected(terminalView.alt());
            ctrl.setTextColor(terminalView.ctrl() ? ACCENT : TEXT);
            alt.setTextColor(terminalView.alt() ? ACCENT : TEXT);
        });
        key(strip, "Esc", () -> terminalView.specialKey(KeyEvent.KEYCODE_ESCAPE));
        key(strip, "Tab", () -> terminalView.specialKey(KeyEvent.KEYCODE_TAB));
        key(strip, "←", () -> terminalView.specialKey(KeyEvent.KEYCODE_DPAD_LEFT));
        key(strip, "↑", () -> terminalView.specialKey(KeyEvent.KEYCODE_DPAD_UP));
        key(strip, "↓", () -> terminalView.specialKey(KeyEvent.KEYCODE_DPAD_DOWN));
        key(strip, "→", () -> terminalView.specialKey(KeyEvent.KEYCODE_DPAD_RIGHT));
        key(strip, "回车", () -> terminalView.specialKey(KeyEvent.KEYCODE_ENTER));
        root.addView(keys);
    }

    private void showExtraKeys() {
        if (terminalView == null) return;
        String[] labels = {"Ctrl+C（中断）", "Ctrl+D（EOF）", "Ctrl+Z（挂起）", "Ctrl+L（清屏）",
            "Home", "End", "PgUp", "PgDn", "Insert", "Delete", "退格", "回车",
            "F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10", "F11", "F12"};
        int[] codes = {KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END, KeyEvent.KEYCODE_PAGE_UP,
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_INSERT, KeyEvent.KEYCODE_FORWARD_DEL,
            KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_F1, KeyEvent.KEYCODE_F2,
            KeyEvent.KEYCODE_F3, KeyEvent.KEYCODE_F4, KeyEvent.KEYCODE_F5, KeyEvent.KEYCODE_F6,
            KeyEvent.KEYCODE_F7, KeyEvent.KEYCODE_F8, KeyEvent.KEYCODE_F9, KeyEvent.KEYCODE_F10,
            KeyEvent.KEYCODE_F11, KeyEvent.KEYCODE_F12};
        QizhouDialog dialog = new QizhouDialog(this, "终端快捷键");
        if (!preferences.shortcutBar()) {
            TerminalView view = terminalView;
            dialog.option(view.ctrl() ? "取消 Ctrl" : "Ctrl", view.ctrl() ? UiIcon.Kind.CHECK : null, view::toggleCtrl);
            dialog.option(view.alt() ? "取消 Alt" : "Alt", view.alt() ? UiIcon.Kind.CHECK : null, view::toggleAlt);
            String[] commonLabels = {"Esc", "Tab", "上", "下", "左", "右"};
            int[] commonCodes = {KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT};
            for (int i = 0; i < commonLabels.length; i++) {
                int keyCode = commonCodes[i];
                dialog.option(commonLabels[i], null, () -> view.specialKey(keyCode));
            }
        }
        for (int i = 0; i < labels.length; i++) {
            int which = i;
            dialog.option(labels[i], null, () -> {
                if (terminalView == null) return;
                if (which < 4) terminalView.controlShortcut("cdzl".charAt(which));
                else terminalView.specialKey(codes[which - 4]);
            });
        }
        dialog.option("应用设置", UiIcon.Kind.SETTINGS, this::showSettings)
            .action("关闭", false, null).show();
    }

    private void refreshHomeResume() {
        if (homeResume == null || editing || showingTerminal || showingSettings || filePage != null) return;
        boolean active = service != null && service.active();
        homeResume.setVisibility(active ? View.VISIBLE : View.GONE);
        if (active) {
            String label = "返回终端 · " + service.profileTitle();
            if (!label.contentEquals(homeResume.getText())) homeResume.setText(label);
        }
    }

    private void refresh() {
        if (service == null) return;
        if (!showingTerminal) {
            refreshHomeResume();
            return;
        }
        SshService.Status status = service.status();
        if (status != lastStatus) {
            lastStatus = status;
            statusTitle.setText(status.title());
            statusTitle.setTextColor(status.phase() == SshService.Phase.ONLINE ? ACCENT
                : status.phase() == SshService.Phase.IDLE ? TEXT : AMBER);
            statusDetail.setText(status.detail());
            statusDetail.setVisibility(status.detail().isEmpty() ? View.GONE : View.VISIBLE);
            terminalAction.setText(service.active() ? "断开" : "连接设置");
            pasteButton.setEnabled(status.phase() == SshService.Phase.ONLINE);
        }
        terminalView.invalidate();
        if (resumed) displayFingerprint();
    }

    private void showBackgroundSettings() {
        boolean restricted = Build.VERSION.SDK_INT >= 28
            && getSystemService(ActivityManager.class).isBackgroundRestricted();
        boolean exempt = getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName());
        int dataSaver = getSystemService(ConnectivityManager.class).getRestrictBackgroundStatus();
        StringBuilder message = new StringBuilder(
            "在系统的应用信息中，将电池用量设为「允许后台活动」或「不受限制」。"
            + "如果启用了省流量，也请检查栖舟的后台数据权限。");
        if (restricted) message.append("\n\n安卓报告此应用受到后台限制。");
        if (exempt) message.append("\n\n此应用已设为忽略安卓电池优化。");
        if (dataSaver == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED) {
            message.append("\n\n安卓报告省流量模式限制此应用的后台数据。");
        }
        String report = service == null ? "" : service.lastLoss();
        if (!report.isEmpty()) message.append("\n\n上次断线\n").append(report);
        QizhouDialog dialog = new QizhouDialog(this, "后台联网").message(message.toString());
        if (!report.isEmpty()) {
            dialog.option("复制诊断", null, () -> getSystemService(ClipboardManager.class)
                .setPrimaryClip(ClipData.newPlainText("SSH 断线诊断", report)));
        }
        dialog.action("关闭", false, null).action("打开设置", true, () -> startActivity(
            new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", getPackageName(), null))));
        dialog.show();
    }

    private void displayFingerprint() {
        SshService.Verify pending = service.verification();
        if (shownVerification == pending) return;
        dismissFingerprint();
        if (pending == null || pending.reply().isDone()) return;
        shownVerification = pending;
        fingerprintDialog = new QizhouDialog(this, "核对服务器指纹")
            .message(pending.host() + "\n\n" + pending.fingerprint()
                + "\n\n请与服务器管理员提供的 SHA-256 指纹核对。")
            .action("取消连接", false, () -> pending.reply().complete(false))
            .action("确认并信任", true, () -> pending.reply().complete(true));
        fingerprintDialog.setOnCancelListener(dialog -> pending.reply().complete(false));
        fingerprintDialog.show();
    }

    private void dismissFingerprint() {
        if (fingerprintDialog != null) fingerprintDialog.dismiss();
        fingerprintDialog = null;
        shownVerification = null;
    }

    private void pasteClipboard() {
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        ClipData clip = clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            toast("剪贴板没有文本");
            return;
        }
        CharSequence content = clip.getItemAt(0).getText();
        if (content == null || content.length() == 0) {
            toast("剪贴板没有文本");
            return;
        }
        if (content.length() > 32_768) {
            toast("单次粘贴最多 32,768 个字符");
            return;
        }
        String paste = content.toString();
        if (paste.contains("\n") || paste.contains("\r") || paste.contains("\t")) {
            new QizhouDialog(this, "粘贴到远程终端？")
                .message("文本包含换行或制表符，可能会触发远程终端执行。")
                .action("取消", false, null)
                .action("粘贴", true, () -> { if (terminalView != null) terminalView.paste(paste); })
                .show();
        } else {
            terminalView.paste(paste);
        }
    }

    private void showScreenText() {
        if (service == null) return;
        String captured = service.terminal().visibleText();
        if (captured.isEmpty()) {
            toast("屏幕上没有可复制的文本");
            return;
        }
        TextView content = text(captured, 14, TEXT, false);
        content.setTypeface(Typeface.MONOSPACE);
        content.setTextIsSelectable(true);
        content.setPadding(dp(6), dp(6), dp(6), dp(6));
        new QizhouDialog(this, "当前屏幕文本").content(content)
            .action("关闭", false, null)
            .action("复制全部", true, () -> {
                getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("SSH", captured));
                if (Build.VERSION.SDK_INT < 33) toast("已复制");
            }).show();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null) return;
        if (request == FilePage.UPLOAD || request == FilePage.DOWNLOAD || request == FilePage.DOWNLOAD_BATCH) {
            if (filePage != null) filePage.onFilePicked(request, data);
            return;
        }
        Uri uri = data.getData();
        if (request != KEY_PICKER || !editing || uri == null) return;
        EditText owner = secret;
        new Thread(() -> {
            try {
                byte[] bytes = readKey(uri);
                String name = fileName(uri);
                runOnUiThread(() -> {
                    if (isDestroyed() || !editing || secret != owner || selectedProtocol() != Protocol.SSH) {
                        Arrays.fill(bytes, (byte) 0);
                        return;
                    }
                    clearKey();
                    keyBytes = bytes;
                    keyName = name;
                    removedKey = true;
                    secret.setText("");
                    updateKeyLabel();
                });
            } catch (IOException | SecurityException error) {
                runOnUiThread(() -> {
                    if (!isDestroyed() && editing && secret == owner) formError("无法读取私钥。请选择小于 128 KiB 的私钥文件。");
                });
            }
        }, "read-private-key").start();
    }

    private byte[] readKey(Uri uri) throws IOException {
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("File unavailable");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4_096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (bytes.size() + count > MAX_KEY_BYTES) throw new IOException("File too large");
                bytes.write(buffer, 0, count);
            }
            if (bytes.size() == 0) throw new IOException("Empty file");
            return bytes.toByteArray();
        }
    }

    String fileName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri,
            new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            String found = cursor != null && cursor.moveToFirst() ? cursor.getString(0) : null;
            return found == null || found.isEmpty() ? "未命名文件" : found;
        } catch (RuntimeException error) {
            return "未命名文件";
        }
    }

    private void updateKeyLabel() {
        boolean privateKey = selectedProtocol() == Protocol.SSH && (keyBytes != null || usesSavedKey());
        boolean saved = !removedKey && sameFormLogin() && editingProfile.hasSavedLogin();
        String currentKey = keyBytes == null && privateKey ? editingProfile.keyName() : keyName;
        keyButton.setText(privateKey ? "私钥：" + currentKey + " · 移除" : "使用 SSH 私钥");
        secret.setHint(saved ? "留空继续使用已保存的凭据" : privateKey ? "私钥未加密可留空" : "输入密码");
        TextView label = (TextView) secret.getTag();
        label.setText(privateKey ? "私钥口令" : "服务器密码");
        secret.setContentDescription(label.getText());
    }

    private void clearKey() {
        if (keyBytes == null) return;
        Arrays.fill(keyBytes, (byte) 0);
        keyBytes = null;
        keyName = null;
    }

    EditText field(LinearLayout parent, String title, String hint, int type, String value) {
        TextView label = text(title, 13, MUTED, true);
        add(parent, label, 6);
        EditText edit = new EditText(this);
        edit.setTextColor(TEXT);
        edit.setHintTextColor(0xff899d95);
        edit.setTextSize(16);
        edit.setSingleLine(true);
        edit.setInputType(type);
        edit.setHint(hint);
        edit.setText(value);
        edit.setTag(label);
        edit.setContentDescription(title);
        edit.setPadding(dp(10), 0, dp(10), 0);
        edit.setBackground(surface(BG, BORDER, 9));
        edit.setSaveEnabled(false);
        LinearLayout.LayoutParams place = new LinearLayout.LayoutParams(-1, dp(43));
        place.topMargin = dp(4);
        parent.addView(edit, place);
        return edit;
    }

    private Button key(LinearLayout parent, String label, Runnable action) {
        Button key = button(label, false);
        key.setMinWidth(dp(42));
        key.setMinimumWidth(dp(42));
        key.setOnClickListener(view -> action.run());
        LinearLayout.LayoutParams place = new LinearLayout.LayoutParams(-2, dp(42));
        place.rightMargin = dp(5);
        parent.addView(key, place);
        return key;
    }

    Button addTool(LinearLayout parent, String label, Runnable action) {
        Button button = button(label, false);
        button.setOnClickListener(view -> action.run());
        LinearLayout.LayoutParams place = new LinearLayout.LayoutParams(0, dp(45), 1);
        place.setMargins(dp(3), 0, dp(3), 0);
        parent.addView(button, place);
        return button;
    }

    Button addTool(LinearLayout parent, UiIcon.Kind icon, String label, Runnable action) {
        Button item = button(label, false);
        item.setMinWidth(0);
        item.setMinimumWidth(0);
        item.setTextSize(12);
        item.setPadding(dp(1), dp(6), dp(1), dp(5));
        UiIcon drawing = new UiIcon(icon, ACCENT);
        drawing.setBounds(0, 0, dp(18), dp(18));
        item.setCompoundDrawables(null, drawing, null, null);
        item.setCompoundDrawablePadding(dp(2));
        item.setOnClickListener(view -> action.run());
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, dp(56), 1);
        size.setMargins(dp(2), 0, dp(2), 0);
        parent.addView(item, size);
        return item;
    }

    ImageButton iconButton(UiIcon.Kind icon, String label, Runnable action) {
        ImageButton button = new ImageButton(this);
        button.setPadding(dp(12), dp(12), dp(12), dp(12));
        button.setBackground(new android.graphics.drawable.RippleDrawable(
            ColorStateList.valueOf(0x468dd9b7), surface(0xff233933, 0xff3b5049, 14), null));
        button.setScaleType(ImageView.ScaleType.FIT_CENTER);
        button.setImageDrawable(new UiIcon(icon, ACCENT));
        button.setContentDescription(label);
        button.setTooltipText(label);
        if (action != null) button.setOnClickListener(view -> action.run());
        return button;
    }

    void addButtonIcon(Button button, UiIcon.Kind icon, int color) {
        UiIcon drawing = new UiIcon(icon, color);
        drawing.setBounds(0, 0, dp(17), dp(17));
        button.setCompoundDrawablesRelative(drawing, null, null, null);
        button.setCompoundDrawablePadding(dp(6));
    }

    Button button(String label, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setSingleLine(true);
        button.setEllipsize(android.text.TextUtils.TruncateAt.END);
        button.setTextSize(14);
        button.setMinHeight(dp(44));
        button.setMinimumHeight(dp(44));
        button.setPadding(dp(10), 0, dp(10), 0);
        int active = primary ? 0xff10251d : TEXT;
        button.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[0]},
            new int[]{0xff7d8d86, active}));
        button.setBackground(new android.graphics.drawable.RippleDrawable(
            ColorStateList.valueOf(primary ? 0x400b3928 : 0x408edbb7),
            surface(primary ? ACCENT : PANEL, primary ? ACCENT : BORDER, 13), null));
        return button;
    }

    TextView text(String value, int size, int color, boolean bold) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextColor(color);
        text.setTextSize(size);
        if (bold) text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return text;
    }

    LinearLayout column() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        return column;
    }

    void add(LinearLayout parent, View view, int above) {
        LinearLayout.LayoutParams place = new LinearLayout.LayoutParams(-1, -2);
        place.topMargin = dp(above);
        parent.addView(view, place);
    }

    GradientDrawable surface(int fill, int stroke, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(radius));
        shape.setStroke(dp(1), stroke);
        return shape;
    }

    int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_SHORT).show(); }
}
