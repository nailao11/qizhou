package com.anqu.ssh;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputType;
import android.text.Selection;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

import com.termux.terminal.KeyHandler;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TextStyle;
import com.termux.view.TerminalRenderer;

import java.nio.charset.StandardCharsets;

final class TerminalView extends View {
    private final SshService service;
    private final TerminalState state;
    private final TerminalRenderer renderer;
    private final float cellWidth;
    private final int cellHeight;
    private final int padding;
    private final GestureDetector gestures;
    private boolean ctrl;
    private boolean alt;
    private Runnable modifiersChanged;
    private float scrollRemainder;

    TerminalView(Context context, SshService service, int fontSize) {
        super(context);
        this.service = service;
        state = service.terminal();
        float density = getResources().getDisplayMetrics().density;
        int textSize = Math.round(android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_SP, fontSize, getResources().getDisplayMetrics()));
        padding = Math.round(8 * density);
        renderer = new TerminalRenderer(textSize, Typeface.MONOSPACE);
        Paint measure = new Paint();
        measure.setTypeface(Typeface.MONOSPACE);
        measure.setTextSize(textSize);
        cellWidth = measure.measureText("X");
        cellHeight = renderer.getFontLineSpacing();
        setFocusableInTouchMode(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setContentDescription("SSH 终端，点按打开键盘，滑动查看输出");
        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) {
                scrollRemainder = 0;
                return true;
            }

            @Override public boolean onSingleTapUp(MotionEvent event) {
                return performClick();
            }

            @Override public boolean onScroll(MotionEvent start, MotionEvent end, float x, float y) {
                scrollRemainder += y / cellHeight;
                int rows = (int) scrollRemainder;
                if (rows != 0) {
                    state.scroll(rows);
                    scrollRemainder -= rows;
                    invalidate();
                }
                return true;
            }
        });
    }

    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        if (w <= padding * 2 || h <= padding * 2) return;
        int width = w - padding * 2;
        int height = h - padding * 2;
        service.resize(new SshTransport.Size(Math.max(4, (int) (width / cellWidth)),
            Math.max(4, height / cellHeight), width, height));
    }

    @Override protected void onDraw(Canvas canvas) {
        TerminalEmulator emulator = state.emulator();
        canvas.drawColor(emulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND]);
        int saved = canvas.save();
        canvas.translate(padding, padding);
        renderer.render(emulator, canvas, state.topRow(), 1, 0, -1, -1);
        canvas.restoreToCount(saved);
    }

    @Override public boolean onTouchEvent(MotionEvent event) { return gestures.onTouchEvent(event); }

    @Override public boolean performClick() {
        super.performClick();
        showKeyboard();
        return true;
    }

    void showKeyboard() {
        requestFocus();
        getContext().getSystemService(InputMethodManager.class).showSoftInput(this,
            InputMethodManager.SHOW_IMPLICIT);
    }

    void onModifiersChanged(Runnable listener) { modifiersChanged = listener; }

    void toggleCtrl() {
        ctrl = !ctrl;
        if (modifiersChanged != null) modifiersChanged.run();
    }

    void toggleAlt() {
        alt = !alt;
        if (modifiersChanged != null) modifiersChanged.run();
    }

    boolean ctrl() { return ctrl; }

    boolean alt() { return alt; }

    private void clearModifiers() {
        if (!ctrl && !alt) return;
        ctrl = false;
        alt = false;
        if (modifiersChanged != null) modifiersChanged.run();
    }

    void controlShortcut(char character) {
        clearModifiers();
        send(String.valueOf((char) control(character)));
    }

    void paste(String text) {
        if (service.status().phase() != SshService.Phase.ONLINE) return;
        state.bottom();
        state.emulator().paste(text);
        invalidate();
    }

    void specialKey(int code) { sendKey(code, null); }

    @Override public boolean onKeyDown(int code, KeyEvent event) {
        return sendKey(code, event) || super.onKeyDown(code, event);
    }

    private boolean sendKey(int code, KeyEvent event) {
        TerminalEmulator emulator = state.emulator();
        int modifiers = 0;
        if (ctrl || (event != null && event.isCtrlPressed())) modifiers |= KeyHandler.KEYMOD_CTRL;
        if (alt || (event != null && event.isAltPressed())) modifiers |= KeyHandler.KEYMOD_ALT;
        if (event != null) {
            if (event.isShiftPressed()) modifiers |= KeyHandler.KEYMOD_SHIFT;
            if (event.isNumLockOn()) modifiers |= KeyHandler.KEYMOD_NUM_LOCK;
        }
        String sequence = KeyHandler.getCode(code, modifiers,
            emulator.isCursorKeysApplicationMode(), emulator.isKeypadApplicationMode());
        if (sequence != null) {
            clearModifiers();
            send(sequence);
            return true;
        }
        if (event == null) return false;
        int character = event.getUnicodeChar(event.getMetaState() & ~(KeyEvent.META_CTRL_MASK | KeyEvent.META_ALT_MASK));
        if (!Character.isValidCodePoint(character) || character == 0) return false;
        if ((modifiers & KeyHandler.KEYMOD_CTRL) != 0) character = control(character);
        clearModifiers();
        String prefix = (modifiers & KeyHandler.KEYMOD_ALT) != 0 ? "\033" : "";
        send(prefix + new String(Character.toChars(character)));
        return true;
    }

    static int control(int character) {
        if ((character >= 'a' && character <= 'z') || (character >= '@' && character <= '_')) {
            return character & 31;
        }
        if (character == ' ' || character == '2') return 0;
        if (character >= '3' && character <= '7') return character - '3' + 27;
        if (character == '?' || character == '8') return 127;
        return character;
    }

    private void sendText(String text) {
        if (text.isEmpty()) return;
        if (ctrl) {
            int first = text.codePointAt(0);
            text = new String(Character.toChars(control(first))) + text.substring(Character.charCount(first));
        }
        if (alt) text = "\033" + text;
        clearModifiers();
        send(text.replace("\r\n", "\r").replace('\n', '\r'));
    }

    private void send(String text) {
        if (service.status().phase() != SshService.Phase.ONLINE) return;
        state.bottom();
        service.send(text.getBytes(StandardCharsets.UTF_8));
        invalidate();
    }

    @Override public boolean onCheckIsTextEditor() { return true; }

    @Override public InputConnection onCreateInputConnection(EditorInfo options) {
        options.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD;
        options.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
            | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING | EditorInfo.IME_ACTION_NONE;
        options.initialSelStart = options.initialSelEnd = 0;
        return new BaseInputConnection(this, true) {
            private void flush() {
                Editable editable = getEditable();
                String text = editable.toString();
                editable.clear();
                Selection.setSelection(editable, 0);
                sendText(text);
            }

            @Override public boolean commitText(CharSequence text, int cursor) {
                boolean result = super.commitText(text, cursor);
                flush();
                return result;
            }

            @Override public boolean finishComposingText() {
                boolean result = super.finishComposingText();
                flush();
                return result;
            }

            @Override public boolean deleteSurroundingText(int before, int after) {
                if (getEditable().length() != 0) return super.deleteSurroundingText(before, after);
                if (before == 1 && after == 0) {
                    specialKey(KeyEvent.KEYCODE_DEL);
                    return true;
                }
                StringBuilder keys = new StringBuilder();
                for (int i = 0; i < Math.min(before, 128); i++) keys.append('\u007f');
                for (int i = 0; i < Math.min(after, 128); i++) keys.append("\033[3~");
                clearModifiers();
                if (keys.length() > 0) send(keys.toString());
                return true;
            }

            @Override public boolean deleteSurroundingTextInCodePoints(int before, int after) {
                if (getEditable().length() != 0) return super.deleteSurroundingTextInCodePoints(before, after);
                return deleteSurroundingText(before, after);
            }
        };
    }
}
