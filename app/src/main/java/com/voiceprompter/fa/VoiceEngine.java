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
import android.os.SystemClock;

import com.voiceprompter.fa.core.Grammar;

import org.json.JSONArray;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * Microphone -> speech engine -> text, on background threads. Callbacks arrive
 * on the main thread.
 *
 * Vosk recognises as the audio comes in. Shenava models only take a finished
 * piece of audio, so for them the last few seconds are recognised again and
 * again while the speaker talks, and the whole phrase once more after a pause.
 */
public final class VoiceEngine {

    public interface Listener {
        void onReady(String info);

        /** The words heard so far in the current phrase; isFinal after a pause. */
        void onHypothesis(String text, boolean isFinal);

        void onLevel(float level);

        /** How long the last recognition pass took (Shenava only). */
        void onDecodeTime(int millis);

        void onError(String message);
    }

    public static final int MIC_AUTO = 0;
    public static final int MIC_BUILTIN = 1;
    public static final int MIC_BLUETOOTH = 2;

    private static final int RATE = 16000;
    private static final int CHUNK = RATE / 10;

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

    public void start(final String script, final boolean restrict, final int mic, final int engine) {
        if (session != null) return;
        final Object s = new Object();
        session = s;
        thread = new Thread(() -> {
            if (engine == Engines.VOSK) loopVosk(s, script, restrict, mic);
            else loopShenava(s, mic, engine);
        }, "voice-engine");
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

    private void fail(Object s, Throwable t) {
        final String msg = t.getMessage() == null ? t.toString() : t.getMessage();
        post(s, () -> listener.onError(msg));
    }

    private static float level(short[] buf, int n) {
        double sum = 0;
        for (int i = 0; i < n; i++) sum += (double) buf[i] * buf[i];
        return (float) Math.sqrt(sum / n);
    }

    // ------------------------------------------------------------------ Vosk

    private void loopVosk(final Object s, String script, boolean restrict, int mic) {
        Mic m = null;
        Recognizer rec = null;
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

            m = openMic(am, mic, RATE);
            m.ar.startRecording();
            final String readyInfo = (m.info + " " + info).trim();
            post(s, () -> listener.onReady(readyInfo));

            short[] buf = new short[CHUNK];
            boolean wasPaused = true;
            while (session == s) {
                int n = m.ar.read(buf, 0, buf.length);
                if (n < 0) throw new IOException("خطای خواندن صدا (" + n + ")");
                if (n == 0) continue;

                final float lv = Math.min(1f, level(buf, n) / 4000f);
                post(s, () -> listener.onLevel(lv));

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
            fail(s, t);
        } finally {
            closeMic(am, m);
            if (rec != null) rec.close();
        }
    }

    // ------------------------------------------------------------------ Shenava

    /** Longest phrase recognised in one piece, and the stretch re-recognised while speaking. */
    private static final int MAX_PHRASE = RATE * 10;
    private static final int LIVE_WINDOW = RATE * 5;
    private static final int PRE_ROLL = RATE * 3 / 10;
    /** Chunks of quiet (0.1 s each) that end a phrase. */
    private static final int PAUSE_CHUNKS = 6;

    private static final class Job {
        final float[] samples;
        final boolean isFinal;

        Job(float[] samples, boolean isFinal) {
            this.samples = samples;
            this.isFinal = isFinal;
        }
    }

    /** The phrase being spoken, shared between the microphone and the recogniser threads. */
    private static final class Phrase {
        private final float[] buf = new float[MAX_PHRASE + CHUNK];
        private int len;
        private int decodedLen;
        private boolean speech;
        private boolean closed;
        private final ArrayDeque<float[]> finished = new ArrayDeque<>();

        synchronized void reset() {
            len = 0;
            decodedLen = 0;
            speech = false;
            finished.clear();
        }

        synchronized void append(short[] src, int n, boolean isSpeech) {
            if (!speech && !isSpeech && len + n > PRE_ROLL) {
                // nothing said yet: keep only a short lead-in
                int drop = Math.min(len, len + n - PRE_ROLL);
                System.arraycopy(buf, drop, buf, 0, len - drop);
                len -= drop;
            }
            if (isSpeech) speech = true;
            int c = Math.min(n, buf.length - len);
            for (int i = 0; i < c; i++) buf[len + i] = src[i] / 32768f;
            len += c;
            notifyAll();
        }

        synchronized boolean hasSpeech() { return speech; }

        synchronized int length() { return len; }

        /** The phrase is over: hand it to the recogniser as a whole and start a new one. */
        synchronized void finish() {
            if (speech && len > 0) finished.add(Arrays.copyOf(buf, len));
            len = 0;
            decodedLen = 0;
            speech = false;
            notifyAll();
        }

        synchronized void close() {
            closed = true;
            notifyAll();
        }

        /** Waits for the next thing to recognise; null when closed. */
        synchronized Job next() throws InterruptedException {
            while (true) {
                if (closed) return null;
                if (!finished.isEmpty()) return new Job(finished.poll(), true);
                if (speech && len - decodedLen >= PRE_ROLL) {
                    decodedLen = len;
                    int win = Math.min(len, LIVE_WINDOW);
                    return new Job(Arrays.copyOfRange(buf, len - win, len), false);
                }
                wait(200);
            }
        }
    }

    private void loopShenava(final Object s, int mic, int engine) {
        Mic m = null;
        SherpaDecoder dec = null;
        Thread worker = null;
        final Phrase phrase = new Phrase();
        AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        try {
            // If loading the model kills the app, the flag stays set and the next
            // launch goes back to Vosk instead of crashing again.
            Prefs.sp(app).edit().putBoolean(Prefs.ENGINE_GUARD, true).commit();
            dec = new SherpaDecoder(app, Engines.assetDir(engine), 4);
            if (session != s) return;

            m = openMic(am, mic, RATE * 4);
            m.ar.startRecording();
            final String readyInfo = m.info.trim();
            post(s, () -> listener.onReady(readyInfo));

            final SherpaDecoder d = dec;
            worker = new Thread(() -> decodeLoop(s, d, phrase), "shenava-decode");
            worker.start();

            short[] buf = new short[CHUNK];
            boolean wasPaused = true;
            double noise = 300;
            int quiet = 0;
            while (session == s) {
                int n = m.ar.read(buf, 0, buf.length);
                if (n < 0) throw new IOException("خطای خواندن صدا (" + n + ")");
                if (n == 0) continue;

                float rms = level(buf, n);
                final float lv = Math.min(1f, rms / 4000f);
                post(s, () -> listener.onLevel(lv));

                if (paused) {
                    wasPaused = true;
                    continue;
                }
                if (wasPaused || resetRequested) {
                    phrase.reset();
                    quiet = 0;
                    wasPaused = false;
                    resetRequested = false;
                }

                // simple voice detection: clearly louder than the room's background
                if (rms < noise) noise = rms;
                else noise = Math.min(noise * 1.01 + 0.5, 4000);
                boolean speech = rms > Math.max(250, noise * 3);
                quiet = speech ? 0 : quiet + 1;

                phrase.append(buf, n, speech);
                if (phrase.hasSpeech() && (quiet >= PAUSE_CHUNKS || phrase.length() >= MAX_PHRASE)) {
                    phrase.finish();
                }
            }
        } catch (final Throwable t) {
            fail(s, t);
        } finally {
            phrase.close();
            boolean workerDone = true;
            if (worker != null) {
                try {
                    worker.join(4000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                workerDone = !worker.isAlive();
            }
            closeMic(am, m);
            if (dec != null && workerDone) dec.close();
            Prefs.sp(app).edit().putBoolean(Prefs.ENGINE_GUARD, false).commit();
        }
    }

    private void decodeLoop(final Object s, SherpaDecoder d, Phrase phrase) {
        try {
            while (session == s) {
                Job j = phrase.next();
                if (j == null) break;
                long t0 = SystemClock.elapsedRealtime();
                final String text = d.decode(j.samples);
                final int ms = (int) (SystemClock.elapsedRealtime() - t0);
                final boolean fin = j.isFinal;
                post(s, () -> {
                    listener.onDecodeTime(ms);
                    listener.onHypothesis(text, fin);
                });
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (final Throwable t) {
            fail(s, t);
        }
    }

    // ------------------------------------------------------------------ microphone

    private static final class Mic {
        AudioRecord ar;
        boolean bluetooth;
        String info = "";
    }

    private Mic openMic(AudioManager am, int mic, int bufferBytes) throws IOException {
        Mic m = new Mic();
        int source = MediaRecorder.AudioSource.VOICE_RECOGNITION;
        AudioDeviceInfo preferred = null;
        if (mic == MIC_BLUETOOTH) {
            m.bluetooth = routeBluetooth(am, true);
            if (m.bluetooth) {
                source = MediaRecorder.AudioSource.VOICE_COMMUNICATION;
                preferred = findInput(am, true);
            } else {
                m.info = "هدست بلوتوث پیدا نشد؛ میکروفون پیش‌فرض فعاله.";
            }
        } else if (mic == MIC_BUILTIN) {
            preferred = findInput(am, false);
        }
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord ar = new AudioRecord(source, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, Math.max(min, bufferBytes));
        if (ar.getState() != AudioRecord.STATE_INITIALIZED) {
            ar.release();
            if (m.bluetooth) routeBluetooth(am, false);
            throw new IOException("میکروفون در دسترس نیست");
        }
        if (preferred != null) ar.setPreferredDevice(preferred);
        m.ar = ar;
        return m;
    }

    private void closeMic(AudioManager am, Mic m) {
        if (m == null) return;
        if (m.ar != null) {
            try {
                m.ar.stop();
            } catch (RuntimeException ignored) {
                // not recording
            }
            m.ar.release();
        }
        if (m.bluetooth) routeBluetooth(am, false);
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
