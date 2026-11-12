package org.neowarp.android;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

/**
 * NeoWarp 窗口基类：创建并配置 WebView（桌面级能力全开），
 * 统一处理 SAF（另存为/打开文件）结果路由与返回键。
 *
 * WebView 配置要点：
 *  - JavaScript + DOM storage（编辑器 localStorage/IndexedDB 都依赖）
 *  - 多窗口（window.open / target=_blank 走 onCreateWindow）
 *  - 媒体自动播放（Scratch 项目音视频）
 *  - largeHeap / 硬件加速由 Manifest 保证，满足 4.19GB 工程压缩等大内存场景
 */
public abstract class NWActivity extends AppCompatActivity {

    /** 窗口类型：editor / home / task-manager / ... （与 bridge.js 的 WIN_TYPE 一致） */
    protected String windowType = "editor";

    protected WebView webView;

    /** 内容是否延伸到系统栏（状态栏/导航栏）后方，主页全屏铺满用 */
    protected boolean extendIntoStatusBar() { return false; }

    /** 系统栏：edge 窗口透明铺满（背景全屏），其余按主题染色 */
    protected void applySystemBarTheme() {
        runOnUiThread(() -> {
            boolean dark = BridgeHub.isDarkTheme(this);
            int color = dark ? 0xFF0A0A0D : 0xFFF2F2F7;
            android.view.Window w = getWindow();
            boolean edge = extendIntoStatusBar();
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(w, !edge);
            androidx.core.view.WindowInsetsControllerCompat c =
                    androidx.core.view.WindowCompat.getInsetsController(w, w.getDecorView());
            c.setAppearanceLightStatusBars(!dark);
            c.setAppearanceLightNavigationBars(!dark);
            if (edge) {
                // 全屏：状态栏/导航栏透明，页面背景（含光晕/背景图）铺满整个屏幕
                w.setStatusBarColor(0x00000000);
                w.setNavigationBarColor(0x00000000);
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    w.setStatusBarContrastEnforced(false);
                    w.setNavigationBarContrastEnforced(false);
                }
            } else {
                w.setStatusBarColor(color);
                w.setNavigationBarColor(color);
            }
        });
    }

    /** 旋转/分屏等配置变化：WebView 需要主动重排（Activity 不重建） */
    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applySystemBarTheme();
        if (webView != null) {
            final WebView wv = webView;
            // 旋转后 WebView 的视觉尺寸会变，但页面内部视口（innerWidth/innerHeight）
            // 可能仍停留在旋转前的值，导致 scratch-gui 按旧尺寸渲染出"页面过大"。
            // 这里先让 WebView 自身完成测量，再通知页面按新视口重排。
            wv.postDelayed(() -> {
                if (wv == null) return;
                wv.requestLayout();
                wv.invalidate();
                // 强制 WebView 重新计算视口并触发页面的 resize / orientationchange
                wv.evaluateJavascript(
                        "(function(){try{"
                                + "var d=document.documentElement,b=document.body;"
                                + "if(d){d.style.width='100%';d.style.height='100%';}"
                                + "if(b){b.style.width='100%';b.style.height='100%';}"
                                + "var r=document.getElementById('app');"
                                + "if(r){r.style.width='100%';r.style.height='100%';}"
                                + "window.dispatchEvent(new Event('resize'));"
                                + "window.dispatchEvent(new Event('orientationchange'));"
                                + "}catch(e){}})();", null);
            }, 80);
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    protected WebView createWebView() {
        applySystemBarTheme(); // 需在 setContentView 前生效的 flags 在内部处理
        webView = new WebView(this);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUserAgentString(s.getUserAgentString());
        s.setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        webView.setBackgroundColor(BridgeHub.isDarkTheme(this) ? 0xFF333333 : 0xFFFFFFFF);
        webView.setWebViewClient(new BridgeWebViewClient(this));
        webView.setWebChromeClient(new BridgeWebChromeClient(this));
        webView.addJavascriptInterface(new NeoWarpBridge(this, windowType), "NeoWarpAndroid");
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);

        // 视口贴合全面屏（刘海/挖孔区域由 viewport-fit=cover 的页面自行处理）
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        // 全面屏适配：允许绘制进刘海/挖孔区域（Android 9+ shortEdges）
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }

        setContentView(webView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        BridgeHub.get(this).registerWindow(this);
        return webView;
    }

    public WebView getWebView() {
        return webView;
    }

    public String getWindowType() {
        return windowType;
    }

    protected void loadMain() {
        webView.loadUrl(BridgeHub.BASE_URL + BridgeHub.entryPathFor(windowType));
    }

    @Override
    protected void onDestroy() {
        unregisterBackGestureCallback();
        BridgeHub.get(this).unregisterWindow(this);
        if (webView != null) {
            webView.removeJavascriptInterface("NeoWarpAndroid");
            webView.destroy();
        }
        super.onDestroy();
    }

    /**
     * 返回键 / 全面屏手势返回：
     * WebView 有历史则后退；否则若当前编辑器有未保存改动，先弹确认框。
     */
    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack() && !BridgeHub.isModalWindow(windowType)) {
            webView.goBack();
            return;
        }
        // 编辑器类窗口且有未保存改动 -> 弹确认框；用户确认后由对话框驱动关闭
        if (!BridgeHub.get(this).requestCloseIfDirty(this)) {
            return;
        }
        super.onBackPressed();
    }

    /**
     * Android 13+（targetSdk 33+）全面屏手势返回走 OnBackInvokedCallback，
     * 不再经过已废弃的 onBackPressed()，因此需单独注册一条同源的返回通道。
     * 回调对象必须持有同一实例，否则 unregister 无法匹配。
     */
    private android.window.OnBackInvokedCallback backGestureCallback;

    protected void registerBackGestureCallback() {
        if (android.os.Build.VERSION.SDK_INT < 33) return;
        try {
            backGestureCallback = this::handleBackGesture;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    backGestureCallback);
        } catch (Throwable ignored) {
        }
    }

    private void unregisterBackGestureCallback() {
        if (android.os.Build.VERSION.SDK_INT < 33 || backGestureCallback == null) return;
        try {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backGestureCallback);
        } catch (Throwable ignored) {
        }
        backGestureCallback = null;
    }

    private void handleBackGesture() {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (webView != null && webView.canGoBack() && !BridgeHub.isModalWindow(windowType)) {
                webView.goBack();
                return;
            }
            if (!BridgeHub.get(this).requestCloseIfDirty(this)) {
                return;
            }
            // 与默认返回行为一致：非主页窗口关闭自己，主页窗口退到后台
            moveTaskToBack(true);
        });
    }

    /* ---------------- SAF 结果路由 ---------------- */

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        BridgeHub.get(this).handleActivityResult(this, requestCode, resultCode, data);
    }

    /** 供 BridgeHub 发起系统文件对话框 */
    public void startIntentForResult(Intent intent, int requestCode) {
        startActivityForResult(intent, requestCode);
    }

    /** Java → JS 事件/回调（UI 线程） */
    public void evaluateJs(final String script) {
        final WebView wv = webView;
        if (wv == null) return;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (!isFinishing() && !isDestroyed()) {
                    wv.evaluateJavascript(script, null);
                }
            }
        });
    }
}
