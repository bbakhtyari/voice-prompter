#!/usr/bin/env python3
"""Fetch the Shenava Persian ASR models and shrink them for use on a tablet.

Run by the GitHub workflow. For each model it downloads the sherpa-onnx export
from Hugging Face, quantises the MatMul weights to 8 bit (about a quarter of the
size, faster on ARM), checks that the model's metadata survived, and puts
model.int8.onnx + tokens.txt into the app's assets.
"""
import os
import shutil
import sys

import onnx
from huggingface_hub import snapshot_download
from onnxruntime.quantization import QuantType, quantize_dynamic

MODELS = {
    "shenava-rizeh": "Reza2kn/Shenava-Rizeh-v1.0-sherpa-onnx",
    "shenava-koochik": "Reza2kn/Shenava-Koochik-v1.0-sherpa-onnx",
}
ASSETS = "app/src/main/assets"
WORK = "build-shenava"


def meta(path):
    m = onnx.load(path, load_external_data=False)
    return {p.key: p.value for p in m.metadata_props}


def main():
    os.makedirs(WORK, exist_ok=True)
    for name, repo in MODELS.items():
        src = snapshot_download(repo_id=repo, local_dir=os.path.join(WORK, name))
        files = sorted(os.listdir(src))
        print(f"== {name}: {repo}\n   files: {files}", flush=True)
        model = os.path.join(src, "model.onnx")
        if not os.path.isfile(model):
            cands = [f for f in files if f.endswith(".onnx")]
            if len(cands) != 1:
                sys.exit(f"cannot pick the model file among {cands}")
            model = os.path.join(src, cands[0])
        tokens = os.path.join(src, "tokens.txt")
        if not os.path.isfile(tokens):
            sys.exit("tokens.txt is missing")

        out = os.path.join(ASSETS, name)
        os.makedirs(out, exist_ok=True)
        dst = os.path.join(out, "model.int8.onnx")
        quantize_dynamic(model, dst, weight_type=QuantType.QUInt8, op_types_to_quantize=["MatMul"])

        before, after = meta(model), meta(dst)
        print(f"   metadata: {before}", flush=True)
        if before != after:
            print("   metadata changed during quantisation; restoring it", flush=True)
            m = onnx.load(dst)
            del m.metadata_props[:]
            for k, v in before.items():
                p = m.metadata_props.add()
                p.key, p.value = k, v
            onnx.save(m, dst)
        shutil.copyfile(tokens, os.path.join(out, "tokens.txt"))
        mb = lambda p: f"{os.path.getsize(p) / 1e6:.0f} MB"
        print(f"   {mb(model)} -> {mb(dst)}", flush=True)


if __name__ == "__main__":
    main()
