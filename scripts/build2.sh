#!/bin/bash
set -x
BT=/root/android-sdk/build-tools/34.0.0
ANDROID_JAR=/root/android-sdk/platforms/android-34/android.jar
APP=/sdcard/Download/Median/Workspace/MT2MCP/123pan-mobile-app
MAIN="$APP/app/src/main"
# ---- 版本号自动递增（本地构建，稳定持久化计数器，类似 CI 递增）----
VC_FILE="$APP/build/.version_code"
mkdir -p "$APP/build"
BASE_VC=170
if [ -f "$VC_FILE" ]; then
  VC=$(( $(cat "$VC_FILE") + 1 ))
else
  VC=$BASE_VC
fi
echo "$VC" > "$VC_FILE"
VERSION_CODE="$VC"
# versionName：1.<minor>.<patch>，patch 从 0 递增值（patch = versionCode - 170）
PATCH=$(( VC - 170 ))
VERSION_NAME="1.7.${PATCH}"
echo "=== versionCode=$VERSION_CODE versionName=$VERSION_NAME ==="
W=/tmp/build_up
rm -rf "$W" && mkdir -p "$W/gen" "$W/obj" "$W/apk"
# 生成带本次版本号的 manifest 临时副本（不改动源码 manifest，避免污染 git）
sed -E "s/android:versionCode=\"[0-9]+\"/android:versionCode=\"$VERSION_CODE\"/; s/android:versionName=\"[^\"]*\"/android:versionName=\"$VERSION_NAME\"/" \
    "$MAIN/AndroidManifest.xml" > "$W/AndroidManifest.xml"
grep -E 'versionCode|versionName' "$W/AndroidManifest.xml" | head -2

# 1) compile resources
/usr/bin/aapt2 compile --dir "$MAIN/res" -o "$W/gateway.zip" || exit 1
# 2) link resources + manifest -> base.apk skeleton, generate R.java
#    -A 把 assets 目录打包进 APK（WebView 依赖 file:///android_asset/index.html）
#    显式注入临时 manifest（已含本次递增版本号），实现与 CI 一致的自动递增版本号
/usr/bin/aapt2 link -I "$ANDROID_JAR" --manifest "$W/AndroidManifest.xml" \
    -A "$MAIN/assets" \
    -o "$W/base.apk" --java "$W/gen" --auto-add-overlay "$W/gateway.zip" || exit 1
echo "=== link done ==="
python3 -c "import zipfile;z=zipfile.ZipFile('$W/base.apk');print('assets in base:', any('assets/' in n for n in z.namelist()), [n for n in z.namelist() if n.startswith('assets/')])"
# 3) compile java -> classes
#    用临时副本编译，并把 getVersion() 返回值动态替换为本次版本号（与 APK 实际版本一致）
rm -rf "$W/java_tmp" && cp -r "$MAIN/java" "$W/java_tmp"
sed -i "s/return \"1\.[0-9.]*\"/return \"$VERSION_NAME\"/" "$W/java_tmp/com/pan/mobile/MainActivity.java"
find "$W/java_tmp" -name '*.java' > "$W/sources.txt"
javac -source 1.8 -target 1.8 -cp "$ANDROID_JAR" \
    -d "$W/obj" @"$W/sources.txt" "$W/gen/com/pan/mobile/R.java" 2>&1 | head -30
echo "=== javac done ==="
# 4) dex with d8
bash "$BT/d8" --release --lib "$ANDROID_JAR" --output "$W/apk" \
    $(find "$W/obj" -name '*.class') 2>&1 | head -20
echo "=== d8 done ==="
# 5) repack: base.apk(含assets) + classes.dex, resources.arsc ZIP_STORED + 4-aligned
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
        if item.filename=='resources.arsc':
            info.compress_type=zipfile.ZIP_STORED
        else:
            info.compress_type=zipfile.ZIP_DEFLATED
        zout.writestr(info, data)
    zout.write(dex, 'classes.dex')
print('repacked:', out)
PYEOF
# 6) zipalign 4
/usr/bin/zipalign -f 4 "$W/apk/unaligned.apk" "$W/apk/aligned.apk" 2>&1 | head
echo "=== zipalign done ==="
# 7) sign
KS=/sdcard/Download/Median/Workspace/MT2MCP/123pan-app/build/pan.keystore
/usr/bin/apksigner sign --ks "$KS" --ks-pass pass:123456 --key-pass pass:123456 \
--ks-key-alias pan --out "$W/apk/upfinal.apk" "$W/apk/aligned.apk" 2>&1 | head
echo "=== sign done ==="
/usr/bin/apksigner verify --print-certs "$W/apk/upfinal.apk" 2>&1 | head -3
python3 -c "import zipfile;z=zipfile.ZipFile('$W/apk/upfinal.apk');print('final assets:', [n for n in z.namelist() if n.startswith('assets/')])"
ls -la "$W/apk/upfinal.apk"