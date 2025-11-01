#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUNTIME_SRC_DIR="$ROOT_DIR/src/main/resources/pglite"
OUT_ZIP="$RUNTIME_SRC_DIR/runtime.zip"
WORK_DIR="$ROOT_DIR/.runtime-build"
NODE_VERSION="${NODE_VERSION:-24.11.0}"
NODE_DIST="node-v${NODE_VERSION}-win-x64.zip"
NODE_URL="${NODE_URL:-https://nodejs.org/dist/v${NODE_VERSION}/${NODE_DIST}}"
NODE_SHA256="${NODE_SHA256:-}"

if ! command -v curl >/dev/null 2>&1; then
  echo "curl is required" >&2
  exit 1
fi
if ! command -v unzip >/dev/null 2>&1; then
  echo "unzip is required" >&2
  exit 1
fi
if ! command -v zip >/dev/null 2>&1; then
  echo "zip is required" >&2
  exit 1
fi

rm -rf "$WORK_DIR"
mkdir -p "$WORK_DIR"

NODE_ARCHIVE="$WORK_DIR/${NODE_DIST}"

if [[ ! -f "$NODE_ARCHIVE" ]]; then
  echo "Downloading Node runtime from $NODE_URL" >&2
  curl -fsSL "$NODE_URL" -o "$NODE_ARCHIVE"
fi

if [[ -n "$NODE_SHA256" ]]; then
  echo "$NODE_SHA256  $NODE_ARCHIVE" | sha256sum --check --status || { echo "Node archive checksum mismatch" >&2; exit 1; }
else
  echo "WARNING: NODE_SHA256 not set, skipping checksum verification" >&2
fi

mkdir -p "$WORK_DIR/runtime"
unzip -q "$NODE_ARCHIVE" -d "$WORK_DIR/runtime"
mv "$WORK_DIR/runtime"/node-v${NODE_VERSION}-win-x64 "$WORK_DIR/runtime/node-win-x64"

cp "$RUNTIME_SRC_DIR/package.json" "$WORK_DIR/runtime/package.json"
cp "$RUNTIME_SRC_DIR/package-lock.json" "$WORK_DIR/runtime/package-lock.json"
cp "$RUNTIME_SRC_DIR/start.mjs" "$WORK_DIR/runtime/start.mjs"

pushd "$WORK_DIR/runtime" >/dev/null
if ! command -v npm >/dev/null 2>&1; then
  echo "npm is required to build node_modules" >&2
  exit 1
fi
npm ci --omit=dev --ignore-scripts
popd >/dev/null

rm -rf "$WORK_DIR/runtime/node_modules/.cache"

pushd "$WORK_DIR/runtime" >/dev/null
find node_modules -type f -exec touch -t 202501010000 {} +
find node_modules -type d -exec touch -t 202501010000 {} +
find node-win-x64 -type f -exec touch -t 202501010000 {} +
find node-win-x64 -type d -exec touch -t 202501010000 {} +
zip -X -9 -r "$OUT_ZIP" node-win-x64 node_modules > /dev/null
popd >/dev/null

rm -rf "$WORK_DIR"

echo "Runtime archive rebuilt at $OUT_ZIP" >&2
