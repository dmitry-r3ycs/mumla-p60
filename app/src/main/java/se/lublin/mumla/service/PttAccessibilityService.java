/*
 * Hytera P60 adaptation: hardware PTT key while the app is in the background.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package se.lublin.mumla.service;

import android.accessibilityservice.AccessibilityService;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import se.lublin.mumla.Settings;
import se.lublin.mumla.app.MumlaActivity;

/**
 * Receives hardware key events even when Mumla is minimized or the screen is
 * off, and forwards the configured push-to-talk key to the running service.
 * It never reads window content; only the PTT key selected in the audio
 * settings is handled, every other key is passed through untouched.
 */
public class PttAccessibilityService extends AccessibilityService {
    private static final String TAG = "MumlaP60";

    /** Receives every key event while the diagnostics screen is open. */
    public interface KeyListener {
        void onAccessibilityKey(KeyEvent event);
    }

    /** Receives the next pressed key while the key selection dialog is open. */
    public interface LearnListener {
        void onKeyLearned(int keyCode);
    }

    private static volatile KeyListener sDiagnosticsListener;
    private static volatile LearnListener sLearnListener;
    private static volatile boolean sRunning;

    public static void setDiagnosticsListener(KeyListener listener) {
        sDiagnosticsListener = listener;
    }

    public static void setLearnListener(LearnListener listener) {
        sLearnListener = listener;
    }

    public static boolean isRunning() {
        return sRunning;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sRunning = true;
    }

    @Override
    public void onDestroy() {
        sRunning = false;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Not used.
    }

    @Override
    public void onInterrupt() {
        // Not used.
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        KeyListener diagnostics = sDiagnosticsListener;
        if (diagnostics != null) {
            diagnostics.onAccessibilityKey(event);
            return false;
        }
        LearnListener learn = sLearnListener;
        if (learn != null && event.getKeyCode() != KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                learn.onKeyLearned(event.getKeyCode());
            }
            return true;
        }

        Settings settings = Settings.getInstance(this);
        int pttKey = settings.getPushToTalkKey();
        if (pttKey <= 0 || event.getKeyCode() != pttKey) {
            return false;
        }
        if (!Settings.ARRAY_INPUT_METHOD_PTT.equals(settings.getInputMethod())) {
            return false;
        }
        if (!settings.isPttBackgroundEnabled() && !MumlaActivity.isOnScreen(this)) {
            // Background PTT is switched off in the Mumla settings.
            return false;
        }
        MumlaService service = MumlaService.getInstance();
        if (service == null || !service.isConnectionEstablished()) {
            // Not connected: leave the key to other apps.
            return false;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) {
                Log.i(TAG, "PTT key down (accessibility), code " + pttKey);
                service.onHardwarePtt(true);
            }
            return true;
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            Log.i(TAG, "PTT key up (accessibility), code " + pttKey);
            service.onHardwarePtt(false);
            return true;
        }
        return false;
    }
}
