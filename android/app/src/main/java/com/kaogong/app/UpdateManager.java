package com.kaogong.app;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * 题库在线更新器。
 *
 * 流程：拉 manifest.json（多源轮询）→ 比对 tiku.dbVersion → 下载 gz/db
 *      → sha256 校验 → 调 NativeDbBridge.replaceTiku() 原子替换 → 重开连接。
 *
 * 线程模型：start() 起后台线程；前端轮询 status() 取进度（不阻塞 JS）。
 * 网络：只用 HttpURLConnection，不引入第三方库。
 */
public class UpdateManager {

    private static final String TAG = "ShanganUpdate";

    private final Context context;
    private final NativeDbBridge db;

    // ---- 状态（volatile，后台线程写、JS 线程读）----
    private volatile String state = "idle";   // idle|checking|downloading|verifying|applying|done|error|uptodate
    private volatile int progress = 0;
    private volatile String message = "";
    private volatile String remoteVersion = "";
    private volatile String localVersion = "";
    private volatile long downloaded = 0;
    private volatile long total = 0;
    private volatile String pendingPath = "";
    /** 待应用的是增量包还是全量库 */
    private volatile boolean pendingIsDelta = false;      // 下载完成待应用的临时文件
    private volatile boolean newAppAvailable = false;
    private volatile String newAppVersion = "";
    private volatile String newAppUrl = "";
    private volatile String newAppSha = "";
    private volatile long newAppSize = 0;
    private volatile int appProgress = 0;
    private volatile long appDownloaded = 0;
    private volatile long appTotal = 0;
    private volatile String appState = "idle";   // idle|downloading|done|error

    private volatile Thread worker;

    public UpdateManager(Context context, NativeDbBridge db) {
        this.context = context;
        this.db = db;
    }

    private void set(String s, int p, String msg) {
        state = s;
        progress = p;
        message = msg == null ? "" : msg;
        Log.i(TAG, s + " " + p + "% " + message);
        // 终态统一收掉前台服务和通知栏，别让通知一直挂着
        if ("done".equals(s) || "error".equals(s) || "uptodate".equals(s) || "idle".equals(s)) {
            DownloadService.stop();
        }
    }

    /**
     * 进度推到通知栏。分块下载每读 128KB 就会调一次，必须按百分点节流，
     * 否则通知栏每秒刷新几十次。
     */
    private volatile int notiPct = -1;

    private void notifyProgress(int pct, String text) {
        if (pct == notiPct) return;
        notiPct = pct;
        DownloadService.update(pct, text);
    }

    /**
     * Android 13+ 通知要运行时授权，没授权时前台服务的通知不显示（服务本身照跑）。
     * 从 Activity 弹一次系统对话框，用户点了以后每次下载都有进度条可看。
     */
    private void ensureNotifPermission() {
        try {
            if (android.os.Build.VERSION.SDK_INT < 33) return;
            if (!(context instanceof android.app.Activity)) return;
            final android.app.Activity a = (android.app.Activity) context;
            if (a.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    == android.content.pm.PackageManager.PERMISSION_GRANTED) return;
            a.runOnUiThread(new Runnable() {
                @Override public void run() {
                    try {
                        a.requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 9001);
                    } catch (Throwable ignored) { }
                }
            });
        } catch (Throwable ignored) { }
    }

    /** 开始检查 + 下载（异步）。manifestUrlsJson 是字符串数组的 JSON。 */
    @android.webkit.JavascriptInterface
    public void start(String manifestUrlsJson) {
        if (worker != null && worker.isAlive()) {
            message = "已有更新任务在进行";
            return;
        }
        final List<String> urls = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(manifestUrlsJson);
            for (int i = 0; i < arr.length(); i++) {
                String u = arr.optString(i, "");
                if (!u.isEmpty()) urls.add(u);
            }
        } catch (Throwable t) {
            set("error", 0, "manifest 地址解析失败：" + t.getMessage());
            return;
        }
        if (urls.isEmpty()) { set("error", 0, "没有配置更新源"); return; }

        worker = new Thread(() -> run(urls), "tiku-update");
        worker.start();
    }

    private void run(List<String> urls) {
        try {
            set("checking", 0, "正在检查更新…");
            localVersion = db.getDbVersion();
            JSONObject manifest = null;
            String lastErr = "";
            for (String u : urls) {
                try {
                    manifest = fetchJson(u);
                    if (manifest != null) break;
                } catch (Throwable t) {
                    lastErr = t.getMessage();
                    Log.w(TAG, "manifest 源失败 " + u + " : " + lastErr);
                }
            }
            if (manifest == null) { set("error", 0, "所有更新源都不可达：" + lastErr); return; }

            JSONObject tiku = manifest.optJSONObject("tiku");
            if (tiku == null) { set("error", 0, "manifest 缺 tiku 段"); return; }
            remoteVersion = tiku.optString("dbVersion", "");

            JSONObject app = manifest.optJSONObject("app");
            if (app != null) {
                int remoteCode = app.optInt("versionCode", 0);
                int localCode = currentVersionCode();
                if (remoteCode > localCode) {
                    newAppAvailable = true;
                    newAppVersion = app.optString("versionName", app.optString("version", ""));
                    newAppUrl = app.optString("url", "");
                    newAppSha = app.optString("sha256", "");
                    newAppSize = app.optLong("size", 0);
                }
            }

            if (remoteVersion.isEmpty()) { set("error", 0, "manifest 没有 dbVersion"); return; }
            if (!remoteVersion.equals(localVersion) && remoteVersion.compareTo(localVersion) < 0) {
                set("uptodate", 100, "本地题库版本更新（" + localVersion + "），无需更新");
                return;
            }
            if (remoteVersion.equals(localVersion)) {
                set("uptodate", 100, "已是最新题库 " + localVersion
                        + (newAppAvailable ? "；发现新版 App " + newAppVersion : ""));
                return;
            }

            // 优先增量：只下变化的那几 MB，全量要 80MB+
            JSONArray deltas = tiku.optJSONArray("delta");
            JSONObject deltaEntry = null;
            if (deltas != null) {
                for (int i = 0; i < deltas.length(); i++) {
                    JSONObject d = deltas.optJSONObject(i);
                    if (d != null && localVersion.equals(d.optString("from", ""))) { deltaEntry = d; break; }
                }
            }
            if (deltaEntry != null && !deltaEntry.optString("url", "").isEmpty()) {
                String dUrl = deltaEntry.optString("url", "");
                String dSha = deltaEntry.optString("sha256", "");
                long dSize = deltaEntry.optLong("size", 0);
                File dDir = new File(context.getFilesDir(), "db");
                if (!dDir.exists() && !dDir.mkdirs()) { set("error", 0, "无法创建目录"); return; }
                File dTmp = new File(dDir, "tiku_delta.json.gz");
                total = dSize;
                notiPct = -1;
                ensureNotifPermission();
                DownloadService.start(context, "正在下载题库更新");
                set("downloading", 0, "开始下载增量 " + humanSize(dSize));
                String dGot = download(dUrl, dTmp, dSha, dSize);
                if (dGot == null) return;
                set("verifying", 100, "校验中…");
                if (!dSha.isEmpty() && !dSha.equalsIgnoreCase(dGot)) {
                    dTmp.delete();
                    set("error", 0, "增量包校验失败，已丢弃");
                    return;
                }
                pendingPath = dTmp.getAbsolutePath();
                pendingIsDelta = true;
                set("done", 100, "增量包就绪 " + humanSize(dTmp.length()) + "，待应用");
                return;
            }

            // 没有可用增量 → 下载全量
            JSONObject full = tiku.optJSONObject("full");
            if (full == null) { set("error", 0, "manifest 缺 full 段"); return; }
            String url = full.optString("url", "");
            String sha = full.optString("sha256", "");
            total = full.optLong("size", 0);
            if (url.isEmpty()) { set("error", 0, "full.url 为空"); return; }

            File dir = new File(context.getFilesDir(), "db");
            if (!dir.exists() && !dir.mkdirs()) { set("error", 0, "无法创建目录"); return; }
            File tmp = new File(dir, "tiku_app.db.download");

            notiPct = -1;
            ensureNotifPermission();
            DownloadService.start(context, "正在下载题库");
            set("downloading", 0, "开始下载 " + humanSize(total));
            String gotSha = download(url, tmp, sha, total);
            if (gotSha == null) return;   // download() 内已 set error

            set("verifying", 100, "校验中…");
            if (!sha.isEmpty() && !sha.equalsIgnoreCase(gotSha)) {
                tmp.delete();
                set("error", 0, "sha256 不匹配，已丢弃（期望 " + shortSha(sha) + " 实际 " + shortSha(gotSha) + "）");
                return;
            }
            pendingPath = tmp.getAbsolutePath();
            set("done", 100, "下载完成 " + humanSize(tmp.length()) + "，待应用");
        } catch (Throwable t) {
            set("error", progress, "更新失败：" + t.getMessage());
        }
    }

    /** 应用已下载的题库（由用户点"立即更新"触发）。 */
    @android.webkit.JavascriptInterface
    public String applyPending() {
        if (pendingPath.isEmpty()) return "error: 没有待应用的更新";
        set("applying", 100, "正在替换题库…");
        try {
            String r;
            if (pendingIsDelta) {
                set("applying", 100, "正在应用增量…");
                r = db.applyDelta(pendingPath, remoteVersion);
            } else {
                r = db.replaceTiku(pendingPath, remoteVersion);
            }
            if (r != null && r.startsWith("error")) { set("error", 100, r); return r; }
            localVersion = remoteVersion;
            pendingPath = "";
            pendingIsDelta = false;
            set("done", 100, "题库已更新到 " + remoteVersion + "，重开 App 生效");
            return "ok";
        } catch (Throwable t) {
            set("error", 100, "替换失败：" + t.getMessage());
            return "error: " + t.getMessage();
        }
    }

    @android.webkit.JavascriptInterface
    public String status() {
        try {
            JSONObject o = new JSONObject();
            o.put("state", state);
            o.put("progress", progress);
            o.put("message", message);
            o.put("remoteVersion", remoteVersion);
            o.put("localVersion", localVersion);
            o.put("downloaded", downloaded);
            o.put("total", total);
            o.put("hasPending", !pendingPath.isEmpty());
            o.put("newAppAvailable", newAppAvailable);
            o.put("newAppVersion", newAppVersion);
            o.put("newAppSize", newAppSize);
            o.put("currentVersionName", appVersionName());
            o.put("newAppState", appState);
            o.put("newAppProgress", appProgress);
            o.put("newAppDownloaded", appDownloaded);
            o.put("newAppTotal", appTotal);
            return o.toString();
        } catch (Throwable t) {
            return "{\"state\":\"error\",\"message\":\"" + t.getMessage() + "\"}";
        }
    }

    @android.webkit.JavascriptInterface
    public void reset() {
        set("idle", 0, "");
        remoteVersion = "";
        downloaded = 0;
        total = 0;
        newAppAvailable = false;
        newAppVersion = "";
        appState = "idle";
        appProgress = 0;
        appDownloaded = 0;
        appTotal = 0;
    }

    // ---------------- 内部工具 ----------------

    private JSONObject fetchJson(String url) throws Exception {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream()) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return new JSONObject(bo.toString("UTF-8"));
        } finally {
            c.disconnect();
        }
    }

    /** 下载并返回 sha256；失败返回 null（已 set error）。 */
    /* ---------- 下载：多线程分块 ---------- */
    /*
     * gh-proxy 这类公共反代对单连接限速很死（实测 0.36 MB/s），但不限单 IP 总带宽：
     * 同时开 6 个 Range 请求能跑到 5 MB/s，十几倍。所以大文件切成 N 段并发下，
     * 每段落到 dest.partN，最后按序拼起来（gz 边拼边解压）。
     * 任何一个分段拿不到 206 / 长度对不上 → 整体回退单流，不影响正确性。
     */

    private static final int DL_THREADS = 16;

    /** 界面可见的已下载字节，只增不减（块重试会回滚实际字节，但进度条不该倒退） */
    private volatile long shown = 0;
    private static final long DL_PARALLEL_MIN = 2L * 1024 * 1024;   // 小于 2MB 单流就够

    private String download(String url, File dest, String expectSha, long sizeHint) {
        HttpURLConnection c = null;
        try {
            boolean isGz = url.endsWith(".gz");
            long size = sizeHint > 0 ? sizeHint : remoteSize(url);

            File[] parts = null;
            if (size >= DL_PARALLEL_MIN) {
                total = size;
                downloaded = 0;
                shown = 0;
                parts = fetchParts(url, size, dest.getParentFile(), dest.getName() + "." + size + ".part", false);
            }
            if (parts != null) {
                if (!assemble(parts, dest, isGz)) throw new java.io.IOException("分块合并失败");
            } else {
                c = open(url);
                long len = c.getContentLengthLong();
                if (len > 0) total = len;
                java.util.zip.GZIPInputStream gz = null;
                InputStream in = c.getInputStream();
                if (isGz) { gz = new java.util.zip.GZIPInputStream(in, 65536); in = gz; }
                FileOutputStream out = new FileOutputStream(dest);
                byte[] buf = new byte[256 * 1024];
                int n;
                long got = 0;
                int lastPct = -1;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    got += n;
                    downloaded = got;
                    if (total > 0) {
                        int pct = (int) Math.min(99, got * 100 / total);
                        if (pct != lastPct) {
                            lastPct = pct;
                            progress = pct;
                            message = "下载中 " + pct + "%  " + humanSize(got) + " / " + humanSize(total);
                            notifyProgress(pct, humanSize(got) + " / " + humanSize(total));
                        }
                    }
                }
                out.close();
                in.close();
            }

            // 校验的是"解压后"的库文件
            return sha256Of(dest);
        } catch (Throwable t) {
            if (dest.exists()) dest.delete();
            set("error", progress, "下载失败：" + t.getMessage());
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private String sha256Of(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new java.io.FileInputStream(f)) {
            byte[] b = new byte[256 * 1024];
            int k;
            while ((k = in.read(b)) > 0) md.update(b, 0, k);
        }
        return hex(md.digest());
    }

    /** HEAD 取远端长度，失败返回 -1 */
    private long remoteSize(String url) {
        HttpURLConnection c = null;
        try {
            c = open(url);
            c.setRequestMethod("HEAD");
            c.connect();
            return c.getContentLengthLong();
        } catch (Throwable t) {
            return -1;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 分块并行下载到 dest.partN。全部成功才返回，否则清理并返回 null（调用方回退单流） */
    /**
     * 分块并行下载到 dest.partN。
     *
     * 不是"按线程数切几大段"，而是切成 2MB 的小块、8 个 worker 抢队列 ——
     * 因为 gh-proxy 的长连接跑到十几 MB 后会被单独限速（实测后半程掉到 0.2MB/s），
     * 每块走新连接反而快。单块失败重试 3 次；任一块最终失败整体返回 null，调用方回退单流。
     */
    private File[] fetchParts(String url, long size, File dir, String prefix, final boolean forApp) {
        final long CHUNK = 4L * 1024 * 1024;
        final int blocks = (int) ((size + CHUNK - 1) / CHUNK);
        if (blocks < 2) return null;
        final int workers = Math.min(DL_THREADS, blocks);
        final File[] parts = new File[blocks];
        for (int i = 0; i < blocks; i++) parts[i] = new File(dir, prefix + i);

        final long fTotal = size;
        final java.util.concurrent.atomic.AtomicLong done = new java.util.concurrent.atomic.AtomicLong(0);
        final java.util.concurrent.atomic.AtomicInteger next = new java.util.concurrent.atomic.AtomicInteger(0);
        final java.util.concurrent.atomic.AtomicBoolean bad = new java.util.concurrent.atomic.AtomicBoolean(false);
        Thread[] ts = new Thread[workers];
        for (int i = 0; i < workers; i++) {
            ts[i] = new Thread(new Runnable() {
                @Override public void run() {
                    int b;
                    while (!bad.get() && (b = next.getAndIncrement()) < blocks) {
                        long from = b * CHUNK;
                        long to = Math.min(size - 1, from + CHUNK - 1);
                        long need = to - from + 1;
                        // 上次跑到一半被杀（切后台/进程回收），这块已经落好了就直接续上
                        if (parts[b].exists() && parts[b].length() == need) {
                            long d = done.addAndGet(need);
                            if (d > shown) shown = d;
                            int pct = (int) Math.min(99, shown * 100 / Math.max(1, fTotal));
                            if (forApp) { appProgress = pct; appDownloaded = shown; appTotal = fTotal; }
                            else { progress = pct; downloaded = shown; }
                            notifyProgress(pct, humanSize(shown) + " / " + humanSize(fTotal));
                            continue;
                        }
                        boolean ok = false;
                        for (int attempt = 0; attempt < 3 && !ok && !bad.get(); attempt++) {
                            ok = fetchBlock(url, from, to, parts[b], done, fTotal, forApp, workers);
                        }
                        if (!ok) { bad.set(true); return; }
                    }
                }
            });
            ts[i].start();
        }
        for (Thread t : ts) { try { t.join(); } catch (InterruptedException ignored) { } }

        boolean ok = !bad.get();
        if (ok) {
            for (int b = 0; b < blocks; b++) {
                long from = b * CHUNK;
                long to = Math.min(size - 1, from + CHUNK - 1);
                if (parts[b] == null || parts[b].length() != (to - from + 1)) { ok = false; break; }
            }
        }
        if (!ok) { for (File f : parts) { if (f != null) f.delete(); } return null; }
        return parts;
    }

    /** 下一块。成功返回 true；失败时把已下载的字节从计数里扣回去，等重试 */
    private boolean fetchBlock(String url, long from, long to, File target,
                               java.util.concurrent.atomic.AtomicLong done, long fTotal, boolean forApp, int workers) {
        HttpURLConnection hc = null;
        FileOutputStream out = null;
        InputStream in = null;
        long wrote = 0;
        try {
            hc = open(url);
            hc.setRequestProperty("Range", "bytes=" + from + "-" + to);
            int code = hc.getResponseCode();
            if (code != 206) return false;
            in = hc.getInputStream();
            out = new FileOutputStream(target);
            byte[] buf = new byte[128 * 1024];
            int k;
            while ((k = in.read(buf)) > 0) {
                out.write(buf, 0, k);
                wrote += k;
                long d = done.addAndGet(k);
                if (d > shown) shown = d;
                int pct = (int) Math.min(99, shown * 100 / Math.max(1, fTotal));
                if (forApp) {
                    appProgress = pct;
                    appDownloaded = shown;
                    appTotal = fTotal;
                    notifyProgress(pct, humanSize(shown) + " / " + humanSize(fTotal) + "（" + workers + " 线程）");
                } else {
                    progress = pct;
                    downloaded = shown;
                    message = "下载中 " + pct + "%  " + humanSize(shown) + " / " + humanSize(fTotal) + "（" + workers + " 线程）";
                    notifyProgress(pct, humanSize(shown) + " / " + humanSize(fTotal) + "（" + workers + " 线程）");
                }
            }
            return target.length() == (to - from + 1);
        } catch (Throwable t) {
            return false;
        } finally {
            done.addAndGet(-wrote);
            try { if (out != null) out.close(); } catch (Throwable ignored) { }
            try { if (in != null) in.close(); } catch (Throwable ignored) { }
            if (hc != null) hc.disconnect();
        }
    }

    /** 按序拼接分块；gz 的话边拼边解压。无论成败都会清掉分块文件 */
    private boolean assemble(File[] parts, File dest, boolean isGz) {
        List<InputStream> list = new ArrayList<InputStream>();
        FileOutputStream out = null;
        InputStream in = null;
        try {
            for (File f : parts) list.add(new java.io.BufferedInputStream(new java.io.FileInputStream(f), 128 * 1024));
            InputStream seq = new java.io.SequenceInputStream(java.util.Collections.enumeration(list));
            in = isGz ? new java.util.zip.GZIPInputStream(seq, 65536) : seq;
            out = new FileOutputStream(dest);
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            try { if (out != null) out.close(); } catch (Throwable ignored) { }
            try { if (in != null) in.close(); } catch (Throwable ignored) { }
            for (File f : parts) { if (f != null) f.delete(); }
        }
    }

    /* ---------- App 自更新 ---------- */

    /** 前端查询：有没有新版 App。返回 JSON。 */
    @android.webkit.JavascriptInterface
    public String appUpdateInfo() {
        JSONObject o = new JSONObject();
        try {
            o.put("available", newAppAvailable);
            o.put("version", newAppVersion);
            o.put("size", newAppSize);
            o.put("state", appState);
            o.put("progress", appProgress);
            o.put("currentVersionCode", currentVersionCode());
        } catch (Throwable ignored) { }
        return o.toString();
    }

    /** 下载新版 APK 并调起安装器（异步）。 */
    @android.webkit.JavascriptInterface
    public void downloadApp() {
        if (!newAppAvailable || newAppUrl.isEmpty()) { appState = "error"; return; }
        if ("downloading".equals(appState)) return;
        appState = "downloading";
        appProgress = 0;
        appDownloaded = 0;
        appTotal = newAppSize;
        notiPct = -1;
        ensureNotifPermission();
        DownloadService.start(context, "正在下载新版 App");
        new Thread(() -> {
            File out = new File(context.getCacheDir(), "shangan-update.apk");
            try {
                long size = newAppSize > 0 ? newAppSize : remoteSize(newAppUrl);
                if (size > 0) appTotal = size;
                File[] parts = size >= DL_PARALLEL_MIN
                        ? fetchParts(newAppUrl, size, context.getCacheDir(), "shangan-update.apk." + size + ".part", true)
                        : null;
                if (parts != null) {
                    if (!assemble(parts, out, false)) throw new Exception("分块合并失败");
                } else {
                    HttpURLConnection c = open(newAppUrl);
                    c.connect();
                    int code = c.getResponseCode();
                    if (code >= 400) throw new Exception("HTTP " + code);
                    long total = c.getContentLength();
                    try (InputStream in = c.getInputStream(); FileOutputStream fo = new FileOutputStream(out)) {
                        byte[] b = new byte[16384];
                        int n; long got = 0;
                        int lastPct = -1;
                        while ((n = in.read(b)) > 0) {
                            fo.write(b, 0, n);
                            got += n;
                            int pct = total > 0 ? (int) (got * 100 / total) : 0;
                            appProgress = pct;
                            appDownloaded = got;
                            appTotal = total > 0 ? total : newAppSize;
                            if (pct != lastPct) {
                                lastPct = pct;
                                notifyProgress(pct, humanSize(got) + " / " + humanSize(appTotal));
                            }
                        }
                    } finally {
                        c.disconnect();
                    }
                }
                if (!newAppSha.isEmpty() && !sha256Of(out).equalsIgnoreCase(newAppSha)) {
                    out.delete();
                    appState = "error";
                    message = "新版 App 校验失败，已丢弃";
                    DownloadService.stop();
                    return;
                }
                appState = "done";
                appProgress = 100;
                if (appTotal > 0) appDownloaded = appTotal;
                DownloadService.stop();
                installApk(out);
            } catch (Throwable t) {
                Log.w(TAG, "downloadApp fail", t);
                if (out.exists()) out.delete();
                appState = "error";
                message = "App 下载失败：" + t.getMessage();
                DownloadService.stop();
            }
        }, "app-update").start();
    }

    private void installApk(File apk) {
        try {
            android.content.Intent it = new android.content.Intent(android.content.Intent.ACTION_VIEW);
            android.net.Uri uri;
            if (android.os.Build.VERSION.SDK_INT >= 24) {
                uri = androidx.core.content.FileProvider.getUriForFile(
                        context, context.getPackageName() + ".fileprovider", apk);
                it.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                uri = android.net.Uri.fromFile(apk);
            }
            it.setDataAndType(uri, "application/vnd.android.package-archive");
            it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(it);
        } catch (Throwable t) {
            Log.w(TAG, "install fail", t);
            appState = "error";
            message = "打开安装器失败：" + t.getMessage();
        }
    }

    private HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setInstanceFollowRedirects(true);   // GitHub Release 会 302 到 CDN
        c.setRequestProperty("User-Agent", "shangan-app/update");
        return c;
    }

    private String appVersionName() {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "";
        }
    }

    private int currentVersionCode() {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionCode;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static String shortSha(String s) {
        return s == null ? "" : (s.length() > 12 ? s.substring(0, 12) + "…" : s);
    }

    private static String humanSize(long n) {
        if (n <= 0) return "?";
        if (n < 1024 * 1024) return (n / 1024) + " KB";
        return String.format("%.1f MB", n / 1024.0 / 1024.0);
    }
}
