#!/bin/bash
# Restore the development route: Android USB -> this Mac -> ssh tx -> API.
set -euo pipefail
adb_path="${ANDROID_HOME:-/Users/lc/Library/Android/sdk}/platform-tools/adb"
if [[ ! -x "$adb_path" ]]; then
  echo 'Android SDK 的 adb 不存在，请设置 ANDROID_HOME。' >&2
  exit 1
fi
if [[ "$("$adb_path" get-state 2>/dev/null)" != device ]]; then
  echo '手机未通过 USB 连接或尚未授权调试。' >&2
  exit 1
fi
if ! curl -fsS --max-time 3 http://127.0.0.1:18900/health >/dev/null 2>&1; then
  ssh -f -N -o BatchMode=yes -o ConnectTimeout=10 -o ExitOnForwardFailure=yes \
    -o ServerAliveInterval=30 -o ServerAliveCountMax=3 \
    -L 127.0.0.1:18900:127.0.0.1:8900 tx
fi
curl -fsS --max-time 5 http://127.0.0.1:18900/health >/dev/null
"$adb_path" reverse tcp:18900 tcp:18900 >/dev/null
echo 'tx USB 调试连接已恢复；拔掉 USB 后需重新执行此脚本。'
