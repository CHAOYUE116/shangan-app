package com.kaogong.app;

import android.content.Context;
import android.content.res.AssetManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Base64;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * 原生 SQLite 同步桥（替代 sql.js + WASM 整体加载题库）：
 * - 首次启动把 assets 中 136MB 题库流式复制到应用私有目录（此后跳过），以只读方式打开；
 * - 通过 addJavascriptInterface 暴露同步查询（open/all/get），JS 侧同步拿到 JSON；
 * - 题库不再进入 WebView JS 堆，首启内存峰值从 ~270MB 降到几十 MB，低端机不再白屏/OOM；
 * - images.db（公式图）由同一桥按 SQL 内容路由，blob 列以 {"__b64": "..."} 包装返回，
 *   JS 适配层还原为 Uint8Array，与 sql.js 的 getAsObject 结构一致，上层代码零改动。
 *
 * 线程模型：@JavascriptInterface 方法运行在 Chromium JavaBridge 线程（非 UI 线程），
 * SQLiteDatabase 连接线程安全；JS 单线程同步调用，无并发问题。
 */
public class NativeDbBridge {

    /**
     * assets 里题库文件所在目录。
     * 2026-11-12：原来读写 {@code public/app-assets/}，可 WebView 侧只需要那几张 skill-*.json，
     * 把 400 MB 的 .db 也塞进 public/ 会让 APK 里出现两份库（一份给桥读、一份被 WebView
     * 目录镜像进来），包体直接涨到 299 MB。改成 assets 根目录后只有一份。
     */
    private static final String DB_ASSET_DIR = "";

    private final Context context;

    // 首次启动要把 assets 里的题库解压到 files/db/：tiku_app.db 332 MB + images.db 76 MB，
    // APK 里是压缩存放（26% / 63%），实际要写出 408 MB —— 这是首启十几秒的全部来源。
    // 拷贝过程把字节数暴露成静态字段，SplashBridge 轮询它，用户在启动图上能看到进度。
    public static volatile long copyBytes = 0;
    public static volatile long copyTotal = 0;
    public static volatile String copyName = "";
    private volatile SQLiteDatabase tikuDb;
    private volatile SQLiteDatabase imagesDb;

    public NativeDbBridge(Context context) {
        this.context = context;
    }

    /** 初始化：复制 assets 题库到私有目录并打开只读连接。返回 "ok" 或错误信息。 */
    @JavascriptInterface
    public String open() {
        try {
            openIfNeeded();
            return "ok";
        } catch (Throwable t) {
            return "error: " + t.getMessage();
        }
    }

    // 预热线程（Application）和 JS 桥线程可能同时进来，必须串行，
    // 否则两条线程会同时往 files/db/ 里解压同一个 332 MB 文件
    private synchronized void openIfNeeded() throws Exception {
        if (tikuDb == null || !tikuDb.isOpen()) {
            // 版本比对：assets 里的 db-version.txt vs 私有目录记录的版本
            // 覆盖安装后 assets 可能变新 → 需要重拷；若本地是网络热更新过的（版本更高）→ 不倒退
            // 格式：<版本> <questions 行数> <question_categories 行数>
            // 后两个数是为了防「版本号写着新的、库里数据还是旧的」——
            // 覆盖安装 / 增量包写一半 / 用户改过库，都会让版本号和内容脱节，
            // 表现出来就是「更新完题库，题反而变少了」。
            String assetTxt = readAssetText(DB_ASSET_DIR + "db-version.txt");
            String[] parts = assetTxt.trim().split("\\s+");
            String assetVer = parts.length > 0 ? parts[0] : "";
            int expectQ = 0, expectQc = 0;
            try {
                if (parts.length > 1) expectQ = Integer.parseInt(parts[1]);
                if (parts.length > 2) expectQc = Integer.parseInt(parts[2]);
            } catch (Throwable ignored) { }
            // 首启只阻塞拷贝题库本体（332 MB）。图片库 76 MB 交给后台线程，
            // 这样启动耗时从「408 MB 解压写出」降到「332 MB」，少约 19%。
            copyTotal = 332L * 1024 * 1024;
            copyBytes = 0;
            File f = copyAssetIfNeeded(DB_ASSET_DIR + "tiku_app.db", "tiku_app.db",
                    assetVer, true, expectQ, expectQc);
            tikuDb = SQLiteDatabase.openDatabase(f.getAbsolutePath(), null,
                    SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
            finishCopyProgress();
            // 首屏出来之后再补图库，不挡启动
            prepareImagesAsync();
        } else if (imagesDb == null || !imagesDb.isOpen()) {
            prepareImagesAsync();
        }
    }

    /** 后台把 images.db 就位并打开；真正的图片查询如果抢在它前面，会同步等它完成。 */
    private void prepareImagesAsync() {
        if (imagesDb != null && imagesDb.isOpen()) return;
        Thread t = new Thread(() -> {
            try { ensureImages(); } catch (Throwable ignored) { /* 没有图库也能刷题 */ }
        }, "images-prep");
        t.setDaemon(true);
        t.start();
    }

    private synchronized void ensureImages() throws Exception {
        if (imagesDb != null && imagesDb.isOpen()) return;
        // 图片库以前传的是 null 版本号：只要本地文件在就永远不重拷，
        // 结果每次升级新增的公式图都进不来（用户看到的还是「图打不开」）。
        // 现在跟题库用同一个版本号：版本不同就把旧的删掉重拷。
        String ver = assetDbVersion();
        if (ver != null) {
            File dir0 = new File(context.getFilesDir(), "db");
            File verFile = new File(dir0, "images.db.ver");
            String local = verFile.exists() ? readText(verFile).trim() : "";
            if (!local.equals(ver)) {
                File img = new File(dir0, "images.db");
                if (img.exists() && !img.delete()) android.util.Log.w("NativeDB", "旧图片库删不掉");
                if (verFile.exists()) verFile.delete();
            }
        }
        File f = copyAssetIfNeeded(DB_ASSET_DIR + "images.db", "images.db", ver, false);
        imagesDb = SQLiteDatabase.openDatabase(f.getAbsolutePath(), null,
                SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
    }

    /** assets 里带的题库版本（db-version.txt 首段），读不到返回 null。 */
    private String assetDbVersion() {
        if (cachedAssetVer != null) return cachedAssetVer.isEmpty() ? null : cachedAssetVer;
        String v = "";
        try (java.io.InputStream in = context.getAssets().open("db-version.txt")) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[256];
            int n;
            while ((n = in.read(b)) > 0) bos.write(b, 0, n);
            String[] parts = new String(bos.toByteArray(), "UTF-8").trim().split("\\s+");
            v = parts.length > 0 ? parts[0].trim() : "";
        } catch (Throwable ignored) {}
        cachedAssetVer = v;
        return v.isEmpty() ? null : v;
    }

    private static String cachedAssetVer = null;

    /** 当前生效的题库版本（私有目录记录，没有则返回空串）。 */
    @JavascriptInterface
    public String getDbVersion() {
        try {
            File v = new File(new File(context.getFilesDir(), "db"), "tiku_app.db.ver");
            return v.exists() ? readText(v).trim() : "";
        } catch (Throwable t) {
            return "";
        }
    }

    /** 关闭连接（热更新前置：替换文件前必须先关）。 */
    @JavascriptInterface
    public void close() {
        try { if (tikuDb != null && tikuDb.isOpen()) tikuDb.close(); } catch (Throwable ignored) {}
        try { if (imagesDb != null && imagesDb.isOpen()) imagesDb.close(); } catch (Throwable ignored) {}
        tikuDb = null;
        imagesDb = null;
    }

    /**
     * 用已下载好的文件替换题库（热更新落地）。
     * 原子性：先关连接 → 旧库改名 .old → 新库 rename → 失败则回滚 → 重开。
     * @param tmpPath 下载并校验通过的新库绝对路径
     * @param version 新版本号（写入 tiku_app.db.ver，防止下次被 assets 旧版本覆盖）
     */
    @JavascriptInterface
    public String replaceTiku(String tmpPath, String version) {
        File dir = new File(context.getFilesDir(), "db");
        File target = new File(dir, "tiku_app.db");
        File old = new File(dir, "tiku_app.db.old");
        File tmp = new File(tmpPath);
        if (!tmp.exists() || tmp.length() == 0) return "error: 新库文件不存在";
        close();
        try {
            if (old.exists()) old.delete();
            if (target.exists() && !target.renameTo(old)) return "error: 旧库改名失败";
            if (!tmp.renameTo(target)) {
                if (old.exists()) old.renameTo(target);   // 回滚
                return "error: 新库就位失败";
            }
            writeText(new File(dir, "tiku_app.db.ver"), version == null ? "" : version.trim());
            if (old.exists()) old.delete();
            return open();   // 重新打开并验证
        } catch (Throwable t) {
            if (old.exists() && !target.exists()) old.renameTo(target);
            return "error: " + t.getMessage();
        }
    }

    /** 回滚到上一个版本（.old 还在时可用）。 */
    @JavascriptInterface
    public String rollback() {
        File dir = new File(context.getFilesDir(), "db");
        File target = new File(dir, "tiku_app.db");
        File old = new File(dir, "tiku_app.db.old");
        if (!old.exists()) return "error: 没有可回滚的备份";
        close();
        try {
            if (target.exists()) target.delete();
            if (!old.renameTo(target)) return "error: 回滚失败";
            return open();
        } catch (Throwable t) {
            return "error: " + t.getMessage();
        }
    }

    /**
     * 应用题库增量包（比全量替换省 90%+ 流量）。
     *
     * 增量包结构（tools/build-tiku-delta.mjs 产出）：
     *   { schema, from, to, papers:[行...], questions:[行...], deleted:{ questions:[id], papers:[id] } }
     * 行是「整行」，所以直接 INSERT OR REPLACE 即可，不需要字段级 diff。
     *
     * @param jsonGzPath 下载好的 .json.gz 绝对路径
     * @param version    应用成功后写入的 dbVersion
     * @return {"ok":true,"papers":n,"questions":n,"deleted":n,"ms":n} 或 "error: ..."
     */
    @JavascriptInterface
    public String applyDelta(String jsonGzPath, String version) {
        File src = new File(jsonGzPath);
        if (!src.exists() || src.length() == 0) return "error: 增量包不存在";
        try {
            openIfNeeded();
            // openIfNeeded() 给的是 READONLY 连接，写增量必须先换成可写连接
            if (tikuDb != null && tikuDb.isOpen()) {
                tikuDb.close();
                tikuDb = null;
            }
            File writeDb = new File(context.getFilesDir(), "db/tiku_app.db");
            tikuDb = SQLiteDatabase.openDatabase(writeDb.getAbsolutePath(), null,
                    SQLiteDatabase.OPEN_READWRITE | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
            String json;
            // UpdateManager.download() 对 .gz 已经 gunzip 过；这里按魔数判断，两种情况都能吃
            boolean isGz = false;
            try (java.io.FileInputStream fis = new java.io.FileInputStream(src)) {
                int b0 = fis.read(), b1 = fis.read();
                isGz = b0 == 0x1f && b1 == 0x8b;
            }
            // v2 的增量包是 NDJSON：首行是头，后面一行一条记录。
            // v1 是整块 JSON —— 85MB 的包一次性读进内存，中低端机直接 OOM
            // （Failed to allocate a 134217744 byte allocation）。所以优先走流式。
            JSONObject head = peekHeader(src, isGz);
            if (head != null && head.optInt("v", 0) >= 2) {
                return applyDeltaV2(src, isGz, head, version);
            }
            try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                java.io.InputStream in = isGz
                        ? new GZIPInputStream(new FileInputStream(src))
                        : new FileInputStream(src);
                try {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                } finally {
                    in.close();
                }
                json = bos.toString("UTF-8");
            }

            JSONObject root = new JSONObject(json);
            String from = root.optString("from", "");
            String local = getDbVersion();
            if (!from.isEmpty() && !local.isEmpty() && !local.equals(from)) {
                return "error: 版本不匹配（本地 " + local + "，增量要求 " + from + "）";
            }

            JSONArray papers = root.optJSONArray("papers");
            JSONArray questions = root.optJSONArray("questions");
            JSONObject deleted = root.optJSONObject("deleted");
            // 分类索引整表下发（它在 practice.db 里，没法做行级 diff）。
            // 不带它的话，新题能搜到、能算进总数，但进不了模块树 —— 题库页题数会停在旧值。
            JSONArray qc = root.optJSONArray("question_categories");

            long t0 = System.currentTimeMillis();
            int np = 0, nq = 0, nd = 0, nqc = 0;
            // 老版本 App 装出来的库可能少列（早期打包为了省体积砍掉过 papers.time 等），
            // 这里按需补列，否则 INSERT OR REPLACE 会报 "no column named xxx"。
            ensureColumns(tikuDb);
            tikuDb.beginTransaction();
            try {
                np = upsertRows("papers", papers);
                nq = upsertRows("questions", questions);
                if (qc != null && qc.length() > 0) {
                    tikuDb.execSQL("DELETE FROM question_categories");
                    nqc = upsertRows("question_categories", qc);
                }
                if (deleted != null) {
                    nd += deleteIds("questions", "questionId", deleted.optJSONArray("questions"));
                    nd += deleteIds("papers", "id", deleted.optJSONArray("papers"));
                }
                tikuDb.setTransactionSuccessful();
            } finally {
                tikuDb.endTransaction();
            }

            String v = version == null ? root.optString("to", "") : version.trim();
            if (!v.isEmpty()) writeText(new File(context.getFilesDir(), "db/tiku_app.db.ver"), v);
            if (src.exists()) src.delete();

            // 写连接用完就关，让后续查询重开只读连接（也避免长期持有写锁）
            try { tikuDb.close(); } catch (Throwable ignored) { }
            tikuDb = null;

            return "{\"ok\":true,\"papers\":" + np + ",\"questions\":" + nq
                    + ",\"categories\":" + nqc + ",\"deleted\":" + nd
                    + ",\"ms\":" + (System.currentTimeMillis() - t0) + "}";
        } catch (Throwable t) {
            return "error: " + t.getMessage();
        }
    }

    /** 把一批「整行 JSON」写进表里，已存在则覆盖。 */
    private int upsertRows(String table, JSONArray rows) throws Exception {
        if (rows == null || rows.length() == 0) return 0;
        int n = 0;
        for (int i = 0; i < rows.length(); i++) {
            upsertOne(table, rows.getJSONObject(i));
            n++;
        }
        return n;
    }

    /** 单行 upsert；流式增量包逐行喂进来，内存占用与包大小无关 */
    private void upsertOne(String table, JSONObject r) throws Exception {
        StringBuilder cols = new StringBuilder();
        StringBuilder marks = new StringBuilder();
        List<Object> vals = new ArrayList<>();
        Iterator<String> it = r.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (cols.length() > 0) { cols.append(','); marks.append(','); }
            cols.append('"').append(k).append('"');
            marks.append('?');
            Object v = r.get(k);
            vals.add(v == JSONObject.NULL ? null : v);
        }
        String sql = "INSERT OR REPLACE INTO " + table + " (" + cols + ") VALUES (" + marks + ")";
        android.database.sqlite.SQLiteStatement st = tikuDb.compileStatement(sql);
        st.clearBindings();
        for (int j = 0; j < vals.size(); j++) {
            Object v = vals.get(j);
            int idx = j + 1;
            if (v == null) st.bindNull(idx);
            else if (v instanceof Number) st.bindDouble(idx, ((Number) v).doubleValue());
            else st.bindString(idx, String.valueOf(v));
        }
        st.executeInsert();
    }

    /** 读第一行，只为判断增量包格式；不做整包解析 */
    private JSONObject peekHeader(File src, boolean isGz) {
        java.io.BufferedReader br = null;
        try {
            java.io.InputStream in = isGz
                    ? new GZIPInputStream(new java.io.FileInputStream(src), 1 << 16)
                    : new java.io.FileInputStream(src);
            br = new java.io.BufferedReader(new java.io.InputStreamReader(in, "UTF-8"));
            String line = br.readLine();
            if (line == null) return null;
            if (line.length() > 4 * 1024 * 1024) return null;   // v1 整包，首行就是全部
            return new JSONObject(line);
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (br != null) br.close(); } catch (Throwable ignored) { }
        }
    }

    /**
     * NDJSON 增量包：一行一条记录，逐行应用。
     * 头：{"v":2,"from":..,"to":..,"reset":["question_categories"]}
     * 行：{"t":"q","r":{...}} / {"t":"p",...} / {"t":"c",...} / {"t":"dq","id":n} / {"t":"dp","id":n}
     */
    private String applyDeltaV2(File src, boolean isGz, JSONObject head, String version) throws Exception {
        String from = head.optString("from", "");
        String local = getDbVersion();
        if (!from.isEmpty() && !local.isEmpty() && !local.equals(from)) {
            return "error: 版本不匹配（本地 " + local + "，增量要求 " + from + "）";
        }
        JSONArray reset = head.optJSONArray("reset");
        long t0 = System.currentTimeMillis();
        int np = 0, nq = 0, nqc = 0, nd = 0;
        java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(
                isGz ? new GZIPInputStream(new java.io.FileInputStream(src), 1 << 16)
                     : new java.io.FileInputStream(src), "UTF-8"), 1 << 16);
        ensureColumns(tikuDb);
        tikuDb.beginTransaction();
        try {
            if (reset != null) {
                for (int i = 0; i < reset.length(); i++) {
                    tikuDb.execSQL("DELETE FROM " + reset.getString(i));
                }
            }
            String line;
            int since = 0;
            while ((line = br.readLine()) != null) {
                if (line.isEmpty()) continue;
                JSONObject o = new JSONObject(line);
                String t = o.optString("t", "");
                if ("q".equals(t)) { upsertOne("questions", o.getJSONObject("r")); nq++; }
                else if ("p".equals(t)) { upsertOne("papers", o.getJSONObject("r")); np++; }
                else if ("c".equals(t)) { upsertOne("question_categories", o.getJSONObject("r")); nqc++; }
                else if ("dq".equals(t)) { deleteOne("questions", "questionId", o.optLong("id")); nd++; }
                else if ("dp".equals(t)) { deleteOne("papers", "id", o.optLong("id")); nd++; }
                // 每 2000 行落一次盘：事务别撑太大，中途被杀也不至于全丢
                if (++since >= 2000) {
                    tikuDb.setTransactionSuccessful();
                    tikuDb.endTransaction();
                    tikuDb.beginTransaction();
                    since = 0;
                }
            }
            tikuDb.setTransactionSuccessful();
        } finally {
            tikuDb.endTransaction();
            try { br.close(); } catch (Throwable ignored) { }
        }
        String v = version == null ? head.optString("to", "") : version.trim();
        if (!v.isEmpty()) writeText(new File(context.getFilesDir(), "db/tiku_app.db.ver"), v);
        if (src.exists()) src.delete();
        try { tikuDb.close(); } catch (Throwable ignored) { }
        tikuDb = null;
        return "{\"ok\":true,\"papers\":" + np + ",\"questions\":" + nq
                + ",\"categories\":" + nqc + ",\"deleted\":" + nd
                + ",\"ms\":" + (System.currentTimeMillis() - t0) + "}";
    }

    private void deleteOne(String table, String keyCol, long id) {
        tikuDb.execSQL("DELETE FROM " + table + " WHERE " + keyCol + " = ?", new Object[]{ id });
    }

    private int deleteIds(String table, String keyCol, JSONArray ids) {
        if (ids == null || ids.length() == 0) return 0;
        int n = 0;
        for (int i = 0; i < ids.length(); i++) {
            Object id = ids.opt(i);
            if (id == null || id == JSONObject.NULL) continue;
            tikuDb.execSQL("DELETE FROM " + table + " WHERE " + keyCol + " = ?", new Object[]{ id });
            n++;
        }
        return n;
    }

    /**
     * 补列：早期版本打包时砍掉了 papers.time / exerciseId / crawledAt 和
     * questions.questionType 等列。老用户覆盖安装后库里还是旧结构，
     * 应用增量会报 SQLITE_ERROR: table papers has no column named time。
     * 这里用 PRAGMA 列出已有列，缺的 ALTER TABLE 补上（幂等，且不碰已有数据）。
     */
    private void ensureColumns(SQLiteDatabase db) {
        ensureColumns(db, "papers", new String[]{
                "time", "difficulty", "exerciseId", "chapters", "crawledAt"
        });
        ensureColumns(db, "questions", new String[]{
                "questionType", "material", "optionType", "difficulty",
                "source", "hasVideo", "analysisHtml", "contentHtml"
        });
    }

    private void ensureColumns(SQLiteDatabase db, String table, String[] cols) {
        java.util.Set<String> have = new java.util.HashSet<>();
        try (android.database.Cursor c = db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
            while (c.moveToNext()) {
                int idx = c.getColumnIndex("name");
                if (idx >= 0) have.add(c.getString(idx));
            }
        } catch (Throwable t) {
            android.util.Log.w("NativeDB", "读 " + table + " 结构失败: " + t.getMessage());
            return;
        }
        for (String col : cols) {
            if (have.contains(col)) continue;
            try {
                db.execSQL("ALTER TABLE " + table + " ADD COLUMN " + col + " TEXT");
                android.util.Log.i("NativeDB", "补列 " + table + "." + col);
            } catch (Throwable t) {
                android.util.Log.w("NativeDB", "补列 " + table + "." + col + " 失败: " + t.getMessage());
            }
        }
    }

    /** 多行查询：返回 JSON 数组字符串。 */
    @JavascriptInterface
    public String all(String sql, String paramsJson) {
        try {
            openIfNeeded();
            return queryRows(pickDb(sql), sql, parseParams(paramsJson)).toString();
        } catch (Throwable t) {
            return errorJson(t);
        }
    }

    /** 单行查询：返回 JSON 对象字符串；未命中返回 "null"。 */
    @JavascriptInterface
    public String get(String sql, String paramsJson) {
        try {
            openIfNeeded();
            JSONArray rows = queryRows(pickDb(sql), sql, parseParams(paramsJson));
            return rows.length() > 0 ? rows.get(0).toString() : "null";
        } catch (Throwable t) {
            return errorJson(t);
        }
    }

    /** 当前构建的 flavor（prod / lab）。实验版前端靠它决定要不要加载实验室与能力引擎。 */
    @JavascriptInterface
    public String flavor() {
        return BuildConfig.FLAVOR;
    }

    /** 按 SQL 内容路由到对应库（images 表 → images.db，其余 → tiku_app.db）。 */
    private SQLiteDatabase pickDb(String sql) {
        String s = sql == null ? "" : sql;
        boolean images = s.contains("FROM images") || s.contains("from images");
        if (!images) return tikuDb;
        // 图库是后台线程在拷的，这次查询如果抢在前面，就同步等一下（通常只发生在首屏刚出来时）
        if (imagesDb == null || !imagesDb.isOpen()) {
            try { ensureImages(); } catch (Throwable ignored) { return tikuDb; }
        }
        return imagesDb;
    }

    private JSONArray queryRows(SQLiteDatabase db, String sql, String[] args) throws org.json.JSONException {
        JSONArray rows = new JSONArray();
        try (Cursor c = db.rawQuery(sql, args)) {
            int n = c.getColumnCount();
            String[] cols = c.getColumnNames();
            while (c.moveToNext()) {
                JSONObject row = new JSONObject();
                for (int i = 0; i < n; i++) {
                    row.put(cols[i], colValue(c, i));
                }
                rows.put(row);
            }
        }
        return rows;
    }

    private Object colValue(Cursor c, int i) {
        switch (c.getType(i)) {
            case Cursor.FIELD_TYPE_INTEGER:
                return c.getLong(i);
            case Cursor.FIELD_TYPE_FLOAT:
                return c.getDouble(i);
            case Cursor.FIELD_TYPE_BLOB:
                try {
                    JSONObject o = new JSONObject();
                    o.put("__b64", Base64.encodeToString(c.getBlob(i), Base64.NO_WRAP));
                    return o;
                } catch (Exception e) {
                    return JSONObject.NULL;
                }
            case Cursor.FIELD_TYPE_NULL:
                return JSONObject.NULL;
            default:
                return c.getString(i);
        }
    }

    private String[] parseParams(String json) {
        if (json == null || json.isEmpty() || json.equals("null")) return null;
        try {
            JSONArray arr = new JSONArray(json);
            String[] out = new String[arr.length()];
            for (int i = 0; i < arr.length(); i++) {
                Object v = arr.get(i);
                out[i] = v == null || v == JSONObject.NULL ? null : String.valueOf(v);
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    private String errorJson(Throwable t) {
        String m = t.getMessage() == null ? t.toString() : t.getMessage();
        return "{\"__error\":\"" + m.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
    }

    /**
     * 流式复制 assets 文件到私有目录。
     * assetVersion 为 null 时按"已存在且非空"跳过（图片库这类不带版本的）；
     * 非 null 时按版本比对：
     *   - 本地无记录 或 本地版本 < assets 版本 → 重拷（覆盖安装后 assets 变新）
     *   - 本地版本 >= assets 版本 → 跳过（本地被网络热更新过，不能被旧 assets 覆盖回去）
     */
    private File copyAssetIfNeeded(String assetPath, String name, String assetVersion) throws Exception {
        return copyAssetIfNeeded(assetPath, name, assetVersion, true);
    }

    /**
     * @param report 是否计入首启进度条。图片库是后台拷的，不能混进题库的百分比里。
     */
    private File copyAssetIfNeeded(String assetPath, String name, String assetVersion, boolean report) throws Exception {
        return copyAssetIfNeeded(assetPath, name, assetVersion, report, 0, 0);
    }

    /**
     * @param expectQ  assets 里这道库的 questions 行数（0 = 不校验）
     * @param expectQc assets 里这道库的 question_categories 行数（0 = 不校验）
     *
     * 为什么按行数校验而不只信版本号：
     *   版本号存在单独的小文件里，库文件是另一个。任何一步出错（增量包写一半、
     *   覆盖安装把 assets 换成新的但私有目录还是旧的、用户手动替换过）都会出现
     *   「版本号是新的、数据是旧的」。这时前端会认为已是最新、不再更新，
     *   用户看到的就是「更新完题库，题变少了」。
     *   校验一次 COUNT 大约几十毫秒，只在启动时做。
     */
    private File copyAssetIfNeeded(String assetPath, String name, String assetVersion, boolean report,
                                   int expectQ, int expectQc) throws Exception {
        File dir = new File(context.getFilesDir(), "db");
        if (!dir.exists() && !dir.mkdirs()) throw new RuntimeException("无法创建数据库目录");
        File target = new File(dir, name);
        File verFile = new File(dir, name + ".ver");

        if (assetVersion == null) {
            if (target.exists() && target.length() > 0) return target;
        } else {
            String localVer = verFile.exists() ? readText(verFile).trim() : "";
            boolean has = target.exists() && target.length() > 1024 * 1024;   // 1MB 以上才算有效库
            // 本地已经通过增量更新到比 assets 更新的版本 → 绝不能拿 assets 的旧库盖回去
            // （行数必然对不上，旧的行数校验会把它当成"损坏"从而回滚，等于每次启动都白拷 332MB）
            if (has && !localVer.isEmpty() && cmpVer(localVer, assetVersion) > 0) {
                android.util.Log.i("NativeDB", "本地题库 " + localVer + " 已新于 assets "
                        + assetVersion + "，跳过校验与重拷");
                return target;
            }
            if (has) {
                // 版本相同或落后：先看内容对不对，再看版本号 —— 版本号可能是假的
                boolean contentOk = true;
                if (expectQ > 0 || expectQc > 0) {
                    int[] c = countRows(target);
                    if (c == null) {
                        contentOk = false;
                    } else if (expectQ > 0 && Math.abs(c[0] - expectQ) > 0) {
                        android.util.Log.w("NativeDB", "题库行数不符：本地 " + c[0] + " / assets " + expectQ + "，重拷");
                        contentOk = false;
                    } else if (expectQc > 0 && c[1] != expectQc) {
                        android.util.Log.w("NativeDB", "分类行数不符：本地 " + c[1] + " / assets " + expectQc + "，重拷");
                        contentOk = false;
                    }
                }
                if (contentOk) {
                    // 行数一样不代表内容一样：q_bad 打标、材料回填、解析覆盖都不会改行数。
                    // 所以只有版本号也相同才敢跳过；版本不同一律重拷（宁可多拷一次，也别让用户带着旧数据）。
                    if (localVer.equals(assetVersion)) return target;
                    android.util.Log.i("NativeDB", "本地题库 " + localVer + " ≠ assets " + assetVersion
                            + "（行数相同但内容可能已修），重拷");
                }
            }
        }

        File tmp = new File(dir, name + ".tmp");
        AssetManager am = context.getAssets();
        if (report) {
            copyName = name.equals("tiku_app.db") ? "题库" : "图片库";
            if (copyTotal <= 0) copyTotal = 332L * 1024 * 1024;
        }
        try (InputStream in = am.open(assetPath); FileOutputStream out = new FileOutputStream(tmp)) {
            long t0 = System.currentTimeMillis();
            byte[] buf = new byte[256 * 1024];
            int n;
            long written = 0;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                written += n;
                if (report) copyBytes += n;
            }
            android.util.Log.i("NativeDB", "copy " + name + " " + (written / 1048576) + "MB in "
                    + (System.currentTimeMillis() - t0) + "ms");
        }
        if (target.exists()) target.delete();
        if (!tmp.renameTo(target)) {
            if (tmp.exists()) tmp.delete();
            throw new RuntimeException("题库文件就位失败: " + name);
        }
        if (assetVersion != null) writeText(verFile, assetVersion);
        return target;
    }

    /** 首启准备完成后把进度顶满，SplashBridge 靠它决定什么时候显示「好了」 */
    public static void finishCopyProgress() {
        if (copyTotal > 0) copyBytes = copyTotal;
    }

    /**
     * 快速取一张库的 questions / question_categories 行数。
     * 返回 null 表示打不开或表不存在（调用方按「内容不可信」处理）。
     */
    /**
     * 比较两个版本号（形如 0.5.10-20261110-1010）。
     * 不能直接用 String.compareTo："0.5.10" < "0.5.9"，字典序会把新版判成旧版。
     * 先按 "-" 切出「数字版本」与「时间戳」，数字版本按 "." 逐段转 int 比。
     */
    private static int cmpVer(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        String[] x = a.trim().split("-", 2);
        String[] y = b.trim().split("-", 2);
        String[] p = x[0].isEmpty() ? new String[0] : x[0].split("\\.");
        String[] q = y[0].isEmpty() ? new String[0] : y[0].split("\\.");
        int n = Math.max(p.length, q.length);
        for (int i = 0; i < n; i++) {
            int pi = i < p.length ? parseIntSafe(p[i]) : 0;
            int qi = i < q.length ? parseIntSafe(q[i]) : 0;
            if (pi != qi) return pi < qi ? -1 : 1;
        }
        String ts1 = x.length > 1 ? x[1] : "";
        String ts2 = y.length > 1 ? y[1] : "";
        return ts1.compareTo(ts2);
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9]", ""));
        } catch (Exception e) {
            return 0;
        }
    }

    private int[] countRows(File db) {
        SQLiteDatabase d = null;
        try {
            d = SQLiteDatabase.openDatabase(db.getAbsolutePath(), null,
                    SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
            long q = android.database.DatabaseUtils.longForQuery(d, "SELECT COUNT(1) FROM questions", null);
            long c;
            try {
                c = android.database.DatabaseUtils.longForQuery(d, "SELECT COUNT(1) FROM question_categories", null);
            } catch (Throwable t) {
                c = -1;
            }
            return new int[]{(int) q, (int) c};
        } catch (Throwable t) {
            return null;
        } finally {
            if (d != null) { try { d.close(); } catch (Throwable ignored) { } }
        }
    }

    private String readAssetText(String assetPath) {
        try (InputStream in = context.getAssets().open(assetPath)) {
            byte[] b = new byte[in.available() > 0 ? in.available() : 256];
            int n = in.read(b);
            return n > 0 ? new String(b, 0, n, "UTF-8").trim() : "";
        } catch (Throwable t) {
            return "";
        }
    }

    private String readText(File f) throws Exception {
        try (InputStream in = new java.io.FileInputStream(f)) {
            byte[] b = new byte[(int) Math.min(f.length(), 4096)];
            int n = in.read(b);
            return n > 0 ? new String(b, 0, n, "UTF-8") : "";
        }
    }

    private void writeText(File f, String s) throws Exception {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write((s == null ? "" : s).getBytes("UTF-8"));
        }
    }
}
