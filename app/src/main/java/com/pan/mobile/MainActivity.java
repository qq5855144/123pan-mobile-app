package com.pan.mobile;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.graphics.Color;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;

/**
 * 123云盘移动端 (复刻 123pan-open 的 API 客户端能力)
 *
 * 架构：
 *  - 原生网络层 HttpURLConnection 调用 123 云盘 API（复刻 123pan-open 端点）
 *  - 通过 JS 桥 NativeBridge 暴露给内嵌移动端 SPA（assets/index.html）
 *  - token 持久化到 SharedPreferences
 */
public class MainActivity extends Activity {

    private WebView webView;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private SharedPreferences prefs;

    private static final String PREF = "pan_prefs";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_USER = "user";
    private static final String KEY_PASS = "pass";
    private static final String KEY_DEVICE = "deviceType";

    private ValueCallback<Uri[]> uploadMessage;
    private static final int FILE_CHOOSER_REQUEST = 1001;

    private String loginuuid = UUID.randomUUID().toString().replace("-", "");
    private String deviceType = "X12";
    private String osVersion = "13";
    private String devicename = "Xiaomi";

    // 自研流式下载任务状态表：taskId -> {done, total, expected, status}
    // status: 1=下载中 8=成功 16=失败。仅当实际写入字节数 >= expected 才置为 8。
    private final java.util.Map<Long, long[]> streamTasks =
        new java.util.concurrent.ConcurrentHashMap<>();
    // stream 任务成功落盘后的文件绝对路径：taskId -> path（供 openDownloadedFile 定位）
    private final java.util.Map<Long, String> streamTaskFiles =
        new java.util.concurrent.ConcurrentHashMap<>();
    // stream 任务成功后的 MediaStore content URI：taskId -> content://media/external/downloads/<id>
    // （打开/安装优先用它，避免 PanProvider path 解析问题导致安装器读到损坏内容）
    private final java.util.Map<Long, String> streamTaskUris =
        new java.util.concurrent.ConcurrentHashMap<>();
    private long nextTaskId = 900000000L;
    private String baseHeaders =
        "platform=android;app-version=61;x-app-version=2.4.0;user-agent=123pan/v2.4.0("
        + osVersion + ";Xiaomi)";

    // ---- 官方登录（主 WebView 直接加载官方登录页） ----
    private boolean officialLoginDone = false; // 已捕获到 sso-token（避免重复回填）
    private static final String OFFICIAL_LOGIN_URL =
        "https://user.123pan.cn/centerlogin?redirect_url=https%3A%2F%2Fyun.123pan.cn%2F&source_page=website";

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(
            android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
        webView = new WebView(this);
        setContentView(webView);
        prefs = getSharedPreferences(PREF, Context.MODE_PRIVATE);
        loginuuid = prefs.getString("loginuuid", loginuuid);

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(true);
        ws.setUseWideViewPort(true);
        ws.setLoadWithOverviewMode(true);
        ws.setSupportZoom(false);
        ws.setBuiltInZoomControls(true);
        ws.setDisplayZoomControls(false);
        ws.setCacheMode(WebSettings.LOAD_NO_CACHE);

        webView.addJavascriptInterface(new NativeBridge(this), "NativeBridge");

        // 文件选择（<input type=file> 上传）支持
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView wv, ValueCallback<Uri[]> filePathCallback,
                                             FileChooserParams fileChooserParams) {
                // 若已有未完成回调，先取消，避免 UI 卡死
                if (uploadMessage != null) { uploadMessage.onReceiveValue(null); }
                uploadMessage = filePathCallback;
                Intent intent = fileChooserParams.createIntent();
                try {
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST);
                } catch (Exception e) {
                    uploadMessage = null;
                    toast("无法打开文件选择器");
                    return false;
                }
                return true;
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                // 官方登录页按设备宽度渲染（禁用 wide viewport，避免阿里云滑块/浮层比视口宽无法拖动）；
                // 本地 SPA 保持移动端宽视口
                if (url != null && url.contains("123pan.cn")) {
                    WebSettings s = view.getSettings();
                    s.setUseWideViewPort(false);
                    s.setLoadWithOverviewMode(false);
                    s.setSupportZoom(false);
                } else if (url != null && url.startsWith("file://")) {
                    WebSettings s = view.getSettings();
                    s.setUseWideViewPort(true);
                    s.setLoadWithOverviewMode(true);
                    s.setSupportZoom(false);
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (url != null && url.startsWith("file://")) {
                    // 本地 SPA：注入持久化登录态（登录成功/已登录恢复会话）
                    String token = prefs.getString(KEY_TOKEN, "");
                    String user = prefs.getString(KEY_USER, "");
                    if (!token.isEmpty()) {
                        view.evaluateJavascript(
                            "window.__restoreSession&&window.__restoreSession("
                            + bindJson(json(token)) + "," + bindJson(json(user)) + ");", null);
                    }
                } else if (url != null && url.contains("123pan.cn")) {
                    // 官方登录页：尝试捕获 sso-token，成功则回到本地 SPA
                    tryCaptureSsoTokenFromMain();
                }
            }
        });

        // 下载支持：a[download]/新窗口下载 URL 经 DownloadManager 落盘到 Download 目录
        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent,
                                        String contentDisposition, String mimetype, long contentLength) {
                downloadViaManager(url, inferName(url, contentDisposition, mimetype));
            }
        });

        // 未登录：直接显示官方登录页（账号密码 / 验证码登录均在官方页完成，含安全滑块）
        // 已登录：加载本地 SPA 恢复会话
        String savedToken = prefs.getString(KEY_TOKEN, "");
        if (savedToken != null && !savedToken.isEmpty()) {
            webView.loadUrl("file:///android_asset/index.html");
        } else {
            webView.loadUrl(OFFICIAL_LOGIN_URL);
        }
    }

    private static String json(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private static String bindJson(String s) {
        return "\"" + s + "\"";
    }

    private void toast(final String msg) {
        handler.post(new Runnable() {
            @Override public void run() {
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_REQUEST) {
            if (uploadMessage == null) return;
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int n = data.getClipData().getItemCount();
                    results = new Uri[n];
                    for (int i = 0; i < n; i++) {
                        results[i] = data.getClipData().getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    results = new Uri[]{ data.getData() };
                }
            }
            if (results == null) {
                // 用户取消，通知前端
                uploadMessage.onReceiveValue(null);
                uploadMessage = null;
                return;
            }
            // 将 URI 拷贝为本地临时文件（文件选择器常提供只读 content:// URI）
            String[] paths = new String[results.length];
            for (int i = 0; i < results.length; i++) {
                paths[i] = copyUriToTemp(results[i]);
            }
            uploadMessage.onReceiveValue(results);
            uploadMessage = null;
            // 回传路径给前端，供后续上传
            final String jsList = buildPathsJson(paths);
            handler.post(new Runnable() {
                @Override public void run() {
                    webView.evaluateJavascript(
                        "window.__onFilesPicked&&window.__onFilesPicked(" + jsList + ");", null);
                }
            });
        }
    }

    // 将 content:// URI 拷贝为外部缓存临时文件，返回可读路径
    private String copyUriToTemp(Uri uri) {
        try {
            String name = "upload_" + System.currentTimeMillis() + ".bin";
            try {
                android.database.Cursor c = getContentResolver().query(uri, null, null, null, null);
                if (c != null && c.moveToFirst()) {
                    int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0 && c.getString(idx) != null) {
                        name = c.getString(idx);
                    }
                    c.close();
                }
            } catch (Exception ignore) { }
            File tmp = new File(getExternalCacheDir(), name);
            InputStream in = getContentResolver().openInputStream(uri);
            FileOutputStream out = new FileOutputStream(tmp);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush(); out.close(); in.close();
            return tmp.getAbsolutePath();
        } catch (Exception e) {
            Log.e("PAN", "copyUriToTemp fail: " + uri + " -> " + e);
            return uri.toString();
        }
    }

    // 生成 JS 数组字符串（路径列表）
    private String buildPathsJson(String[] paths) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < paths.length; i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(json(paths[i])).append("\"");
        }
        sb.append("]");
        return sb.toString();
    }

    @Override
    public void onBackPressed() {
        // 前端的回退（面包屑/抽屉）交给 JS；仅在没有可回退时退出
        handler.post(new Runnable() {
            @Override public void run() {
                webView.evaluateJavascript(
                    "window.__handleBack&&window.__handleBack();", null);
            }
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 进入后台：通知前端停止扫码轮询，避免在 cached 状态产生过量
        // HTTP 请求 / JS 桥 binder 流量（此前被系统以 EXCESSIVE CPU/RESOURCE
        // USAGE 杀死，根因正是后台持续轮询）。
        if (webView != null) {
            webView.onPause();
            handler.post(new Runnable() {
                @Override public void run() {
                    webView.evaluateJavascript(
                        "window.__onAppPause&&window.__onAppPause();", null);
                }
            });
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
            handler.post(new Runnable() {
                @Override public void run() {
                    webView.evaluateJavascript(
                        "window.__onAppResume&&window.__onAppResume();", null);
                }
            });
        }
    }


    // 使用系统 DownloadManager 下载；返回下载任务 ID，若为 -1 表示失败。
    private long downloadViaManager(String url, String name) {
        try {
            String fname = sanitizeFileName(name);
            if (fname == null || fname.isEmpty()) fname = "download_" + System.currentTimeMillis();
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            req.setTitle(fname);
            req.setDescription("123云盘下载");
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fname);
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            long id = dm.enqueue(req);
            Log.d("PAN", "download enqueued: " + fname + " id=" + id);
            toast("已加入下载任务：Download/" + fname);
            return id;
        } catch (Exception e) {
            Log.e("PAN", "downloadViaManager fail: " + url + " -> " + e, e);
            toast("下载失败：" + (e != null && e.getMessage() != null ? e.getMessage() : e));
            return -1;
        }
    }

    // 从 content-disposition / filename 参数 推断友好文件名
    private String inferName(String url, String contentDisposition, String mimetype) {
        String name = null;
        try {
            if (contentDisposition != null) {
                int i = contentDisposition.indexOf("filename=");
                if (i >= 0) {
                    name = contentDisposition.substring(i + 9).trim();
                    name = name.replace("\"", "").replace("'", "");
                    int semi = name.indexOf(";");
                    if (semi > 0) name = name.substring(0, semi).trim();
                }
            }
        } catch (Exception ignore) { }
        if (name == null || name.isEmpty()) {
            try {
                String q = new URL(url).getQuery();
                if (q != null) {
                    for (String p : q.split("&")) {
                        if (p.startsWith("filename=")) name = java.net.URLDecoder.decode(p.substring(9), "UTF-8");
                    }
                }
            } catch (Exception ignore) { }
        }
        if (name == null || name.isEmpty()) {
            try { name = new URL(url).getPath(); } catch (Exception ignore) { }
            if (name != null) {
                String[] seg = name.split("/");
                name = seg.length > 0 ? seg[seg.length - 1] : null;
            }
        }
        if (name == null || name.isEmpty()) name = "download_" + System.currentTimeMillis();
        return name;
    }

    private String sanitizeFileName(String n) {
        if (n == null) return null;
        StringBuilder sb = new StringBuilder(n.length());
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (c == '/' || c == '\\' || c == ':' || c == '*' || c == '?'
                || c == '"' || c == '<' || c == '>' || c == '|') {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }
        String r = sb.toString();
        if (r.length() > 120) r = r.substring(r.length() - 120);
        return r;
    }

    // ============ 自研流式下载（严格校验字节完整性） ============
    // 根因：系统 DownloadManager 用默认 UA 直连 123pan 下载直链时，可能被服务端
    // 重定向/拦截返回错误页或截断内容，却标记 STATUS_SUCCESSFUL(8)，导致"未下载完就显示完成"。
    // 自研下载器带上与 API 一致的认证头请求直链，并按 expectedSize 严格校验，
    // 只有真实写盘字节数 >= 期望大小才标记成功(status 8)，否则标记失败(16)。
    // 123pan 的 DownloadUrl 可能是 download-v2/?params= 中转跳转页，需递归解析出真实 CDN 直链。
    // 返回任务 id（>=900000000 表示原生任务）；失败返回 -1。
    public long downloadStream(final String url, final String filename, final long expectedSize) {
        try {
            final String fname = sanitizeFileName(filename);
            final long taskId = nextTaskId++;
            streamTasks.put(taskId, new long[]{ 0, 0, expectedSize, 1 }); // done,total,expected,status
            logDl("downloadStream CALLED fname=" + fname + " expected=" + expectedSize + " url=" + url);
            final MainActivity act = this;
            executor.execute(new Runnable() {
                @Override public void run() {
                    long[] st = streamTasks.get(taskId);
                    java.io.OutputStream out = null;
                    HttpURLConnection conn = null;
                    android.net.Uri itemUri = null;
                    try {
                        // ---- 多级解析最终真实下载直链 ----
                        String finalUrl = resolveRealDownloadUrl(url, fname);
                        logDl("resolve finalUrl=" + finalUrl);
                        if (finalUrl == null) {
                            st[3] = 16;
                            logDl("resolve FAILED (no real url) " + fname);
                            return;
                        }
                        conn = (HttpURLConnection) new URL(finalUrl).openConnection();
                        conn.setConnectTimeout(20000);
                        conn.setReadTimeout(120000);
                        conn.setRequestMethod("GET");
                        conn.setInstanceFollowRedirects(true);
                        String token = prefs.getString(KEY_TOKEN, "");
                        conn.setRequestProperty("user-agent", "123pan/v2.4.0(" + osVersion + ";Xiaomi)");
                        conn.setRequestProperty("authorization", token.isEmpty() ? "" : "Bearer " + token);
                        conn.setRequestProperty("osversion", osVersion);
                        conn.setRequestProperty("platform", "web");
                        conn.setRequestProperty("devicetype", deviceType);
                        conn.setRequestProperty("devicename", devicename);
                        conn.setRequestProperty("app-version", "61");
                        conn.setRequestProperty("x-app-version", "2.4.0");
                        conn.setRequestProperty("Origin", "https://yun.123pan.cn");
                        conn.setRequestProperty("Referer", "https://yun.123pan.cn/");
                        int code = conn.getResponseCode();
                        logDl("stream dl HTTP " + code + " for " + fname + " len=" + conn.getContentLengthLong()
                            + " tokenEmpty=" + (token == null || token.isEmpty()));
                        if (code < 200 || code >= 300) {
                            st[3] = 16; // 失败
                            Log.e("PAN", "stream dl HTTP " + code + " for " + fname);
                            return;
                        }
                        long contentLen = conn.getContentLengthLong();
                        if (st != null) st[1] = contentLen > 0 ? contentLen : expectedSize;
                        // 用 MediaStore 写入公共 Download（Android 10+ 无写权限也可写，文件对其他 App 可见/可安装）
                        android.content.ContentValues cv = new android.content.ContentValues();
                        cv.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fname);
                        String mime = fname != null && fname.toLowerCase().endsWith(".apk")
                            ? "application/vnd.android.package-archive"
                            : "application/octet-stream";
                        cv.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime);
                        cv.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                        cv.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1);
                        itemUri = act.getContentResolver().insert(
                            android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                        logDl("MediaStore insert uri=" + (itemUri != null ? itemUri.toString() : "NULL"));
                        if (itemUri == null) { st[3] = 16; Log.e("PAN", "stream dl: MediaStore insert fail"); return; }
                        java.io.InputStream in = conn.getInputStream();
                        out = act.getContentResolver().openOutputStream(itemUri, "wa");
                        if (out == null) { st[3] = 16; logDl("MediaStore openOutputStream NULL"); return; }
                        byte[] buf = new byte[65536];
                        long written = 0;
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            out.write(buf, 0, n);
                            written += n;
                            if (st != null) st[0] = written;
                        }
                        out.flush(); out.close(); out = null;
                        // 清除"不可见"标记，让文件立即可见
                        android.content.ContentValues pend = new android.content.ContentValues();
                        pend.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0);
                        act.getContentResolver().update(itemUri, pend, null, null);
                        String realPath = queryMediaDataPath(act, itemUri);
                        // 严格校验：实际写盘字节数必须 >= 期望字节（若期望已知）
                        if (st != null) {
                            if (expectedSize <= 0 || written >= expectedSize) {
                                st[3] = 8; // 成功
                                streamTaskFiles.put(taskId, realPath != null ? realPath : itemUri.toString());
                                streamTaskUris.put(taskId, itemUri.toString()); // 供打开/安装优先用 MediaStore URI
                                Log.d("PAN", "stream dl ok: " + fname + " id=" + taskId + " bytes=" + written);
                                logDl("stream dl SUCCESS " + fname + " bytes=" + written + " expected=" + expectedSize);
                            } else {
                                st[3] = 16; // 字节数不足 -> 失败
                                try { act.getContentResolver().delete(itemUri, null, null); } catch (Exception ignore) {}
                                Log.w("PAN", "stream dl incomplete: " + fname + " got " + written
                                    + " expected " + expectedSize);
                                logDl("stream dl INCOMPLETE " + fname + " got=" + written + " expected=" + expectedSize);
                            }
                        }
                    } catch (Exception e) {
                        Log.e("PAN", "stream dl fail: " + (fname == null ? "" : fname) + " -> " + e, e);
                        if (st != null) st[3] = 16;
                        logDl("stream dl EXCEPTION " + fname + " -> " + e);
                        if (itemUri != null) { try { act.getContentResolver().delete(itemUri, null, null); } catch (Exception ignore) {} }
                    } finally {
                        try { if (out != null) out.close(); } catch (Exception ignore) {}
                        if (conn != null) conn.disconnect();
                    }
                }
            });
            Log.d("PAN", "stream dl enqueued: " + fname + " id=" + taskId + " expected=" + expectedSize);
            return taskId;
        } catch (Exception e) {
            Log.e("PAN", "downloadStream fail: " + e, e);
            logDl("downloadStream EXCEPTION " + e);
            return -1;
        }
    }

    // 解析 123pan 的多级下载直链，返回真正可直接流式下载的最终 CDN URL。
    // 处理两种中转：
    //  1) DownloadUrl 形如 ..../download-v2/?params=<base64>&is_s3=0 —— 直接 base64 解码 params 得真实 S3 直链
    //  2) GET 真实 S3 直链若返回 HTTP 210 + JSON{code,data.redirect_url} —— 取其 redirect_url 作为最终 URL
    // 返回最终直链；无法解析则返回 null。
    private String resolveRealDownloadUrl(String url, String fname) {
        try {
            String cur = url;
            for (int hop = 0; hop < 8; hop++) {
                if (cur == null || cur.isEmpty()) return null;
                logDl("resolve hop" + hop + " url=" + cur);
                // 情况1：download-v2 中转页 —— 从 query 提取 params(base64) 解码出真实 S3 直链
                int pIdx = cur.indexOf("params=");
                if (cur.contains("download-v2") && pIdx >= 0) {
                    String params = cur.substring(pIdx + "params=".length());
                    int amp = params.indexOf('&');
                    if (amp >= 0) params = params.substring(0, amp);
                    // URL 解码
                    params = java.net.URLDecoder.decode(params, "UTF-8");
                    // base64 解码
                    byte[] dec = android.util.Base64.decode(params, android.util.Base64.DEFAULT);
                    if (dec != null && dec.length > 0) {
                        String real = new String(dec, "UTF-8");
                        cur = real;
                        continue; // 跳到情况2 GET 试探
                    }
                }
                // 对当前候选 URL 发起一次 GET 试探（仅读响应头/小体积响应体判断是否需再跳转）
                HttpURLConnection c = (HttpURLConnection) new URL(cur).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(20000);
                c.setRequestMethod("GET");
                c.setInstanceFollowRedirects(true);
                String token = prefs.getString(KEY_TOKEN, "");
                c.setRequestProperty("user-agent", "123pan/v2.4.0(" + osVersion + ";Xiaomi)");
                c.setRequestProperty("authorization", token.isEmpty() ? "" : "Bearer " + token);
                c.setRequestProperty("osversion", osVersion);
                c.setRequestProperty("platform", "web");
                c.setRequestProperty("devicetype", deviceType);
                c.setRequestProperty("devicename", devicename);
                c.setRequestProperty("app-version", "61");
                c.setRequestProperty("x-app-version", "2.4.0");
                c.setRequestProperty("Origin", "https://yun.123pan.cn");
                c.setRequestProperty("Referer", "https://yun.123pan.cn/");
                String ctype = c.getContentType();
                int ccode = c.getResponseCode();
                logDl("resolve probe HTTP " + ccode + " type=" + ctype + " len=" + c.getContentLengthLong());
                // HTTP 210：服务端返回 JSON { message, data:{ redirect_url } }
                if (ccode == 210) {
                    java.io.InputStream es = c.getErrorStream();
                    if (es == null) es = c.getInputStream();
                    byte[] body = readAll(es, 65536);
                    c.disconnect();
                    String txt = body != null ? new String(body, "UTF-8") : "";
                    logDl("resolve 210 body=" + (txt.length() > 120 ? txt.substring(0, 120) : txt));
                    int ru = txt.indexOf("redirect_url");
                    if (ru >= 0) {
                        int st = txt.indexOf('"', ru + "redirect_url".length() + 2);
                        if (st >= 0) {
                            int en = txt.indexOf('"', st + 1);
                            if (en > st) {
                                String red = txt.substring(st + 1, en)
                                    .replace("\\/", "/").replace("\\u0026", "&");
                                cur = red;
                                continue;
                            }
                        }
                    }
                    return null;
                }
                // HTTP 200 且是二进制流（application/octet-stream 或非 text/html）-> 最终直链
                if (ccode >= 200 && ccode < 300) {
                    boolean isHtml = ctype != null && ctype.toLowerCase().contains("text/html");
                    if (!isHtml) {
                        String finalUrl = cur;
                        c.disconnect();
                        return finalUrl;
                    }
                    // 仍是 html 壳（可能是别的中转），读 body 尝试从其中提取 downloadv2 参数
                    java.io.InputStream is = c.getInputStream();
                    byte[] body = readAll(is, 65536);
                    c.disconnect();
                    String txt = body != null ? new String(body, "UTF-8") : "";
                    logDl("resolve html shell, try extract params, len=" + txt.length());
                    // 某些中转页 body 里可能直接含 <a href=真实url>，简单尝试找 https:// 直链
                    int hp = txt.indexOf("https://");
                    if (hp >= 0) {
                        int he = txt.indexOf('"', hp);
                        int he2 = txt.indexOf('\'', hp);
                        if (he < 0) he = he2;
                        if (he > hp) {
                            String cand = txt.substring(hp, he);
                            if (cand.contains("download-cdn") || cand.contains("123773.com")
                                || cand.contains("pd1.cjjd19") || cand.contains(".apk")
                                || cand.contains("filename=")) {
                                cur = cand;
                                continue;
                            }
                        }
                    }
                    return null;
                }
                // 其它状态码 -> 失败
                c.disconnect();
                return null;
            }
            return cur;
        } catch (Exception e) {
            logDl("resolve EXCEPTION " + e);
            return null;
        }
    }

    // 读取流全部内容（限制 max），用于解析 210 JSON 或 html 壳；读不到返回 null。
    private byte[] readAll(java.io.InputStream in, int max) {
        try {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
                if (bo.size() > max) break;
            }
            try { in.close(); } catch (Exception ignore) {}
            return bo.toByteArray();
        } catch (Exception e) { return null; }
    }

    // 诊断：把下载关键事件/异常写入公共 Download 目录的日志文件，便于 shell 读取排查。
    // 用 MediaStore 写入（Android 10+ 无需写权限），保证 app 内能成功落盘到公共目录。
    private void logDl(String msg) {
        java.io.OutputStream os = null;
        try {
            String line = System.currentTimeMillis() + " " + msg + "\n";
            Uri u = null;
            try {
                // 尝试打开已存在的日志文件（追加）
                String[] proj = { android.provider.MediaStore.MediaColumns._ID };
                android.database.Cursor c = getContentResolver().query(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, proj,
                    android.provider.MediaStore.MediaColumns.DISPLAY_NAME + "=?", 
                    new String[]{"pan_dl_log.txt"}, null);
                if (c != null) {
                    if (c.moveToFirst()) {
                        long id = c.getLong(0);
                        u = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
                            .buildUpon().appendPath(String.valueOf(id)).build();
                    }
                    c.close();
                }
            } catch (Exception ignore) {}
            if (u != null) {
                try {
                    os = getContentResolver().openOutputStream(u, "wa");
                } catch (Exception e) { os = null; }
            }
            if (os == null) {
                android.content.ContentValues cv = new android.content.ContentValues();
                cv.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, "pan_dl_log.txt");
                cv.put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain");
                cv.put(android.provider.MediaStore.Downloads.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_DOWNLOADS);
                u = getContentResolver().insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (u != null) os = getContentResolver().openOutputStream(u, "wa");
            }
            if (os != null) {
                os.write(line.getBytes("UTF-8"));
                os.close();
            }
        } catch (Exception ignore) {
            try { if (os != null) os.close(); } catch (Exception ignore2) {}
        }
    }

    // 从 MediaStore 条目查询物理绝对路径（_data），供"打开/安装"使用；查不到返回 null
    private String queryMediaDataPath(MainActivity act, Uri itemUri) {
        try {
            Cursor c = act.getContentResolver().query(itemUri,
                new String[]{ android.provider.MediaStore.MediaColumns.DATA }, null, null, null);
            if (c != null) {
                try {
                    if (c.moveToFirst()) {
                        int idx = c.getColumnIndex(android.provider.MediaStore.MediaColumns.DATA);
                        if (idx >= 0) return c.getString(idx);
                    }
                } finally { c.close(); }
            }
            return null;
        } catch (Exception e) {
            Log.e("PAN", "queryMediaDataPath fail", e);
            return null;
        }
    }

    // 查询自研流式下载任务进度，返回 JSON 数组 [{id,name,total,done,status}]
    public String streamingTasksJson() {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (java.util.Map.Entry<Long, long[]> e : streamTasks.entrySet()) {
            long[] st = e.getValue();
            if (st == null) continue;
            if (!first) sb.append(",");
            first = false;
            sb.append("{\"id\":").append(e.getKey());
            sb.append(",\"name\":\"\"");
            sb.append(",\"total\":").append(st[1]);
            sb.append(",\"done\":").append(st[0]);
            sb.append(",\"status\":").append(st[3]);
            sb.append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    // 查询本应用经 DownloadManager 发起的下载任务进度，返回 JSON 数组 [{id,name,total,done,status}]
    // status: 1=下载中 8=成功 16=失败, done/total 单位字节
    public String queryDownloadsJson() {
        StringBuilder sb = new StringBuilder("[");
        try {
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Query q = new DownloadManager.Query();
            Cursor c = dm.query(q);
            boolean first = true;
            if (c != null) {
                int idxId = c.getColumnIndex(DownloadManager.COLUMN_ID);
                int idxTitle = c.getColumnIndex(DownloadManager.COLUMN_TITLE);
                int idxDesc = c.getColumnIndex(DownloadManager.COLUMN_DESCRIPTION);
                int idxTotal = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES);
                int idxDone = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);
                int idxStatus = c.getColumnIndex(DownloadManager.COLUMN_STATUS);
                while (c.moveToNext()) {
                    String desc = c.getString(idxDesc);
                    if (desc == null || !desc.contains("123云盘下载")) continue;
                    if (!first) sb.append(",");
                    first = false;
                    String title = c.getString(idxTitle);
                    long total = c.getLong(idxTotal);
                    long done = c.getLong(idxDone);
                    int st = c.getInt(idxStatus);
                    long id = c.getLong(idxId);
                    sb.append("{\"id\":").append(id);
                    sb.append(",\"name\":\"").append(escapeJson(title)).append("\"");
                    sb.append(",\"total\":").append(total);
                    sb.append(",\"done\":").append(done);
                    sb.append(",\"status\":").append(st);
                    sb.append("}");
                }
                c.close();
            }
        } catch (Exception e) {
            Log.e("PAN", "queryDownloads fail: " + e, e);
        }
        sb.append("]");
        return sb.toString();
    }

    // 转义 JSON 字符串中的特殊字符
    private String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\\') sb.append("\\\\");
            else if (ch == '"') sb.append("\\\"");
            else if (ch == '\n') sb.append("\\n");
            else if (ch == '\r') sb.append("\\r");
            else if (ch == '\t') sb.append("\\t");
            else sb.append(ch);
        }
        return sb.toString();
    }

    private boolean isApk(String n) {
        return n != null && n.toLowerCase().endsWith(".apk");
    }

    // 打开已下载文件：优先 App 私有下载目录（自研流式下载落盘处），其次系统公共 Download 目录（DownloadManager 落盘处）。
    // apk 直接唤醒系统安装程序，其它走系统"打开方式"
    public void openDownloadedFile(String name) {
        try {
            String fname = sanitizeFileName(name);
            logDl("OPEN CALLED name=" + name + " fname=" + fname);
            File f = null;
            // -2) 最可靠：通过 MediaStore 前缀查询 Download 列表，找出文件名匹配（含 "(1)" 后缀）的
            //     IS_PENDING=0 的条目，取最新一个的 content URI 与真实路径。
            //     注意：必须用【去扩展名的 baseName】做 LIKE 前缀，否则带 "(1)" 后缀的文件（DisplayName 变成 xxx (1).apk）
            //     无法被 "xxx.apk%" 匹配到，会误选同名的旧损坏文件（如 5344 字节 HTML 壳）。
            //     即使 app 重启、内存 map 清空，也能定位到新下载的完整文件，避免误打开同名旧损坏文件。
            String baseName = fname;
            if (baseName != null) {
                int idx = baseName.lastIndexOf('.');
                if (idx > 0) baseName = baseName.substring(0, idx);
            }
            Uri bestMediaUri = null;
            String bestMediaPath = null;
            long bestSize = -1;
            try {
                android.database.Cursor c = getContentResolver().query(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    new String[]{
                        android.provider.MediaStore.MediaColumns._ID,
                        android.provider.MediaStore.MediaColumns.DISPLAY_NAME,
                        android.provider.MediaStore.MediaColumns.SIZE,
                        android.provider.MediaStore.MediaColumns.DATA,
                        android.provider.MediaStore.MediaColumns.IS_PENDING
                    },
                    android.provider.MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ? ",
                    new String[]{ baseName + "%" },
                    android.provider.MediaStore.MediaColumns.DATE_MODIFIED + " DESC");
                if (c != null) {
                    while (c.moveToNext()) {
                        long id = c.getLong(0);
                        String dn = c.getString(1);
                        long size = c.getLong(2);
                        int pending = c.getInt(4);
                        if (dn != null && dn.startsWith(baseName) && pending == 0 && size > 0) {
                            bestMediaUri = android.content.ContentUris.withAppendedId(
                                android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, id);
                            bestMediaPath = c.getString(3);
                            bestSize = size;
                            break; // 取最新一条完整记录
                        }
                    }
                    c.close();
                }
            } catch (Exception ignore) {}
            logDl("OPEN bestMediaUri=" + (bestMediaUri != null ? bestMediaUri.toString() : "null")
                + " size=" + bestSize + " path=" + bestMediaPath);
            // -1) 优先用内存中保存的 MediaStore content URI（自研流式下载成功时保存，本进程内最准）
            java.util.Map.Entry<Long, String> uriEntry = null;
            try {
                for (java.util.Map.Entry<Long, String> en : streamTaskUris.entrySet()) {
                    String p = streamTaskFiles.get(en.getKey());
                    if (p == null) continue;
                    File sf = new File(p);
                    String bn = sf.getName();
                    if (sf.exists() && bn != null && bn.startsWith(fname)) { uriEntry = en; break; }
                }
            } catch (Exception ignore) {}
            logDl("OPEN uriEntry=" + (uriEntry != null ? uriEntry.getValue() : "null"));
            // 0) 按文件名匹配自研流式下载已落盘文件（MediaStore 可能自动加后缀，用 basename 前缀匹配）
            try {
                for (String p : streamTaskFiles.values()) {
                    if (p == null) continue;
                    File sf = new File(p);
                    String bn = sf.getName();
                    if (sf.exists() && bn != null && bn.startsWith(fname)) { f = sf; break; }
                }
            } catch (Exception ignore) {}
            // 1) 系统公共 Download 目录：若已存在精确名文件，优先用 MediaStore 查到的完整文件（bestMediaPath），
            //    否则用 fname 精确匹配（可能是旧 DownloadManager 下载，需要校验大小不误开损坏文件）
            if (f == null && bestMediaPath != null) {
                File bf = new File(bestMediaPath);
                if (bf.exists()) f = bf;
            }
            if (f == null) {
                try {
                    File cd = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS).getAbsolutePath(), fname);
                    if (cd.exists() && cd.length() > 0) f = cd;
                } catch (Exception ignore) {}
            }
            // 2) App 私有外部下载目录
            if (f == null) {
                try {
                    File pd = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath(), fname);
                    if (pd.exists() && pd.length() > 0) f = pd;
                } catch (Exception ignore) {}
            }
            logDl("OPEN file=" + (f != null ? f.getAbsolutePath() : "null") + " exists=" + (f != null && f.exists()));
            if (isApk(fname)) {
                // apk：优选 MediaStore content URI（内存 uriEntry > MediaStore 前缀查询 bestMediaUri）唤醒系统包安装器
                Uri apkUri = null;
                if (uriEntry != null) apkUri = Uri.parse(uriEntry.getValue());
                else if (bestMediaUri != null) apkUri = bestMediaUri;
                if (apkUri != null) {
                    try {
                        logDl("OPEN apk via MediaStore uri=" + apkUri);
                        Intent install = new Intent(Intent.ACTION_INSTALL_PACKAGE);
                        install.setDataAndType(apkUri, "application/vnd.android.package-archive");
                        install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        install.setClipData(ClipData.newRawUri("", apkUri));
                        startActivity(install);
                        logDl("OPEN apk install launched via MediaStore uri");
                        return;
                    } catch (Exception e) {
                        Log.w("PAN", "open apk via MediaStore uri fail, fallback to path: " + e, e);
                        logDl("OPEN MediaStore uri EXCEPTION " + e);
                    }
                }
                if (f == null || !f.exists()) {
                    toast("文件不存在：" + (fname == null ? "" : fname));
                    logDl("OPEN no file found, toast");
                    return;
                }
            } else if (f == null || !f.exists()) {
                toast("文件不存在：" + (fname == null ? "" : fname));
                logDl("OPEN no file found (non-apk), toast");
                return;
            }
            String contentUri = "content://com.pan.mobile.pan/file?path=" + Uri.encode(f.getAbsolutePath());
            Uri cu = Uri.parse(contentUri);
            logDl("OPEN via PanProvider uri=" + cu + " size=" + (f != null ? f.length() : 0));
            Log.d("PAN", "open file: " + f.getAbsolutePath());
            if (isApk(fname)) {
                // apk：直接唤醒系统包安装器（不走 chooser，安装必然有软件包安装程序）
                try {
                    Intent install = new Intent(Intent.ACTION_INSTALL_PACKAGE);
                    // 必须用 setDataAndType：分开 setData+setType 会清空 data，导致安装器收不到文件
                    install.setDataAndType(cu, "application/vnd.android.package-archive");
                    install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    install.setClipData(ClipData.newRawUri("", cu));
                    startActivity(install);
                    logDl("OPEN apk install launched via PanProvider");
                    return;
                } catch (Exception e) {
                    Log.e("PAN", "install direct fail -> chooser: " + e, e);
                    logDl("OPEN PanProvider install EXCEPTION " + e);
                }
            }
            // 其它类型（含 apk 兜底）：系统推荐打开方式
            MimeTypeMap mimeMap = MimeTypeMap.getSingleton();
            String ext = MimeTypeMap.getFileExtensionFromUrl(Uri.fromFile(f).toString());
            String mime = (ext != null) ? mimeMap.getMimeTypeFromExtension(ext.toLowerCase()) : null;
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(cu,
                isApk(fname) ? "application/vnd.android.package-archive"
                    : ((mime != null && !mime.isEmpty()) ? mime : "*/*"));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setClipData(ClipData.newRawUri("", cu));
            try {
                startActivity(Intent.createChooser(intent, "打开方式"));
            } catch (Exception e) {
                toast("没有可打开该文件的应用");
            }
        } catch (Exception e) {
            Log.e("PAN", "open fail: " + e, e);
            toast("打开失败：" + (e.getMessage() != null ? e.getMessage() : e));
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) webView.destroy();
        executor.shutdown();
        super.onDestroy();
    }

    /**
     * 在后台线程执行 API 请求，回调前端 JS。
     * 复刻 123pan-open 的端点：
     *   sign_in / file/list/new / download_info / trash / rename / mod_pid / user/info
     */
    private void doApi(final String callback, final String method,
                       final String url, final String body, final boolean withAuth) {
        executor.execute(new Runnable() {
            @Override public void run() {
                String result;
                Log.d("PAN", "api req: " + method + " " + url
                    + (body != null && !body.isEmpty() ? " body=" + body : ""));
                try {
                    result = httpRequest(method, url, body, withAuth);
                } catch (Exception e) {
                    Log.e("PAN", "api fail: " + method + " " + url + " -> " + e, e);
                    result = "{\"ok\":false,\"error\":\"网络异常: " + json(e.getMessage()) + "\"}";
                }
                String logBody = result;
                if (logBody != null) {
                    // list 接口完整打印（供定位移动落盘），其余接口仍截断前 200
                    boolean isList = url != null && url.contains("file/list");
                    int cap = isList ? 8000 : 200;
                    if (logBody.length() > cap) logBody = logBody.substring(0, cap);
                }
                Log.d("PAN", "api resp: " + method + " " + url + " -> " + logBody);
                final String js = callback + "(" + result + ");";
                handler.post(new Runnable() {
                    @Override public void run() {
                        if (webView != null) webView.evaluateJavascript(js, null);
                    }
                });
            }
        });
    }

    /** 基础 HTTP 请求 */
    private String httpRequest(String method, String url, String body, boolean withAuth)
            throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        conn.setRequestMethod(method);
        // 复刻 123pan-open 的标准请求头
        String token = prefs.getString(KEY_TOKEN, "");
        conn.setRequestProperty("user-agent",
            "123pan/v2.4.0(" + osVersion + ";Xiaomi)");
        conn.setRequestProperty("authorization",
            withAuth && !token.isEmpty() ? "Bearer " + token : "");
        conn.setRequestProperty("osversion", osVersion);
        conn.setRequestProperty("loginuuid", loginuuid);
        // 关键：platform 必须是 "web"，否则服务端对上传走 android 分支，
        // 导致 complete 返回 code:0 但文件不真正落盘（Location 空 / 文件夹 Total:0）。
        conn.setRequestProperty("platform", "web");
        conn.setRequestProperty("devicetype", deviceType);
        conn.setRequestProperty("devicename", devicename);
        conn.setRequestProperty("app-version", "61");
        conn.setRequestProperty("x-app-version", "2.4.0");
        // 与官方 web 客户端保持一致的 Origin/Referer（配合 platform=web）
        conn.setRequestProperty("Origin", "https://yun.123pan.cn");
        conn.setRequestProperty("Referer", "https://yun.123pan.cn/");
        if (body != null && !body.isEmpty()) {
            conn.setDoOutput(true);
            conn.setRequestProperty("content-type", "application/json; charset=UTF-8");
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            OutputStream os = conn.getOutputStream();
            os.write(b);
            os.flush();
            os.close();
        }
        int code = conn.getResponseCode();
        // HttpURLConnection (API 19+) 在未手动设置 Accept-Encoding 时会自动发送
        // gzip 请求头并自动解压 gzip 响应，故此处直接读取明文流即可，无需手动解压。
        InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        BufferedReader r = new BufferedReader(
            new InputStreamReader(is == null ? (InputStream) null : is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line);
        r.close();
        conn.disconnect();
        return sb.toString();
    }

    // ============ 上传 ============
    /** 计算文件 MD5（123pan 的 etag 用） */
    private static String md5File(File f) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            FileInputStream in = new FileInputStream(f);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            in.close();
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            Log.e("PAN", "md5 fail: " + e);
            return "";
        }
    }

    /**
     * 原生上传单文件，完整走 123pan 流程：
     *  1) POST file/upload_request  获取预签名上传信息
     *  2) 据返回上传文件字节
     *  3) 结束/确认（如需要）
     * 结果通过 window.__onUploadDone(ok,msg) 回传前端。
     */
    private void uploadFile(final String localPath, final long parentFileId,
                            final String callback) {
        executor.execute(new Runnable() {
            @Override public void run() {
                String ok = "false", msg = "";
                try {
                    File f = new File(localPath);
                    if (!f.exists() || !f.isFile()) {
                        msg = "本地文件不可读：" + localPath;
                    } else {
                        long size = f.length();
                        String etag = md5File(f);
                        String fname = f.getName();
                        Log.d("PAN", "upload start: " + fname + " size=" + size + " etag=" + etag
                            + " parent=" + parentFileId);

                        String token = prefs.getString(KEY_TOKEN, "");
                        final String API = "https://api.123pan.cn";
                        StringBuilder log = new StringBuilder();
                        log.append("==== ").append(new java.util.Date()).append(" ====\nfile=").append(fname)
                          .append(" size=").append(size).append(" etag=").append(etag)
                          .append(" parent=").append(parentFileId)
                          .append(" token=").append(token.isEmpty() ? "(EMPTY)" : "(has)").append("\n");

                        // ============ 1) upload_request：获取上传任务 ============
                        // 根因：字段名必须为 fileName（大写N），否则 400 参数校验失败。
                        // 协议来自开源 123pan-uploader-cli（OlyMarco/123pan-uploader-cli）。
                        String upBody = "{\"driveId\":0,\"fileName\":\"" + json(fname)
                            + "\",\"etag\":\"" + etag
                            + "\",\"size\":" + size
                            + ",\"parentFileId\":" + parentFileId
                            + ",\"type\":0"
                            + ",\"duplicate\":0}";
                        Log.d("PAN", "[1]upload_request req body=" + upBody);
                        Log.d("PAN", "[1]DEBUG token=" + token);
                        String upResp = httpRequest("POST", API + "/b/api/file/upload_request", upBody, true);
                        Log.d("PAN", "[1]upload_request resp=" + upResp);
                        log.append("[1]upload_request: ").append(upResp).append("\n");
                        org.json.JSONObject upJson = new org.json.JSONObject(upResp);
                        if (upJson.optInt("code", -1) != 0) {
                            msg = "upload_request 失败: " + upJson.optString("message");
                            appendUploadLog(log.toString());
                            throw new IOException(msg);
                        }
                        org.json.JSONObject upData = upJson.getJSONObject("data");
                        String bucket = upData.optString("Bucket");
                        String storageNode = upData.optString("StorageNode");
                        String uploadKey = upData.optString("Key");
                        String uploadId = upData.optString("UploadId");
                        long fileId = upData.optLong("FileId", 0);
                        long sliceSize = upData.optLong("SliceSize", 5L * 1024 * 1024);
                        boolean reuse = upData.optBoolean("Reuse", false);
                        log.append("  bucket=").append(bucket).append(" node=").append(storageNode)
                           .append(" key=").append(uploadKey).append(" uploadId=").append(uploadId)
                           .append(" fileId=").append(fileId).append(" slice=").append(sliceSize)
                           .append(" reuse=").append(reuse).append("\n");

                        if (reuse) {
                            // 服务端已按 MD5 复用，无需实际上传
                            msg = "上传成功（云端已有相同内容，已秒传复用，fileId=" + fileId + "）";
                            ok = "true";
                            appendUploadLog(log.toString() + "REUSED\n");
                            throw new StopUpload(msg);
                        }
                        // ============ 2) 整对象直传（官方 Web 路径，决定性修复）============
                        // 根因（2026-08-31，Median Browser 抓包 + curl 复现确认）：
                        // App 此前实现的是"分片上传"路径
                        //   (upload_request -> s3_list_upload_parts 初始化 -> s3_repare_upload_parts_batch
                        //    -> PUT(UploadPart) -> list_parts -> s3_complete_multipart_upload -> upload_complete)。
                        // 但该分片路径在当前 123 云盘服务端仅返回 code:0（Location 空、文件不归档），
                        // 造成"提示上传成功但文件未落盘"的经典假成功。
                        // 官方 Web 端小文件实际走"整对象直传"路径，已实测真实落盘：
                        //   upload_request -> s3_upload_object/auth(整对象鉴权拿预签名PUT)
                        //   -> PUT(整对象, x-id=PutObject) -> upload_complete/v2(完成归档)
                        // 详见 /tmp/pan_whole_123pan_cn.sh 的可复现验证。
                        byte[] all = readBytes(f);
                        log.append("[2]whole-object size=").append(all.length)
                           .append(" key=").append(uploadKey).append(" bucket=").append(bucket).append("\n");

                        // 2a) 整对象上传鉴权：获取该对象的预签名 PUT URL（x-id=PutObject）
                        // 请求体精确对齐官方 Web（2026-08-31 hook 抓包权威确认）：
                        //   {bucket,key,partNumberStart:1,partNumberEnd:2,uploadId,StorageNode}
                        // 注：必须用小写 bucket/key/uploadId + 大写 StorageNode；
                        // 之前用大写 {Key,Bucket,FileId,...} 虽然偶发能返回 preSigned，
                        // 但非官方格式，故统一改为官方字段命名。
                        String authBody = "{\"bucket\":\"" + bucket
                            + "\",\"key\":\"" + uploadKey
                            + "\",\"partNumberStart\":1"
                            + ",\"partNumberEnd\":2"
                            + ",\"uploadId\":\"" + uploadId
                            + "\",\"StorageNode\":\"" + storageNode + "\"}";
                        Log.d("PAN", "[2]s3_upload_object/auth req body=" + authBody);
                        String authResp = httpRequest("POST",
                            API + "/b/api/file/s3_upload_object/auth", authBody, true);
                        Log.d("PAN", "[2]s3_upload_object/auth resp=" + authResp);
                        log.append("[2]s3_upload_object/auth: ").append(authResp).append("\n");
                        org.json.JSONObject authJson = new org.json.JSONObject(authResp);
                        if (authJson.optInt("code", -1) != 0) {
                            msg = "整对象上传鉴权失败: " + authJson.optString("message");
                            appendUploadLog(log.toString());
                            throw new IOException(msg);
                        }
                        org.json.JSONObject presigned = authJson.getJSONObject("data")
                            .getJSONObject("presignedUrls");
                        String putUrl = presigned.optString("1");
                        if (putUrl.isEmpty()) {
                            // 兼容 presignedUrls 只含单个键（非 "1"）的情况
                            java.util.Iterator<String> itu = presigned.keys();
                            while (itu.hasNext()) putUrl = presigned.optString(itu.next());
                        }
                        if (putUrl.isEmpty()) {
                            msg = "整对象预签名 URL 为空";
                            appendUploadLog(log.toString());
                            throw new IOException(msg);
                        }
                        log.append("[2]presigned PUT url=").append(putUrl).append("\n");

                        // 2b) PUT 整个对象到预签名 URL（x-id=PutObject 整对象直传）
                        // 与官方 Web 一致：整对象一次性 PUT，request body 即文件全部字节。
                        HttpURLConnection put = (HttpURLConnection) new URL(putUrl).openConnection();
                        put.setConnectTimeout(30000);
                        put.setReadTimeout(120000);
                        put.setRequestMethod("PUT");
                        put.setDoOutput(true);
                        put.setFixedLengthStreamingMode(all.length);
                        java.io.OutputStream pos = put.getOutputStream();
                        pos.write(all);
                        pos.flush();
                        pos.close();
                        int putCode = put.getResponseCode();
                        log.append("[2]PUT(whole) status=").append(putCode);
                        java.io.InputStream pis = putCode >= 400
                            ? put.getErrorStream() : put.getInputStream();
                        if (pis != null) {
                            log.append(" resp=").append(readText(pis));
                            pis.close();
                        }
                        log.append("\n");
                        if (putCode < 200 || putCode >= 300) {
                            msg = "整对象上传失败 HTTP " + putCode;
                            appendUploadLog(log.toString());
                            throw new IOException(msg);
                        }
                        Log.d("PAN", "upload object done (" + putCode + ")");

                        // 2c) 完成归档（官方 Web 用 /v2 端点，body 精确对齐官方 hook 抓包）
                        //   {fileId,bucket,fileSize,key,isMultipart:false,uploadId,StorageNode}
                        // isMultipart:false 标记整对象直传（而非分片），是真正归档的关键。
                        String closeBody = "{\"fileId\":" + fileId
                            + ",\"bucket\":\"" + bucket
                            + "\",\"fileSize\":" + size
                            + ",\"key\":\"" + uploadKey
                            + "\",\"isMultipart\":false"
                            + ",\"uploadId\":\"" + uploadId
                            + "\",\"StorageNode\":\"" + storageNode + "\"}";
                        Log.d("PAN", "[3]upload_complete/v2 req body=" + closeBody);
                        String closeResp = httpRequest("POST",
                            API + "/b/api/file/upload_complete/v2", closeBody, true);
                        Log.d("PAN", "[3]upload_complete/v2 resp=" + closeResp);
                        log.append("[3]upload_complete/v2: ").append(closeResp).append("\n");
                        org.json.JSONObject closeJson = new org.json.JSONObject(closeResp);
                        if (closeJson.optInt("code", -1) != 0) {
                            msg = "上传收尾失败: " + closeJson.optString("message");
                            appendUploadLog(log.toString());
                            throw new IOException(msg);
                        }
                        // 从 /v2 响应中解析最终落盘的 file_info.FileId，用于更准确的成功回执
                        org.json.JSONObject fin = closeJson.optJSONObject("data");
                        if (fin != null) {
                            org.json.JSONObject fileInfo = fin.optJSONObject("file_info");
                            if (fileInfo != null) {
                                long realFileId = fileInfo.optLong("FileId", fileId);
                                String realName = fileInfo.optString("FileName", fname);
                                log.append("[3]归档 fileId=").append(realFileId)
                                   .append(" name=").append(realName)
                                   .append(" parent=").append(fileInfo.optLong("ParentFileId", parentFileId))
                                   .append("\n");
                                fileId = realFileId;
                            }
                        }

                        msg = "上传成功：" + fname + "（" + (size / 1024) + "KB, fileId=" + fileId + "）";
                        ok = "true";
                        appendUploadLog(log.toString() + "==> SUCCESS: " + msg + "\n");
                    }
                } catch (StopUpload su) {
                    // 成功提前终止（如秒传复用），ok 已置 true，保留成功消息
                    msg = su.getMessage();
                } catch (Exception e) {
                    Log.e("PAN", "upload fail: " + e, e);
                    msg = e.getMessage();
                }
                final String fmsg = msg, fok = ok;
                handler.post(new Runnable() {
                    @Override public void run() {
                        if (webView != null) {
                            webView.evaluateJavascript(
                                "window.__onUploadDone&&window.__onUploadDone(" + fok + ","
                                + (fmsg == null ? "\"\"" : bindJson(json(fmsg == null ? "" : fmsg))) + ");", null);
                        }
                        if (!"false".equals(fok) || true) {
                            // 调试：上传在未完全打通时亦把响应打出来，便于核对协议
                            Log.d("PAN", "upload callback ok=" + fok + " msg=" + fmsg);
                        }
                    }
                });
            }
        });
    }

    // ============ 上传辅助方法 ============
    /** 成功时提前终止上传流程的控制流异常（带成功消息），不走错误 catch。 */
    static class StopUpload extends RuntimeException {
        StopUpload(String msg) { super(msg); }
    }

    /** 读取本地文件全部字节（小文件直读，用于分片上传）。 */
    private byte[] readBytes(File f) throws IOException {
        FileInputStream fis = new FileInputStream(f);
        try {
            byte[] buf = new byte[(int) f.length()];
            int off = 0, n;
            while (off < buf.length && (n = fis.read(buf, off, buf.length - off)) != -1) {
                off += n;
            }
            return buf;
        } finally {
            fis.close();
        }
    }

    /** 将响应流读取为文本（UTF-8），用于预签名上传的 PUT 响应体。 */
    private String readText(InputStream in) throws IOException {
        if (in == null) return "";
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line);
        r.close();
        return sb.length() > 300 ? sb.substring(0, 300) : sb.toString();
    }

    /** 将上传过程的完整日志追加写入公共 Downloads（MediaStore，兼容 Android 11+）。 */
    private void appendUploadLog(String content) {
        try {
            android.content.ContentValues cv = new android.content.ContentValues();
            cv.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "pan_upload_result.txt");
            cv.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain");
            cv.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            android.net.Uri uri = getContentResolver().insert(
                android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri != null) {
                java.io.OutputStream os = getContentResolver().openOutputStream(uri, "wa");
                os.write(content.getBytes("UTF-8"));
                os.write("\n----------------------------------------\n".getBytes("UTF-8"));
                os.close();
            }
        } catch (Exception e) {
            Log.e("PAN", "write upload result fail: " + e);
        }
    }

    // 计算应用缓存大小（cacheDir + filesDir），返回字节数
    private long calcCacheSize() {
        long total = 0;
        try { total += dirSize(getCacheDir()); } catch (Exception ignored) {}
        try { total += dirSize(getFilesDir()); } catch (Exception ignored) {}
        return total;
    }
    private long dirSize(File dir) {
        if (dir == null || !dir.exists()) return 0;
        long sum = 0;
        File[] fs = dir.listFiles();
        if (fs != null) {
            for (File f : fs) {
                if (f.isDirectory()) sum += dirSize(f);
                else sum += f.length();
            }
        }
        return sum;
    }
    // 清除应用缓存：WebView 缓存 + 应用私有缓存目录 + Cookie
    private void clearAppCache() {
        handler.post(new Runnable() {
            @Override public void run() {
                try { if (webView != null) webView.clearCache(true); } catch (Exception ignored) {}
                try { CookieManager.getInstance().removeAllCookies(null); } catch (Exception ignored) {}
            }
        });
        try { deleteDir(new File(getCacheDir(), "http")); } catch (Exception ignored) {}
        try { deleteChildren(getCacheDir()); } catch (Exception ignored) {}
        try { deleteDir(new File(getFilesDir(), "cache")); } catch (Exception ignored) {}
        try { deleteDir(new File(getFilesDir(), "app_webview")); } catch (Exception ignored) {}
    }
    private void deleteChildren(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) deleteDir(f);
    }
    private void deleteDir(File dir) {
        if (dir == null || !dir.exists()) return;
        if (dir.isDirectory()) {
            File[] fs = dir.listFiles();
            if (fs != null) for (File f : fs) deleteDir(f);
        }
        dir.delete();
    }

    // ============ 官方登录（主 WebView 直接显示官方登录页） ============

    /** 在官方登录页捕获 sso-token，成功则保存会话并切回本地 SPA 主界面。 */
    private void tryCaptureSsoTokenFromMain() {
        try {
            if (officialLoginDone) return;
            String sso = null;
            String[] domains = {
                "https://user.123pan.cn",
                "https://yun.123pan.cn",
                "https://www.123pan.cn",
                "https://123pan.cn"
            };
            for (String d : domains) {
                String cookies = CookieManager.getInstance().getCookie(d);
                if (cookies == null || cookies.isEmpty()) continue;
                for (String kv : cookies.split(";")) {
                    kv = kv.trim();
                    if (kv.startsWith("sso-token=")) { sso = kv.substring("sso-token=".length()); break; }
                }
                if (sso != null && !sso.isEmpty()) break;
            }
            if (sso == null || sso.isEmpty()) return;
            officialLoginDone = true;
            String username = extractUsernameFromSso(sso);
            prefs.edit()
                .putString(KEY_TOKEN, sso)
                .putString(KEY_USER, username)
                .putString("loginuuid", loginuuid)
                .apply();
            Log.d("PAN", "official login captured sso-token, user=" + username);
            // 切回本地 SPA 主界面；onPageFinished 会注入 __restoreSession 恢复会话
            handler.post(() -> {
                if (webView != null) {
                    webView.loadUrl("file:///android_asset/index.html");
                }
            });
        } catch (Exception e) {
            Log.e("PAN", "capture sso-token fail: " + e);
        }
    }

    /** 从 sso-token(JWT) 中提取 username（payload 段 base64url 解码后取 username 字段）。 */
    private String extractUsernameFromSso(String sso) {
        try {
            String[] parts = sso.split("\\.");
            if (parts.length >= 2) {
                String payload = parts[1];
                // base64url -> base64
                String b64 = payload.replace('-', '+').replace('_', '/');
                while (b64.length() % 4 != 0) b64 += "=";
                byte[] decoded = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
                String jsonStr = new String(decoded, java.nio.charset.StandardCharsets.UTF_8);
                // 简单提取 username 字段
                String key = "\"username\":\"";
                int idx = jsonStr.indexOf(key);
                if (idx >= 0) {
                    int start = idx + key.length();
                    int end = jsonStr.indexOf("\"", start);
                    if (end > start) return jsonStr.substring(start, end);
                }
            }
        } catch (Exception ignored) { }
        return "";
    }

    /** dp 换算为 px。 */
    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    // ============ JS 桥 ============
    static class NativeBridge {
        private final MainActivity act;
        NativeBridge(MainActivity a) { this.act = a; }

        @JavascriptInterface
        public void toast(final String msg) { act.toast(msg); }

        @JavascriptInterface
        public void apiRequest(final String callback, final String method,
                               final String url, final String body, final boolean withAuth) {
            act.doApi(callback, method, url, body, withAuth);
        }

        @JavascriptInterface
        public void saveSession(final String token, final String user, final String pass) {
            act.prefs.edit()
                .putString(KEY_TOKEN, token)
                .putString(KEY_USER, user)
                .putString(KEY_PASS, pass)
                .putString("loginuuid", act.loginuuid)
                .apply();
        }

        @JavascriptInterface
        public String loadToken() {
            return act.prefs.getString(KEY_TOKEN, "");
        }

        @JavascriptInterface
        public void clearSession() {
            act.prefs.edit().remove(KEY_TOKEN).remove(KEY_USER).remove(KEY_PASS).apply();
        }

        // 退出当前账号：清除本地会话 + WebView 官方域 cookie（含 sso-token），并回到官方登录页
        @JavascriptInterface
        public void logout() {
            act.prefs.edit().remove(KEY_TOKEN).remove(KEY_USER).remove(KEY_PASS).apply();
            act.handler.post(() -> {
                if (act.webView != null) {
                    try {
                        android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
                        cm.removeAllCookies(null);
                        cm.flush();
                    } catch (Exception ignored) {}
                    act.officialLoginDone = false;
                    act.webView.loadUrl(OFFICIAL_LOGIN_URL);
                }
            });
        }

        // 重新打开官方登录页（前端兜底：登录页异常时再次进入官方登录）
        @JavascriptInterface
        public void openOfficialLogin() {
            act.handler.post(() -> {
                if (act.webView != null) {
                    act.officialLoginDone = false;
                    act.webView.loadUrl(OFFICIAL_LOGIN_URL);
                }
            });
        }

        @JavascriptInterface
        public String getVersion() { return "1.7.0"; }

        @JavascriptInterface
        public String getLoginuuid() { return act.loginuuid; }

        @JavascriptInterface
        public long download(final String url, final String filename) {
            // @JavascriptInterface 方法在 UI 线程调用，同步发起下载即可返回真实 id
            return act.downloadViaManager(url, filename);
        }

        // 自研流式下载（带认证头 + 严格字节校验），返回任务 id（>=900000000；失败 -1）
        @JavascriptInterface
        public long downloadStream(final String url, final String filename, final long expectedSize) {
            return act.downloadStream(url, filename, expectedSize);
        }

        // 自研流式下载任务进度
        @JavascriptInterface
        public String streamingTasks() {
            return act.streamingTasksJson();
        }

        @JavascriptInterface
        public String queryDownloads() {
            return act.queryDownloadsJson();
        }

        @JavascriptInterface
        public void uploadFiles(final String localPath, final long parentFileId,
                                final String callback) {
            act.uploadFile(localPath, parentFileId, callback);
        }

        @JavascriptInterface
        public void openFile(final String name) {
            act.openDownloadedFile(name);
        }

        @JavascriptInterface
        public void exitApp() {
            act.handler.post(new Runnable() {
                @Override public void run() {
                    act.finish();
                }
            });
        }

        @JavascriptInterface
        public long getCacheSize() { return act.calcCacheSize(); }

        @JavascriptInterface
        public void clearCache() { act.clearAppCache(); }
    }
}
