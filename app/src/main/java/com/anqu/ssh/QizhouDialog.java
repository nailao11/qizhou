package com.anqu.ssh;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

final class QizhouDialog extends Dialog {
    private final MainActivity activity;
    private final LinearLayout shell;
    private final LinearLayout body;
    private final LinearLayout footer;

    QizhouDialog(MainActivity activity, String title) {
        super(activity);
        this.activity = activity;
        shell = activity.column();
        shell.setBackground(activity.surface(0xff213530, MainActivity.BORDER, 25));
        shell.setPadding(activity.dp(20), activity.dp(19), activity.dp(20), activity.dp(16));
        TextView heading = activity.text(title, 21, MainActivity.TEXT, true);
        if (android.os.Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        heading.setMaxLines(2);
        heading.setEllipsize(android.text.TextUtils.TruncateAt.END);
        activity.add(shell, heading, 0);

        ScrollView scroll = new ScrollView(activity) {
            @Override protected void onMeasure(int width, int height) {
                int max = Math.min(activity.dp(415), getResources().getDisplayMetrics().heightPixels / 2);
                super.onMeasure(width, MeasureSpec.makeMeasureSpec(
                    MeasureSpec.getMode(height) == MeasureSpec.UNSPECIFIED ? max
                        : Math.min(max, MeasureSpec.getSize(height)), MeasureSpec.AT_MOST));
            }
        };
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false);
        body = activity.column();
        scroll.addView(body);
        activity.add(shell, scroll, 9);
        footer = new LinearLayout(activity);
        activity.add(shell, footer, 14);
    }

    QizhouDialog message(String message) {
        TextView text = activity.text(message, 15, MainActivity.MUTED, false);
        text.setLineSpacing(activity.dp(3), 1f);
        text.setTextIsSelectable(true);
        activity.add(body, text, 1);
        return this;
    }

    QizhouDialog content(View view) {
        activity.add(body, view, 2);
        return this;
    }

    QizhouDialog option(String title, UiIcon.Kind icon, Runnable action) {
        boolean danger = icon == UiIcon.Kind.TRASH;
        Button button = activity.button(title, false);
        button.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        button.setPaddingRelative(activity.dp(11), 0, activity.dp(12), 0);
        if (danger) button.setTextColor(MainActivity.DANGER);
        if (icon != null) activity.addButtonIcon(button, icon, danger ? MainActivity.DANGER : MainActivity.ACCENT);
        button.setOnClickListener(view -> {
            dismiss();
            action.run();
        });
        activity.add(body, button, 6);
        return this;
    }

    QizhouDialog action(String label, boolean primary, Runnable action) {
        return actionButton(label, primary, false, () -> {
            dismiss();
            if (action != null) action.run();
        });
    }

    QizhouDialog destructive(String label, Runnable action) {
        return actionButton(label, false, true, () -> {
            dismiss();
            action.run();
        });
    }

    QizhouDialog keepOpen(String label, Runnable action) {
        return actionButton(label, true, false, action);
    }

    private QizhouDialog actionButton(String label, boolean primary, boolean danger, Runnable action) {
        Button button = activity.button(label, primary);
        button.setTextSize(14);
        if (danger) {
            button.setBackground(activity.surface(0xff523431, 0xff714641, 13));
            button.setTextColor(MainActivity.DANGER);
        }
        button.setOnClickListener(view -> action.run());
        LinearLayout.LayoutParams place = new LinearLayout.LayoutParams(0, activity.dp(46), 1);
        if (footer.getChildCount() != 0) place.leftMargin = activity.dp(7);
        footer.addView(button, place);
        return this;
    }

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(shell);
        setCanceledOnTouchOutside(true);
    }

    @Override protected void onStart() {
        super.onStart();
        Window window = getWindow();
        if (window == null) return;
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDimAmount(.56f);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        window.setLayout(Math.min(activity.dp(440),
            activity.getResources().getDisplayMetrics().widthPixels - activity.dp(32)), -2);
    }
}
