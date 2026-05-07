#!/usr/bin/env bash
# Refresh the voices-v2 release on jphein/VoxSherpa-TTS by pulling the
# current set of fp32 Piper + Kokoro tarballs from k2-fsa/sherpa-onnx
# upstream, extracting the model + tokens + (for Kokoro) voices.bin,
# and uploading flat single-file assets so storyvox's VoiceManager can
# download them without on-device tarball extraction.
#
# Why this script exists: the int8-quantized models we used to ship in
# voices-v1 produced audible fuzz on Samsung tablets. fp32 weights from
# k2-fsa fix that. Decompressing tarballs on a Tab A7 Lite is slow, so
# we extract once on a beefy host and re-host as flat downloads.
#
# Usage:
#   ./scripts/voices/refresh-voices-v2.sh                # full refresh
#   ./scripts/voices/refresh-voices-v2.sh --check-only   # just diff vs upstream
#
# Requires: gh CLI authenticated against jphein with write access to
#           VoxSherpa-TTS, ~5GB free disk under /tmp/voices-extract,
#           bzip2 + tar (standard on Linux).
set -euo pipefail

CHECK_ONLY=0
[[ "${1:-}" == "--check-only" ]] && CHECK_ONLY=1

WORK="${VOICES_WORK_DIR:-/tmp/voices-extract}"
mkdir -p "$WORK"
cd "$WORK"

UPSTREAM_REPO="k2-fsa/sherpa-onnx"
UPSTREAM_TAG="tts-models"
TARGET_REPO="jphein/VoxSherpa-TTS"
TARGET_TAG="voices-v2"

# ── 1. List the fp32 piper + kokoro tarballs we want from upstream ────────
# Filter: english piper, no -int8/-fp16 suffix; kokoro multi-lang v1_1 (the
# 53-speaker version that matches our existing speaker IDs).
UPSTREAM_TARBALLS=$(gh release view "$UPSTREAM_TAG" --repo "$UPSTREAM_REPO" \
    --json assets --jq '.assets[].name' \
    | grep -E '^(vits-piper-(en_US|en_GB)-[a-z_]+-?[a-z]*\.tar\.bz2|kokoro-multi-lang-v1_1\.tar\.bz2)$' \
    | grep -v -- '-int8\.tar\.bz2' \
    | grep -v -- '-fp16\.tar\.bz2')

echo "Upstream tarballs ($(echo "$UPSTREAM_TARBALLS" | wc -l)):"
echo "$UPSTREAM_TARBALLS" | sed 's/^/  /'

# ── 2. List what voices-v2 currently has ──────────────────────────────────
EXISTING=$(gh release view "$TARGET_TAG" --repo "$TARGET_REPO" \
    --json assets --jq '.assets[].name' 2>/dev/null || echo "")
EXPECTED_FROM_UPSTREAM=$(echo "$UPSTREAM_TARBALLS" | sed -E '
    s|^vits-piper-(.*)\.tar\.bz2$|\1.onnx|
    s|^kokoro-multi-lang-v1_1\.tar\.bz2$|kokoro-model.onnx|
')

MISSING=$(comm -23 <(echo "$EXPECTED_FROM_UPSTREAM" | sort -u) <(echo "$EXISTING" | sort -u))

if [[ -z "$MISSING" ]]; then
    echo "voices-v2 is in sync with upstream."
    [[ $CHECK_ONLY -eq 1 ]] && exit 0
fi

if [[ -n "$MISSING" ]]; then
    echo
    echo "Missing in voices-v2:"
    echo "$MISSING" | sed 's/^/  /'
fi

[[ $CHECK_ONLY -eq 1 ]] && exit 0

# ── 3. Download all needed tarballs ───────────────────────────────────────
echo
echo "Downloading $(echo "$UPSTREAM_TARBALLS" | wc -l) tarballs (parallel x8)…"
echo "$UPSTREAM_TARBALLS" | xargs -P 8 -I {} \
    curl -sL -o "{}" "https://github.com/${UPSTREAM_REPO}/releases/download/${UPSTREAM_TAG}/{}"

# ── 4. Extract + flatten ──────────────────────────────────────────────────
echo "Extracting + flattening…"
mkdir -p out
rm -f out/*.onnx out/*.tokens.txt out/*.bin
for t in vits-piper-*.tar.bz2; do
    base="${t%.tar.bz2}"
    voice="${base#vits-piper-}"
    [[ -d "$base" ]] || tar -xjf "$t"
    onnx_in=$(find "$base" -maxdepth 1 -name '*.onnx' | head -1)
    if [[ -n "$onnx_in" && -f "$base/tokens.txt" ]]; then
        cp "$onnx_in" "out/${voice}.onnx"
        cp "$base/tokens.txt" "out/${voice}.tokens.txt"
    fi
done
if [[ -f kokoro-multi-lang-v1_1.tar.bz2 ]]; then
    [[ -d kokoro-multi-lang-v1_1 ]] || tar -xjf kokoro-multi-lang-v1_1.tar.bz2
    cp kokoro-multi-lang-v1_1/model.onnx     out/kokoro-model.onnx
    cp kokoro-multi-lang-v1_1/voices.bin     out/kokoro-voices.bin
    cp kokoro-multi-lang-v1_1/tokens.txt     out/kokoro-tokens.txt
fi

echo "Flattened files: $(ls out | wc -l)  ($(du -sh out | awk '{print $1}'))"

# ── 5. Ensure the release exists, then upload (clobber on duplicates) ─────
gh release view "$TARGET_TAG" --repo "$TARGET_REPO" >/dev/null 2>&1 || \
    gh release create "$TARGET_TAG" --repo "$TARGET_REPO" \
        --title "voices-v2 — fp32 piper + kokoro models (no fuzz)" \
        --notes "Full-precision Piper + Kokoro v1_1 weights re-hosted from k2-fsa/sherpa-onnx upstream tarballs as flat single-file downloads. Replaces voices-v1 INT8 weights that produced quantization fuzz on Samsung tablets." \
        --target main

cd out
echo "Uploading $(ls | wc -l) files…"
gh release upload "$TARGET_TAG" --repo "$TARGET_REPO" --clobber *.onnx *.tokens.txt *.bin

echo
echo "Done. voices-v2 now has $(gh release view "$TARGET_TAG" --repo "$TARGET_REPO" --json assets --jq '.assets | length') assets."
