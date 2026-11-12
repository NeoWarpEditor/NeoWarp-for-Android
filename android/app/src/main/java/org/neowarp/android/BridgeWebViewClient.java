package org.neowarp.android;

import android.content.Intent;
import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NeoWarp WebViewClient。
 *
 * 职责（对应 Electron protocols.js + ProjectRunningWindow.onBeforeRequest）：
 *  1. 伺服 https://localhost/** 静态资产（assets/www/**，.br 自动按 brotli 头返回）
 *  2. /__file/<handleId>   → SAF 文件内容流（编辑器 getFile 的大文件通道）
 *  3. /__fetch/?u=<url>    → Java 代理网络请求（免 CORS，等价主进程 fetch / tw-ai-proxy）
 *  4. assets.scratch.mit.edu 上命中本地素材库的请求 → 重定向 /library/<md5ext>
 *  5. extensions.turbowarp.org → 本地 /extensions/**
 *  6. tw-*://、nw-expands:// 导航 → 映射回本地路径
 */
public class BridgeWebViewClient extends WebViewClient {

    private static final Pattern MD5EXT = Pattern.compile("[0-9a-fA-F]{32}\\.\\w{3}");

    private final NWActivity activity;

    public BridgeWebViewClient(NWActivity activity) {
        this.activity = activity;
    }

    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        Uri url = request.getUrl();
        String host = url.getHost();
        String path = url.getPath();
        if (host == null) return null;

        try {
            BridgeHub hub = BridgeHub.get(activity);

            if (host.equals("localhost")) {
                if (path != null && path.startsWith("/__file/")) {
                    String handleId = path.substring("/__file/".length());
                    return hub.serveFileHandle(handleId);
                }
                if (path != null && path.startsWith("/__fetch/")) {
                    String u = url.getQueryParameter("u");
                    return hub.proxyFetch(u);
                }
                return hub.serveAsset("www" + (path == null ? "/" : path));
            }

            // Scratch 素材：本地命中直接回，未命中走代理
            if (host.equals("assets.scratch.mit.edu") || host.equals("cdn.assets.scratch.mit.edu")) {
                Matcher m = MD5EXT.matcher(String.valueOf(url));
                if (m.find()) {
                    String md5ext = m.group();
                    WebResourceResponse local = hub.serveAssetNullable("www/library/" + md5ext);
                    if (local != null) return local;
                }
                return hub.proxyFetch(url.toString());
            }

            if (host.equals("resources.scratch.mit.edu")) {
                Matcher m = MD5EXT.matcher(String.valueOf(url));
                if (m.find()) {
                    WebResourceResponse local = hub.serveAssetNullable("www/library/" + m.group());
                    if (local != null) return local;
                }
                return hub.proxyFetch(url.toString());
            }

            // TurboWarp 官方扩展 → 本地 dist-extensions
            if (host.equals("extensions.turbowarp.org")) {
                return hub.serveAssetNullable("www/extensions" + (path == null ? "/" : path));
            }

            return null; // 其余域名 WebView 直连
        } catch (Exception e) {
            return BridgeHub.errorResponse(502, "intercept: " + e);
        }
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        Uri url = request.getUrl();
        String scheme = url.getScheme() == null ? "" : url.getScheme();

        // 自定义协议导航（migrate-helper / 文档等）：映射到本地路径
        if (scheme.startsWith("tw-") || scheme.equals("nw-expands")) {
            String mapped = BridgeHub.mapCustomSchemeToPath(url);
            if (mapped != null) {
                view.loadUrl(BridgeHub.BASE_URL + mapped);
                return true;
            }
            return true;
        }

        // 外部链接交给系统浏览器
        if (scheme.equals("http") || scheme.equals("https")) {
            if (!"localhost".equals(url.getHost())) {
                try {
                    activity.startActivity(new Intent(Intent.ACTION_VIEW, url));
                } catch (Exception ignored) {
                }
                return true;
            }
        }
        return false;
    }
}
