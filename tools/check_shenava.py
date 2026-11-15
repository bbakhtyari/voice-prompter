#!/usr/bin/env python3
"""Sanity check, run by the GitHub workflow: do the shrunken models still hear Persian?

A few real Persian recordings (FLEURS test set, with reference text) are
recognised with each original model and with its 8-bit version. The log shows
the texts, the word error rate and the speed, so a bad model is noticed before
the APK is handed out.
"""
import io
import os
import re
import sys
import time

import numpy as np
import requests
import sherpa_onnx
import soundfile as sf

ROWS = ("https://datasets-server.huggingface.co/first-rows"
        "?dataset=google%2Ffleurs&config=fa_ir&split=test")
N = 6


def norm(t):
    t = t.replace("ي", "ی").replace("ك", "ک").replace("‌", " ")
    t = re.sub(r"[ً-ٰٟ]", "", t)
    t = re.sub(r"[^\w\s]", " ", t)
    return t.split()


def wer_counts(ref, hyp):
    d = list(range(len(hyp) + 1))
    for i in range(1, len(ref) + 1):
        prev, d[0] = d[0], i
        for j in range(1, len(hyp) + 1):
            cur = d[j]
            d[j] = min(d[j] + 1, d[j - 1] + 1, prev + (ref[i - 1] != hyp[j - 1]))
            prev = cur
    return d[len(hyp)], len(ref)


def samples():
    rows = requests.get(ROWS, timeout=60).json()["rows"][:N]
    out = []
    for r in rows:
        row = r["row"]
        data = requests.get(row["audio"][0]["src"], timeout=120).content
        wav, rate = sf.read(io.BytesIO(data), dtype="float32", always_2d=True)
        out.append((wav[:, 0].copy(), rate, row.get("transcription") or row.get("raw_transcription") or ""))
    return out


def main():
    try:
        data = samples()
    except Exception as e:  # the check is a bonus; never block the build on it
        print(f"could not fetch test recordings: {e!r}")
        return 0
    total = sum(len(w) / r for w, r, _ in data)
    print(f"{len(data)} recordings, {total:.0f} s of speech", flush=True)
    for name in ("shenava-rizeh", "shenava-koochik"):
        tokens = f"app/src/main/assets/{name}/tokens.txt"
        for label, model in (
            ("original", f"build-shenava/{name}/model.onnx"),
            ("8-bit", f"app/src/main/assets/{name}/model.int8.onnx"),
        ):
            if not os.path.isfile(model):
                print(f"{name} {label}: file missing")
                continue
            rec = sherpa_onnx.OfflineRecognizer.from_nemo_ctc(model=model, tokens=tokens, num_threads=4)
            errs = words = 0
            t0 = time.time()
            texts = []
            for wav, rate, ref in data:
                s = rec.create_stream()
                s.accept_waveform(rate, wav)
                rec.decode_stream(s)
                texts.append((ref, s.result.text))
                e, n = wer_counts(norm(ref), norm(s.result.text))
                errs += e
                words += n
            dt = time.time() - t0
            print(f"\n### {name} / {label}: WER {100 * errs / max(1, words):.1f}%  "
                  f"speed x{dt / total:.3f} real time", flush=True)
            for ref, hyp in texts[:3]:
                print(f"   ref: {ref}\n   got: {hyp}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
