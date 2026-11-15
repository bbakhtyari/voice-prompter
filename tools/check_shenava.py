#!/usr/bin/env python3
"""Sanity check, run by the GitHub workflow: does the shrunken model still hear Persian?

Persian speech is made with a text-to-speech voice, then recognised with the
original model and with the 8-bit one. The log shows both, with timings.
"""
import glob
import os
import sys
import time

import numpy as np
import sherpa_onnx

TEXT = "سلام رفیق. امروز می‌خوام یه حرف مهم با تو بزنم. می‌دونم که خسته‌ای ولی تو می‌تونی ادامه بدی."
TTS = "vits-piper-fa_IR-amir-medium"


def speak():
    onnx_files = glob.glob(f"{TTS}/*.onnx")
    cfg = sherpa_onnx.OfflineTtsConfig(
        model=sherpa_onnx.OfflineTtsModelConfig(
            vits=sherpa_onnx.OfflineTtsVitsModelConfig(
                model=onnx_files[0], tokens=f"{TTS}/tokens.txt", data_dir=f"{TTS}/espeak-ng-data"
            ),
            num_threads=2,
        )
    )
    audio = sherpa_onnx.OfflineTts(cfg).generate(TEXT, sid=0, speed=1.0)
    return np.asarray(audio.samples, dtype=np.float32), audio.sample_rate


def hear(model, tokens, samples, rate):
    rec = sherpa_onnx.OfflineRecognizer.from_nemo_ctc(model=model, tokens=tokens, num_threads=4)
    t0 = time.time()
    s = rec.create_stream()
    s.accept_waveform(rate, samples)
    rec.decode_stream(s)
    return s.result.text, time.time() - t0


def main():
    samples, rate = speak()
    secs = len(samples) / rate
    print(f"spoken text : {TEXT}\naudio       : {secs:.1f} s at {rate} Hz", flush=True)
    for name in ("shenava-rizeh", "shenava-koochik"):
        tokens = f"app/src/main/assets/{name}/tokens.txt"
        for label, model in (
            ("original", f"build-shenava/{name}/model.onnx"),
            ("8-bit   ", f"app/src/main/assets/{name}/model.int8.onnx"),
        ):
            if not os.path.isfile(model):
                print(f"{name} {label}: file missing")
                continue
            text, dt = hear(model, tokens, samples, rate)
            print(f"{name} {label}: {dt:.2f} s (x{dt / secs:.2f} real time)  ->  {text}", flush=True)


if __name__ == "__main__":
    sys.exit(main())
