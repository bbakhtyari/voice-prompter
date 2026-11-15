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

API = "https://datasets-server.huggingface.co"
# Persian speech sets with reference text; the first one that can be read is used.
DATASETS = ("Reza2kn/visualears-golden-6669", "google/fleurs", "Reza2kn/visualears-persian-asr-16k")
TEXT_KEYS = ("transcription", "text", "sentence", "normalized_text", "raw_transcription", "transcript")
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


def first_rows(dataset):
    splits = requests.get(f"{API}/splits", params={"dataset": dataset}, timeout=60).json()
    if "splits" not in splits:
        raise RuntimeError(f"no splits: {str(splits)[:200]}")
    cands = splits["splits"]
    cands.sort(key=lambda x: (("fa" not in x["config"].lower()) and x["config"] != "default", x["split"] != "test"))
    pick = cands[0]
    rows = requests.get(f"{API}/first-rows", timeout=60, params={
        "dataset": dataset, "config": pick["config"], "split": pick["split"]}).json()
    if "rows" not in rows:
        raise RuntimeError(f"no rows: {str(rows)[:200]}")
    return rows["rows"]


def samples():
    last = None
    for dataset in DATASETS:
        try:
            out = []
            for r in first_rows(dataset)[:N]:
                row = r["row"]
                audio = next(v for v in row.values()
                             if isinstance(v, list) and v and isinstance(v[0], dict) and "src" in v[0])
                ref = next((row[k] for k in TEXT_KEYS if isinstance(row.get(k), str) and row[k]), "")
                data = requests.get(audio[0]["src"], timeout=120).content
                wav, rate = sf.read(io.BytesIO(data), dtype="float32", always_2d=True)
                out.append((wav[:, 0].copy(), rate, ref))
            if out:
                print(f"test recordings from {dataset}", flush=True)
                return out
        except Exception as e:
            last = e
            print(f"   {dataset}: {e!r}", flush=True)
    raise RuntimeError(f"no test set could be read ({last!r})")


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
