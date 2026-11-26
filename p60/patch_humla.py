#!/usr/bin/env python3
"""Build-time changes to the humla network library for the Hytera P60 build.

Every replacement must match exactly once; otherwise the build stops, so a
changed library version can never be patched silently in the wrong place.
"""
import sys

root = sys.argv[1] + "/src/main/java/se/lublin/humla/"


def patch(rel, old, new):
    path = root + rel
    with open(path, encoding="utf-8") as f:
        text = f.read()
    count = text.count(old)
    if count != 1:
        sys.exit("PATCH FAILED: %s: expected 1 match, found %d for:\n%s" % (rel, count, old))
    with open(path, "w", encoding="utf-8") as f:
        f.write(text.replace(old, new))
    print("patched", rel)


# 1. Configurable ping interval (battery saver).
patch("net/HumlaConnection.java",
      "scheduleAtFixedRate(mPingRunnable, 0, 5, TimeUnit.SECONDS)",
      'scheduleAtFixedRate(mPingRunnable, 0, Integer.getInteger("mumla.p60.pingInterval", 5), TimeUnit.SECONDS)')

# 2. Ping on demand, for when the CPU sleeps and an alarm wakes it.
patch("net/HumlaConnection.java",
      "    private Runnable mPingRunnable = new Runnable() {",
      """    /** P60: send a ping right now. */
    public void p60PingNow() {
        try {
            ScheduledExecutorService executor = mPingExecutorService;
            if (executor != null && !executor.isShutdown()) {
                executor.execute(mPingRunnable);
            }
        } catch (RuntimeException e) {
            // shutting down
        }
    }

    private Runnable mPingRunnable = new Runnable() {""")

patch("HumlaService.java",
      "    private HumlaConnection mConnection;",
      """    private HumlaConnection mConnection;

    /** P60: send a ping right now. */
    protected void p60PingNow() {
        HumlaConnection connection = mConnection;
        if (connection != null) connection.p60PingNow();
    }""")

# 3. Optional: do not keep the CPU awake for the whole connection.
patch("HumlaService.java",
      "        mWakeLock.acquire();",
      '        if (!Boolean.getBoolean("mumla.p60.deepSleep")) mWakeLock.acquire();')

# 4. Optional: release the microphone while waiting for push-to-talk.
patch("audio/AudioInput.java",
      """    @Override
    public void run() {
        android.os.Process.setThreadPriority(""",
      """    /** P60: release the microphone. Call only from the record thread. */
    public void p60PauseCapture() {
        try {
            mAudioRecord.stop();
        } catch (IllegalStateException e) {
            Log.w(TAG, "P60: cannot pause capture: " + e);
        }
    }

    /** P60: open the microphone again. Call only from the record thread. */
    public void p60ResumeCapture() {
        try {
            mAudioRecord.startRecording();
        } catch (IllegalStateException e) {
            Log.w(TAG, "P60: cannot resume capture: " + e);
        }
    }

    @Override
    public void run() {
        android.os.Process.setThreadPriority(""")

patch("protocol/AudioHandler.java",
      """        if (!talking) {
            mInputMode.waitForInput();
        }""",
      """        if (!talking) {
            boolean p60MicOff = Boolean.getBoolean("mumla.p60.micOff")
                    && mInputMode instanceof se.lublin.humla.audio.inputmode.ToggleInputMode;
            if (p60MicOff) mInput.p60PauseCapture();
            mInputMode.waitForInput();
            if (p60MicOff) mInput.p60ResumeCapture();
        }""")
