package com.anqu.ssh;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

final class SettingsPage {
    private static final String REPOSITORY = "https://github.com/nailao11/qizhou";

    private final MainActivity activity;
    private final AppPreferences preferences;
    private final LinearLayout page;

    SettingsPage(MainActivity activity, AppPreferences preferences, LinearLayout page,
                 Runnable back, Runnable background) {
        this.activity = activity;
        this.preferences = preferences;
        this.page = page;
        activity.pageHeader(page, "应用设置", back);

        LinearLayout terminal = section("终端");
        row(terminal, "终端字号", () -> preferences.terminalSize() + " sp", this::chooseSize);
        toggle(terminal, "显示快捷键栏", "隐藏时仍可从「按键」打开快捷键。", preferences.shortcutBar(),
            preferences::shortcutBar);

        LinearLayout files = section("文件管理");
        row(files, "文件排序", () -> preferences.fileOrder().label, this::chooseOrder);
        note(files, "文件夹始终排在文件前面。");

        LinearLayout connections = section("新服务器默认设置");
        row(connections, "SSH 心跳间隔", () -> preferences.defaultHeartbeat() + " 秒", this::chooseHeartbeat);
        toggle(connections, "加密保存登录凭据", "可在添加服务器时单独修改。", preferences.rememberDefault(),
            preferences::rememberDefault);

        LinearLayout system = section("系统");
        row(system, "后台联网", null, refresh -> background.run());
        row(system, "通知设置", null, refresh -> activity.startActivity(
            new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, activity.getPackageName())));

        LinearLayout about = section("关于栖舟");
        row(about, "版本", this::version, null);
        row(about, "源代码与许可证", null,
            refresh -> activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(REPOSITORY))));
    }

    private LinearLayout section(String title) {
        TextView heading = activity.text(title, 14, MainActivity.ACCENT, true);
        if (android.os.Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        activity.add(page, heading, 19);
        LinearLayout body = activity.column();
        body.setPadding(activity.dp(13), activity.dp(4), activity.dp(13), activity.dp(5));
        body.setBackground(activity.surface(MainActivity.PANEL, MainActivity.BORDER, 17));
        activity.add(page, body, 7);
        return body;
    }

    private void row(LinearLayout parent, String title, TextValue value, RowAction action) {
        LinearLayout row = new LinearLayout(activity);
        row.setMinimumHeight(activity.dp(49));
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = activity.text(title, 15, MainActivity.TEXT, true);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        TextView detail = activity.text(value == null ? "" : value.get(), 14, MainActivity.MUTED, false);
        row.addView(detail);
        if (action != null) {
            activity.applySelectableBackground(row);
            row.setFocusable(true);
            row.setOnClickListener(view -> action.run(() -> {
                if (value != null) detail.setText(value.get());
            }));
            android.widget.ImageView arrow = new android.widget.ImageView(activity);
            arrow.setImageDrawable(new UiIcon(UiIcon.Kind.FORWARD, MainActivity.MUTED));
            LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(activity.dp(17), activity.dp(17));
            space.setMarginStart(activity.dp(7));
            row.addView(arrow, space);
        }
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private void toggle(LinearLayout parent, String title, String subtitle, boolean checked, ToggleAction action) {
        Switch control = new Switch(activity);
        control.setText(title);
        control.setTextColor(MainActivity.TEXT);
        control.setTextSize(15);
        control.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        control.setMinimumHeight(activity.dp(47));
        control.setChecked(checked);
        control.setOnCheckedChangeListener((button, enabled) -> action.run(enabled));
        activity.add(parent, control, 1);
        note(parent, subtitle);
    }

    private void note(LinearLayout parent, String content) {
        TextView note = activity.text(content, 12, MainActivity.MUTED, false);
        note.setPadding(0, 0, 0, activity.dp(7));
        activity.add(parent, note, 0);
    }

    private void chooseSize(Runnable refresh) {
        QizhouDialog dialog = new QizhouDialog(activity, "终端字号");
        for (int size : AppPreferences.TERMINAL_SIZES) {
            dialog.option(size + " sp", size == preferences.terminalSize() ? UiIcon.Kind.CHECK : null, () -> {
                preferences.terminalSize(size);
                refresh.run();
            });
        }
        dialog.action("取消", false, null).show();
    }

    private void chooseHeartbeat(Runnable refresh) {
        QizhouDialog dialog = new QizhouDialog(activity, "SSH 心跳间隔");
        for (int value : AppPreferences.HEARTBEATS) {
            dialog.option(value + " 秒", value == preferences.defaultHeartbeat() ? UiIcon.Kind.CHECK : null, () -> {
                preferences.defaultHeartbeat(value);
                refresh.run();
            });
        }
        dialog.action("取消", false, null).show();
    }

    private void chooseOrder(Runnable refresh) {
        QizhouDialog dialog = new QizhouDialog(activity, "文件排序");
        for (AppPreferences.FileOrder order : AppPreferences.FileOrder.values()) {
            dialog.option(order.label, order == preferences.fileOrder() ? UiIcon.Kind.CHECK : null, () -> {
                preferences.fileOrder(order);
                refresh.run();
            });
        }
        dialog.action("取消", false, null).show();
    }

    private String version() {
        try {
            return activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException unavailable) {
            return "";
        }
    }

    private interface TextValue { String get(); }
    private interface RowAction { void run(Runnable refresh); }
    private interface ToggleAction { void run(boolean enabled); }
}
