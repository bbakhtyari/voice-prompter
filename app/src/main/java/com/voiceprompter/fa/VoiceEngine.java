package com.voiceprompter.fa;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.voiceprompter.fa.core.Grammar;

import org.json.JSONArray;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.IOException;

/** Microphone -> Vosk -> text, on a background thread. Callbacks arrive on the main thread. */
public final class VoiceEngine {

    public interface Listener {
        void onReady(String info);

        /** Called for every audio chunk (about ten times a second) while not paused. */
        void onHypothesis(String text, boolean isFinal);

        void onLevel(float level);

        void onError(String message);
    }

    public static final int MIC_AUTO = 0;
    public static final int MIC_BUILTIN = 1;
    public static final int MIC_BLUETOOTH = 2;

    private static final int RATE = 16000;

    private final Context app;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    private volatile Object session;
    private Thread thread;
    private volatile boolean paused = true;
    private volatile boolean resetRequested;

    public VoiceEngine(Context c, Listener l) {
        app = c.getApplicationContext();
        listener = l;
    }

    public boolean isRunning() { return session != null; }

    public void setPaused(boolean p) { paused = p; }

    /** Forget the words of the sentence in progress (used after jumping back). */
    public void resetUtterance() { resetRequested = true; }

    public void start(final String script, final boolean restrict, final int mic) {
        if (session != null) return;
        final Object s = new Object();
        session = s;
        thread = new Thread(() -> loop(s, script, restrict, mic), "voice-engine");
        thread.start();
    }

    /** Stops listening and waits briefly for the microphone to be released. */
    public void stop() {
        session = null;
        Thread t = thread;
        thread = null;
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(800);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void post(final Object s, final Runnable r) {
        main.post(() -> {
            if (session == s) r.run();
        });
    }

    private void loop(final Object s, String script, boolean restrict, int mic) {
        AudioRecord ar = null;
        Recognizer rec = null;
        boolean bt = false;
        AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        try {
            Model model = ModelStore.get(app);
            String info = "";

            if (restrict) {
                Grammar.Result g = Grammar.build(script, ModelStore.wordsFile(app));
                if (!g.phrases.isEmpty()) {
                    JSONArray arr = new JSONArray();
                    for (String p : g.phrases) arr.put(p);
                    arr.put("[unk]");
                    // If the native library aborts here, the flag stays set and the
                    // next launch switches this option off instead of crashing again.
                    Prefs.sp(app).edit().putBoolean(Prefs.GRAMMAR_GUARD, true).commit();
                    try {
                        rec = new Recognizer(model, RATE, arr.toString());
                    } catch (Exception e) {
                        rec = null;
                        info = "محدودسازی لغات با این مدل کار نکرد.";
                    } finally {
                        Prefs.sp(app).edit().putBoolean(Prefs.GRAMMAR_GUARD, false).commit();
                    }
                    if (rec != null && !g.unknown.isEmpty()) {
                        StringBuilder sb = new StringBuilder();
                        int shown = 0;
                        for (String w : g.unknown) {
                            if (shown++ == 6) break;
                            if (sb.length() > 0) sb.append("، ");
                            sb.append(w);
                        }
                        info = g.unknown.size() + " کلمه برای مدل ناآشناست: " + sb;
                    }
                }
            }
            if (rec == null) rec = new Recognizer(model, RATE);
            if (session != s) return;

            int source = MediaRecorder.AudioSource.VOICE_RECOGNITION;
            AudioDeviceInfo preferred = null;
            if (mic == MIC_BLUETOOTH) {
                bt = routeBluetooth(am, true);
                if (bt) {
                    source = MediaRecorder.AudioSource.VOICE_COMMUNICATION;
                    preferred = findInput(am, true);
                } else {
                    info = "هدست بلوتوث پیدا نشد؛ میکروفون پیش‌فرض فعاله. " + info;
                }
            } else if (mic == MIC_BUILTIN) {
                preferred = findInput(am, false);
            }

            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            ar = new AudioRecord(source, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, RATE));
            if (ar.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IOException("میکروفون در دسترس نیست");
            }
            if (preferred != null) ar.setPreferredDevice(preferred);
            ar.startRecording();

            final String readyInfo = info.trim();
            post(s, () -> listener.onReady(readyInfo));

            short[] buf = new short[RATE / 10];
            boolean wasPaused = true;
            while (session == s) {
                int n = ar.read(buf, 0, buf.length);
                if (n < 0) throw new IOException("خطای خواندن صدا (" + n + ")");
                if (n == 0) continue;

                double sum = 0;
                for (int i = 0; i < n; i++) sum += (double) buf[i] * buf[i];
                final float level = (float) Math.min(1.0, Math.sqrt(sum / n) / 4000.0);
                post(s, () -> listener.onLevel(level));

                if (paused) {
                    wasPaused = true;
                    continue;
                }
                if (wasPaused || resetRequested) {
                    rec.reset();
                    wasPaused = false;
                    resetRequested = false;
                }
                final boolean fin = rec.acceptWaveForm(buf, n);
                String json = fin ? rec.getResult() : rec.getPartialResult();
                final String text = new JSONObject(json).optString(fin ? "text" : "partial", "");
                post(s, () -> listener.onHypothesis(text, fin));
            }
        } catch (final Throwable t) {
            final String msg = t.getMessage() == null ? t.toString() : t.getMessage();
            post(s, () -> listener.onError(msg));
        } finally {
            if (ar != null) {
                try {
                    ar.stop();
                } catch (RuntimeException ignored) {
                    // not recording
                }
                ar.release();
            }
            if (rec != null) rec.close();
            if (bt) routeBluetooth(am, false);
        }
    }

    private static AudioDeviceInfo findInput(AudioManager am, boolean bluetooth) {
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            int t = d.getType();
            if (bluetooth) {
                if (t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) return d;
                if (Build.VERSION.SDK_INT >= 31 && t == AudioDeviceInfo.TYPE_BLE_HEADSET) return d;
            } else if (t == AudioDeviceInfo.TYPE_BUILTIN_MIC) {
                return d;
            }
        }
        return null;
    }

    /** Opens (or closes) the Bluetooth headset microphone link. */
    @SuppressWarnings("deprecation")
    private static boolean routeBluetooth(AudioManager am, boolean on) {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                if (!on) {
                    am.clearCommunicationDevice();
                    return true;
                }
                for (AudioDeviceInfo d : am.getAvailableCommunicationDevices()) {
                    int t = d.getType();
                    if (t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || t == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                        return am.setCommunicationDevice(d);
                    }
                }
                return false;
            }
            if (on) {
                if (!am.isBluetoothScoAvailableOffCall()) return false;
                am.setMode(AudioManager.MODE_IN_COMMUNICATION);
                am.startBluetoothSco();
                am.setBluetoothScoOn(true);
            } else {
                am.setBluetoothScoOn(false);
                am.stopBluetoothSco();
                am.setMode(AudioManager.MODE_NORMAL);
            }
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
