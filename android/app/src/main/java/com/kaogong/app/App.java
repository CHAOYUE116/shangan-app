package com.kaogong.app;

import android.app.Application;

/**
 * 进程级预热。
 *
 * 冷启动慢的主要原因不是 JS（整页 DOMContentLoaded 只要 1 秒），而是原生侧：
 * WebView 创建、Capacitor 插件注入，以及打开 348 MB 的题库。
 * 这三件事原本全串在 MainActivity.onCreate 里，一件等一件。
 *
 * 放在 Application 里先起一条后台线程把库打开，让「开库」和「WebView 初始化」并行。
 * 拿不到也不影响启动：MainActivity 会退回到自己 new 一个。
 */
public class App extends Application {

    /** 预热好的桥，MainActivity 直接复用，避免第二次打开同一个库。 */
    public static volatile NativeDbBridge warm;

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            final NativeDbBridge db = new NativeDbBridge(this);
            warm = db;
            Thread t = new Thread(() -> {
                long s = System.currentTimeMillis();
                try {
                    db.open();
                    android.util.Log.i("NativeDB", "warm open in " + (System.currentTimeMillis() - s) + "ms");
                } catch (Throwable e) {
                    android.util.Log.w("NativeDB", "warm open failed: " + e.getMessage());
                }
            }, "db-warm");
            t.setDaemon(true);
            t.start();
        } catch (Throwable e) {
            android.util.Log.w("NativeDB", "warm 线程没起来：" + e.getMessage());
        }
    }
}
