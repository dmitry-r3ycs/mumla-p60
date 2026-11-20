/*
 * Hytera P60 adaptation: hardware PTT broadcasts sent by PoC radio firmware.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package se.lublin.mumla.service.ipc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

import se.lublin.mumla.service.MumlaService;

/**
 * Many PoC radios announce their dedicated PTT button with a system-wide
 * broadcast instead of (or in addition to) a key event. This receiver listens
 * for the commonly used "pressed" / "released" actions.
 */
public class HardwarePttReceiver extends BroadcastReceiver {
    private static final String TAG = "MumlaP60";

    private static final String[] DOWN_ACTIONS = {
            "android.intent.action.PTT_DOWN",
            "android.intent.action.PTT.down",
            "com.sonim.intent.action.PTT_KEY_DOWN",
            "com.runbo.ptt.key.down",
            "com.zello.ptt.down",
    };
    private static final String[] UP_ACTIONS = {
            "android.intent.action.PTT_UP",
            "android.intent.action.PTT.up",
            "com.sonim.intent.action.PTT_KEY_UP",
            "com.runbo.ptt.key.up",
            "com.zello.ptt.up",
    };

    private final MumlaService mService;

    public HardwarePttReceiver(MumlaService service) {
        mService = service;
    }

    public static IntentFilter createFilter() {
        IntentFilter filter = new IntentFilter();
        for (String action : DOWN_ACTIONS) filter.addAction(action);
        for (String action : UP_ACTIONS) filter.addAction(action);
        return filter;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;
        for (String down : DOWN_ACTIONS) {
            if (down.equals(action)) {
                Log.i(TAG, "PTT down (broadcast " + action + ")");
                mService.onHardwarePtt(true);
                return;
            }
        }
        for (String up : UP_ACTIONS) {
            if (up.equals(action)) {
                Log.i(TAG, "PTT up (broadcast " + action + ")");
                mService.onHardwarePtt(false);
                return;
            }
        }
    }
}
