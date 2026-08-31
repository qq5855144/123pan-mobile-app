# 123pan-mobile-app

123云盘（123pan）移动端复刻应用 —— 基于 Android WebView 实现。

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

本仓库通过 GitHub Actions（`.github/workflows/build.yml`）自动构建 APK。

**正式发版采用 tag 驱动：**

- 推送形如 `v*` 的 tag（如 `v1.7.888`）→ 自动构建并发布正式 GitHub Release
  - `versionName` = tag 去掉 `v`（如 `1.7.888`）
  - `versionCode` = `1000 + patch`（如 v1.7.888 → 1888），随版本稳定递增
  - Release 产物文件名即 `123pan-mobile-1.7.888.apk`
- `push` 到 `main` 分支 或 手动触发（Actions）→ 每次构建都**自动发布 dev 预发布（prerelease）到 Releases**，同时上传 **123pan-mobile-apk** artifact；dev 版本不标记 latest，避免覆盖正式版

> 提示：正式版版本号完全由 tag 决定，发布新版本请按语义化版本打递增 tag（如 `v1.7.889`）。

## 手动构建

1. 配置 Android SDK（build-tools 34.0.0 + platform android-34）+ JDK 17
2. 设置环境变量 `ANDROID_HOME`
3. 运行 `scripts/build.sh`

**必须使用正式 keystore 签名**（不配置则脚本直接失败，绝不回退随机 debug keystore，以保证签名稳定、可覆盖安装）。本地构建通过环境变量指定正式 keystore：

```bash
ANDROID_KEYSTORE=/path/to/pan.keystore \
ANDROID_KEYSTORE_PASS=123456 \
ANDROID_KEYSTORE_ALIAS=pan \
./scripts/build.sh
```

## GitHub Releases 配置（重要）

为保证 CI 构建与本地构建使用**同一个正式签名**（签名一致、可覆盖安装），需在仓库 **Settings → Secrets and variables → Actions** 配置以下 Secrets：

| Secret | 说明 |
|--------|------|
| `KEYSTORE_B64` | 正式 keystore（pan.keystore）的 base64 内容 |
| `KEYSTORE_PASS` | keystore 密码（示例 `123456`） |
| `KEYSTORE_ALIAS` | keystore 别名（示例 `pan`） |

生成 `KEYSTORE_B64`：

```bash
base64 -w0 /path/to/pan.keystore   # Linux/macOS
```

> 未配置 `KEYSTORE_B64` 时，CI 构建会在签名阶段直接失败（`build.sh` 已禁止回退随机 debug keystore），以强制使用正式签名、避免每次 Release 签名不一致。
