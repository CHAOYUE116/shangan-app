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

    private final Context context;
    private SQLiteDatabase tikuDb;
    private SQLiteDatabase imagesDb;

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

    private void openIfNeeded() throws Exception {
        if (tikuDb == null || !tikuDb.isOpen()) {
            // 版本比对：assets 里的 db-version.txt vs 私有目录记录的版本
            // 覆盖安装后 assets 可能变新 → 需要重拷；若本地是网络热更新过的（版本更高）→ 不倒退
            String assetVer = readAssetText("public/app-assets/db-version.txt");
            File f = copyAssetIfNeeded("public/app-assets/tiku_app.db", "tiku_app.db", assetVer);
            tikuDb = SQLiteDatabase.openDatabase(f.getAbsolutePath(), null,
                    SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
        }
        if (imagesDb == null || !imagesDb.isOpen()) {
            File f = copyAssetIfNeeded("public/app-assets/images.db", "images.db", null);
            imagesDb = SQLiteDatabase.openDatabase(f.getAbsolutePath(), null,
                    SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
        }
    }

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
            JSONObject r = rows.getJSONObject(i);
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
            n++;
        }
        return n;
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

    /** 按 SQL 内容路由到对应库（images 表 → images.db，其余 → tiku_app.db）。 */
    private SQLiteDatabase pickDb(String sql) {
        String s = sql == null ? "" : sql;
        boolean images = s.contains("FROM images") || s.contains("from images");
        return images ? imagesDb : tikuDb;
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
        File dir = new File(context.getFilesDir(), "db");
        if (!dir.exists() && !dir.mkdirs()) throw new RuntimeException("无法创建数据库目录");
        File target = new File(dir, name);
        File verFile = new File(dir, name + ".ver");

        if (assetVersion == null) {
            if (target.exists() && target.length() > 0) return target;
        } else {
            String localVer = verFile.exists() ? readText(verFile).trim() : "";
            boolean has = target.exists() && target.length() > 1024 * 1024;   // 1MB 以上才算有效库
            if (has) {
                // 关键：本地库已经在，就不再因为 assets 变新而整块重拷。
                // 320MB 顺序拷贝要几十秒，而增量包通常只有 1~3MB。
                // 覆盖安装后版本落后，交给前端「检查更新」走增量。
                if (!localVer.isEmpty() && localVer.compareTo(assetVersion) < 0) {
                    android.util.Log.i("NativeDB", "本地题库 " + localVer
                            + " 落后于 assets " + assetVersion + "，本次跳过重拷，走增量");
                }
                return target;
            }
        }

        File tmp = new File(dir, name + ".tmp");
        AssetManager am = context.getAssets();
        try (InputStream in = am.open(assetPath); FileOutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        if (target.exists()) target.delete();
        if (!tmp.renameTo(target)) {
            if (tmp.exists()) tmp.delete();
            throw new RuntimeException("题库文件就位失败: " + name);
        }
        if (assetVersion != null) writeText(verFile, assetVersion);
        return target;
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
