package com.kaogong.app;

import android.app.Activity;
import android.content.Intent;
import android.webkit.JavascriptInterface;

/**
 * 前端调用 window.NativeWebView.open(url, title) 打开 App 内嵌浏览器。
 */
public class WebBridge {

    private final Activity activity;

    public WebBridge(Activity activity) {
        this.activity = activity;
    }

    @JavascriptInterface
    public void open(String url, String title) {
        if (url == null || url.isEmpty()) return;
        try {
            Intent i = new Intent(activity, WebViewActivity.class);
            i.putExtra(WebViewActivity.EXTRA_URL, url);
            i.putExtra(WebViewActivity.EXTRA_TITLE, title == null ? "" : title);
            activity.startActivity(i);
        } catch (Exception ignored) {
        }
    }

    @JavascriptInterface
    public boolean available() {
        return true;
    }
}
