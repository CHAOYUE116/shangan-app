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
    /** App 包的候选下载源（manifest 的 app.sources），下载前会测速挑最快的 */
    private volatile String[] newAppSources = new String[0];
    private volatile String newAppSha = "";
    private volatile long newAppSize = 0;
    private volatile int appProgress = 0;
    private volatile long appDownloaded = 0;
    private volatile long appTotal = 0;
    private volatile String appState = "idle";   // idle|downloading|done|error

    /** 这次 manifest 是从哪个源拿到的（诊断用，展示在更新弹层里） */
    private volatile String manifestSource = "";
    /** 所有源都不通、改用了本机缓存清单 */
    private volatile boolean usedCache = false;
    /** 缓存清单的年龄（毫秒） */
    private volatile long cacheAgeMs = 0;

    private volatile Thread worker;

    /*
     * manifest 缓存：国内到 raw.githubusercontent 时通时不通，公共反代也会 502。
     * 只要成功拿到过一次，就把原文存到 cache 目录；下次全源失败时直接用它，
     * 用户至少还能看到「有新版本」并从清单里的地址下，而不是一片「检查失败」。
     */
    private static final String CACHE_NAME = "update-manifest-cache.json";
    private static final Object CACHE_LOCK = new Object();

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
     *
     * 另外这里必须**锁死单调不回退**：分块是 8 个线程抢着报的，重试时还会把
     * 计数扣回去，直接透传就会出现 47% → 46% → 48% 的来回跳，看着像卡死重来。
     */
    private volatile int notiPct = -1;
    private volatile long notiAtMs = 0;

    private void notifyProgress(int pct, String text) {
        int p = Math.max(0, Math.min(100, pct));
        if (notiPct >= 0 && p < notiPct) p = notiPct;
        long now = android.os.SystemClock.elapsedRealtime();
        if (p == notiPct && now - notiAtMs < 400) return;
        if (now - notiAtMs < 250) return;
        notiPct = p;
        notiAtMs = now;
        DownloadService.update(p, text);
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
            // 多源并发拉 manifest，谁先回用谁；整体 12 秒封顶。
            // 串行的话：国外直连不通时，用户要盯着"正在检查更新…"等到超时。
            //
            // 但 jsDelivr 这类 CDN 有 12 小时缓存，如果和直连/反代一起赛跑，
            // 它反而最先返回（因为快），拿到的却可能是半天前的清单 —— 于是误判"已是最新"。
            // 所以分两波：第一波只跑无缓存的反代/直连，全挂了才上 CDN 兜底。
            List<String> wave1 = new ArrayList<>();
            List<String> wave2 = new ArrayList<>();
            for (String u : urls) {
                if (u == null || u.isEmpty()) continue;
                if (isCdnMirror(u)) wave2.add(u); else wave1.add(u);
            }
            if (wave1.isEmpty()) { wave1.addAll(wave2); wave2.clear(); }

            String errMsg = "";
            ManifestHit hit;
            if (!hasNetwork()) {
                // 飞行模式/断网：别等 20 秒超时，直接看有没有缓存清单
                Log.w(TAG, "设备无网络，跳过联网检查");
                hit = new ManifestHit();
                hit.err = "设备没有网络连接";
            } else {
                hit = raceManifest(wave1, 8000);
                if (hit.json == null && !wave2.isEmpty()) {
                    Log.w(TAG, "第一波（" + wave1.size() + " 个源）全失败，改用 CDN 兜底");
                    ManifestHit h2 = raceManifest(wave2, 5000);
                    if (h2.json != null) hit = h2;
                    else errMsg = (hit.err == null ? "" : hit.err + "；") + h2.err;
                } else {
                    errMsg = hit.err;
                }
            }

            JSONObject manifest = hit.json;
            usedCache = false;
            cacheAgeMs = 0;
            manifestSource = hit.source == null ? "" : hit.source;
            if (manifest != null) {
                writeManifestCache(hit.text);
                // CDN 有 12 小时缓存 —— 如果本机存过比它更新的清单，用缓存里的
                if ("jsDelivr".equals(manifestSource)) {
                    JSONObject c = readManifestCache();
                    String cv = dbVerOf(c);
                    String hv = dbVerOf(manifest);
                    if (!cv.isEmpty() && !hv.isEmpty() && cmpVersion(cv, hv) > 0) {
                        Log.w(TAG, "CDN 清单比本机缓存旧（" + hv + " < " + cv + "），改用缓存");
                        manifest = c;
                        usedCache = true;
                        manifestSource = "本机缓存";
                    }
                }
            } else {
                // 全源都失败 → 退到本机缓存清单，别让用户只看到一句「检查失败」
                JSONObject cached = readManifestCache();
                if (cached != null) {
                    manifest = cached;
                    usedCache = true;
                    manifestSource = "本机缓存";
                    Log.w(TAG, "改用本机缓存清单（" + (cacheAgeMs / 60000) + " 分钟前）：" + errMsg);
                } else {
                    set("error", 0, errMsg.contains("没有网络")
                            ? "手机没联网，先连上网再检查更新"
                            : ("更新源都连不上（试了 " + urls.size() + " 个）"
                                + (errMsg.isEmpty() ? "" : "：" + errMsg)));
                    return;
                }
            }

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
                    newAppSources = sourcesOf(app);
                    newAppSha = app.optString("sha256", "");
                    newAppSize = app.optLong("size", 0);
                }
            }

            if (remoteVersion.isEmpty()) { set("error", 0, "manifest 没有 dbVersion"); return; }
            String cacheNote = usedCache ? "（网络不稳，用的是本机 " + cacheAgeMin() + " 分钟前的清单）" : "";
            // 不能用 String.compareTo：按字典序 "0.5.10" 排在 "0.5.9" 前面（'1' < '9'），
            // 那 0.5.9 → 0.5.10 这种跨位数的版本会被判成「本地更新，无需更新」。
            if (!remoteVersion.equals(localVersion) && cmpVersion(remoteVersion, localVersion) < 0) {
                set("uptodate", 100, "本地题库版本更新（" + localVersion + "），无需更新" + cacheNote);
                return;
            }
            if (remoteVersion.equals(localVersion)) {
                set("uptodate", 100, "已是最新题库 " + localVersion
                        + (newAppAvailable ? "；发现新版 App " + newAppVersion : "") + cacheNote);
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
                String[] dUrls = sourcesOf(deltaEntry);
                String dSha = deltaEntry.optString("sha256", "");
                long dSize = deltaEntry.optLong("size", 0);
                File dDir = new File(context.getFilesDir(), "db");
                if (!dDir.exists() && !dDir.mkdirs()) { set("error", 0, "无法创建目录"); return; }
                File dTmp = new File(dDir, "tiku_delta.json.gz");
                total = dSize;
                ensureNotifPermission();
                DownloadService.start(context, "正在下载题库更新");
                set("downloading", 0, "开始下载增量 " + humanSize(dSize));
                long dRaw = deltaEntry.optLong("rawSize", 0);
                String dGot; synchronized (DL_LOCK) { dGot = download(dUrls, dTmp, dSha, dSize, dRaw); }
                if (dGot == null) return;
                set("verifying", 100, "校验中…");
                if (!dSha.isEmpty() && !dSha.equalsIgnoreCase(dGot)) {
                    dTmp.delete();
                    set("error", 0, "增量包校验失败，已丢弃（期望 " + shortSha(dSha) + " 实际 " + shortSha(dGot) + "，大小 " + humanSize(dTmp.length()) + "）");
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
            String[] fUrls = sourcesOf(full);
            String sha = full.optString("sha256", "");
            total = full.optLong("size", 0);
            if (url.isEmpty()) { set("error", 0, "full.url 为空"); return; }

            File dir = new File(context.getFilesDir(), "db");
            if (!dir.exists() && !dir.mkdirs()) { set("error", 0, "无法创建目录"); return; }
            File tmp = new File(dir, "tiku_app.db.download");
            ensureNotifPermission();
            DownloadService.start(context, "正在下载题库");
            set("downloading", 0, "开始下载 " + humanSize(total));
            String gotSha; synchronized (DL_LOCK) { gotSha = download(fUrls, tmp, sha, total, full.optLong("rawSize", 0)); }
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
            boolean running = worker != null && worker.isAlive();
            // 后台还有任务在跑时，即便 state 被重置成 idle 也要如实报成 downloading，
            // 否则前端只看到「0%」，以为没在下载
            String st = state;
            if (running && ("idle".equals(st) || st == null)) st = "downloading";
            o.put("state", st);
            o.put("running", running);
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
            o.put("manifestSource", manifestSource);
            o.put("cachedManifest", usedCache);
            o.put("cacheAgeMin", usedCache ? cacheAgeMin() : 0);
            return o.toString();
        } catch (Throwable t) {
            return "{\"state\":\"error\",\"message\":\"" + t.getMessage() + "\"}";
        }
    }

    @android.webkit.JavascriptInterface
    public void reset() {
        // 正在下载时不清状态：原来这里无条件 set("idle", 0)，前端每次打开弹层都会先 reset，
        // 结果弹层显示「0%、空进度条」，而下载其实还在后台跑（通知栏那条进度是好的）。
        if (worker != null && worker.isAlive()) return;
        if ("downloading".equals(appState)) return;
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

    /** 一个 manifest 源的命中结果 */
    private static final class ManifestHit {
        JSONObject json;
        String text;
        String source;
        String err;
    }

    /** jsDelivr 这类 CDN 有 12 小时缓存，只能兜底，不能和直连一起赛跑 */
    private static boolean isCdnMirror(String u) {
        String s = u.toLowerCase();
        return s.contains("jsdelivr");
    }

    /** 系统有没有可用网络（飞行模式、断网时不用白等超时） */
    private boolean hasNetwork() {
        try {
            android.net.ConnectivityManager cm =
                    (android.net.ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return true;
            android.net.Network n = cm.getActiveNetwork();
            if (n == null) return false;
            android.net.NetworkCapabilities c = cm.getNetworkCapabilities(n);
            return c != null && c.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Throwable t) {
            return true;   // 判断不出来就当有网，交给正常流程去试
        }
    }

    /** 并发跑一组 manifest 源，先成功者胜；budgetMs 到点就收 */
    private ManifestHit raceManifest(List<String> urls, long budgetMs) {
        ManifestHit out = new ManifestHit();
        if (urls == null || urls.isEmpty()) return out;
        final java.util.concurrent.atomic.AtomicReference<ManifestHit> got =
                new java.util.concurrent.atomic.AtomicReference<>(null);
        final java.util.concurrent.atomic.AtomicReference<String> lastErr =
                new java.util.concurrent.atomic.AtomicReference<>("");
        Thread[] ts = new Thread[urls.size()];
        for (int i = 0; i < urls.size(); i++) {
            final String u = urls.get(i);
            ts[i] = new Thread(new Runnable() {
                @Override public void run() {
                    if (got.get() != null) return;
                    try {
                        String text = fetchText(u);
                        JSONObject m = new JSONObject(text);
                        ManifestHit h = new ManifestHit();
                        h.json = m;
                        h.text = text;
                        h.source = sourceName(u);
                        got.compareAndSet(null, h);
                    } catch (Throwable t) {
                        lastErr.set(sourceName(u) + " " + t.getMessage());
                        Log.w(TAG, "manifest 源失败 " + sourceName(u) + " : " + t.getMessage());
                    }
                }
            }, "manifest-" + i);
            ts[i].setDaemon(true);
            ts[i].start();
        }
        long deadline = System.currentTimeMillis() + budgetMs;
        // 不能 join 所有线程：慢源会把整个检查拖到 12 秒（就算快源 1 秒就回了）。
        // 改成轮询 got，谁先回立刻返回。
        while (got.get() == null && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(120); } catch (InterruptedException ignored) { break; }
        }
        ManifestHit w = got.get();
        if (w != null) return w;
        out.err = lastErr.get();
        return out;
    }

    private String fetchText(String url) throws Exception {
        HttpURLConnection c = open(url);
        c.setConnectTimeout(8000);      // manifest 就几 KB，等不了那么久
        c.setReadTimeout(10000);
        try (InputStream in = c.getInputStream()) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private JSONObject fetchJson(String url) throws Exception {
        return new JSONObject(fetchText(url));
    }

    // ---------------- manifest 本机缓存 ----------------

    private File manifestCacheFile() {
        return new File(context.getCacheDir(), CACHE_NAME);
    }

    private void writeManifestCache(String text) {
        if (text == null || text.length() < 20) return;
        synchronized (CACHE_LOCK) {
            File dst = manifestCacheFile();
            File tmp = new File(dst.getAbsolutePath() + ".tmp");
            try (FileOutputStream fo = new FileOutputStream(tmp)) {
                fo.write(text.getBytes("UTF-8"));
                fo.getFD().sync();
            } catch (Throwable t) {
                Log.w(TAG, "写 manifest 缓存失败：" + t.getMessage());
                return;
            }
            if (!tmp.renameTo(dst)) {
                // 某些机型 rename 会失败（跨挂载点/文件被占），退成「先删再改名」
                //noinspection ResultOfMethodCallIgnored
                dst.delete();
                if (!tmp.renameTo(dst)) {
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                    Log.w(TAG, "manifest 缓存改名失败");
                }
            }
        }
    }

    private JSONObject readManifestCache() {
        synchronized (CACHE_LOCK) {
            File f = manifestCacheFile();
            if (!f.exists()) return null;
            long age = System.currentTimeMillis() - f.lastModified();
            // 超过 30 天的清单没有意义了（版本号早就变了），宁可直接报错
            if (age > 30L * 24 * 3600 * 1000) return null;
            try {
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                try (InputStream in = new java.io.FileInputStream(f)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                }
                JSONObject o = new JSONObject(bo.toString("UTF-8"));
                cacheAgeMs = age;
                return o;
            } catch (Throwable t) {
                Log.w(TAG, "读 manifest 缓存失败：" + t.getMessage());
                return null;
            }
        }
    }

    private long cacheAgeMin() {
        return Math.max(1, cacheAgeMs / 60000);
    }

    private static String dbVerOf(JSONObject m) {
        if (m == null) return "";
        JSONObject t = m.optJSONObject("tiku");
        return t == null ? "" : t.optString("dbVersion", "");
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

    /** 同一时刻只允许一条下载线占着通知栏，否则两个百分比互相覆盖 */
    private static final Object DL_LOCK = new Object();
    private static final long DL_PARALLEL_MIN = 2L * 1024 * 1024;   // 小于 2MB 单流就够

    /* ================= 下载源选择 ================= */

    /*
     * 为什么要有这一段：
     * manifest 里同一个文件有好几个镜像 —— 直连 github.com、gh-proxy 公共反代、
     * jsDelivr。它们的速度完全不在一个量级，而且会变。
     *
     * 实测同机同文件（86 MB 的 tiku_app.db.gz）：
     *   直连 release-assets.githubusercontent.com   8.78 MB/s
     *   gh-proxy（走代理）                           3.24 ~ 4.80 MB/s
     *   gh-proxy（不走代理）                         0.15 MB/s
     * 而且 gh-proxy 是"它自己去 GitHub 拉"，你自己开的代理只覆盖"你→gh-proxy"
     * 这一段，拉不动对面机房。所以固定死用某一个源，就是听天由命。
     *
     * 做法：下载前对每个源发一个 256 KB 的 Range 请求并发探速，用最快的那个。
     * 探测很快（快的源毫秒级返回），慢的源最多等 5 秒就被放弃。
     */

    private static final int PROBE_BYTES = 256 * 1024;
    private static final long PROBE_MAX_MS = 5000;

    /** 从 manifest 的一段里取候选下载源：sources[].url 优先，其次 url，最后 mirrors[] */
    private String[] sourcesOf(JSONObject entry) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (entry == null) return new String[0];
        JSONArray src = entry.optJSONArray("sources");
        if (src != null) {
            for (int i = 0; i < src.length(); i++) {
                JSONObject o = src.optJSONObject(i);
                String u = o != null ? o.optString("url", "") : "";
                if (!u.isEmpty() && !out.contains(u)) out.add(u);
            }
        }
        String primary = entry.optString("url", "");
        if (!primary.isEmpty() && !out.contains(primary)) out.add(primary);
        JSONArray mir = entry.optJSONArray("mirrors");
        if (mir != null) {
            for (int i = 0; i < mir.length(); i++) {
                String u = mir.optString(i, "");
                if (!u.isEmpty() && !out.contains(u)) out.add(u);
            }
        }
        return out.toArray(new String[0]);
    }

    /** 给 URL 起个短名，给用户看 */
    private static String sourceName(String url) {
        if (url == null || url.isEmpty()) return "未知源";
        if (url.contains("gh-proxy") || url.contains("ghproxy")) return "加速镜像";
        if (url.contains("gitmirror")) return "镜像 gitmirror";
        if (url.contains("jsdelivr")) return "jsDelivr";
        if (url.contains("githubusercontent") || url.contains("github.com")) return "直连 GitHub";
        try { return new java.net.URL(url).getHost(); } catch (Throwable t) { return "自定义源"; }
    }

    /** 探一个源的实际吞吐（MB/s）。拉前 256 KB，最多等 5 秒；失败返回 -1 */
    private double probe(String url) {
        HttpURLConnection c = null;
        try {
            c = open(url);
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            c.setRequestProperty("Range", "bytes=0-" + (PROBE_BYTES - 1));
            int code = c.getResponseCode();
            if (code != 200 && code != 206) return -1;
            long t0 = System.currentTimeMillis();
            long got = 0;
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while (got < PROBE_BYTES && (n = in.read(buf)) > 0) {
                    got += n;
                    if (System.currentTimeMillis() - t0 > PROBE_MAX_MS) break;
                }
            }
            long ms = System.currentTimeMillis() - t0;
            if (got <= 0 || ms <= 0) return -1;
            return (got / 1048576.0) / (ms / 1000.0);
        } catch (Throwable t) {
            return -1;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 并发探所有源，按吞吐从高到低排。全失败就返回原顺序（交给下载阶段去试错） */
    private String[] orderSources(String[] urls) {
        java.util.List<String> cand = new java.util.ArrayList<>();
        if (urls != null) {
            for (String u : urls) if (u != null && !u.isEmpty() && !cand.contains(u)) cand.add(u);
        }
        if (cand.size() <= 1) return cand.toArray(new String[0]);

        final double[] rate = new double[cand.size()];
        java.util.Arrays.fill(rate, -1);
        Thread[] ts = new Thread[cand.size()];
        for (int i = 0; i < cand.size(); i++) {
            final int idx = i;
            final String u = cand.get(i);
            ts[i] = new Thread(new Runnable() {
                @Override public void run() { rate[idx] = probe(u); }
            }, "probe-" + i);
            ts[i].setDaemon(true);
            ts[i].start();
        }
        long deadline = System.currentTimeMillis() + PROBE_MAX_MS + 1500;
        for (Thread t : ts) {
            long left = deadline - System.currentTimeMillis();
            if (left > 0) { try { t.join(left); } catch (InterruptedException ignored) { } }
        }

        Integer[] idx = new Integer[cand.size()];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        java.util.Arrays.sort(idx, (a, b) -> Double.compare(rate[b], rate[a]));

        StringBuilder sb = new StringBuilder();
        String[] out = new String[cand.size()];
        for (int i = 0; i < idx.length; i++) {
            out[i] = cand.get(idx[i]);
            if (sb.length() > 0) sb.append("、");
            double r = rate[idx[i]];
            sb.append(sourceName(out[i])).append(r > 0 ? String.format(" %.1fMB/s", r) : " 不通");
        }
        Log.i(TAG, "测速结果：" + sb);
        message = "测速：" + sb;
        return out;
    }

    /** 挑一个最快的源（不下载） */
    private String pickSource(String[] urls) {
        String[] ordered = orderSources(urls);
        return ordered.length == 0 ? "" : ordered[0];
    }

    /** 多源下载：按测速顺序逐个试，第一个成功的胜出 */
    private String download(String[] urls, File dest, String expectSha, long sizeHint, long rawHint) {
        String[] ordered = orderSources(urls);
        if (ordered.length == 0) {
            set("error", progress, "manifest 里没有可用的下载地址");
            return null;
        }
        String lastErr = null;
        for (String u : ordered) {
            if (u == null || u.isEmpty()) continue;
            message = "正在从「" + sourceName(u) + "」下载";
            Log.i(TAG, "下载源 " + sourceName(u) + " " + u);
            String r = downloadOne(u, dest, expectSha, sizeHint, rawHint);
            if (r != null) return r;
            lastErr = sourceName(u);          // 换源等于重新开始，允许进度条回到 0
        }
        set("error", progress, "所有下载源都失败（最后一个：" + lastErr + "）");
        return null;
    }

    private String downloadOne(String url, File dest, String expectSha, long sizeHint, long rawHint) {
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
                // 分块失败不再直接判死：弱网/代理下经常是个别分块连续三次超时。
                // 退回单线程整取下完再解压——慢一点但成功率最高；进度条有单调保护，
                // 不会因为「单线程从头数」而往回跳。
                if (parts == null) {
                    Log.w(TAG, "分块下载未完成，改用单线程整取");
                    message = "分块失败，改用单线程重下…";
                }
            }
            if (parts != null) {
                if (!assemble(parts, dest, isGz, rawHint, false)) throw new java.io.IOException("分块合并失败");
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
                        int pct = (int) Math.min(96, got * 100 / total);
                        if (pct != lastPct) {
                            lastPct = pct;
                            progress = Math.max(progress, pct);
                            message = (isGz ? "正在下载并解压 " : "下载中 ") + pct + "%  " + humanSize(got) + " / " + humanSize(total);
                            notifyProgress(pct, humanSize(got) + " / " + humanSize(total));
                        }
                    }
                }
                out.close();
                in.close();
            }

            // 校验的是"解压后"的库文件
            return sha256Of(dest, dest.length(), false);
        } catch (Throwable t) {
            if (dest.exists()) dest.delete();
            Log.w(TAG, "源 " + sourceName(url) + " 下载失败：" + t.getMessage());
            message = "「" + sourceName(url) + "」失败，换下一个源…";
            return null;          // 不在这里 set error —— 上层还有别的源要试
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /**
     * 版本号比较：形如 0.5.10-20261110-1010。
     * 前半段按 . 分段做数值比较（0.5.10 > 0.5.9），相同时再比后半段时间戳。
     */
    private static int cmpVersion(String a, String b) {
        String[] pa = a.split("-", 2);
        String[] pb = b.split("-", 2);
        String[] na = pa[0].split("\\.");
        String[] nb = pb[0].split("\\.");
        int n = Math.max(na.length, nb.length);
        for (int i = 0; i < n; i++) {
            int va = i < na.length ? parseIntSafe(na[i]) : 0;
            int vb = i < nb.length ? parseIntSafe(nb[i]) : 0;
            if (va != vb) return va < vb ? -1 : 1;
        }
        String ta = pa.length > 1 ? pa[1] : "";
        String tb = pb.length > 1 ? pb[1] : "";
        return ta.compareTo(tb);
    }

    private static int parseIntSafe(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }

    /** 算 sha256。total>0 时顺带把 99→100 这段进度推出去，免得校验那几秒又像卡死 */
    private String sha256Of(File f, long totalBytes, boolean forApp) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        long done = 0;
        long lastReport = 0;
        try (InputStream in = new java.io.FileInputStream(f)) {
            byte[] b = new byte[256 * 1024];
            int k;
            while ((k = in.read(b)) > 0) {
                md.update(b, 0, k);
                done += k;
                if (totalBytes <= 0) continue;
                long now = android.os.SystemClock.elapsedRealtime();
                if (now - lastReport < 300) continue;
                lastReport = now;
                int pct = (int) (done >= totalBytes ? 100 : 99);
                String txt = "正在校验 " + humanSize(done) + " / " + humanSize(totalBytes);
                if (forApp) { appProgress = pct; } else { progress = pct; message = txt; }
                notifyProgress(pct, txt);
            }
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
        final long CHUNK = 2L * 1024 * 1024;
        final int blocks = (int) ((size + CHUNK - 1) / CHUNK);
        if (blocks < 2) return null;
        final int workers = Math.min(4, blocks);
        final File[] parts = new File[blocks];
        for (int i = 0; i < blocks; i++) parts[i] = new File(dir, prefix + i);

        // 续传标记：只有「同一个包」的残留分块才允许复用。
        // 以前一律复用，上一次失败的残片会被当成已完成（done 一次性加满），
        // 进度条就出现「一直 0，然后突然蹦到 70%」——现在换包先清干净。
        File marker = new File(dir, prefix + ".src");
        String sig = url + "#" + size;
        String oldSig = null;
        try {
            if (marker.exists()) oldSig = new String(java.nio.file.Files.readAllBytes(marker.toPath()), "UTF-8");
        } catch (Throwable ignored) { }
        if (!sig.equals(oldSig)) {
            for (File f : parts) { if (f != null && f.exists()) f.delete(); }
            try { java.nio.file.Files.write(marker.toPath(), sig.getBytes("UTF-8")); } catch (Throwable ignored) { }
        }
        final File markerFile = marker;

        final long fTotal = size;
        final java.util.concurrent.atomic.AtomicLong done = new java.util.concurrent.atomic.AtomicLong(0);
        final java.util.concurrent.atomic.AtomicInteger next = new java.util.concurrent.atomic.AtomicInteger(0);
        final java.util.concurrent.atomic.AtomicBoolean bad = new java.util.concurrent.atomic.AtomicBoolean(false);
        // 心跳：只要 9 秒没有新字节就换文案，让人知道它在重试而不是死了
        final Thread beat = new Thread(new Runnable() {
            @Override public void run() {
                long lastSeen = -1;
                int sameFor = 0;
                while (!Thread.currentThread().isInterrupted()) {
                    try { Thread.sleep(3000); } catch (InterruptedException e) { return; }
                    long d = done.get();
                    // 失败重试会把计数扣回去，这里用「已完成过的最高水位」展示，
                    // 免得状态栏从 13.7MB 突然跳回 0 KB，看着像进度倒退
                    if (d > shown) shown = d;
                    if (d == lastSeen) sameFor++; else { sameFor = 0; lastSeen = d; }
                    if (sameFor >= 12) { bad.set(true); return; }  // 36 秒无进展：换源/降级
                    if (sameFor >= 3 && shown < fTotal) {
                        int pct = (int) Math.min(92, shown * 100 / Math.max(1, fTotal));
                        String txt = "网络有点慢，正在重试 " + humanSize(shown) + " / " + humanSize(fTotal);
                        if (forApp) { appDownloaded = shown; appTotal = fTotal; } else { downloaded = shown; message = txt; }
                        notifyProgress(pct, txt);
                    }
                    if (d >= fTotal) return;
                }
            }
        });
        beat.setDaemon(true);
        beat.start();
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
                            int pct = (int) Math.min(92, shown * 100 / Math.max(1, fTotal));
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
        beat.interrupt();

        boolean ok = !bad.get();
        if (ok) {
            for (int b = 0; b < blocks; b++) {
                long from = b * CHUNK;
                long to = Math.min(size - 1, from + CHUNK - 1);
                if (parts[b] == null || parts[b].length() != (to - from + 1)) { ok = false; break; }
            }
        }
        if (!ok) { for (File f : parts) { if (f != null) f.delete(); } return null; }
        if (markerFile != null) markerFile.delete();
        return parts;
    }

    /** 下一块。成功返回 true；失败时把已下载的字节从计数里扣回去，等重试 */
    private boolean fetchBlock(String url, long from, long to, File target,
                               java.util.concurrent.atomic.AtomicLong done, long fTotal, boolean forApp, int workers) {
        HttpURLConnection hc = null;
        FileOutputStream out = null;
        InputStream in = null;
        final long need = to - from + 1;
        long wrote = 0;
        boolean counted = false;
        try {
            hc = open(url);
            hc.setRequestProperty("Range", "bytes=" + from + "-" + to);
            int code = hc.getResponseCode();
            if (code != 206) return false;
            in = hc.getInputStream();
            out = new FileOutputStream(target);
            byte[] buf = new byte[128 * 1024];
            int k;
            long blockStart = System.currentTimeMillis();
            while ((k = in.read(buf)) > 0) {
                out.write(buf, 0, k);
                wrote += k;
                // 单块拖过 20 秒还没下完，大概率是这条连接被限速/挂住了。
                // 断开换一条新连接重来，比让进度条干等强 —— 实测 gh-proxy 后半程经常这样
                if (wrote < need && System.currentTimeMillis() - blockStart > 20000) return false;
                long d = done.addAndGet(k);
                if (d > shown) shown = d;
                int pct = (int) Math.min(92, shown * 100 / Math.max(1, fTotal));
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
            boolean okLen = target.length() == need;
            if (okLen) {
                // 补齐这一块的整段字节（read 循环里只加了 wrote）
                done.addAndGet(need - wrote);
                counted = true;
            }
            return okLen;
        } catch (Throwable t) {
            return false;
        } finally {
            if (!counted) done.addAndGet(-wrote);
            try { if (out != null) out.close(); } catch (Throwable ignored) { }
            try { if (in != null) in.close(); } catch (Throwable ignored) { }
            if (hc != null) hc.disconnect();
        }
    }

    /** 按序拼接分块；gz 的话边拼边解压。无论成败都会清掉分块文件 */
    private boolean assemble(File[] parts, File dest, boolean isGz, long rawHint, boolean forApp) {
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
            long written = 0;
            long lastReport = 0;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                written += n;
                long now = android.os.SystemClock.elapsedRealtime();
                if (now - lastReport < 300) continue;
                lastReport = now;
                int pct = rawHint > 0
                        ? (int) Math.min(99, 92 + written * 7 / Math.max(1, rawHint))
                        : 95;
                // 文案必须说清「这是在本机解压安装，不是又下载一遍」：
                // 之前只写「正在解压 X / 394 MB」，用户以为下完 106MB 又要下 300 多 MB。
                String txt = (isGz ? "正在安装（解压）" : "正在写入") + " " + humanSize(written)
                        + (rawHint > 0 ? " / " + humanSize(rawHint) : "") + "，不用再下载";
                if (forApp) {
                    appProgress = pct;
                    appDownloaded = written;
                    if (rawHint > 0) appTotal = rawHint;
                } else {
                    progress = pct;
                    downloaded = written;
                    if (rawHint > 0) total = rawHint;
                    message = txt;
                }
                notifyProgress(pct, txt);
            }
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
        new Thread(() -> {
            File out = new File(context.getCacheDir(), "shangan-update.apk");
            File stamp = new File(context.getCacheDir(), "shangan-update.ok");
            try {
                // 同一个包下过就别下了 —— 之前每次点都重下 135 MB
                if (out.exists() && out.length() > 0 && stamp.exists()
                        && newAppSha.equalsIgnoreCase(readText(stamp).trim())) {
                    appState = "done";
                    appProgress = 100;
                    appDownloaded = out.length();
                    appTotal = out.length();
                    message = "新版本已经下载过了，直接装";
                    notifyReadyToInstall(out);
                    return;
                }
                String apkUrl = pickSource(newAppSources.length > 0 ? newAppSources : new String[]{newAppUrl});
                if (apkUrl.isEmpty()) throw new Exception("没有可用的下载源");
                // 到这一步才确定要真下载了，此时再起前台服务
                ensureNotifPermission();
                DownloadService.start(context, "正在下载新版 App");
                appState = "downloading";
                message = "正在从「" + sourceName(apkUrl) + "」下载 App";
                long size = newAppSize > 0 ? newAppSize : remoteSize(apkUrl);
                if (size > 0) appTotal = size;
                File[] parts = null;
                synchronized (DL_LOCK) {
                    parts = size >= DL_PARALLEL_MIN
                            ? fetchParts(apkUrl, size, context.getCacheDir(), "shangan-update.apk." + size + ".part", true)
                            : null;
                }
                if (parts != null) {
                    if (!assemble(parts, out, false, 0, true)) throw new Exception("分块合并失败");
                } else {
                    HttpURLConnection c = open(apkUrl);
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
                if (!newAppSha.isEmpty() && !sha256Of(out, out.length(), true).equalsIgnoreCase(newAppSha)) {
                    out.delete();
                    stamp.delete();
                    appState = "error";
                    message = "新版 App 校验失败，已丢弃";
                    DownloadService.stop();
                    return;
                }
                try { writeText(stamp, newAppSha); } catch (Throwable ignored) { }
                appState = "done";
                appProgress = 100;
                if (appTotal > 0) appDownloaded = appTotal;
                message = "下载完成，点安装";
                DownloadService.stop();
                notifyReadyToInstall(out);   // 不直接 startActivity
            } catch (Throwable t) {
                Log.w(TAG, "downloadApp fail", t);
                if (out.exists()) out.delete();
                appState = "error";
                message = "App 下载失败：" + t.getMessage();
                DownloadService.stop();
            }
        }, "app-update").start();
    }

    /** 构造安装用的 Intent（FileProvider 授权读） */
    private android.content.Intent apkIntent(File apk) {
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
        return it;
    }

    /** 用户点通知时用的 PendingIntent */
    private android.app.PendingIntent installPendingIntent(File apk) {
        int flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= 23) flags |= android.app.PendingIntent.FLAG_IMMUTABLE;
        return android.app.PendingIntent.getActivity(context, 2, apkIntent(apk), flags);
    }

    /**
     * 下载完了只发通知，不直接拉安装器。
     *
     * 原因：Android 10 起禁止后台启动 Activity。后台下载（我们现在就是后台下）
     * 下完那一刻 App 多半在后台，startActivity 会被系统静默拒绝 ——
     * 用户看到的就是"下载完了但什么都没发生"。AppUpdate / XUpdate 这些库
     * 处理这个问题的办法也一样：落通知，用户点了才装。
     */
    private void notifyReadyToInstall(File apk) {
        try {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) context.getSystemService(android.content.Context.NOTIFICATION_SERVICE);
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                android.app.NotificationChannel ch = new android.app.NotificationChannel(
                        "shangan_install", "安装", android.app.NotificationManager.IMPORTANCE_DEFAULT);
                ch.setShowBadge(true);
                nm.createNotificationChannel(ch);
            }
            androidx.core.app.NotificationCompat.Builder b =
                    new androidx.core.app.NotificationCompat.Builder(context, "shangan_install")
                            .setSmallIcon(android.R.drawable.stat_sys_download_done)
                            .setContentTitle("新版本 " + (newAppVersion.isEmpty() ? "" : newAppVersion) + " 已就绪")
                            .setContentText("点这里安装（安装过程中 App 会关闭）")
                            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
                            .setAutoCancel(true)
                            .setContentIntent(installPendingIntent(apk));
            nm.notify(10202, b.build());
        } catch (Throwable t) {
            Log.w(TAG, "安装通知失败", t);
        }
    }

    /** 能不能装：Android 8+ 需要用户允许「安装未知应用」 */
    private boolean canInstall() {
        if (android.os.Build.VERSION.SDK_INT < 26) return true;
        try { return context.getPackageManager().canRequestPackageInstalls(); } catch (Throwable t) { return true; }
    }

    private void openInstallSettings() {
        try {
            android.content.Intent it = new android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    android.net.Uri.parse("package:" + context.getPackageName()));
            it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(it);
        } catch (Throwable t) {
            Log.w(TAG, "打开安装权限设置失败", t);
        }
    }

    /** 供前端「立即安装」按钮调用：用户主动点，不受后台启动限制 */
    @android.webkit.JavascriptInterface
    public String installApp() {
        File apk = new File(context.getCacheDir(), "shangan-update.apk");
        if (!apk.exists() || apk.length() <= 0) return "error: 安装包不在本地，请先下载";
        if (!canInstall()) {
            openInstallSettings();
            return "needPermission";
        }
        try {
            context.startActivity(apkIntent(apk));
            appState = "installing";
            message = "已打开安装器";
            return "ok";
        } catch (Throwable t) {
            Log.w(TAG, "install fail", t);
            return "error: " + t.getMessage();
        }
    }

    private HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(15000);
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

    private static String readText(File f) {
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static void writeText(File f, String s) throws Exception {
        try (java.io.Writer w = new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(f), "UTF-8")) {
            w.write(s == null ? "" : s);
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
        if (n <= 0) return "0 KB";
        if (n < 1024 * 1024) return (n / 1024) + " KB";
        return String.format("%.1f MB", n / 1024.0 / 1024.0);
    }
}
