package com.voiceprompter.fa;

import android.content.Context;

import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;

/** Runs a Shenava (NeMo FastConformer CTC) model through sherpa-onnx. */
final class SherpaDecoder {
    static final boolean AVAILABLE = true;

    private final OfflineRecognizer rec;

    /** @param assetDir folder inside the APK assets holding model.int8.onnx and tokens.txt */
    SherpaDecoder(Context c, String assetDir, int threads) {
        OfflineRecognizerConfig cfg = new OfflineRecognizerConfig();
        OfflineModelConfig mc = cfg.getModelConfig();
        OfflineNemoEncDecCtcModelConfig nemo = new OfflineNemoEncDecCtcModelConfig();
        nemo.setModel(assetDir + "/model.int8.onnx");
        mc.setNemo(nemo);
        mc.setTokens(assetDir + "/tokens.txt");
        mc.setNumThreads(threads);
        mc.setDebug(false);
        rec = new OfflineRecognizer(c.getAssets(), cfg);
    }

    /** Recognises a piece of 16 kHz mono audio (samples in -1..1). */
    String decode(float[] samples) {
        OfflineStream st = rec.createStream();
        try {
            st.acceptWaveform(samples, 16000);
            rec.decode(st);
            String t = rec.getResult(st).getText();
            return t == null ? "" : t.trim();
        } finally {
            st.release();
        }
    }

    void close() {
        rec.release();
    }
}
