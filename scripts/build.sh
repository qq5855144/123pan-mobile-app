#!/bin/bash
# 123pan-mobile-app 自动构建脚本（CI / 本地通用）
# 依赖：Android SDK (ANDROID_HOME) build-tools 34.0.0 + platform android-34 + JDK
#
# 环境变量：
#   ANDROID_BUILD_TOOLS / ANDROID_PLATFORM_JAR  覆盖 SDK 路径
#   VERSION_CODE / VERSION_NAME                  覆盖 versionCode / versionName（未设置时自动迭代）
#   KEYSTORE_B64 / ANDROID_KEYSTORE_PASS / ANDROID_KEYSTORE_ALIAS   正式签名（CI Secrets）
#   未设置 KEYSTORE_B64 时：若 ANDROID_KEYSTORE 指定则用之，否则用 debug.keystore
set -e

BT="${ANDROID_BUILD_TOOLS:-$ANDROID_HOME/build-tools/34.0.0}"
ANDROID_JAR="${ANDROID_PLATFORM_JAR:-$ANDROID_HOME/platforms/android-34/android.jar}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MAIN="$ROOT/app/src/main"

# ---- 版本号：优先环境变量；否则自动迭代 ----
RUN_NUM="${GITHUB_RUN_NUMBER:-1}"
REF_TYPE="${GITHUB_REF_TYPE:-}"
TAG_NAME="${GITHUB_REF_NAME:-v2.3.4}"
if [ "$REF_TYPE" = "tag" ]; then
    # tag 触发：versionName 取 tag（去掉前导 v），versionCode 以 run 递增基数
    VN_default="${TAG_NAME#v}"
    VC_default=$(( 1000 + RUN_NUM ))
else
    # 分支/手动触发：dev 版本，run_number 递增
    VN_default="dev-${RUN_NUM}"
    VC_default=$(( 1000 + RUN_NUM ))
fi
VERSION_CODE="${VERSION_CODE:-$VC_default}"
VERSION_NAME="${VERSION_NAME:-$VN_default}"
echo "=== versionCode=$VERSION_CODE versionName=$VERSION_NAME (ref_type=$REF_TYPE) ==="

W=/tmp/build_up
rm -rf "$W" && mkdir -p "$W/gen" "$W/obj" "$W/apk"

# 1) compile resources -> gateway.zip
"$BT/aapt2" compile --dir "$MAIN/res" -o "$W/gateway.zip"

# 2) link resources + manifest -> base.apk (含 assets), 生成 R.java, 覆盖版本号
"$BT/aapt2" link -I "$ANDROID_JAR" --manifest "$MAIN/AndroidManifest.xml" \
    --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
    -A "$MAIN/assets" -o "$W/base.apk" --java "$W/gen" --auto-add-overlay "$W/gateway.zip"

# 3) compile java
find "$MAIN/java" -name '*.java' > "$W/sources.txt"
javac -source 1.8 -target 1.8 -cp "$ANDROID_JAR" \
    -d "$W/obj" @"$W/sources.txt" "$W/gen/com/pan/mobile/R.java" 2>&1 | head -30

# 4) dex with d8
"$BT/d8" --release --lib "$ANDROID_JAR" --output "$W/apk" \
    $(find "$W/obj" -name '*.class') 2>&1 | head -20

# 5) repack: base.apk(含assets) + classes.dex, resources.arsc STORED
python3 - "$W" <<'PYEOF'
import sys, zipfile, os
W=sys.argv[1]
out=os.path.join(W,'apk','unaligned.apk')
src=os.path.join(W,'base.apk')
dex=os.path.join(W,'apk','classes.dex')
with zipfile.ZipFile(src) as zin, zipfile.ZipFile(out,'w',zipfile.ZIP_DEFLATED) as zout:
    for item in zin.infolist():
        data=zin.read(item.filename)
        info=zipfile.ZipInfo(item.filename, item.date_time)
        info.external_attr=item.external_attr
        info.compress_type = zipfile.ZIP_STORED if item.filename=='resources.arsc' else zipfile.ZIP_DEFLATED
        zout.writestr(info, data)
    zout.write(dex, 'classes.dex')
print('repacked:', out)
PYEOF

# 6) zipalign 4
"$BT/zipalign" -f 4 "$W/apk/unaligned.apk" "$W/apk/aligned.apk"

# 7) sign：优先 CI Secrets（KEYSTORE_B64），其次 ANDROID_KEYSTORE，最后 debug.keystore
if [ -n "$KEYSTORE_B64" ]; then
    echo "=== sign with CI keystore (secrets) ==="
    echo "$KEYSTORE_B64" | base64 -d > "$W/ci.keystore"
    KS="$W/ci.keystore"
    KS_PASS="${ANDROID_KEYSTORE_PASS:-123456}"
    KS_ALIAS="${ANDROID_KEYSTORE_ALIAS:-pan}"
else
    KS="${ANDROID_KEYSTORE:-$HOME/.android/debug.keystore}"
    KS_PASS="${ANDROID_KEYSTORE_PASS:-android}"
    KS_ALIAS="${ANDROID_KEYSTORE_ALIAS:-androiddebugkey}"
fi
"$BT/apksigner" sign --ks "$KS" \
    --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" --ks-key-alias "$KS_ALIAS" \
    --out "$W/apk/upfinal.apk" "$W/apk/aligned.apk"

# 8) output：文件名带版本号
mkdir -p "$ROOT/build"
APK_NAME="123pan-mobile-${VERSION_NAME}.apk"
cp "$W/apk/upfinal.apk" "$ROOT/build/${APK_NAME}"
echo "APK built: build/${APK_NAME}"