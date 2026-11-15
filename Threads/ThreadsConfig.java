package org.telegram.ui.threads;

import org.telegram.messenger.R;

// THREADS: single place for Threads feature constants.
public final class ThreadsConfig {

    // THREADS: the Thread icon. Swap here.
    // Outline (OFF): the selection panel's Thread button and the reply-panel toggle when off.
    // Filled (ON): the reply-panel toggle when on and the thread info circle.
    // THREADS: single-bubble design ("Thread icon_outline.svg" / "Thread icon_filled.svg", 24x24).
    // (earlier: "Thread icon A" two bubbles; stage 8 threads_icon.xml; before that R.drawable.msg_discuss)
    public static final int THREAD_ICON = R.drawable.ic_thread_outline;
    public static final int THREAD_ICON_FILLED = R.drawable.ic_thread_filled;

    // THREADS (stage 9a): the Thread accent, purple. Used by the reply-panel toggle icon (ON), the "Thread on"
    // title, the indicator row and the thread info circle. Deliberately not tied to the theme's accent. Change it here only.
    public static final int THREAD_ACCENT_COLOR = 0xFF8F68D8;
    // THREADS (stage 9c): checked on Day too: about 5:1 on white bubbles, so one value serves both themes.

    // THREADS (stage 9b): the first-time tooltip on the reply panel's thread toggle (shown once).
    public static final int TOGGLE_HINT_TEXT = R.string.ThreadsToggleHint;

    private ThreadsConfig() {
    }
}
