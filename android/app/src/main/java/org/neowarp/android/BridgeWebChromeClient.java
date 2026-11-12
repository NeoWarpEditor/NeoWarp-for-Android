package org.neowarp.android;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.webkit.JsResult;
import android.webkit.JsPromptResult;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.core.content.ContextCompat;

/**
 * ChromeClient：系统文件选择器（<input type=file>，导入 sb3/素材）、
 * JS 对话框、麦克风/摄像头授权、window.open 多窗口。
 */
public class BridgeWebChromeClient extends WebChromeClient {

    private final NWActivity activity;

    public BridgeWebChromeClient(NWActivity activity) {
        this.activity = activity;
    }

    /* ---------------- <input type=file> → SAF 文件选择器 ---------------- */

    @Override
    public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback,
                                     FileChooserParams fileChooserParams) {
        BridgeHub.get(activity).startFileChooser(activity, filePathCallback, fileChooserParams.getAcceptTypes());
        return true;
    }

    /* ---------------- JS 对话框 ---------------- */

    @Override
    public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
        showJsDialog(message, null, result, false);
        return true;
    }

    @Override
    public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
        showJsDialog(message, null, result, true);
        return true;
    }

    @Override
    public boolean onJsPrompt(WebView view, String url, String message, String defaultValue, JsPromptResult result) {
        showJsDialog(message, defaultValue, result, true);
        return true;
    }

    private void showJsDialog(String message, String promptDefault, Object result, boolean cancellable) {
        new AlertDialog.Builder(activity)
                .setTitle("NeoWarp")
                .setMessage(message)
                .setPositiveButton("OK", (d, w) -> {
                    if (result instanceof JsResult) ((JsResult) result).confirm();
                    else if (result instanceof JsPromptResult)
                        ((JsPromptResult) result).confirm(promptDefault == null ? "" : promptDefault);
                })
                .setOnCancelListener(d -> {
                    if (result instanceof JsResult) ((JsResult) result).cancel();
                    else if (result instanceof JsPromptResult) ((JsPromptResult) result).cancel();
                })
                .setCancelable(cancellable)
                .show();
    }

    /* ---------------- 麦克风 / 摄像头（视频侦测、录音扩展） ---------------- */

    @Override
    public void onPermissionRequest(final PermissionRequest request) {
        activity.runOnUiThread(() -> {
            try {
                // 仅放行系统已授权的资源，其余 deny（targetSdk 34 下 grant 未授权资源会抛异常）
                java.util.List<String> granted = new java.util.ArrayList<>();
                for (String res : request.getResources()) {
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(res)
                            && ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO)
                            == PackageManager.PERMISSION_GRANTED) {
                        granted.add(res);
                    } else if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(res)
                            && ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA)
                            == PackageManager.PERMISSION_GRANTED) {
                        granted.add(res);
                    } else if (PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID.equals(res)) {
                        granted.add(res);
                    }
                }
                if (granted.isEmpty()) request.deny();
                else request.grant(granted.toArray(new String[0]));
            } catch (Exception e) {
                request.deny();
            }
        });
    }

    /* ---------------- window.open / target=_blank 多窗口 ---------------- */

    @Override
    public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, android.os.Message resultMsg) {
        // WebView.createWindow 的 URL 需通过 transport 获取：注入脚本取 window.open 目标
        WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
        // 先创建空白 WebView 完成握手，再由导航拦截转发到新窗口 Activity
        WebView temp = new WebView(activity);
        temp.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, android.webkit.WebResourceRequest req) {
                Uri url = req.getUrl();
                openAsWindow(url);
                return true;
            }

            @Override
            public void onPageFinished(WebView v, String u) {
                // data:/about:blank 之类无法取 URL 的场景：交给系统
            }
        });
        transport.setWebView(temp);
        resultMsg.sendToTarget();
        return true;
    }

    private void openAsWindow(Uri url) {
        String scheme = url.getScheme() == null ? "" : url.getScheme();
        if (scheme.startsWith("tw-") || scheme.equals("nw-expands")) {
            String mapped = BridgeHub.mapCustomSchemeToPath(url);
            if (mapped != null) {
                WindowActivity.show(activity, BridgeHub.windowTypeForPath(mapped), null);
                return;
            }
        }
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, url));
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onCloseWindow(WebView window) {
        activity.finish();
    }
}
