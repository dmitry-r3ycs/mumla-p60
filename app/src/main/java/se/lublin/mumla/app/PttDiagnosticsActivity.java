/*
 * Hytera P60 adaptation: shows how the hardware keys reach Android.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package se.lublin.mumla.app;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import se.lublin.mumla.R;
import se.lublin.mumla.Settings;
import se.lublin.mumla.service.MumlaService;
import se.lublin.mumla.service.PttAccessibilityService;

/**
 * Logs every way a hardware button can reach an app: key events seen by the
 * foreground window [APP], key events seen by the accessibility service [ACC],
 * media button events [MEDIA] and firmware broadcasts [INT]. Candidate
 * broadcast names are collected from the manifests of installed apps [SCAN].
 */
public class PttDiagnosticsActivity extends Activity implements PttAccessibilityService.KeyListener {
    private static final String[] KNOWN_ACTIONS = {
            "android.intent.action.PTT_DOWN", "android.intent.action.PTT_UP",
            "android.intent.action.PTT.down", "android.intent.action.PTT.up",
            "android.intent.action.ACTION_PTT_BUTTON_DOWN", "android.intent.action.ACTION_PTT_BUTTON_UP",
            "android.intent.action.PTT_BUTTON",
            "com.sonim.intent.action.PTT_KEY_DOWN", "com.sonim.intent.action.PTT_KEY_UP",
            "com.runbo.ptt.key.down", "com.runbo.ptt.key.up",
            "com.zello.ptt.down", "com.zello.ptt.up",
            "com.kodiak.intent.action.PTT_BUTTON",
            // Bluetooth headsets and speaker-microphones
            "android.bluetooth.headset.profile.action.AUDIO_STATE_CHANGED",
            "android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED",
            "android.bluetooth.headset.action.VENDOR_SPECIFIC_HEADSET_EVENT",
            "android.bluetooth.device.action.ACL_CONNECTED",
            "android.bluetooth.device.action.ACL_DISCONNECTED",
            "android.media.ACTION_SCO_AUDIO_STATE_UPDATED",
    };
    private static final int MAX_ACTIONS = 600;
    private static final int MAX_LOG_CHARS = 60000;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final StringBuilder mText = new StringBuilder();
    private final SimpleDateFormat mTime = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private final Set<String> mActions = new LinkedHashSet<>();
    private TextView mLogView;
    private ScrollView mScroll;
    private BroadcastReceiver mReceiver;
    private MediaSession mSession;
    private volatile boolean mDestroyed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.addView(makeButton(R.string.pttDiagClear, v -> {
            mText.setLength(0);
            writeHeader();
        }));
        buttons.addView(makeButton(R.string.pttDiagCopy, v -> copyLog()));
        buttons.addView(makeButton(R.string.pttDiagShare, v -> shareLog()));
        root.addView(buttons, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mScroll = new ScrollView(this);
        mLogView = new TextView(this);
        mLogView.setTextSize(12);
        mLogView.setPadding(12, 12, 12, 12);
        mScroll.addView(mLogView);
        root.addView(mScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        writeHeader();

        for (String action : KNOWN_ACTIONS) mActions.add(action);
        registerActions();
        startMediaSession();

        new Thread(this::scanPackages, "ptt-scan").start();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && checkSelfPermission("android.permission.BLUETOOTH_CONNECT")
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.BLUETOOTH_CONNECT"}, 1);
        }
    }

    private Button makeButton(int textRes, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(textRes);
        button.setFocusable(false);
        button.setOnClickListener(listener);
        button.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return button;
    }

    private void writeHeader() {
        log(getString(R.string.pttDiagHint));
        log("Mumla P60 version: " + se.lublin.mumla.BuildConfig.VERSION_NAME
                + ", PTT in background: " + (Settings.getInstance(this).isPttBackgroundEnabled() ? "ON" : "OFF"));
        log("Device: " + Build.MANUFACTURER + " " + Build.MODEL + ", Android " + Build.VERSION.RELEASE
                + " (" + Build.DISPLAY + ")");
        log("Accessibility service Mumla PTT: " + (PttAccessibilityService.isRunning() ? "ON" : "OFF"));
        logMumlaState();
        logBluetoothDevices();
    }

    /** Lists paired Bluetooth devices so the type of a PTT accessory can be identified. */
    private void logBluetoothDevices() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && checkSelfPermission("android.permission.BLUETOOTH_CONNECT")
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                log("[BT] no permission yet");
                return;
            }
            android.bluetooth.BluetoothAdapter adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                log("[BT] Bluetooth is off");
                return;
            }
            Set<android.bluetooth.BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded == null || bonded.isEmpty()) {
                log("[BT] no paired devices");
                return;
            }
            for (android.bluetooth.BluetoothDevice device : bonded) {
                StringBuilder sb = new StringBuilder("[BT] ");
                sb.append(device.getName()).append(" type=").append(device.getType());
                if (device.getBluetoothClass() != null) {
                    sb.append(" class=0x").append(Integer.toHexString(device.getBluetoothClass().getDeviceClass()));
                }
                android.os.ParcelUuid[] uuids = device.getUuids();
                if (uuids != null) {
                    sb.append(" uuids=");
                    for (android.os.ParcelUuid uuid : uuids) {
                        String text = uuid.toString();
                        sb.append(text.length() >= 8 ? text.substring(4, 8) : text).append(' ');
                    }
                }
                log(sb.toString().trim());
            }
        } catch (RuntimeException e) {
            log("[BT] " + e);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        logBluetoothDevices();
        registerActions();
    }

    /** Makes this screen the target of headset media buttons by playing a moment of silence. */
    private void playSilence() {
        try {
            int sampleRate = 8000;
            short[] silence = new short[sampleRate / 4];
            android.media.AudioTrack track = new android.media.AudioTrack(
                    android.media.AudioManager.STREAM_MUSIC, sampleRate,
                    android.media.AudioFormat.CHANNEL_OUT_MONO,
                    android.media.AudioFormat.ENCODING_PCM_16BIT,
                    silence.length * 2, android.media.AudioTrack.MODE_STATIC);
            track.write(silence, 0, silence.length);
            track.play();
            mHandler.postDelayed(() -> {
                try {
                    track.release();
                } catch (RuntimeException e) {
                    // already released
                }
            }, 1000);
        } catch (RuntimeException e) {
            log("[MEDIA] silence: " + e);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        PttAccessibilityService.setDiagnosticsListener(this);
        playSilence();
    }

    @Override
    protected void onPause() {
        PttAccessibilityService.setDiagnosticsListener(null);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        mDestroyed = true;
        PttAccessibilityService.setDiagnosticsListener(null);
        unregisterActions();
        if (mSession != null) {
            mSession.setActive(false);
            mSession.release();
            mSession = null;
        }
        super.onDestroy();
    }

    private void logMumlaState() {
        MumlaService service = MumlaService.getInstance();
        boolean connected = service != null && service.isConnectionEstablished();
        Settings settings = Settings.getInstance(this);
        String state = "[MUMLA] connected=" + (connected ? "YES" : "NO")
                + " mode=" + settings.getInputMethod()
                + " toggle=" + settings.isPushToTalkToggle();
        if (connected) {
            try {
                state += " talking=" + service.isTalking();
            } catch (RuntimeException e) {
                state += " talking=?";
            }
        }
        log(state);
    }

    // --- key events ---------------------------------------------------------

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        log("[APP] " + describe(event));
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            return super.dispatchKeyEvent(event);
        }
        return true;
    }

    @Override
    public void onAccessibilityKey(KeyEvent event) {
        final String line = "[ACC] " + describe(event);
        mHandler.post(() -> log(line));
    }

    private static String describe(KeyEvent event) {
        String action;
        switch (event.getAction()) {
            case KeyEvent.ACTION_DOWN:
                action = "DOWN";
                break;
            case KeyEvent.ACTION_UP:
                action = "UP";
                break;
            default:
                action = "ACTION" + event.getAction();
        }
        String device = event.getDevice() != null ? event.getDevice().getName() : "?";
        return action + " code=" + event.getKeyCode()
                + " (" + KeyEvent.keyCodeToString(event.getKeyCode()) + ")"
                + " scan=" + event.getScanCode()
                + " repeat=" + event.getRepeatCount()
                + " dev=" + device;
    }

    // --- media buttons ------------------------------------------------------

    private void startMediaSession() {
        try {
            mSession = new MediaSession(this, "MumlaP60Diagnostics");
            mSession.setCallback(new MediaSession.Callback() {
                @Override
                public boolean onMediaButtonEvent(Intent mediaButtonIntent) {
                    Object extra = mediaButtonIntent.getExtras() != null
                            ? mediaButtonIntent.getExtras().get(Intent.EXTRA_KEY_EVENT) : null;
                    if (extra instanceof KeyEvent) {
                        log("[MEDIA] " + describe((KeyEvent) extra));
                    } else {
                        log("[MEDIA] " + mediaButtonIntent);
                    }
                    return true;
                }
            });
            mSession.setPlaybackState(new PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                            | PlaybackState.ACTION_PLAY_PAUSE)
                    .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                    .build());
            mSession.setActive(true);
        } catch (RuntimeException e) {
            log("[MEDIA] unavailable: " + e);
        }
    }

    // --- broadcasts ---------------------------------------------------------

    private void registerActions() {
        unregisterActions();
        IntentFilter filter = new IntentFilter();
        for (String action : mActions) filter.addAction(action);
        mReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                log("[INT] " + intent.getAction()
                        + (isInitialStickyBroadcast() ? " (sticky)" : "")
                        + describeExtras(intent.getExtras()));
                // Show what Mumla did with it a moment later
                mHandler.postDelayed(() -> {
                    if (!mDestroyed) logMumlaState();
                }, 300);
            }
        };
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(mReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(mReceiver, filter);
            }
        } catch (RuntimeException e) {
            mReceiver = null;
            log("[INT] cannot register: " + e);
        }
    }

    private void unregisterActions() {
        if (mReceiver != null) {
            try {
                unregisterReceiver(mReceiver);
            } catch (IllegalArgumentException e) {
                // was not registered
            }
            mReceiver = null;
        }
    }

    private static String describeExtras(Bundle extras) {
        if (extras == null) return "";
        StringBuilder sb = new StringBuilder(" extras={");
        try {
            boolean first = true;
            for (String key : extras.keySet()) {
                if (!first) sb.append(", ");
                first = false;
                sb.append(key).append('=').append(extras.get(key));
            }
        } catch (RuntimeException e) {
            sb.append('?');
        }
        return sb.append('}').toString();
    }

    // --- scan of installed apps --------------------------------------------

    private void scanPackages() {
        final Set<String> found = new LinkedHashSet<>();
        final StringBuilder report = new StringBuilder();
        Pattern utf8 = Pattern.compile("[A-Za-z0-9_.]{6,120}");
        Pattern utf16 = Pattern.compile("(?:[A-Za-z0-9_.]\\x00){6,120}");
        int scanned = 0;
        try {
            List<PackageInfo> packages = getPackageManager().getInstalledPackages(0);
            for (PackageInfo info : packages) {
                if (mDestroyed) return;
                ApplicationInfo app = info.applicationInfo;
                if (app == null || app.sourceDir == null) continue;
                if (getPackageName().equals(info.packageName)) continue;
                byte[] manifest = readManifest(app.sourceDir);
                if (manifest == null) continue;
                scanned++;
                String raw = new String(manifest, StandardCharsets.ISO_8859_1);
                Set<String> tokens = new LinkedHashSet<>();
                Matcher m = utf8.matcher(raw);
                while (m.find()) tokens.add(m.group());
                m = utf16.matcher(raw);
                while (m.find()) tokens.add(m.group().replace("\u0000", ""));
                for (String token : tokens) {
                    if (token.indexOf('.') < 0) continue;
                    if (!token.toLowerCase(Locale.US).contains("ptt")) continue;
                    if (found.size() >= MAX_ACTIONS) break;
                    if (found.add(token)) {
                        report.append("[SCAN] ").append(info.packageName).append(": ")
                                .append(token).append('\n');
                    }
                }
            }
        } catch (RuntimeException e) {
            report.append("[SCAN] error: ").append(e).append('\n');
        }
        final int total = scanned;
        mHandler.post(() -> {
            if (mDestroyed) return;
            log("[SCAN] apps scanned: " + total + ", names with \"ptt\": " + found.size());
            if (report.length() > 0) log(report.toString().trim());
            mActions.addAll(found);
            registerActions();
            log(getString(R.string.pttDiagReady));
        });
    }

    private static byte[] readManifest(String apkPath) {
        try (ZipFile zip = new ZipFile(apkPath)) {
            ZipEntry entry = zip.getEntry("AndroidManifest.xml");
            if (entry == null) return null;
            try (InputStream in = zip.getInputStream(entry)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                    if (out.size() > 4 * 1024 * 1024) break;
                }
                return out.toByteArray();
            }
        } catch (Exception e) {
            return null;
        }
    }

    // --- log ----------------------------------------------------------------

    private void log(String line) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mHandler.post(() -> log(line));
            return;
        }
        mText.append(mTime.format(new Date())).append(' ').append(line).append('\n');
        if (mText.length() > MAX_LOG_CHARS) {
            mText.delete(0, mText.length() - MAX_LOG_CHARS);
        }
        if (mLogView != null) {
            mLogView.setText(mText);
            mScroll.post(() -> mScroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    private void copyLog() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("Mumla P60 PTT", mText.toString()));
            Toast.makeText(this, R.string.pttDiagCopied, Toast.LENGTH_SHORT).show();
        }
    }

    private void shareLog() {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, "Mumla P60 PTT");
        send.putExtra(Intent.EXTRA_TEXT, mText.toString());
        try {
            startActivity(Intent.createChooser(send, getString(R.string.pttDiagShare)));
        } catch (RuntimeException e) {
            Toast.makeText(this, String.valueOf(e), Toast.LENGTH_LONG).show();
        }
    }
}
