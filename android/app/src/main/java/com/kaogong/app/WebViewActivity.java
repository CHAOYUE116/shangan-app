package com.kaogong.app;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

/**
 * App 内嵌浏览器：用于打开飞书知识库这类需要登录态的外部页面。
 * 登录 cookie 走系统 CookieManager，关掉再开仍是登录态。
 */
public class WebViewActivity extends AppCompatActivity {

    public static final String EXTRA_URL = "url";
    public static final String EXTRA_TITLE = "title";

    private WebView web;
    private ProgressBar bar;
    private TextView titleView;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String url = getIntent().getStringExtra(EXTRA_URL);
        String name = getIntent().getStringExtra(EXTRA_TITLE);
        if (name == null || name.isEmpty()) name = "备考资料库";

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        // ---- 顶部栏 ----
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(6), dp(8), dp(8), dp(8));
        topBar.setBackgroundColor(0xFFF7F8FA);

        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextSize(28);
        back.setTextColor(0xFF1F2329);
        back.setPadding(dp(10), 0, dp(12), dp(4));
        back.setOnClickListener(v -> handleBack());
        topBar.addView(back);

        titleView = new TextView(this);
        titleView.setText(name);
        titleView.setTextSize(16);
        titleView.setTextColor(0xFF1F2329);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        topBar.addView(titleView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView openSys = new TextView(this);
        openSys.setText("浏览器");
        openSys.setTextSize(13);
        openSys.setTextColor(0xFF3370FF);
        openSys.setPadding(dp(12), dp(6), dp(10), dp(6));
        openSys.setOnClickListener(v -> openInSystemBrowser());
        topBar.addView(openSys);

        root.addView(topBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setVisibility(View.GONE);
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)));

        // ---- WebView ----
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        // 去掉 "; wv" 标记，部分站点会据此降级或拒绝
        s.setUserAgentString(s.getUserAgentString().replace("; wv", ""));

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int p) {
                bar.setProgress(p);
                bar.setVisibility(p >= 100 ? View.GONE : (p > 3 ? View.VISIBLE : View.GONE));
            }

            @Override
            public void onReceivedTitle(WebView view, String t) {
                if (t != null && !t.isEmpty()) titleView.setText(t);
            }
        });

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                String scheme = u == null ? "" : u.getScheme();
                if (u == null || scheme == null) return false;
                // 非 http(s)（如 feishu://、mailto:）交给系统处理
                if (!scheme.startsWith("http")) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                    return true;
                }
                return false;
            }
        });

        root.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        if (url != null && !url.isEmpty()) web.loadUrl(url);
        else finish();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void handleBack() {
        if (web != null && web.canGoBack()) web.goBack();
        else finish();
    }

    @Override
    public void onBackPressed() {
        handleBack();
    }

    private void openInSystemBrowser() {
        try {
            String u = web != null ? web.getUrl() : null;
            if (u == null) return;
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u)));
        } catch (Exception e) {
            Toast.makeText(this, "没能唤起浏览器", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.loadUrl("about:blank");
            web.destroy();
            web = null;
        }
        try { CookieManager.getInstance().flush(); } catch (Exception ignored) { }
        super.onDestroy();
    }
}
