#!/usr/bin/env bash
# Build script for WhoopBleBridge — compiles the C# BLE bridge and copies
# the output to the deployment locations searched by findBridgePath().
#
# Prerequisites:
#   - .NET 8.0 SDK (net8.0-windows10.0.22621.0 target)
#   - Windows 10/11 with Bluetooth support
#
# Usage:
#   ./build.sh                # Debug build
#   ./build.sh Release        # Release build
#   ./build.sh Release deploy # Release build + deploy to %LOCALAPPDATA%\NOOP\ble\
set -euo pipefail

CONFIG="${1:-Debug}"
DEPLOY_SYSTEM="${2:-}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BRIDGE_DIR="$SCRIPT_DIR"

echo "Building WhoopBleBridge ($CONFIG)..."
cd "$BRIDGE_DIR"
dotnet build -c "$CONFIG"

# Build output directory
OUT_DIR="$SCRIPT_DIR/bin/$CONFIG/net8.0-windows10.0.22621.0"

# Deploy locations (must match findBridgePath() search order in RealWindowsBleClient.kt):
#   1. ./ble/  (next to the app JAR / working directory)
#   2. %LOCALAPPDATA%\NOOP\ble\  (user-local, preferred for installed apps)
#   3. %APPDATA%\NOOP\ble\  (roaming, fallback)

LOCAL_DEPLOY="$SCRIPT_DIR/../ble"
SYSTEM_DEPLOY=""

if [[ -n "$DEPLOY_SYSTEM" ]] || [[ "$CONFIG" == "Release" ]]; then
    if [[ -n "${LOCALAPPDATA:-}" ]]; then
        SYSTEM_DEPLOY="$LOCALAPPDATA/NOOP/ble"
    elif [[ -n "${USERPROFILE:-}" ]]; then
        SYSTEM_DEPLOY="$USERPROFILE/AppData/Local/NOOP/ble"
    fi
fi

deploy_to() {
    local dir="$1"
    if [ -d "$OUT_DIR" ]; then
        mkdir -p "$dir"
        cp "$OUT_DIR/WhoopBleBridge.dll" "$dir/"
        cp "$OUT_DIR/WhoopBleBridge.exe" "$dir/" 2>/dev/null || true
        cp "$OUT_DIR/WhoopBleBridge.runtimeconfig.json" "$dir/" 2>/dev/null || true
        cp "$OUT_DIR/WhoopBleBridge.deps.json" "$dir/" 2>/dev/null || true
        echo "  Deployed to: $dir"
    else
        echo "  ERROR: Build output not found at $OUT_DIR"
        exit 1
    fi
}

if [ ! -d "$OUT_DIR" ]; then
    echo "ERROR: Build output not found at $OUT_DIR"
    exit 1
fi

echo "Deploying..."
deploy_to "$LOCAL_DEPLOY"

if [ -n "$SYSTEM_DEPLOY" ]; then
    deploy_to "$SYSTEM_DEPLOY"
fi

echo "Done. findBridgePath() will search (in order):"
echo "  1. ./ble/WhoopBleBridge.dll"
echo "  2. %LOCALAPPDATA%\\NOOP\\ble\\WhoopBleBridge.dll"
echo "  3. %APPDATA%\\NOOP\\ble\\WhoopBleBridge.dll"
