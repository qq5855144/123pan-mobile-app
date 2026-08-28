#!/bin/bash
# 123pan-mobile-app 自动构建脚本（CI / 本地通用）
# 依赖：Android SDK (ANDROID_HOME) build-tools 34.0.0 + platform android-34 + JDK
set -e

BT="${ANDROID_BUILD_TOOLS:-$ANDROID_HOME/build-tools/34.0.0}"
ANDROID_JAR="${ANDROID_PLATFORM_JAR:-$ANDROID_HOME/platforms/android-34/android.jar}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MAIN="$ROOT/app/src/main"
TAG="${GITHUB_REF_NAME:-v2.3.4}"
W=/tmp/build_up
rm -rf "$W" && mkdir -p "$W/gen" "$W/obj" "$W/apk"

# 1) compile resources -> gateway.zip
"$BT/aapt2" compile --dir "$MAIN/res" -o "$W/gateway.zip"

# 2) link resources + manifest -> base.apk (含 assets), 生成 R.java
"$BT/aapt2" link -I "$ANDROID_JAR" --manifest "$MAIN/AndroidManifest.xml" \
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

# 7) sign（默认 debug keystore；CI 用 ANDROID_KEYSTORE 覆盖）
KS="${ANDROID_KEYSTORE:-$HOME/.android/debug.keystore}"
KS_PASS="${ANDROID_KEYSTORE_PASS:-android}"
KS_ALIAS="${ANDROID_KEYSTORE_ALIAS:-androiddebugkey}"
"$BT/apksigner" sign --ks "$KS" \
    --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" --ks-key-alias "$KS_ALIAS" \
    --out "$W/apk/upfinal.apk" "$W/apk/aligned.apk"

# 8) output
mkdir -p "$ROOT/build"
cp "$W/apk/upfinal.apk" "$ROOT/build/${TAG}.apk"
echo "APK built: build/${TAG}.apk"
