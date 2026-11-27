package com.anqu.ssh;

import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalOutput;
import com.termux.terminal.TextStyle;

import java.util.function.Consumer;

final class TerminalState {
    static final int BACKGROUND = 0xff0b1414;
    private final Consumer<byte[]> send;
    private TerminalEmulator emulator;
    private SshTransport.Size size = new SshTransport.Size(80, 24, 640, 432);
    private int topRow;

    TerminalState(Consumer<byte[]> send) {
        this.send = send;
        reset();
    }

    void reset() {
        emulator = new TerminalEmulator(new TerminalOutput() {
            @Override public void write(byte[] data, int offset, int count) {
                send.accept(java.util.Arrays.copyOfRange(data, offset, offset + count));
            }

            @Override public void titleChanged(String oldTitle, String newTitle) {}
            @Override public void onCopyTextToClipboard(String text) {}
            @Override public void onPasteTextFromClipboard() {}
            @Override public void onBell() {}
            @Override public void onColorsChanged() {}
        }, size.columns(), size.rows(), cellWidth(), cellHeight(), 2_000, null);
        emulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND] = BACKGROUND;
        emulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_FOREGROUND] = 0xffdbebe2;
        emulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] = 0xff94e6be;
        topRow = 0;
    }

    void resize(SshTransport.Size value) {
        if (size.equals(value)) return;
        size = value;
        emulator.resize(value.columns(), value.rows(), cellWidth(), cellHeight());
        topRow = 0;
    }

    void append(byte[] bytes) {
        emulator.append(bytes, bytes.length);
        if (topRow < 0) scroll(-emulator.getScrollCounter());
        emulator.clearScrollCounter();
    }

    void scroll(int rows) {
        topRow = Math.max(-emulator.getScreen().getActiveTranscriptRows(), Math.min(0, topRow + rows));
    }

    void bottom() { topRow = 0; }

    String visibleText() {
        return emulator.getScreen()
            .getSelectedText(0, topRow, emulator.mColumns, topRow + emulator.mRows - 1)
            .trim();
    }

    TerminalEmulator emulator() { return emulator; }

    int topRow() { return topRow; }

    private int cellWidth() { return Math.max(1, size.width() / size.columns()); }

    private int cellHeight() { return Math.max(1, size.height() / size.rows()); }
}
