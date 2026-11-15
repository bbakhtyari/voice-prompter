package com.voiceprompter.fa;

import android.content.Context;

/** Stand-in used when the app is built without the sherpa-onnx library. */
final class SherpaDecoder {
    static final boolean AVAILABLE = false;

    SherpaDecoder(Context c, String assetDir, int threads) {
        throw new UnsupportedOperationException("این نسخه موتور Shenava رو نداره");
    }

    String decode(float[] samples) {
        return "";
    }

    void close() {
    }
}
