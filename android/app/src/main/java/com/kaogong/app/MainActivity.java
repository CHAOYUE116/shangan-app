package com.kaogong.app;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    private SplashBridge splash;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // 始终开启 WebView 远程调试（本地联调/真机验证用；仅本机 adb forward 可连，无安全影响）
        // 必须在 super.onCreate（BridgeActivity 创建 WebView）之前调用，否则开关失效、9222 不监听
        android.webkit.WebView.setWebContentsDebuggingEnabled(true);
        super.onCreate(savedInstanceState);
        try {
            // 原生 SQLite 同步桥：注入 window.NativeDB（替代 sql.js 整体加载题库，
            // 首启内存从 ~270MB 降到几十 MB；不可用时前端自动回退 sql.js）
            // 优先用 Application 里已经预热好的那一个，省掉一次开库（348 MB）
            NativeDbBridge nativeDb = App.warm != null ? App.warm : new NativeDbBridge(this);
            getBridge().getWebView().addJavascriptInterface(nativeDb, "NativeDB");
            // 题库在线更新器：window.AppUpdate（下载/校验/原子替换，复用同一个 db 实例）
            getBridge().getWebView().addJavascriptInterface(new UpdateManager(this, nativeDb), "AppUpdate");
            // 内嵌浏览器：window.NativeWebView.open(url, title)。
            // 飞书知识库是私有的（302 到 accounts.feishu.cn），iframe 打不开，必须原生 WebView 带登录态。
            getBridge().getWebView().addJavascriptInterface(new WebBridge(this), "NativeWebView");
        } catch (Throwable t) {
            android.util.Log.w("NativeDB", "bridge 注册失败（将回退 sql.js）：" + t.getMessage());
        }

        // 启动图覆盖层：系统 splash 撤掉后，WebView 首屏还没渲染完会白屏几秒。
        // 这里盖一张同样的图，前端首屏渲染完调 window.AndroidSplash.hide() 再淡出。
        try {
            splash = new SplashBridge(this);
            getBridge().getWebView().addJavascriptInterface(splash, "AndroidSplash");
            getBridge().getWebView().post(() -> {
                try { splash.show(); } catch (Throwable ignored) { }
            });
        } catch (Throwable t) {
            android.util.Log.w("Splash", "注册失败：" + t.getMessage());
        }

        // 仅 debug 构建：页面加载后触发前端自测（runSelfTest 输出到 logcat 的 Capacitor/Console）
        if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            getBridge().getWebView().postDelayed(() -> {
                try {
                    getBridge().getWebView().evaluateJavascript(
                            "if (typeof runSelfTest === 'function') runSelfTest();", null);
                } catch (Throwable ignored) {
                }
            }, 8000);
        }
    }

    /**
     * 安卓物理返回键：先问前端要不要消费（关弹层 / 退出练习 / 回上一级），
     * 前端返回 "pass" 才交给系统（退出 App）。
     * 之前没重写，返回键直接 finish()，在任何页面都会退出应用。
     */
    @Override
    public void onBackPressed() {
        try {
            getBridge().getWebView().evaluateJavascript(
                    "window.__handleBack ? window.__handleBack() : 'pass'",
                    value -> {
                        if (value != null && value.contains("handled")) return;   // 前端已处理
                        // 首页：退到后台（不杀进程，用户再点图标能秒回）
                        runOnUiThread(() -> {
                            if (!moveTaskToBack(true)) finish();
                        });
                    });
        } catch (Throwable t) {
            moveTaskToBack(true);
        }
    }
}
