package com.anqu.ssh;

import android.view.KeyEvent;

import com.termux.terminal.KeyHandler;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class TerminalKeysTest {
    @Test public void sendsNavigationAndFunctionKeys() {
        assertEquals("\033[A", code(KeyEvent.KEYCODE_DPAD_UP, 0, false));
        assertEquals("\033OA", code(KeyEvent.KEYCODE_DPAD_UP, 0, true));
        assertEquals("\033[1;3D", code(KeyEvent.KEYCODE_DPAD_LEFT, KeyHandler.KEYMOD_ALT, false));
        assertEquals("\033[H", code(KeyEvent.KEYCODE_MOVE_HOME, 0, false));
        assertEquals("\033[6~", code(KeyEvent.KEYCODE_PAGE_DOWN, 0, false));
        assertEquals("\033[3~", code(KeyEvent.KEYCODE_FORWARD_DEL, 0, false));
        assertEquals("\033[24~", code(KeyEvent.KEYCODE_F12, 0, false));
    }

    @Test public void mapsControlCharacters() {
        assertEquals(3, TerminalView.control('c'));
        assertEquals(4, TerminalView.control('d'));
        assertEquals(12, TerminalView.control('l'));
        assertEquals(26, TerminalView.control('z'));
    }

    private String code(int key, int modifiers, boolean application) {
        return KeyHandler.getCode(key, modifiers, application, false);
    }
}
