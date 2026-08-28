# 123pan-mobile-app

123云盘（123pan）移动端应用—— 基于 Android WebView 实现。

## 功能特性

- 文件 / 文件夹管理（浏览、上传、下载、重命名、删除、分享）
- 回收站（还原 / 彻底删除 / 清空）
- 123 云盘原生分享（含"直连分享"直链方案）
- 传输列表与进度
- 文件点击菜单（按文件 / 文件夹定制）
- 自定义确认弹窗（替代 WebView 原生浏览器弹窗）
- 自定义 App 图标

## 项目结构

```
app/src/main/
├── assets/              # Web 资源（index.html / style.css / app.js）
├── java/com/pan/mobile/ # Android 源码（MainActivity / PanProvider）
├── res/                 # 资源（values / mipmap 各密度图标）
└── AndroidManifest.xml
scripts/build.sh         # 构建脚本（aapt2 + javac + d8 + zipalign + apksigner）
```

## 自动构建

本仓库通过 GitHub Actions 自动构建 APK：

- `push` 到 `main` 分支
- 推送形如 `v*` 的 tag（如 `v2.3.4`）
- 手动触发（Actions → Build APK → Run workflow）

构建产物会在 Actions 执行完成后以 **123pan-mobile-apk** artifact 形式提供下载，文件名即 tag/分支名。

## 手动构建

1. 配置 Android SDK（build-tools 34.0.0 + platform android-34）+ JDK 17
2. 设置环境变量 `ANDROID_HOME`
3. 运行 `scripts/build.sh`

默认使用 debug keystore 签名；如需正式签名，可通过环境变量覆盖：

```bash
ANDROID_KEYSTORE=/path/to/pan.keystore \
ANDROID_KEYSTORE_PASS=123456 \
ANDROID_KEYSTORE_ALIAS=pan \
./scripts/build.sh
```