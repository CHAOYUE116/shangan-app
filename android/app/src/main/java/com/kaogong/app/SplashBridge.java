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

    public SplashBridge(Activity a) { this.activity = a; }

    /** 显示覆盖层（在主线程调用） */
    public void show() {
        try {
            if (overlay != null) return;
            android.view.ViewGroup parent = findParent();
            if (parent == null) return;

            android.widget.ImageView iv = new android.widget.ImageView(activity);
            iv.setImageResource(R.drawable.splash);
            // 保持比例居中，不拉伸；底色与图同色，边缘看不出来
            iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            iv.setBackgroundColor(0xFFF5F6F8);
            if (parent instanceof android.widget.FrameLayout) {
                android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                lp.gravity = android.view.Gravity.CENTER;
                parent.addView(iv, lp);
            } else {
                parent.addView(iv, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                iv.setTranslationX(0);
                iv.setTranslationY(0);
            }
            overlay = iv;
            ui.postDelayed(() -> hide(), 8000);
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
        ui.post(() -> {
            final View v = overlay;
            if (v == null) return;
            overlay = null;
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
