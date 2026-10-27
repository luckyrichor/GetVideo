#!/usr/bin/env bash
# 把项目固定的 Android 发布密钥安装到本机 ~/.android-keys/。
#
# 用法：
#   scripts/setup-release-key.sh                 # 从服务器备份 tx:~/.app-keys/ 拉取
#   scripts/setup-release-key.sh <本地目录>       # 从本地备份目录拷贝
#   REMOTE=myhost scripts/setup-release-key.sh   # 换一台备份主机
#
# 装好后 `./gradlew assembleRelease` 就能用同一把证书签名，
# 产出的 APK 可以覆盖安装到任何已装过本项目的手机上。
set -euo pipefail

NAME="getvideo"
REMOTE="${REMOTE:-tx}"
REMOTE_DIR="${REMOTE_DIR:-~/.app-keys}"
KEYDIR="$HOME/.android-keys"

need() { command -v "$1" >/dev/null 2>&1 || { echo "缺少命令：$1" >&2; exit 1; }; }

if [ -f "$KEYDIR/$NAME-release.jks" ] && [ -f "$KEYDIR/$NAME.properties" ]; then
  echo "已存在 $KEYDIR/$NAME-release.jks，跳过安装。"
  echo "如需强制重装：先 rm -rf \"$KEYDIR/$NAME-release.jks\" \"$KEYDIR/$NAME.properties\" 再跑本脚本。"
  exit 0
fi

mkdir -p "$KEYDIR"
chmod 700 "$KEYDIR"

SRC="${1:-}"
if [ -n "$SRC" ]; then
  echo "从本地目录 $SRC 安装…"
  cp "$SRC/$NAME-release.jks" "$SRC/$NAME.properties" "$KEYDIR/"
else
  need scp
  echo "从 $REMOTE:$REMOTE_DIR 拉取密钥…"
  scp -q "$REMOTE:$REMOTE_DIR/$NAME-release.jks" "$REMOTE:$REMOTE_DIR/$NAME.properties" "$KEYDIR/"
fi

chmod 600 "$KEYDIR/$NAME-release.jks" "$KEYDIR/$NAME.properties"

# 校验：properties 里的 storeFile 必须指向真实存在的文件
STORE="$(grep '^storeFile=' "$KEYDIR/$NAME.properties" | cut -d= -f2-)"
if [ ! -f "$STORE" ]; then
  echo "错误：properties 里的 storeFile 不存在：$STORE" >&2
  exit 1
fi

echo "已安装："
ls -l "$KEYDIR/$NAME-release.jks" "$KEYDIR/$NAME.properties"
shasum -a 256 "$KEYDIR/$NAME-release.jks" | sed "s|$HOME|~|"
echo
echo "确认证书指纹（应与 docs/android-release-signing.md 里记录的一致）："
grep '^storePassword=' "$KEYDIR/$NAME.properties" | cut -d= -f2- | xargs -I{} \
  keytool -list -v -keystore "$KEYDIR/$NAME-release.jks" -storepass {} -alias "$NAME" 2>/dev/null \
  | grep -E "Alias name|Creation date|SHA256:"
