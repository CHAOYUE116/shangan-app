package com.kaogong.app;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

/**
 * 启动图覆盖层。
 *
 * 系统 splash（Theme.SplashScreen）在 Activity 显示后就被撤掉了，
 * 而 WebView 从加载 HTML 到首屏渲染完还要几秒，中间是一段白屏。
 *
 * 这里在 WebView 之上盖一张同样的 splash 图，等前端首屏渲染完
 * 调 window.AndroidSplash.hide() 再淡出，视觉上就是「启动图直接变成首页」。
 *
 * 兜底：8 秒还没收到 hide() 也强制撤掉，避免前端报错时一直盖着。
 */
public class SplashBridge {

    private static final String TAG = "Splash";
    private final Activity activity;
    private View overlay;
    private final Handler ui = new Handler(Looper.getMainLooper());

    /**
     * 前端可能比 show() 更早调用 hide()。
     *
     * WebView 创建那几秒主线程是堵死的（实测一次 traverse 就 1.3 秒），
     * MainActivity 里 post 出去的 show() 要等主线程空出来才执行；
     * 而前端把首屏画完（含本地快照直出）往往在 1.7 秒左右就调 hide() 了。
     * 早期实现里 hide() 看到 overlay 还是 null 就直接 return，
     * 紧接着 show() 才把图贴上去 —— 于是启动图一直盖到 loadHome 结束（7 秒多）。
     * 用一个标志记住「已经要求隐藏」，show() 就别再贴。
     */
    private volatile boolean hideRequested = false;

    // 首启要把 408 MB 题库解压到私有目录（APK 里是压缩存放，写出要十几秒）。
    // 只放一张图标用户会以为卡死了，所以挂个轮询，把 NativeDbBridge 的拷贝字节数
    // 变成「第一次启动，正在准备题库 · 42%」。
    private android.widget.TextView copyTip;
    private final Runnable copyTicker = new Runnable() {
        @Override public void run() {
            if (copyTip == null) return;
            long total = NativeDbBridge.copyTotal;
            long done = NativeDbBridge.copyBytes;
            if (total > 0 && done < total) {
                int pct = (int) Math.min(99, done * 100 / total);
                String name = NativeDbBridge.copyName;
                if (name == null || name.isEmpty()) name = "题库";
                copyTip.setText("第一次启动，正在准备" + name + " · " + pct + "%");
            } else if (done > 0) {
                copyTip.setText("题库就绪，正在打开…");
            }
            ui.postDelayed(this, 250);
        }
    };

    public SplashBridge(Activity a) { this.activity = a; }

    /** 显示覆盖层（在主线程调用） */
    public void show() {
                try {
            if (hideRequested) return;   // 前端已经说完事了，别再把图贴回去
            if (overlay != null) return;
            android.view.ViewGroup parent = findParent();
            if (parent == null) return;

            android.widget.LinearLayout box = new android.widget.LinearLayout(activity);
            box.setOrientation(android.widget.LinearLayout.VERTICAL);
            box.setGravity(android.view.Gravity.CENTER);
            box.setBackgroundColor(0xFFF5F6F8);

            android.widget.ImageView iv = new android.widget.ImageView(activity);
            iv.setImageResource(R.drawable.splash);
            // 保持比例居中，不拉伸
            iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            box.addView(iv, new android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

            copyTip = new android.widget.TextView(activity);
            copyTip.setText("");
            copyTip.setTextSize(13f);
            copyTip.setTextColor(0xFF8A8F99);
            copyTip.setGravity(android.view.Gravity.CENTER);
            android.widget.LinearLayout.LayoutParams tp = new android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tp.bottomMargin = (int) (56 * activity.getResources().getDisplayMetrics().density);
            box.addView(copyTip, tp);

            if (parent instanceof android.widget.FrameLayout) {
                android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                lp.gravity = android.view.Gravity.CENTER;
                parent.addView(box, lp);
            } else {
                parent.addView(box, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            overlay = box;
            ui.post(copyTicker);
            ui.postDelayed(() -> hide(), 8000);
            // 万一 hide() 比 show() 先到（主线程被 WebView 初始化堵住时会这样），
            // 这里补一次，否则启动图会一直盖到 loadHome 结束。
            if (hideRequested) { ui.post(this::hide); }
        } catch (Throwable t) {
            Log.w(TAG, "show fail", t);
        }
    }

    private ViewGroup findParent() {
        try {
            View web = ((com.getcapacitor.BridgeActivity) activity).getBridge().getWebView();
            return (ViewGroup) web.getParent();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 前端首屏渲染完调用 */
    @android.webkit.JavascriptInterface
    public void hide() {
                hideRequested = true;
        ui.post(() -> {
            final View v = overlay;
            if (v == null) return;
            overlay = null;
            ui.removeCallbacks(copyTicker);
            copyTip = null;
            try {
                v.animate().alpha(0f).setDuration(160).withEndAction(() -> {
                    try {
                        ViewGroup p = (ViewGroup) v.getParent();
                        if (p != null) p.removeView(v);
                    } catch (Throwable ignored) { }
                }).start();
            } catch (Throwable t) {
                try {
                    ViewGroup p = (ViewGroup) v.getParent();
                    if (p != null) p.removeView(v);
                } catch (Throwable ignored) { }
            }
        });
    }
}
