/*
 * Copyright (C) 2014 Andrew Comminos
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package se.lublin.mumla.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.NetworkCapabilities;
import android.net.Network;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.widget.Toast;

import androidx.preference.PreferenceManager;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import se.lublin.humla.Constants;
import se.lublin.humla.HumlaService;
import se.lublin.humla.exception.AudioException;
import se.lublin.humla.model.IMessage;
import se.lublin.humla.model.IUser;
import se.lublin.humla.model.Message;
import se.lublin.humla.model.TalkState;
import se.lublin.humla.util.HumlaException;
import se.lublin.humla.util.HumlaObserver;
import se.lublin.mumla.R;
import se.lublin.mumla.Settings;
import se.lublin.mumla.app.MumlaActivity;
import se.lublin.mumla.service.ipc.HardwarePttReceiver;
import se.lublin.mumla.service.ipc.TalkBroadcastReceiver;
import se.lublin.mumla.util.HtmlUtils;

/**
 * An extension of the Humla service with some added Mumla-exclusive non-standard Mumble features.
 * Created by andrew on 28/07/13.
 */
public class MumlaService extends HumlaService implements
        SharedPreferences.OnSharedPreferenceChangeListener,
        MumlaConnectionNotification.OnActionListener,
        MumlaReconnectNotification.OnActionListener, IMumlaService {
    private static final String TAG = MumlaService.class.getName();

    /** Undocumented constant that permits a proximity-sensing wake lock. */
    public static final int PROXIMITY_SCREEN_OFF_WAKE_LOCK = 32;
    public static final int TTS_THRESHOLD = 250; // Maximum number of characters to read
    // P60: the library's own retry timer is pushed far out; reconnecting is driven
    // by the reconnect watch below, which backs off and survives CPU sleep.
    public static final int RECONNECT_DELAY = 10 * 60 * 1000;

    private Settings mSettings;
    private MumlaConnectionNotification mNotification;
    private MumlaMessageNotification mMessageNotification;
    private MumlaReconnectNotification mReconnectNotification;
    /** Channel view overlay. */
    private MumlaOverlay mChannelOverlay;
    /** Proximity lock for handset mode. */
    private PowerManager.WakeLock mProximityLock;
    /** Play sound when push to talk key is pressed */
    private boolean mPTTSoundEnabled;
    /** Try to shorten spoken messages when using TTS */
    private boolean mShortTtsMessagesEnabled;
    /**
     * True if an error causing disconnection has been dismissed by the user.
     * This should serve as a hint not to bother the user.
     */
    private boolean mErrorShown;
    private List<IChatMessage> mMessageLog;
    private boolean mSuppressNotifications;

    private TextToSpeech mTTS;
    private TextToSpeech.OnInitListener mTTSInitListener = new TextToSpeech.OnInitListener() {
        @Override
        public void onInit(int status) {
            if(status == TextToSpeech.ERROR)
                logWarning(getString(R.string.tts_failed));
        }
    };

    /** The view representing the hot corner. */
    private MumlaHotCorner mHotCorner;
    private MumlaHotCorner.MumlaHotCornerListener mHotCornerListener = new MumlaHotCorner.MumlaHotCornerListener() {
        @Override
        public void onHotCornerDown() {
            onTalkKeyDown();
        }

        @Override
        public void onHotCornerUp() {
            onTalkKeyUp();
        }
    };

    private BroadcastReceiver mTalkReceiver;
    private BroadcastReceiver mHardwarePttReceiver;
    /** Last state reported by a hardware PTT source, to ignore duplicates. */
    private boolean mHardwarePttDown;

    private static volatile MumlaService sInstance;

    // --- P60: reliable automatic reconnect while in the background ----------

    /** How often a new connection attempt is made while the network is up. */
    private static final int RECONNECT_RETRY_INTERVAL = 15000;
    /** Pause before attempt number N (the last value repeats): quick at first, rare later. */
    private static final int[] RECONNECT_BACKOFF = {3000, 15000, 15000, 30000, 60000, 120000, 300000};
    private static final String RECONNECT_ALARM_ACTION = "se.lublin.mumla.p60.RECONNECT_ALARM";
    /** Delay before the first attempt after the connection or the network changed. */
    private static final int RECONNECT_FIRST_DELAY = 3000;
    /** Upper bound for holding the CPU awake without a successful reconnect. */
    private static final long RECONNECT_WAKELOCK_TIMEOUT = 40 * 1000L;

    // --- P60: journal of connection events, shown on the diagnostics screen ---

    private static final java.util.ArrayDeque<String> sEvents = new java.util.ArrayDeque<>();

    public static void p60Event(String text) {
        Log.i(TAG, "P60 " + text);
        String line = new java.text.SimpleDateFormat("dd.MM HH:mm:ss", java.util.Locale.US)
                .format(new java.util.Date()) + " " + text;
        synchronized (sEvents) {
            sEvents.addLast(line);
            while (sEvents.size() > 150) sEvents.removeFirst();
        }
    }

    public static java.util.List<String> getP60Events() {
        synchronized (sEvents) {
            return new java.util.ArrayList<>(sEvents);
        }
    }

    /** Number of failed attempts in a row; selects the pause before the next one. */
    private int mReconnectAttempts;
    private long mNextReconnectTickAt;
    private BroadcastReceiver mReconnectAlarmReceiver;
    private android.app.PendingIntent mReconnectAlarmIntent;

    private int nextReconnectDelay() {
        int index = Math.min(mReconnectAttempts, RECONNECT_BACKOFF.length - 1);
        return RECONNECT_BACKOFF[index];
    }

    /**
     * Schedules the next reconnect check. A handler timer does not run while the
     * CPU sleeps, so an alarm is set as well; the CPU is not kept awake in between.
     */
    private void scheduleReconnectTick(int delay) {
        mNextReconnectTickAt = SystemClock.elapsedRealtime() + delay;
        mReconnectHandler.removeCallbacks(mReconnectTick);
        mReconnectHandler.postDelayed(mReconnectTick, delay);
        if (delay < 10000) {
            // Short pause: just stay awake for it
            holdReconnectWakeLock();
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        try {
            android.app.AlarmManager am = (android.app.AlarmManager) getSystemService(ALARM_SERVICE);
            if (am == null) return;
            if (mReconnectAlarmIntent == null) {
                Intent intent = new Intent(RECONNECT_ALARM_ACTION).setPackage(getPackageName());
                mReconnectAlarmIntent = android.app.PendingIntent.getBroadcast(this, 0, intent,
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
            }
            am.setAndAllowWhileIdle(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    mNextReconnectTickAt, mReconnectAlarmIntent);
        } catch (RuntimeException e) {
            Log.w(TAG, "P60 reconnect: cannot set alarm: " + e);
        }
    }

    private void cancelReconnectAlarm() {
        try {
            android.app.AlarmManager am = (android.app.AlarmManager) getSystemService(ALARM_SERVICE);
            if (am != null && mReconnectAlarmIntent != null) am.cancel(mReconnectAlarmIntent);
        } catch (RuntimeException e) {
            // nothing to cancel
        }
    }

    private final Handler mReconnectHandler = new Handler(Looper.getMainLooper());
    private boolean mReconnectWatchActive;
    private PowerManager.WakeLock mReconnectWakeLock;
    private ConnectivityManager.NetworkCallback mReconnectNetworkCallback;

    private final Runnable mReconnectTick = new Runnable() {
        @Override
        public void run() {
            if (!mReconnectWatchActive) return;
            if (SystemClock.elapsedRealtime() < mNextReconnectTickAt - 1000) {
                // Woken early (timer and alarm both fire); the real time has not come yet.
                return;
            }
            ConnectionState state = getConnectionState();
            if (state == ConnectionState.CONNECTED && !mForceReconnectPending) {
                stopReconnectWatch();
                return;
            }
            if (state != ConnectionState.CONNECTING) {
                if (!isReconnecting() && !mForceReconnectPending) {
                    // Reconnecting was cancelled or is pointless (kicked, rejected...)
                    stopReconnectWatch();
                    return;
                }
                if (isNetworkAvailable()) {
                    holdReconnectWakeLock();
                    mForceReconnectPending = false;
                    mReconnectAttempts++;
                    mSettings.applyPingInterval();
                    p60Event("reconnect attempt " + mReconnectAttempts);
                    try {
                        connect();
                    } catch (RuntimeException e) {
                        Log.w(TAG, "P60 reconnect: connect failed: " + e);
                    }
                    // If this attempt fails, onDisconnected schedules the next one
                    // with a longer pause. This is only a safety net.
                    scheduleReconnectTick(Math.max(RECONNECT_RETRY_INTERVAL, nextReconnectDelay()));
                    return;
                } else {
                    // Nothing to do until the network returns; let the CPU sleep.
                    // The network callback wakes us up again.
                    releaseReconnectWakeLock();
                    scheduleReconnectTick(RECONNECT_BACKOFF[RECONNECT_BACKOFF.length - 1]);
                    return;
                }
            }
            scheduleReconnectTick(RECONNECT_RETRY_INTERVAL);
        }
    };

    // --- P60: watch the live connection (network change, dead link, slow link) ---

    /** No ping answer from the server for this long means the link is dead. */
    private static final long PING_TIMEOUT = 20000;
    private static final int PING_CHECK_INTERVAL = 5000;
    /** Economy audio profile for slow (2G) links. */
    private static final int ECONOMY_BITRATE = 12000;
    private static final int ECONOMY_FRAMES_PER_PACKET = 6;
    /** Estimated uplink speed, kbit/s: enter economy below, leave above. */
    private static final int ECONOMY_ENTER_KBPS = 100;
    private static final int ECONOMY_LEAVE_KBPS = 200;

    /** True while we dropped a dead connection ourselves and are about to reconnect. */
    private boolean mForceReconnectPending;
    private ConnectivityManager.NetworkCallback mLinkCallback;
    private Network mLinkNetwork;
    private String mLinkAddresses;
    private long mLastPingValue;
    private long mLastPingChange;
    private boolean mEconomyActive;
    private boolean mEconomyEvaluated;
    private long mEconomyChangedAt;
    private long mPingTimeoutWindowStart;
    private int mPingTimeoutCount;
    private boolean mPingWatchdogDisabled;
    /** Ping interval the current connection was started with, seconds. */
    private int mConnectedPingInterval = Settings.PING_INTERVAL_NORMAL;

    private final Runnable mPingWatchdog = new Runnable() {
        @Override
        public void run() {
            if (mLinkCallback == null || mPingWatchdogDisabled) return;
            if (getConnectionState() == ConnectionState.CONNECTED) {
                long now = SystemClock.elapsedRealtime();
                long value = mLastPingValue;
                try {
                    value = getTCPLatency();
                } catch (RuntimeException e) {
                    // not connected right now
                }
                if (value != mLastPingValue) {
                    mLastPingValue = value;
                    mLastPingChange = now;
                } else if (now - mLastPingChange > Math.max(PING_TIMEOUT, mConnectedPingInterval * 2500L)) {
                    // Safety: if this fires again and again, the check itself is
                    // wrong for this server; switch it off instead of reconnecting forever.
                    if (now - mPingTimeoutWindowStart > 10 * 60 * 1000L) {
                        mPingTimeoutWindowStart = now;
                        mPingTimeoutCount = 0;
                    }
                    mPingTimeoutCount++;
                    if (mPingTimeoutCount > 3) {
                        mPingWatchdogDisabled = true;
                        p60Event("ping check switched off: too many timeouts in 10 minutes");
                        return;
                    }
                    forceReconnect("no ping answer for " + (now - mLastPingChange) / 1000 + " s");
                    return;
                }
            }
            mReconnectHandler.postDelayed(this, PING_CHECK_INTERVAL);
        }
    };

    /** Drops a connection that can no longer work and reconnects right away. */
    private void forceReconnect(String reason) {
        if (mForceReconnectPending) return;
        if (getConnectionState() != ConnectionState.CONNECTED) return;
        if (!mSettings.isAutoReconnectEnabled()) return;
        p60Event("forced reconnect: " + reason);
        mForceReconnectPending = true;
        holdReconnectWakeLock();
        try {
            disconnect();
        } catch (RuntimeException e) {
            Log.w(TAG, "P60 reconnect: disconnect failed: " + e);
        }
        startReconnectWatch();
    }

    private static String describeAddresses(LinkProperties properties) {
        if (properties == null) return "";
        try {
            return String.valueOf(properties.getLinkAddresses());
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** Starts watching the network the live connection runs over. */
    private void startLinkWatch() {
        stopLinkWatch();
        mLinkNetwork = null;
        mLinkAddresses = null;
        mEconomyEvaluated = false;
        mLastPingValue = Long.MIN_VALUE;
        mLastPingChange = SystemClock.elapsedRealtime();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return;
        final ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                mReconnectHandler.post(() -> {
                    if (mLinkCallback != this) return;
                    if (mLinkNetwork != null && !mLinkNetwork.equals(network)) {
                        mLinkNetwork = network;
                        mLinkAddresses = null;
                        forceReconnect("default network changed");
                    } else {
                        mLinkNetwork = network;
                    }
                });
            }

            @Override
            public void onLinkPropertiesChanged(Network network, LinkProperties properties) {
                final String addresses = describeAddresses(properties);
                mReconnectHandler.post(() -> {
                    if (mLinkCallback != this) return;
                    // New addresses appearing is harmless (IPv6 adds them all the time);
                    // only an address that vanished can break the live connection.
                    boolean lost = false;
                    if (mLinkAddresses != null) {
                        for (String old : mLinkAddresses.split(",")) {
                            String trimmed = old.replace("[", "").replace("]", "").trim();
                            if (trimmed.length() > 0 && !addresses.contains(trimmed)) {
                                lost = true;
                                break;
                            }
                        }
                    }
                    mLinkAddresses = addresses;
                    if (lost) forceReconnect("IP address changed");
                });
            }

            @Override
            public void onLost(Network network) {
                mReconnectHandler.post(() -> {
                    if (mLinkCallback != this) return;
                    forceReconnect("network lost");
                });
            }

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                final boolean cellular = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
                final int upKbps = capabilities.getLinkUpstreamBandwidthKbps();
                mReconnectHandler.post(() -> {
                    if (mLinkCallback != this) return;
                    updateEconomyMode(cellular, upKbps);
                });
            }
        };
        mLinkCallback = callback;
        try {
            cm.registerDefaultNetworkCallback(callback);
        } catch (RuntimeException e) {
            Log.w(TAG, "P60 link watch: cannot watch network: " + e);
        }
        mReconnectHandler.removeCallbacks(mPingWatchdog);
        mReconnectHandler.postDelayed(mPingWatchdog, PING_CHECK_INTERVAL);
    }

    // --- P60: Bluetooth headset audio remembered across connections ----------

    private AudioDeviceCallback mAudioDeviceCallback;

    /** @return true if a Bluetooth headset with a voice channel is connected to the device. */
    public static boolean isBluetoothHeadsetPresent(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        try {
            AudioManager am = (AudioManager) context.getSystemService(AUDIO_SERVICE);
            if (am == null) return false;
            for (AudioDeviceInfo device : am.getDevices(AudioManager.GET_DEVICES_ALL)) {
                if (device.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) return true;
            }
        } catch (RuntimeException e) {
            // fall through
        }
        return false;
    }

    private final Runnable mApplyBluetoothPreference = new Runnable() {
        @Override
        public void run() {
            try {
                if (!mSettings.isBluetoothScoWanted()) return;
                if (!isConnectionEstablished()) return;
                if (usingBluetoothSco()) return;
                if (!isBluetoothHeadsetPresent(MumlaService.this)) return;
                Log.i(TAG, "P60 bluetooth: switching audio to the headset");
                enableBluetoothSco();
            } catch (RuntimeException e) {
                Log.w(TAG, "P60 bluetooth: cannot enable: " + e);
            }
        }
    };

    private void startBluetoothWatch() {
        stopBluetoothWatch();
        mReconnectHandler.postDelayed(mApplyBluetoothPreference, 1500);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am == null) return;
        mAudioDeviceCallback = new AudioDeviceCallback() {
            @Override
            public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
                // A headset was switched on or came back into range
                mReconnectHandler.removeCallbacks(mApplyBluetoothPreference);
                mReconnectHandler.postDelayed(mApplyBluetoothPreference, 2000);
            }
        };
        try {
            am.registerAudioDeviceCallback(mAudioDeviceCallback, mReconnectHandler);
        } catch (RuntimeException e) {
            mAudioDeviceCallback = null;
        }
    }

    private void stopBluetoothWatch() {
        mReconnectHandler.removeCallbacks(mApplyBluetoothPreference);
        if (mAudioDeviceCallback != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            try {
                if (am != null) am.unregisterAudioDeviceCallback(mAudioDeviceCallback);
            } catch (RuntimeException e) {
                // was not registered
            }
        }
        mAudioDeviceCallback = null;
    }

    private void stopLinkWatch() {
        stopBluetoothWatch();
        mReconnectHandler.removeCallbacks(mPingWatchdog);
        if (mLinkCallback != null) {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            try {
                if (cm != null) cm.unregisterNetworkCallback(mLinkCallback);
            } catch (RuntimeException e) {
                // was not registered
            }
            mLinkCallback = null;
        }
    }

    /**
     * Switches to a low-bitrate audio profile on slow mobile links (2G) and
     * back to the user's settings on faster ones.
     */
    private void updateEconomyMode(boolean cellular, int upKbps) {
        boolean wanted;
        if (!mSettings.isEconomyModeEnabled() || !cellular || upKbps <= 0) {
            wanted = false;
        } else if (mEconomyActive) {
            wanted = upKbps < ECONOMY_LEAVE_KBPS;
        } else {
            wanted = upKbps < ECONOMY_ENTER_KBPS;
        }
        if (mEconomyEvaluated && wanted == mEconomyActive) return;
        long now = SystemClock.elapsedRealtime();
        if (mEconomyEvaluated && now - mEconomyChangedAt < 60000) {
            // The speed estimate jitters; do not restart the audio more than once a minute.
            return;
        }
        mEconomyChangedAt = now;
        boolean changed = wanted != mEconomyActive;
        boolean firstTime = !mEconomyEvaluated;
        mEconomyEvaluated = true;
        mEconomyActive = wanted;
        if (firstTime && !wanted && !changed) {
            // Fresh connection on a fast link: the user's settings are already applied.
            return;
        }
        int bitrate = mSettings.getInputQuality();
        int framesPerPacket = mSettings.getFramesPerPacket();
        if (wanted) {
            bitrate = Math.min(bitrate, ECONOMY_BITRATE);
            framesPerPacket = Math.max(framesPerPacket, ECONOMY_FRAMES_PER_PACKET);
        }
        p60Event("economy mode " + (wanted ? "ON" : "OFF") + ", uplink " + upKbps
                + " kbit/s, bitrate " + bitrate + ", frames per packet " + framesPerPacket);
        Bundle extras = new Bundle();
        extras.putInt(EXTRAS_INPUT_QUALITY, bitrate);
        extras.putInt(EXTRAS_FRAMES_PER_PACKET, framesPerPacket);
        try {
            configureExtras(extras);
        } catch (AudioException e) {
            Log.w(TAG, "P60 economy mode: cannot apply: " + e);
        } catch (RuntimeException e) {
            Log.w(TAG, "P60 economy mode: cannot apply: " + e);
        }
        if (changed) {
            Toast.makeText(this, wanted ? R.string.p60EconomyOn : R.string.p60EconomyOff,
                    Toast.LENGTH_SHORT).show();
        }
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return true;
        try {
            NetworkInfo info = cm.getActiveNetworkInfo();
            return info != null && info.isConnected();
        } catch (RuntimeException e) {
            return true;
        }
    }

    private void holdReconnectWakeLock() {
        try {
            if (mReconnectWakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                mReconnectWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Mumla:Reconnect");
                mReconnectWakeLock.setReferenceCounted(false);
            }
            mReconnectWakeLock.acquire(RECONNECT_WAKELOCK_TIMEOUT);
        } catch (RuntimeException e) {
            Log.w(TAG, "P60 reconnect: wake lock failed: " + e);
        }
    }

    private void releaseReconnectWakeLock() {
        try {
            if (mReconnectWakeLock != null && mReconnectWakeLock.isHeld()) {
                mReconnectWakeLock.release();
            }
        } catch (RuntimeException e) {
            // already released by its timeout
        }
    }

    /** Starts watching the network and retrying until the connection is back. */
    private void startReconnectWatch() {
        // The pause grows with every failed attempt in a row, so an unreachable
        // server does not drain the battery with a connection attempt every few seconds.
        scheduleReconnectTick(nextReconnectDelay());
        if (mReconnectWatchActive) return;
        mReconnectWatchActive = true;
        if (mReconnectAlarmReceiver == null) {
            mReconnectAlarmReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    holdReconnectWakeLock();
                    mReconnectHandler.post(mReconnectTick);
                }
            };
            try {
                IntentFilter filter = new IntentFilter(RECONNECT_ALARM_ACTION);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(mReconnectAlarmReceiver, filter, RECEIVER_NOT_EXPORTED);
                } else {
                    registerReceiver(mReconnectAlarmReceiver, filter);
                }
            } catch (RuntimeException e) {
                mReconnectAlarmReceiver = null;
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm != null) {
                mReconnectNetworkCallback = new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(Network network) {
                        // Called on a system thread as soon as any network comes back
                        holdReconnectWakeLock();
                        mReconnectHandler.post(() -> {
                            if (!mReconnectWatchActive) return;
                            // The network is back: try soon and start the pauses over
                            mReconnectAttempts = 0;
                            scheduleReconnectTick(RECONNECT_FIRST_DELAY);
                        });
                    }
                };
                try {
                    cm.registerDefaultNetworkCallback(mReconnectNetworkCallback);
                } catch (RuntimeException e) {
                    Log.w(TAG, "P60 reconnect: cannot watch network: " + e);
                    mReconnectNetworkCallback = null;
                }
            }
        }
    }

    private void stopReconnectWatch() {
        mReconnectWatchActive = false;
        mReconnectHandler.removeCallbacks(mReconnectTick);
        cancelReconnectAlarm();
        if (mReconnectAlarmReceiver != null) {
            try {
                unregisterReceiver(mReconnectAlarmReceiver);
            } catch (IllegalArgumentException e) {
                // was not registered
            }
            mReconnectAlarmReceiver = null;
        }
        if (mReconnectNetworkCallback != null) {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            try {
                if (cm != null) cm.unregisterNetworkCallback(mReconnectNetworkCallback);
            } catch (RuntimeException e) {
                // was not registered
            }
            mReconnectNetworkCallback = null;
        }
        releaseReconnectWakeLock();
    }

    /** P60 diagnostics: whether the low-bitrate profile is in effect right now. */
    public boolean isEconomyActive() {
        return mEconomyActive;
    }

    /** @return the running service, or null if it is not running. */
    public static MumlaService getInstance() {
        return sInstance;
    }

    /**
     * Called by the hardware PTT sources (accessibility key filter and PTT
     * broadcasts). The same press may arrive from both, so duplicates are dropped.
     */
    public void onHardwarePtt(boolean down) {
        boolean allowed = mSettings.isPttBackgroundEnabled() || MumlaActivity.isOnScreen(this);
        Log.i(TAG, "P60 PTT " + (down ? "down" : "up") + " background=" + mSettings.isPttBackgroundEnabled()
                + " onScreen=" + MumlaActivity.isOnScreen(this) + " allowed=" + allowed);
        if (down && !allowed) {
            // Background PTT is switched off and Mumla is not on screen.
            // Releases are still processed so a transmission can never get stuck.
            return;
        }
        if (!Settings.ARRAY_INPUT_METHOD_PTT.equals(mSettings.getInputMethod())) {
            if (down) {
                Toast.makeText(this, R.string.pttNeedsPttMode, Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (mSettings.isPushToTalkToggle()) {
            // Toggle mode acts on release; drop a duplicate release that
            // arrives from a second source for the same press.
            if (down) {
                mHardwarePttDown = true;
            } else if (mHardwarePttDown) {
                mHardwarePttDown = false;
                onTalkKeyUp();
            }
            return;
        }
        // Hold-to-talk: both calls are idempotent, so never drop an event.
        mHardwarePttDown = down;
        if (down) {
            onTalkKeyDown();
        } else {
            onTalkKeyUp();
        }
    }

    private HumlaObserver mObserver = new HumlaObserver() {
        @Override
        public void onConnecting() {
            // Remove old notification left from reconnect,
            if (mReconnectNotification != null) {
                mReconnectNotification.hide();
                mReconnectNotification = null;
            }

            final String tor = mSettings.isTorEnabled() ? " (Tor)" : "";
            if (mNotification != null) {
                // P60: kept from before the connection was lost, so the service
                // never leaves the foreground while reconnecting.
                mNotification.setCustomContentText(getString(R.string.mumlaConnecting) + tor);
                mNotification.setActionsShown(false);
            } else {
                mNotification = MumlaConnectionNotification.create(MumlaService.this,
                        getString(R.string.mumlaConnecting) + tor,
                        MumlaService.this);
            }
            mNotification.show();

            mErrorShown = false;
        }

        @Override
        public void onConnected() {
            if (mNotification != null) {
                final String tor = mSettings.isTorEnabled() ? " (Tor)" : "";
                mNotification.setCustomContentText(getString(R.string.connected) + tor);
                mNotification.setActionsShown(true);
                mNotification.show();
            }
        }

        @Override
        public void onDisconnected(HumlaException e) {
            if ((e != null && isReconnecting()) || mForceReconnectPending) {
                // P60: stay a foreground service while waiting for the network.
                // Otherwise Android may stop the app in the background and a
                // later reconnect would have no access to the microphone.
                if (mNotification != null) {
                    mNotification.setCustomContentText(getString(R.string.p60WaitingReconnect));
                    mNotification.setActionsShown(false);
                    mNotification.show();
                }
                startReconnectWatch();
            } else {
                stopReconnectWatch();
                if (mNotification != null) {
                    mNotification.hide();
                    mNotification = null;
                }
            }
            if (e != null && !mSuppressNotifications) {
                mReconnectNotification =
                        MumlaReconnectNotification.show(MumlaService.this,
                                e.getMessage() + (mSettings.isTorEnabled() ? " (Tor)" : ""),
                                isReconnecting(), MumlaService.this);
            }
        }

        @Override
        public void onUserConnected(IUser user) {
            if (user.getTextureHash() != null &&
                    user.getTexture() == null) {
                // Request avatar data if available.
                requestAvatar(user.getSession());
            }
        }

        @Override
        public void onUserStateUpdated(IUser user) {
            if (user == null) {
                return;
            }

            int selfSession;
            try {
                selfSession = getSessionId();
            } catch (IllegalStateException e) {
                Log.d(TAG, "exception in onUserStateUpdated: " + e);
                return;
            }

            if (user.getSession() == selfSession) {
                mSettings.setMutedAndDeafened(user.isSelfMuted(), user.isSelfDeafened()); // Update settings mute/deafen state
                if(mNotification != null) {
                    String contentText;
                    if (user.isSelfMuted() && user.isSelfDeafened())
                        contentText = getString(R.string.status_notify_muted_and_deafened);
                    else if (user.isSelfMuted())
                        contentText = getString(R.string.status_notify_muted);
                    else
                        contentText = getString(R.string.connected);
                    mNotification.setCustomContentText(contentText);
                    mNotification.show();
                }
            }

            if (user.getTextureHash() != null && user.getTexture() == null) {
                // Update avatar data if available.
                requestAvatar(user.getSession());
            }
        }

        @Override
        public void onMessageLogged(IMessage message) {
            // Split on / strip all HTML tags.
            Document parsedMessage = Jsoup.parseBodyFragment(message.getMessage());
            String strippedMessage = parsedMessage.text();

            String ttsMessage;
            if(mShortTtsMessagesEnabled) {
                for (Element anchor : parsedMessage.getElementsByTag("A")) {
                    // Get just the domain portion of links
                    String href = anchor.attr("href");
                    // Only shorten anchors without custom text
                    if (href != null && href.equals(anchor.text())) {
                        String urlHostname = HtmlUtils.getHostnameFromLink(href);
                        if (urlHostname != null) {
                            anchor.text(getString(R.string.chat_message_tts_short_link, urlHostname));
                        }
                    }
                }
                ttsMessage = parsedMessage.text();
            } else {
                ttsMessage = strippedMessage;
            }

            String formattedTtsMessage = getString(R.string.notification_message,
                    message.getActorName(), ttsMessage);

            // Read if TTS is enabled, the message is less than threshold, is a text message, and not deafened
            if(mSettings.isTextToSpeechEnabled() &&
                    mTTS != null &&
                    formattedTtsMessage.length() <= TTS_THRESHOLD &&
                    getSessionUser() != null &&
                    !getSessionUser().isSelfDeafened()) {
                mTTS.speak(formattedTtsMessage, TextToSpeech.QUEUE_ADD, null);
            }

            // TODO: create a customizable notification sieve
            if (mSettings.isChatNotifyEnabled()) {
                mMessageNotification.show(message);
            }

            mMessageLog.add(new IChatMessage.TextMessage(message));
        }

        @Override
        public void onLogInfo(String message) {
            mMessageLog.add(new IChatMessage.InfoMessage(IChatMessage.InfoMessage.Type.INFO, message));
        }

        @Override
        public void onLogWarning(String message) {
            mMessageLog.add(new IChatMessage.InfoMessage(IChatMessage.InfoMessage.Type.WARNING, message));
        }

        @Override
        public void onLogError(String message) {
            mMessageLog.add(new IChatMessage.InfoMessage(IChatMessage.InfoMessage.Type.ERROR, message));
        }

        @Override
        public void onPermissionDenied(String reason) {
            if(mNotification != null && !mSuppressNotifications) {
                mNotification.show();
            }
        }

        @Override
        public void onUserTalkStateUpdated(IUser user) {
            int selfSession = -1;
            try {
                selfSession = getSessionId();
            } catch (IllegalStateException e) {
                Log.d(TAG, "exception in onUserTalkStateUpdated: " + e);
            }

            if (isConnectionEstablished() &&
                    user.getSession() == selfSession &&
                    getTransmitMode() == Constants.TRANSMIT_PUSH_TO_TALK &&
                    user.getTalkState() == TalkState.TALKING &&
                    mPTTSoundEnabled) {
                AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
                audioManager.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD, -1);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        registerObserver(mObserver);

        // Register for preference changes
        mSettings = Settings.getInstance(this);
        mPTTSoundEnabled = mSettings.isPttSoundEnabled();
        mShortTtsMessagesEnabled = mSettings.isShortTextToSpeechMessagesEnabled();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        preferences.registerOnSharedPreferenceChangeListener(this);

        // Manually set theme to style overlay views
        // XML <application> theme does NOT do this!
        setTheme(R.style.Theme_Mumla);

        mMessageLog = new ArrayList<>();
        mMessageNotification = new MumlaMessageNotification(MumlaService.this);

        // Instantiate overlay view
        mChannelOverlay = new MumlaOverlay(this);
        mHotCorner = new MumlaHotCorner(this, mSettings.getHotCornerGravity(), mHotCornerListener);

        // Set up TTS
        if(mSettings.isTextToSpeechEnabled())
            mTTS = new TextToSpeech(this, mTTSInitListener);

        mTalkReceiver = new TalkBroadcastReceiver(this);
        mHardwarePttReceiver = new HardwarePttReceiver(this);
        sInstance = this;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return new MumlaBinder(this);
    }

    /**
     * P60: the user swiped Mumla away from the recent apps list. Treat it as
     * "quit": drop the connection, stop reconnecting and stop the service.
     */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        if (!mSettings.isQuitOnSwipeEnabled()) return;
        p60Event("swiped away, quitting");
        mForceReconnectPending = false;
        try {
            if (isReconnecting()) cancelReconnect();
        } catch (RuntimeException e) {
            Log.w(TAG, "P60 quit: " + e);
        }
        stopLinkWatch();
        stopReconnectWatch();
        try {
            disconnect();
        } catch (RuntimeException e) {
            // was not connected
        }
        if (mNotification != null) {
            mNotification.hide();
            mNotification = null;
        }
        if (mReconnectNotification != null) {
            mReconnectNotification.hide();
            mReconnectNotification = null;
        }
        stopSelf();
    }

    @Override
    public void onDestroy() {
        stopLinkWatch();
        stopReconnectWatch();
        if (mNotification != null) {
            mNotification.hide();
            mNotification = null;
        }
        if (mReconnectNotification != null) {
            mReconnectNotification.hide();
            mReconnectNotification = null;
        }

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        preferences.unregisterOnSharedPreferenceChangeListener(this);
        try {
            unregisterReceiver(mTalkReceiver);
        } catch (IllegalArgumentException e) {
            e.printStackTrace();
        }
        try {
            unregisterReceiver(mHardwarePttReceiver);
        } catch (IllegalArgumentException e) {
            // was not registered
        }
        sInstance = null;

        unregisterObserver(mObserver);
        if(mTTS != null) mTTS.shutdown();
        mMessageLog = null;
        mMessageNotification.dismiss();
        super.onDestroy();
    }

    @Override
    public void onConnectionSynchronized() {
        // TODO? We seem to be getting a RuntimeException here, from the call
        //  to the superclass function (in HumlaService). In there,
        //  mConnect.getSession() finds that isSynchronized==false and throws
        //  NotSynchronizedException (which is re-thrown as the
        //  RuntimeException). But how can it be !isSynchronized? -- A server
        //  msg triggers HumlaConnection.messageServerSync(), which sets up
        //  mSession and mSynchronized==true and then proceeds to call us from
        //  a Runnable post()ed to a Handler. The reason could only be that
        //  HumlaConnect.connect() or disconnect() is called again in the
        //  middle of all this? And it's made possible by the Handler?
        mForceReconnectPending = false;
        mReconnectAttempts = 0;
        mConnectedPingInterval = Integer.getInteger(Settings.PING_INTERVAL_PROPERTY, Settings.PING_INTERVAL_NORMAL);
        p60Event("connected, ping every " + mConnectedPingInterval + " s");
        stopReconnectWatch();
        try {
            super.onConnectionSynchronized();
        } catch (RuntimeException e) {
            Log.d(TAG, "exception in onConnectionSynchronized: " + e);
            return;
        }
        startLinkWatch();
        startBluetoothWatch();

        // Restore mute/deafen state
        if(mSettings.isMuted() || mSettings.isDeafened()) {
            setSelfMuteDeafState(mSettings.isMuted(), mSettings.isDeafened());
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            registerReceiver(mTalkReceiver, new IntentFilter(TalkBroadcastReceiver.BROADCAST_TALK), RECEIVER_EXPORTED);
            registerReceiver(mHardwarePttReceiver, HardwarePttReceiver.createFilter(), RECEIVER_EXPORTED);
        } else {
            registerReceiver(mTalkReceiver, new IntentFilter(TalkBroadcastReceiver.BROADCAST_TALK));
            registerReceiver(mHardwarePttReceiver, HardwarePttReceiver.createFilter());
        }
        mHardwarePttDown = false;

        if (mSettings.isHotCornerEnabled()) {
            mHotCorner.setShown(true);
        }
        // Configure proximity sensor
        if (mSettings.isHandsetMode()) {
            setProximitySensorOn(true);
        }
    }

    @Override
    public void onConnectionDisconnected(HumlaException e) {
        p60Event("disconnected" + (e != null ? ": " + e.getMessage() : ""));
        stopLinkWatch();
        super.onConnectionDisconnected(e);
        try {
            unregisterReceiver(mTalkReceiver);
        } catch (IllegalArgumentException iae) {
        }
        try {
            unregisterReceiver(mHardwarePttReceiver);
        } catch (IllegalArgumentException iae) {
        }
        mHardwarePttDown = false;

        // Remove overlay if present.
        mChannelOverlay.hide();

        mHotCorner.setShown(false);

        setProximitySensorOn(false);

        clearMessageLog();
        mMessageNotification.dismiss();
    }

    /**
     * Called when the user makes a change to their preferences.
     * Should update all preferences relevant to the service.
     */
    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        Bundle changedExtras = new Bundle();
        boolean requiresReconnect = false;
        switch (key) {
            case Settings.PREF_INPUT_METHOD:
                /* Convert input method defined in settings to an integer format used by Humla. */
                int inputMethod = mSettings.getHumlaInputMethod();
                changedExtras.putInt(HumlaService.EXTRAS_TRANSMIT_MODE, inputMethod);
                mChannelOverlay.setPushToTalkShown(inputMethod == Constants.TRANSMIT_PUSH_TO_TALK);
                break;
            case Settings.PREF_HANDSET_MODE:
                setProximitySensorOn(isConnectionEstablished() && mSettings.isHandsetMode());
                changedExtras.putInt(HumlaService.EXTRAS_AUDIO_STREAM, mSettings.isHandsetMode() ?
                                     AudioManager.STREAM_VOICE_CALL : AudioManager.STREAM_MUSIC);
                break;
            case Settings.PREF_BATTERY_SAVER:
                // Takes effect when the next connection starts
                mSettings.applyPingInterval();
                break;
            case Settings.PREF_AUDIO_SOURCE:
                changedExtras.putInt(HumlaService.EXTRAS_AUDIO_SOURCE, mSettings.getAudioSource());
                break;
            case Settings.PREF_THRESHOLD:
                changedExtras.putFloat(HumlaService.EXTRAS_DETECTION_THRESHOLD,
                        mSettings.getDetectionThreshold());
                break;
            case Settings.PREF_HOT_CORNER_KEY:
                mHotCorner.setGravity(mSettings.getHotCornerGravity());
                mHotCorner.setShown(isConnectionEstablished() && mSettings.isHotCornerEnabled());
                break;
            case Settings.PREF_USE_TTS:
                if (mTTS == null && mSettings.isTextToSpeechEnabled())
                    mTTS = new TextToSpeech(this, mTTSInitListener);
                else if (mTTS != null && !mSettings.isTextToSpeechEnabled()) {
                    mTTS.shutdown();
                    mTTS = null;
                }
                break;
            case Settings.PREF_SHORT_TTS_MESSAGES:
                mShortTtsMessagesEnabled = mSettings.isShortTextToSpeechMessagesEnabled();
                break;
            case Settings.PREF_AMPLITUDE_BOOST:
                changedExtras.putFloat(EXTRAS_AMPLITUDE_BOOST,
                        mSettings.getAmplitudeBoostMultiplier());
                break;
            case Settings.PREF_HALF_DUPLEX:
                changedExtras.putBoolean(EXTRAS_HALF_DUPLEX, mSettings.isHalfDuplex());
                break;
            case Settings.PREF_PREPROCESSOR_ENABLED:
                changedExtras.putBoolean(EXTRAS_ENABLE_PREPROCESSOR,
                        mSettings.isPreprocessorEnabled());
                break;
            case Settings.PREF_ECHO_CANCELLATION_METHOD:
                changedExtras.putString(EXTRAS_ECHO_CANCELLATION_METHOD,
                        mSettings.getEchoCancellationMethod());
                break;
            case Settings.PREF_PTT_SOUND:
                mPTTSoundEnabled = mSettings.isPttSoundEnabled();
                break;
            case Settings.PREF_INPUT_QUALITY:
                changedExtras.putInt(EXTRAS_INPUT_QUALITY, mSettings.getInputQuality());
                break;
            case Settings.PREF_INPUT_RATE:
                changedExtras.putInt(EXTRAS_INPUT_RATE, mSettings.getInputSampleRate());
                break;
            case Settings.PREF_FRAMES_PER_PACKET:
                changedExtras.putInt(EXTRAS_FRAMES_PER_PACKET, mSettings.getFramesPerPacket());
                break;
            case Settings.PREF_CERT_ID:
            case Settings.PREF_FORCE_TCP:
            case Settings.PREF_USE_TOR:
            case Settings.PREF_DISABLE_OPUS:
                // These are settings we flag as 'requiring reconnect'.
                requiresReconnect = true;
                break;
        }
        if (changedExtras.size() > 0) {
            try {
                // Reconfigure the service appropriately.
                requiresReconnect |= configureExtras(changedExtras);
            } catch (AudioException e) {
                e.printStackTrace();
            }
        }

        if (requiresReconnect && isConnectionEstablished()) {
            Toast.makeText(this, R.string.change_requires_reconnect, Toast.LENGTH_LONG).show();
        }
    }

    private void setProximitySensorOn(boolean on) {
        if(on) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            mProximityLock = pm.newWakeLock(PROXIMITY_SCREEN_OFF_WAKE_LOCK, "Mumla:Proximity");
            mProximityLock.acquire();
        } else {
            if(mProximityLock != null) mProximityLock.release();
            mProximityLock = null;
        }
    }

    @Override
    public void onMuteToggled() {
        IUser user = getSessionUser();
        if (isConnectionEstablished() && user != null) {
            boolean muted = !user.isSelfMuted();
            boolean deafened = user.isSelfDeafened() && muted;
            setSelfMuteDeafState(muted, deafened);
        }
    }

    @Override
    public void onDeafenToggled() {
        IUser user = getSessionUser();
        if (isConnectionEstablished() && user != null) {
            setSelfMuteDeafState(!user.isSelfDeafened(), !user.isSelfDeafened());
        }
    }

    @Override
    public void onOverlayToggled() {
        // Ditch notification shade/panel to make overlay presence/permission request visible.
        // But on Android 12 that's no longer allowed.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            Intent close = new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
            getApplicationContext().sendBroadcast(close);
        }

        if (!mChannelOverlay.isShown()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!android.provider.Settings.canDrawOverlays(getApplicationContext())) {
                    Intent showSetting = new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName()));
                    showSetting.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(showSetting);
                    Toast.makeText(this, R.string.grant_perm_draw_over_apps, Toast.LENGTH_LONG).show();
                    return;
                }
            }
            mChannelOverlay.show();
        } else {
            mChannelOverlay.hide();
        }
    }

    @Override
    public void onReconnectNotificationDismissed() {
        mErrorShown = true;
    }

    @Override
    public void reconnect() {
        connect();
    }

    @Override
    public void cancelReconnect() {
        if (mReconnectNotification != null) {
            mReconnectNotification.hide();
            mReconnectNotification = null;
        }
        super.cancelReconnect();
        mForceReconnectPending = false;
        stopReconnectWatch();
        if (mNotification != null && !isConnectionEstablished()) {
            mNotification.hide();
            mNotification = null;
        }
    }

    @Override
    public void setOverlayShown(boolean showOverlay) {
        if(!mChannelOverlay.isShown()) {
            mChannelOverlay.show();
        } else {
            mChannelOverlay.hide();
        }
    }

    @Override
    public boolean isOverlayShown() {
        return mChannelOverlay.isShown();
    }

    @Override
    public void clearChatNotifications() {
        mMessageNotification.dismiss();
    }

    @Override
    public void markErrorShown() {
        mErrorShown = true;
        // Dismiss the reconnection prompt if a reconnection isn't in progress.
        if (mReconnectNotification != null && !isReconnecting()) {
            mReconnectNotification.hide();
            mReconnectNotification = null;
        }
    }

    @Override
    public boolean isErrorShown() {
        return mErrorShown;
    }

    /**
     * Called when a user presses a talk key down (i.e. when they want to talk).
     * Accounts for talk logic if toggle PTT is on.
     */
    @Override
    public void onTalkKeyDown() {
        if(isConnectionEstablished()
                && Settings.ARRAY_INPUT_METHOD_PTT.equals(mSettings.getInputMethod())) {
            if (!mSettings.isPushToTalkToggle() && !isTalking()) {
                setTalkingState(true); // Start talking
            }
        }
    }

    /**
     * Called when a user releases a talk key (i.e. when they do not want to talk).
     * Accounts for talk logic if toggle PTT is on.
     */
    @Override
    public void onTalkKeyUp() {
        if(isConnectionEstablished()
                && Settings.ARRAY_INPUT_METHOD_PTT.equals(mSettings.getInputMethod())) {
            if (mSettings.isPushToTalkToggle()) {
                setTalkingState(!isTalking()); // Toggle talk state
            } else if (isTalking()) {
                setTalkingState(false); // Stop talking
            }
        }
    }

    @Override
    public List<IChatMessage> getMessageLog() {
        return Collections.unmodifiableList(mMessageLog);
    }

    @Override
    public void clearMessageLog() {
        if (mMessageLog != null) {
            mMessageLog.clear();
        }
    }

    /**
     * Sets whether or not notifications should be suppressed.
     *
     * It's typically a good idea to do this when the main activity is foreground, so that the user
     * is not bombarded with redundant alerts.
     *
     * <b>Chat notifications are NOT suppressed.</b> They may be if a chat indicator is added in the
     * activity itself. For now, the user may disable chat notifications manually.
     *
     * @param suppressNotifications true if Mumla is to disable notifications.
     */
    @Override
    public void setSuppressNotifications(boolean suppressNotifications) {
        mSuppressNotifications = suppressNotifications;
    }

    public static class MumlaBinder extends Binder {
        private final MumlaService mService;

        private MumlaBinder(MumlaService service) {
            mService = service;
        }

        public IMumlaService getService() {
            return mService;
        }
    }

    @Override
    public Message sendUserTextMessage(int session, String message) {
        Message msg = super.sendUserTextMessage(session, message);

        mMessageLog.add(new IChatMessage.TextMessage(msg));
        return msg;
    }

    @Override
    public Message sendChannelTextMessage(int channel, String message, boolean tree) {
        Message msg = super.sendChannelTextMessage(channel, message, tree);

        mMessageLog.add(new IChatMessage.TextMessage(msg));
        return msg;
    }
}
