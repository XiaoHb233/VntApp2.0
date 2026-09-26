package com.rustvnt.vntapp;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

/**
 * 内网 WebView Activity - 承载内网网站访问
 * 继承 AppCompatActivity 以获取 OnBackPressedDispatcher
 */
public class IntranetWebActivity extends AppCompatActivity {
    private static final String TAG = "IntranetWebActivity";
    private static final int FILE_CHOOSER_REQUEST_CODE = 1001;
    private static final long BACK_INTERVAL = 1500;

    public static final String EXTRA_URL = "url";

    private WebView webView;
    private ProgressBar progressBar;
    private long lastBackTime;
    private ValueCallback<Uri[]> filePathCallback;

    // 页面加载超时检测
    private final Handler timeoutHandler = new Handler(Looper.getMainLooper());
    private Runnable timeoutRunnable;
    private volatile boolean pageFinished;
    private static final long PAGE_LOAD_TIMEOUT_MS = 15000;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        // AppCompatActivity 要求 supportRequestWindowFeature 必须在 super.onCreate() 之前调
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE);
        super.onCreate(savedInstanceState);

        // 让系统窗口 insets（状态栏/导航栏）正常生效；targetSdk 37 永远 >= R
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(true);
        }
        setContentView(R.layout.activity_intranet_web);

        Intent intent = getIntent();
        String url = intent.getStringExtra(EXTRA_URL);

        webView = findViewById(R.id.webView);
        progressBar = findViewById(R.id.progressBar);

        initWebView(url);
        setupBackHandler();
    }

    /** 注册 Predictive Back 回调，替代旧式 onKeyDown 拦截 */
    private void setupBackHandler() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                long now = System.currentTimeMillis();
                if (now - lastBackTime > BACK_INTERVAL) {
                    lastBackTime = now;
                    if (webView != null && webView.canGoBack()) {
                        webView.goBack();
                        Toast.makeText(IntranetWebActivity.this, "再按一次返回退出",
                                Toast.LENGTH_SHORT).show();
                    } else {
                        // 交给系统处理（finish Activity）
                        setEnabled(false);
                        getOnBackPressedDispatcher().onBackPressed();
                    }
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });
    }

    private void initWebView(String url) {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setUserAgentString("Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/91.0 Mobile Safari/537.36");
        // 允许混合 HTTP/HTTPS 内容，内网页面常出现
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        // 允许 File 协议访问（内网可能有本地资源）
        settings.setAllowFileAccess(true);

        // minSdk 24 >= KITKAT，可直接调用
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        // URL 加载策略：http/https 在 WebView 内打开，其他 scheme 交给外部 App
        webView.setWebViewClient(new WebViewClient() {
            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrlLoading(Uri.parse(url));
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUrlLoading(request.getUrl());
            }

            private boolean handleUrlLoading(Uri uri) {
                String scheme = uri.getScheme();
                if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                    return false;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (Exception e) {
                    Log.e(TAG, "无法打开外部链接: " + uri, e);
                    Toast.makeText(IntranetWebActivity.this, "无法打开该链接",
                            Toast.LENGTH_SHORT).show();
                }
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                progressBar.setVisibility(View.VISIBLE);
                // 启动超时检测
                startTimeoutCheck(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progressBar.setVisibility(View.GONE);
                pageFinished = true;
                cancelTimeoutCheck();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                // 只在主文档失败时提示，子资源（图片/JS/CSS）失败不打扰
                if (request.isForMainFrame()) {
                    String desc = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                            ? error.getDescription().toString() : "加载失败";
                    Log.e(TAG, "onReceivedError: url=" + request.getUrl() + " err=" + desc);
                    Toast.makeText(IntranetWebActivity.this,
                            "页面加载失败：" + desc, Toast.LENGTH_LONG).show();
                }
                super.onReceivedError(view, request, error);
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                            android.webkit.WebResourceResponse errorResponse) {
                if (request.isForMainFrame()) {
                    int statusCode = errorResponse.getStatusCode();
                    Log.e(TAG, "onReceivedHttpError: url=" + request.getUrl() + " HTTP " + statusCode);
                    Toast.makeText(IntranetWebActivity.this,
                            "服务器返回 HTTP " + statusCode, Toast.LENGTH_LONG).show();
                }
                super.onReceivedHttpError(view, request, errorResponse);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
                // 超时取消只由 onPageFinished 负责，进度到 100 不代表加载结束
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                filePathCallback = callback;
                openFileChooser(params);
                return true;
            }
        });

        if (url != null) {
            webView.loadUrl(url);
        }
    }

    /** 启动页面加载超时检测，15s 还没 onPageFinished 就提示用户 */
    private void startTimeoutCheck(String url) {
        cancelTimeoutCheck();
        pageFinished = false;
        timeoutRunnable = () -> {
            if (!pageFinished) {
                Log.w(TAG, "页面加载超时（" + PAGE_LOAD_TIMEOUT_MS + "ms）: " + url);
                Toast.makeText(this,
                        "页面加载超时，请求未完成，请确认 VNT 组网已启动且地址可达\n" + url,
                        Toast.LENGTH_LONG).show();
                progressBar.setVisibility(View.GONE);
            }
        };
        timeoutHandler.postDelayed(timeoutRunnable, PAGE_LOAD_TIMEOUT_MS);
    }

    private void cancelTimeoutCheck() {
        if (timeoutRunnable != null) {
            timeoutHandler.removeCallbacks(timeoutRunnable);
            timeoutRunnable = null;
        }
    }

    private void openFileChooser(WebChromeClient.FileChooserParams params) {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        String[] acceptTypes = params.getAcceptTypes();
        intent.setType(acceptTypes.length > 0 && !acceptTypes[0].isEmpty() ? acceptTypes[0] : "*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,
                params.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE);
        startActivityForResult(Intent.createChooser(intent, "选择文件"), FILE_CHOOSER_REQUEST_CODE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_REQUEST_CODE && filePathCallback != null) {
            Uri[] results = null;
            if (resultCode == Activity.RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    results = new Uri[count];
                    for (int i = 0; i < count; i++) {
                        results[i] = data.getClipData().getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    results = new Uri[]{data.getData()};
                }
            }
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.clearHistory();
            webView.removeAllViews();
            webView.destroy();
        }
        super.onDestroy();
    }
}
