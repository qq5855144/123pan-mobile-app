# 123pan-mobile-app

123云盘移动端复刻应用，基于 **Android WebView 壳 + 内嵌 Web 前端** 架构。

## 技术路线

- **双端架构**：WebView 加载内置 `assets/` 前端（`index.html`/`style.css`/`app.js`），通过原生桥（`MainActivity#NativeBridge`）调用系统能力，`PanProvider` 提供 FileProvider 共享。
- **认证**：原生层发起 HTTP 请求并附加 `authorization` 头；多账号凭证（token）本地持久化，**切换账号免重新登录**，仅缺 token 时回退官方登录页。
- **构建链**：`aapt2` 资源编译/链接 → `javac`(1.8) → `d8` → 重打包 `assets` → `zipalign` → `apksigner` 正式签名。
- **签名一致性**：开发/CI 共用同一正式 keystore，保证可覆盖安装。

## 目录结构

```
app/src/main/
├── assets/              # Web 前端（index.html / style.css / app.js）
├── java/com/pan/mobile/ # Android 源码（MainActivity / PanProvider）
├── res/                 # values / 各密度图标
└── AndroidManifest.xml
scripts/build.sh         # 完整构建 + 签名脚本
.github/workflows/build.yml  # CI 自动构建 + 发布
```

## 自动构建与发布

GitHub Actions（`.github/workflows/build.yml`）在每次 `push`/tag 时自动完成：

1. 设置 JDK 17 + Android SDK（platform-34 / build-tools-34.0.0）
2. `scripts/build.sh` 构建并用正式 keystore 签名
3. 上传 APK artifact，并用 `gh release create` **自动发布为正式 Release**（每次构建一个）
4. 版本号：tag 触发取 `v` 去前缀（如 `v1.7.888 → 1.7.888`，`versionCode=1000+patch`）；分支触发用 `dev-<run_number>`；tag 版本额外标为 latest

## GitHub Secrets（必需）

| Secret | 说明 |
|--------|------|
| `KEYSTORE_B64` | 正式 keystore 的 base64 内容 |
| `KEYSTORE_PASS` | keystore 密码（默认 `123456`） |
| `KEYSTORE_ALIAS` | keystore 别名（默认 `pan`） |

生成 `KEYSTORE_B64`：`base64 -w0 /path/to/pan.keystore`

未配置时构建在签名阶段直接失败（禁止回退随机 debug keystore，确保签名稳定）。

## 本地构建

```bash
export ANDROID_HOME=...   # 需 JDK17 + platform-34 + build-tools-34.0.0
ANDROID_KEYSTORE=/path/to/pan.keystore ANDROID_KEYSTORE_PASS=123456 \
ANDROID_KEYSTORE_ALIAS=pan ./scripts/build.sh
```