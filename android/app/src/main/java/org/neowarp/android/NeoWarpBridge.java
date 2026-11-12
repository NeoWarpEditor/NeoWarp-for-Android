package org.neowarp.android;

import android.webkit.JavascriptInterface;

/**
 * 注入为 window.NeoWarpAndroid 的 JS↔Java 桥（薄壳）。
 *
 * 所有方法仅做转发，真正实现集中在 BridgeHub。
 * 注意 @JavascriptInterface 方法运行在 WebView 的 JS 桥线程，
 * BridgeHub 内部自行切主线程。
 */
public class NeoWarpBridge {

    private final NWActivity activity;
    private final String windowType;

    public NeoWarpBridge(NWActivity activity, String windowType) {
        this.activity = activity;
        this.windowType = windowType;
    }

    /** 同步调用（对应 Electron ipcRenderer.sendSync） */
    @JavascriptInterface
    public String rpcSync(String winType, String method, String argsJson) {
        return BridgeHub.get(activity).rpcSync(windowType, method, argsJson);
    }

    /** 异步调用（对应 ipcRenderer.invoke；结果经 __nw.cb 回投 JS） */
    @JavascriptInterface
    public void rpcAsync(String winType, String method, String argsJson, String cbId) {
        BridgeHub.get(activity).rpcAsync(windowType, method, argsJson, cbId);
    }

    /** 编辑器服务端请求的响应回传（sendProjectJSON / sendAIToolResponse / ...） */
    @JavascriptInterface
    public void hubRespond(String winType, String requestId, String json) {
        BridgeHub.get(activity).hubRespond(windowType, requestId, json);
    }

    /* ---------- 保存写流（SAF 另存为的写入端） ---------- */

    @JavascriptInterface
    public void writeChunk(String handleId, String b64) {
        BridgeHub.get(activity).writeChunk(handleId, b64);
    }

    @JavascriptInterface
    public void writeFinish(String handleId) {
        BridgeHub.get(activity).writeFinish(handleId);
    }

    @JavascriptInterface
    public void writeAbort(String handleId) {
        BridgeHub.get(activity).writeAbort(handleId);
    }

    /* ---------- 打包器导入项目：编辑器分块导出 ---------- */

    @JavascriptInterface
    public void exportBegin(String winType, String requestId, String name, int totalChunks) {
        BridgeHub.get(activity).exportBegin(windowType, requestId, name, totalChunks);
    }

    @JavascriptInterface
    public void exportChunk(String winType, String requestId, String b64) {
        BridgeHub.get(activity).exportChunk(requestId, b64);
    }

    @JavascriptInterface
    public void exportEnd(String winType, String requestId) {
        BridgeHub.get(activity).exportEnd(requestId);
    }

    /* ---------- 窗口间转发（舞台帧 / 输入 / 覆盖层） ---------- */

    @JavascriptInterface
    public void forwardToWindow(String targetType, String event, String json) {
        BridgeHub.get(activity).forwardToWindow(windowType, targetType, event, json);
    }

    @JavascriptInterface
    public void log(String message) {
        android.util.Log.d("NeoWarp", String.valueOf(message));
    }
}
