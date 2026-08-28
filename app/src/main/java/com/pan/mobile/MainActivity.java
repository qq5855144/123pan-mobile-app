package com.pan.mobile;

import android.annotation.SuppressLint;
import android.app.Activity;
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
import android.widget.Toast;

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
    private String baseHeaders =
        "platform=android;app-version=61;x-app-version=2.4.0;user-agent=123pan/v2.4.0("
        + osVersion + ";Xiaomi)";

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
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

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
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // 注入持久化登录态
                String token = prefs.getString(KEY_TOKEN, "");
                String user = prefs.getString(KEY_USER, "");
                if (!token.isEmpty()) {
                    view.evaluateJavascript(
                        "window.__restoreSession&&window.__restoreSession("
                        + bindJson(json(token)) + "," + bindJson(json(user)) + ");", null);
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

        // 加载内嵌本地移动端 SPA
        webView.loadUrl("file:///android_asset/index.html");
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

    // 打开已下载到系统下载目录的文件：apk 直接唤醒系统安装程序，其它走系统"打开方式"
    public void openDownloadedFile(String name) {
        try {
            String fname = sanitizeFileName(name);
            String dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath();
            File f = new File(dir, fname);
            if (!f.exists()) { toast("文件不存在：" + (fname == null ? "" : fname)); return; }
            String contentUri = "content://com.pan.mobile.pan/file?path=" + Uri.encode(f.getAbsolutePath());
            Uri cu = Uri.parse(contentUri);
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
                    return;
                } catch (Exception e) {
                    Log.e("PAN", "install direct fail -> chooser: " + e, e);
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
                Log.d("PAN", "api resp: " + method + " " + url + " -> "
                    + (result != null && result.length() > 200 ? result.substring(0, 200) : result));
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

                        // ============ 2) 切分文件并上传分片 ============
                        // 把文件读入内存分片（对小文件直接读全量），按 sliceSize 切分。
                        byte[] all = readBytes(f);
                        int partCount = (int) ((all.length + sliceSize - 1) / sliceSize);
                        if (partCount < 1) partCount = 1;
                        log.append("[2]partCount=").append(partCount).append(" per=").append(sliceSize).append("\n");

                        for (int pi = 1; pi <= partCount; pi++) {
                            // 2a) 获取该分片的预签名上传 URL
                            // partNumberEnd 须为 partNumberStart+1（参考 123pan-uploader-cli），
                            // 传相等/逆序区间会被服务端判为"非法请求"。
                            String prepBody = "{\"bucket\":\"" + bucket
                                + "\",\"key\":\"" + uploadKey
                                + "\",\"partNumberEnd\":" + (pi + 1)
                                + ",\"partNumberStart\":" + pi
                                + ",\"uploadId\":\"" + uploadId
                                + "\",\"StorageNode\":\"" + storageNode + "\"}";
                            Log.d("PAN", "[2]prepare req body=" + prepBody);
                            String prepResp = httpRequest("POST",
                                API + "/b/api/file/s3_repare_upload_parts_batch", prepBody, true);
                            Log.d("PAN", "[2]prepare resp=" + prepResp);
                            log.append("[2.").append(pi).append("]prepare: ").append(prepResp).append("\n");
                            org.json.JSONObject prepJson = new org.json.JSONObject(prepResp);
                            if (prepJson.optInt("code", -1) != 0) {
                                msg = "分片 " + pi + " 获取预签名地址失败: " + prepJson.optString("message");
                                appendUploadLog(log.toString());
                                throw new IOException(msg);
                            }
                            org.json.JSONObject urls = prepJson.getJSONObject("data")
                                .getJSONObject("presignedUrls");
                            String putUrl = urls.optString(String.valueOf(pi));

                            // 2b) 计算本分片偏移与长度
                            int start = (int) ((pi - 1) * sliceSize);
                            int len = Math.min((int) (all.length - start), (int) sliceSize);
                            if (len < 0) len = 0;

                            // 2c) PUT 上传字节到预签名 URL（二进制直传）
                            HttpURLConnection put = (HttpURLConnection) new URL(putUrl).openConnection();
                            put.setConnectTimeout(30000);
                            put.setReadTimeout(60000);
                            put.setRequestMethod("PUT");
                            put.setDoOutput(true);
                            put.setFixedLengthStreamingMode(len);
                            java.io.OutputStream pos = put.getOutputStream();
                            pos.write(all, start, len);
                            pos.flush();
                            pos.close();
                            int putCode = put.getResponseCode();
                            log.append("[2.").append(pi).append("]PUT status=").append(putCode);
                            java.io.InputStream pis = putCode >= 400
                                ? put.getErrorStream() : put.getInputStream();
                            if (pis != null) {
                                log.append(" resp=").append(readText(pis));
                                pis.close();
                            }
                            log.append("\n");
                            if (putCode < 200 || putCode >= 300) {
                                msg = "分片 " + pi + " 上传失败 HTTP " + putCode;
                                appendUploadLog(log.toString());
                                throw new IOException(msg);
                            }
                            Log.d("PAN", "upload part " + pi + "/" + partCount + " done (" + putCode + ")");
                        }

                        // ============ 3) 确认已上传分片 ============
                        // 注意：s3_list_upload_parts / s3_complete_multipart_upload 的
                        // storageNode 字段用小写（对照 123pan-uploader-cli），用大写会
                        // 被服务端静默忽略，导致 complete 看似成功但文件未真正归档。
                        String listPartsBody = "{\"bucket\":\"" + bucket
                            + "\",\"key\":\"" + uploadKey
                            + "\",\"uploadId\":\"" + uploadId
                            + "\",\"storageNode\":\"" + storageNode + "\"}";
                        Log.d("PAN", "[3]list_parts req body=" + listPartsBody);
                        String listPartsResp = httpRequest("POST",
                            API + "/b/api/file/s3_list_upload_parts", listPartsBody, true);
                        Log.d("PAN", "[3]list_parts resp=" + listPartsResp);
                        log.append("[3]list_parts: ").append(listPartsResp).append("\n");

                        // ============ 4) 完成多部分上传 ============
                        // 复刻 123pan-open 官方协议：complete 仅需 {bucket,key,uploadId,storageNode} 4 字段，
                        // 无需带 parts 列表（OlyMarco/123pan-uploader-cli 及 curl web 实测均以此成功）。
                        String compBody = "{\"bucket\":\"" + bucket
                            + "\",\"key\":\"" + uploadKey
                            + "\",\"uploadId\":\"" + uploadId
                            + "\",\"storageNode\":\"" + storageNode + "\"}";
                        Log.d("PAN", "[4]complete req body=" + compBody);
                        String compResp = httpRequest("POST",
                            API + "/b/api/file/s3_complete_multipart_upload", compBody, true);
                        Log.d("PAN", "[4]complete resp=" + compResp);
                        log.append("[4]complete_multipart: ").append(compResp).append("\n");
                        org.json.JSONObject compJson = new org.json.JSONObject(compResp);
                        if (compJson.optInt("code", -1) != 0) {
                            msg = "完成上传失败: " + compJson.optString("message");
                            appendUploadLog(log.toString());
                            throw new IOException(msg);
                        }

                        // ============ 5) 关闭上传会话 ============
                        String closeBody = "{\"fileId\":" + fileId + "}";
                        Log.d("PAN", "[5]upload_complete req body=" + closeBody);
                        String closeResp = httpRequest("POST",
                            API + "/b/api/file/upload_complete", closeBody, true);
                        Log.d("PAN", "[5]upload_complete resp=" + closeResp);
                        log.append("[5]upload_complete: ").append(closeResp).append("\n");
                        org.json.JSONObject closeJson = new org.json.JSONObject(closeResp);
                        if (closeJson.optInt("code", -1) != 0) {
                            msg = "上传收尾失败: " + closeJson.optString("message");
                            appendUploadLog(log.toString());
                            throw new IOException(msg);
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

        @JavascriptInterface
        public String getVersion() { return "1.7.0"; }

        @JavascriptInterface
        public String getLoginuuid() { return act.loginuuid; }

        @JavascriptInterface
        public long download(final String url, final String filename) {
            // @JavascriptInterface 方法在 UI 线程调用，同步发起下载即可返回真实 id
            return act.downloadViaManager(url, filename);
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
    }
}
