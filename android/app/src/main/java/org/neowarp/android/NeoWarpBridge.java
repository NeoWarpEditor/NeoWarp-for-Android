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
        // 传入本 WebView 所属的 activity，保证多开同类型窗口时回调投给发起方自己
        BridgeHub.get(activity).rpcAsync(windowType, method, argsJson, cbId, activity);
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

    /* ---------- SOLO 舞台帧槽（轮询通道） ----------
       大 dataURL 的帧经 __nw.evt 拼接进 evaluateJavascript 在真机上
       不可靠（诊断：宿主 749 帧 0 到达，而 cb 小消息通道正常），帧槽
       轮询让窗口主动拉取，负载走已被证明可靠的 cb 回复通道。 */
    @JavascriptInterface
    public void soloStore(String slot, String json) {
        BridgeHub.get(activity).storeSoloStage(slot, json);
    }

    @JavascriptInterface
    public void log(String message) {
        android.util.Log.d("NeoWarp", String.valueOf(message));
    }
}
