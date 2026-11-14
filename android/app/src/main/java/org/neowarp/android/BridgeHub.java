package org.neowarp.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.webkit.WebResourceResponse;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * NeoWarp 桥接中枢 —— Electron 主进程在 Android 上的等价物。
 *
 * 承担：窗口注册表与路由、SAF 文件句柄/另存为写流、设置存储、
 * 主题广播、最近项目、系统统计、npnp/viewsb3 加密、Expands 扫描、
 * 编辑器「服务端请求」hub、资产伺服（.br brotli 透传）与网络代理。
 */
public class BridgeHub {

    public static final String BASE_URL = "https://localhost";

    private static final String SETTINGS_PREF = "nw-settings";
    private static final String RECENT_PREF = "nw-recent";
    private static final int MAX_RECENT = 10;

    /* ---------------- 检查更新（GitHub Release） ---------------- */
    /** 更新源仓库 */
    public static final String UPDATE_REPO = "NeoWarpEditor/NeoWarp-for-Android";
    // 用列表而非 /releases/latest：后者只认正式版，仓库首发为 prerelease 时会 404
    private static final String UPDATE_API_URL =
            "https://api.github.com/repos/" + UPDATE_REPO + "/releases?per_page=20";
    public static final String UPDATE_RELEASES_URL =
            "https://github.com/" + UPDATE_REPO + "/releases";
    private static final String PREF_LAST_UPDATE_CHECK = "lastUpdateCheckDate";
    private static final String PREF_AVAILABLE_UPDATE = "availableUpdate";

    private static BridgeHub instance;

    public static synchronized BridgeHub get(Context ctx) {
        if (instance == null) instance = new BridgeHub(ctx.getApplicationContext());
        return instance;
    }

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SecureRandom random = new SecureRandom();

    /* ---------------- 窗口注册表 ---------------- */
    private final List<NWActivity> windows = new ArrayList<>(); // UI 线程访问
    private final Map<String, String> windowPayloads = new ConcurrentHashMap<>(); // type -> payload json

    /* ---------------- 未保存状态（退出确认用） ---------------- */
    /** 各窗口是否有未保存改动，key = 窗口 Activity 实例 */
    private final Map<NWActivity, Boolean> dirtyWindows = new ConcurrentHashMap<>();

    /** 需要在退出前拦截确认的窗口类型 */
    private static boolean needsSaveGuard(String type) {
        if (type == null) return false;
        switch (type) {
            case "editor":
            case "extension-editor":
            case "image-editor":
                return true;
            default:
                return false;
        }
    }

    /** 记录窗口未保存状态 */
    public void setWindowDirty(NWActivity win, boolean dirty) {
        if (win == null) return;
        if (dirty) dirtyWindows.put(win, Boolean.TRUE);
        else dirtyWindows.remove(win);
    }

    /** 窗口是否有未保存改动 */
    public boolean isWindowDirty(NWActivity win) {
        return win != null && Boolean.TRUE.equals(dirtyWindows.get(win));
    }

    /* ---------------- 文件句柄与流 ---------------- */
    private final Map<String, NWActivity> cbOwners = new ConcurrentHashMap<>(); // cbId -> 发起窗口
    private static class FileHandle {
        Uri uri;
        String name;
    }
    private final Map<String, FileHandle> handles = new ConcurrentHashMap<>();
    private final Map<String, OutputStream> activeWrites = new ConcurrentHashMap<>();
    private final Map<String, byte[]> decryptCache = new ConcurrentHashMap<>();
    private int handleSeq = 1;

    /* ---------------- SAF 等待回调 ---------------- */
    private static class PendingSaf {
        String winType; String cbId; String kind; String suggestedName;
        boolean multi;
        android.webkit.ValueCallback<Uri[]> chooserCallback;
    }
    private final Map<Integer, PendingSaf> pendingSaf = new ConcurrentHashMap<>();
    private int safSeq = 1000;

    /* ---------------- 编辑器服务端请求 ---------------- */
    private static class PendingHub {
        String fromType; long deadline;
    }
    private final Map<String, PendingHub> pendingHubReqs = new ConcurrentHashMap<>();
    private final Map<String, Object[]> exportBuffers = new ConcurrentHashMap<>(); // requestId -> {name, baos, total, count}
    private final Map<String, Object[]> uploadBuffers = new ConcurrentHashMap<>(); // key -> {baos, meta}

    private Uri pendingInitialFile = null;

    /* CPU 采样 */
    private long lastCpuIdle = 0, lastCpuTotal = 0, lastCpuTime = 0;

    private BridgeHub(Context app) {
        this.app = app;
    }

    /* ============================================================
     * 窗口管理
     * ============================================================ */

    public synchronized void registerWindow(NWActivity a) {
        windows.remove(a);
        windows.add(0, a);
    }

    /**
     * 退出前确认：窗口类型属于编辑器且有未保存改动时弹三选项对话框。
     *
     * @return true 表示已确认关闭（调用方应继续退出）；false 表示用户取消，窗口保持打开
     */
    public boolean requestCloseIfDirty(final NWActivity win) {
        if (win == null || win.isFinishing()) return true;
        if (!needsSaveGuard(win.getWindowType()) || !isWindowDirty(win)) return true;

        final String title;
        if (win.getWindowType().equals("image-editor")) {
            title = "图片编辑器";
        } else if (win.getWindowType().equals("extension-editor")) {
            title = "扩展编辑器";
        } else {
            title = "Scratch 编辑器";
        }

        main.post(() -> {
            try {
                new androidx.appcompat.app.AlertDialog.Builder(win)
                        .setTitle(title)
                        .setMessage("当前编辑的内容尚未保存，是否保存？")
                        .setPositiveButton("保存", (d, w) -> {
                            // 先清除脏标记，再请求页面执行保存并关闭
                            setWindowDirty(win, false);
                            win.evaluateJs("window.__nwOnExitSave && window.__nwOnExitSave()");
                        })
                        .setNegativeButton("放弃", (d, w) -> {
                            setWindowDirty(win, false);
                            win.finish();
                        })
                        .setNeutralButton("取消", null)
                        .setOnCancelListener(null)
                        .show();
            } catch (Exception e) {
                win.finish();
            }
        });
        return false;
    }

    public synchronized void unregisterWindow(NWActivity a) {
        windows.remove(a);
        dirtyWindows.remove(a);
        for (Iterator<Map.Entry<String, String>> it = windowPayloads.entrySet().iterator(); it.hasNext(); ) {
            NWActivity w = findFirstByType(it.next().getKey());
            if (w == null) it.remove();
        }
    }

    public synchronized NWActivity findFirstByType(String type) {
        if (type == null) return null;
        for (NWActivity w : windows) {
            if (type.equals(w.getWindowType())) return w;
        }
        return null;
    }

    public synchronized NWActivity getEditor() {
        return findFirstByType("editor");
    }

    public void onWindowCreated(NWActivity a, String payload) {
        if (payload != null) windowPayloads.put(a.getWindowType(), payload);
    }

    /** 返回键直接关窗的“弹窗型”窗口 */
    public static boolean isModalWindow(String type) {
        switch (type) {
            case "editor":
            case "home":
            case "extension-editor":
            case "ai-assistant":
            case "solo":
                return false;
            default:
                return true;
        }
    }

    /** 单例窗口集合（与 Electron getWindowsByClass(...)[0].show() 行为一致） */
    public static final String SINGLETON_WINDOW = "__always__"; // 全部窗口均按类型单例

    public static String entryPathFor(String type) {
        switch (type) {
            case "editor": return "/editor/gui/gui.html";
            case "home": return "/home/home.html";
            case "onboarding": return "/onboarding/onboarding.html";
            case "task-manager": return "/task-manager/task-manager.html";
            case "desktop-settings": return "/desktop-settings/desktop-settings.html";
            case "about": return "/about/about.html";
            case "privacy": return "/privacy/privacy.html";
            case "contact": return "/contact/contact.html";
            case "todo-list": return "/todo-list/todo-list.html";
            case "ai-assistant": return "/ai-assistant/ai-assistant.html";
            case "solo": return "/ai-assistant/ai-assistant.html?nwwin=solo";
            case "collaboration": return "/collaboration/collaboration.html";
            case "image-editor": return "/image-editor/index.html";
            case "project-analysis": return "/project-analysis/project-analysis.html";
            case "security-prompt": return "/security-prompt/security-prompt.html";
            case "update": return "/update/update.html";
            case "extension-editor": return "/extension-editor/extension-editor.html";
            case "detached-stage": return "/detached-stage/index.html";
            case "solo-stage": return "/solo-stage/index.html";
            case "migrate": return "/migrate/migrate.html";
            case "file-access": return "/file-access/file-access.html";
            case "mobile-preview": return "/mobile-preview/mobile-preview.html";
            case "packager": return "/packager/standalone.html";
            case "addons": return "/editor/addons/addons.html";
            case "extensions": return "/extensions/index.html";
            default: return "/home/home.html";
        }
    }

    public static String windowTypeForPath(String path) {
        if (path.startsWith("/editor/")) return "editor";
        int end = path.indexOf('/', 1);
        String seg = end < 0 ? path.substring(1) : path.substring(1, end);
        return seg.isEmpty() ? "home" : seg;
    }

    /** tw-editor://./gui/gui.html → /editor/gui/gui.html */
    public static String mapCustomSchemeToPath(Uri uri) {
        String scheme = uri.getScheme();
        String host = uri.getHost() == null ? "" : uri.getHost();
        String path = uri.getPath() == null ? "" : uri.getPath();
        String query = uri.getQuery() == null ? "" : ("?" + uri.getQuery());
        String frag = uri.getFragment() == null ? "" : ("#" + uri.getFragment());

        switch (scheme) {
            case "tw-editor": return "/editor" + path + query + frag;
            case "tw-library": return "/library" + path;
            case "tw-extensions": return "/extensions" + path;
            case "nw-expands": return "/expands" + path;
            case "tw-home": return "/home" + path;
            case "tw-onboarding": return "/onboarding" + path;
            case "tw-desktop-settings": return "/desktop-settings" + path;
            case "tw-privacy": return "/privacy" + path;
            case "tw-about": return "/about" + path;
            case "tw-contact": return "/contact" + path;
            case "tw-packager": return "/packager" + path;
            case "tw-update": return "/update" + path;
            case "tw-security-prompt": return "/security-prompt" + path;
            case "tw-file-access": return "/file-access" + path;
            case "tw-detached-stage": return "/detached-stage" + path;
            case "tw-ai-assistant": return "/ai-assistant" + path;
            case "tw-solo": return "/ai-assistant" + path + "?nwwin=solo";
            case "tw-solo-stage": return "/solo-stage" + path;
            case "tw-todo-list": return "/todo-list" + path;
            case "tw-image-editor": return "/image-editor" + path;
            case "tw-project-analysis": return "/project-analysis" + path;
            case "tw-task-manager": return "/task-manager" + path;
            case "tw-collaboration": return "/collaboration" + path;
            case "tw-mobile-preview": return "/mobile-preview" + path;
            case "tw-extension-editor": return "/extension-editor" + path;
            default: return null;
        }
        // host 段（./）被忽略，路径已含完整相对地址
    }

    /* ============================================================
     * JS 调用入口
     * ============================================================ */

    public String rpcSync(String winType, String method, String argsJson) {
        JSONObject args = parse(argsJson);
        try {
            switch (method) {
                case "isInitiallyFullscreen": return jstr(false);
                case "setLocale": {
                    if (args != null) settings().edit().putString("locale", args.optString("locale", "en")).apply();
                    return jstr(null);
                }
                case "getCodeAreaBackgroundImage": return jstr(settingsOpt("codeAreaBackgroundImage"));
                case "getStageAreaBackgroundImage": return jstr(settingsOpt("stageAreaBackgroundImage"));
                case "getTopBarDeviceStats": return jstr(false); // Android 顶部不显示 CPU/内存
                // ---- 2.3.0 新增同步读取 ----
                case "getToolbarHeight": {
                    String v = settings().getString("toolbarHeight", "");
                    return jstr(v == null || v.isEmpty() ? "normal" : v);
                }
                case "getShowSourceCodeButton": return jstr(settings().getBoolean("showSourceCodeButton", true));
                case "getContextMenuGlass": return jstr(settings().getBoolean("contextMenuGlass", false));
                case "getEditorLayout": {
                    String v = settings().getString("editorLayout", "");
                    return jstr(v == null || v.isEmpty() ? "horizontal" : v);
                }
                case "getWindowControlsOverlayState": {
                    // Android 无 Windows 原生窗口控制覆盖层
                    return new JSONObject().put("hasOverlay", false).put("compact", false).toString();
                }
                case "onboardingGetInfo": {
                    JSONObject o = new JSONObject();
                    o.put("locale", effectiveLocale());
                    o.put("uiTheme", settings().getString("uiTheme", "system"));
                    o.put("version", versionName());
                    o.put("logo", settingsOpt("homeLogo"));
                    return o.toString();
                }
                case "aboutGetInfo": {
                    JSONObject o = new JSONObject();
                    o.put("version", versionName());
                    o.put("electron", "");
                    o.put("chrome", "");
                    return o.toString();
                }
                case "homeGetInfo": {
                    JSONObject o = new JSONObject();
                    o.put("locale", effectiveLocale());
                    o.put("version", versionName());
                    o.put("homeLogo", settingsOpt("homeLogo"));
                    o.put("homeLogoText", settingsOpt("homeLogoText"));
                    o.put("homeBackground", settingsOpt("homeBackground"));
                    return o.toString();
                }
                case "isUpdateCheckerAllowed": return jstr(settings().getBoolean("updateChecker", true));
                case "settingsInit": return desktopSettingsInit().toString();
                case "securityInit": {
                    JSONObject o = new JSONObject();
                    o.put("origin", "");
                    o.put("permission", "");
                    return o.toString();
                }
                case "updateGetStrings": return "{}";
                case "updateGetInfo": {
                    JSONObject o = new JSONObject();
                    o.put("version", versionName());
                    o.put("latestVersion", JSONObject.NULL);
                    o.put("ignoredVersion", JSONObject.NULL);
                    return o.toString();
                }
                case "migrateGetInfo": {
                    JSONObject o = new JSONObject();
                    o.put("files", new JSONArray());
                    o.put("rootPath", "");
                    return o.toString();
                }
                case "fileAccessInit": return jstr(null);
                default: return jstr(null);
            }
        } catch (Exception e) {
            return jstr(null);
        }
    }

    public void rpcAsync(String winType, String method, String argsJson, String cbId) {
        JSONObject args = parse(argsJson);
        NWActivity caller = findFirstByType(winType);
        if (cbId != null && caller != null) cbOwners.put(cbId, caller); // reply 回投定位
        try {
            dispatch(winType, method, args, cbId, caller);
        } catch (Exception e) {
            reply(cbId, err(e));
        }
    }

    /** 把 rpcAsync 结果回投给发起窗口（bridge.js 的 __nw.cb 第二参数为 JSON 字符串） */
    private void reply(String cbId, String json) {
        if (cbId == null || cbId.isEmpty()) return;
        NWActivity owner = cbOwners.remove(cbId);
        if (owner == null) {
            synchronized (windows) {
                if (!windows.isEmpty()) owner = windows.get(0);
            }
            if (owner == null) return;
        }
        owner.evaluateJs("__nw.cb(" + jq(cbId) + "," + (json == null ? "null" : jq(json)) + ")");
    }

    private void dispatch(String winType, String method, JSONObject args, String cbId, NWActivity caller) throws Exception {
        if (args == null) args = new JSONObject();
        switch (method) {

            /* ---------- 窗口 ---------- */
            case "openWindow": {
                String type = args.optString("type", "editor");
                JSONObject payload = args.optJSONObject("payload");
                if (args.optString("search", "").length() > 0 && "addons".equals(type)) {
                    payload = new JSONObject().put("search", args.optString("search"));
                }
                WindowActivity.show(caller, type, payload == null ? null : payload.toString());
                reply(cbId, ok());
                return;
            }
            case "closeWindow": {
                // 页面主动关闭（菜单栏关闭按钮等）：编辑器有未保存改动时同样先确认
                if (requestCloseIfDirty(caller)) {
                    if (caller != null) caller.finish();
                }
                reply(cbId, ok());
                return;
            }

            /* ---------- 文件打开/保存（系统“另存为”） ---------- */
            case "showSaveFilePicker": {
                PendingSaf p = new PendingSaf();
                p.winType = winType; p.cbId = cbId; p.kind = "save";
                p.suggestedName = args.optString("suggestedName", "project.sb3");
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("application/octet-stream");
                if (p.suggestedName != null) i.putExtra(Intent.EXTRA_TITLE, p.suggestedName);
                int req = nextSaf(p);
                caller.startIntentForResult(Intent.createChooser(i, "另存为"), req);
                return;
            }
            case "showOpenFilePicker": {
                PendingSaf p = new PendingSaf();
                p.winType = winType; p.cbId = cbId; p.kind = "open";
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                        "application/octet-stream", "application/zip", "application/x-zip-compressed",
                        "application/json", "text/javascript", "*/*"});
                int req = nextSaf(p);
                caller.startIntentForResult(Intent.createChooser(i, "打开项目"), req);
                return;
            }

            /* ---------- 编辑器文件 ---------- */
            case "getInitialFile": {
                Uri uri = pendingInitialFile;
                pendingInitialFile = null;
                if (uri == null) { reply(cbId, jstr(null)); return; }
                FileHandle h = registerHandle(uri, nameOf(uri));
                JSONObject o = new JSONObject();
                o.put("id", handleId(h));
                reply(cbId, o.toString());
                return;
            }
            case "openedFile": {
                FileHandle h = handle(args.optString("id"));
                if (h != null) {
                    addRecent(h.uri, h.name);
                    notifyWindows("home", "recent-changed", null);
                }
                reply(cbId, ok());
                return;
            }
            case "closedFile": {
                reply(cbId, ok());
                return;
            }
            case "getFileInfo": {
                FileHandle h = handle(args.optString("id"));
                if (h == null) { reply(cbId, err(new Exception("File not found"))); return; }
                byte[] head = readHead(h.uri, 5);
                JSONObject o = new JSONObject();
                o.put("id", args.optString("id"));
                o.put("name", h.name);
                o.put("type", "file");
                boolean isView = h.name.endsWith(".viewsb3") || magicEquals(head, "VSB3\u0001");
                boolean isEnc = h.name.endsWith(".npnp") || magicEquals(head, "NPNP\u0001");
                if (isView) {
                    byte[] plain = decryptViewSb3(readAll(h.uri));
                    String token = cacheToken(plain);
                    o.put("token", token);
                    o.put("isViewOnly", true);
                } else if (isEnc) {
                    o.put("isEncrypted", true);
                    o.put("data", JSONObject.NULL);
                }
                reply(cbId, o.toString());
                return;
            }
            case "decryptFile": {
                FileHandle h = handle(args.optString("fileId"));
                if (h == null) { reply(cbId, err(new Exception("File not found"))); return; }
                byte[] all = readAll(h.uri);
                byte[] plain;
                if ("viewsb3".equals(args.optString("kind"))) {
                    plain = decryptViewSb3(all);
                } else {
                    plain = decryptNpnp(all, args.optString("password", ""));
                }
                JSONObject o = new JSONObject();
                o.put("token", cacheToken(plain));
                reply(cbId, o.toString());
                return;
            }

            /* ---------- 写流（另存为写入端） ---------- */
            case "setChanged": case "setIsFullScreen": {
                if ("setChanged".equals(method) && caller != null) {
                    // 记录未保存状态，返回/手势退出时据此弹确认框
                    setWindowDirty(caller, args.optBoolean("changed", false));
                }
                if ("setIsFullScreen".equals(method) && caller != null) {
                    boolean fs = args.optBoolean("isFullScreen", false);
                    caller.runOnUiThread(() -> {
                        android.view.Window w = caller.getWindow();
                        if (fs) {
                            w.getDecorView().setSystemUiVisibility(
                                    android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                                            | android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                            | android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
                        } else {
                            w.getDecorView().setSystemUiVisibility(android.view.View.SYSTEM_UI_FLAG_VISIBLE);
                        }
                    });
                }
                reply(cbId, ok());
                return;
            }

            /* ---------- 设置 ---------- */
            case "setSetting": {
                String key = args.optString("key");
                Object value = args.opt("value");
                setSettingValue(key, value);
                // 2.3.0：设置变更后实时推送给编辑器（顶栏高度、源码按钮、毛玻璃等）
                // 页面回调按标量消费，此处直接推 value，不包对象
                if ("toolbarHeight".equals(key) || "showSourceCodeButton".equals(key)
                        || "contextMenuGlass".equals(key) || "compactWindowStyle".equals(key)
                        || "editorLayout".equals(key)) {
                    notifyEditorEvent(key.replace("showSourceCodeButton", "show-source-code-button")
                            .replace("contextMenuGlass", "context-menu-glass")
                            .replace("compactWindowStyle", "compact-window-style")
                            .replace("editorLayout", "editor-layout")
                            .replace("toolbarHeight", "toolbar-height") + "-changed", value);
                } else if ("topBarDeviceStats".equals(key)) {
                    notifyEditorEvent("top-bar-device-stats-changed", false);
                }
                reply(cbId, ok());
                return;
            }
            case "setUITheme": {
                settings().edit().putString("uiTheme", args.optString("value", "system")).apply();
                String theme = effectiveTheme();
                broadcastTheme(theme);
                reply(cbId, ok());
                return;
            }
            case "setBackgroundThrottling": case "setBypassCORS":
            case "setSpellchecker": case "setExitFullscreenOnEscape":
            case "setRichPresence": {
                reply(cbId, ok());
                return;
            }
            case "setBranding": {
                String key = args.optString("key");
                Object value = args.opt("value");
                setSettingValue(key, value);
                notifyWindows("home", "branding-changed", null);
                reply(cbId, ok());
                return;
            }
            case "resetHomeBranding": {
                settings().edit()
                        .putString("homeLogo", "")
                        .putString("homeLogoText", "")
                        .putString("homeBackground", "")
                        .apply();
                notifyWindows("home", "branding-changed", null);
                reply(cbId, ok());
                return;
            }
            case "getDefaultLogo": {
                reply(cbId, jstr(assetDataUrl("www/home/logo.png")));
                return;
            }
            case "checkForUpdates": {
                // 设置页「立即检查」：网络请求放后台线程，结果回投仍走 reply
                new Thread(() -> reply(cbId, performUpdateCheck().toString()),
                        "nw-update-manual").start();
                return;
            }
            case "enumerateMediaDevices": {
                JSONArray arr = new JSONArray();
                arr.put(new JSONObject().put("deviceId", "default").put("kind", "audioinput").put("label", "Default microphone"));
                arr.put(new JSONObject().put("deviceId", "default").put("kind", "videoinput").put("label", "Default camera"));
                arr.put(new JSONObject().put("deviceId", "default").put("kind", "audiooutput").put("label", "Default speaker"));
                JSONObject o = new JSONObject();
                o.put("devices", arr);
                reply(cbId, o.toString());
                return;
            }
            case "openUserData": {
                toast(caller, "数据目录：应用私有存储（Android/data/org.neowarp.android）");
                reply(cbId, ok());
                return;
            }

            /* ---------- 主题 ---------- */
            case "getTheme": {
                reply(cbId, jstr(effectiveTheme()));
                return;
            }
            case "themeApplied": {
                boolean dark = "dark".equals(args.optString("theme", "light"));
                caller.runOnUiThread(() -> caller.getWebView().setBackgroundColor(dark ? 0xFF333333 : 0xFFFFFFFF));
                reply(cbId, ok());
                return;
            }
            case "notifyThemeChanged": {
                broadcastTheme(args.optString("theme", effectiveTheme()));
                reply(cbId, ok());
                return;
            }
            case "getGlobalUITheme": {
                reply(cbId, jstr(effectiveTheme()));
                return;
            }

            /* ---------- 统计 ---------- */
            case "getSystemStats": reply(cbId, systemStats().toString()); return;
            case "getAISystemInfo": reply(cbId, aiSystemInfo().toString()); return;
            case "getPreferredMediaDevices": {
                JSONObject o = new JSONObject();
                o.put("microphone", settingsOpt("microphone"));
                o.put("camera", settingsOpt("camera"));
                reply(cbId, o.toString());
                return;
            }
            case "getAdvancedCustomizations": {
                JSONObject o = new JSONObject();
                o.put("userscript", "");
                o.put("userstyle", "");
                reply(cbId, o.toString());
                return;
            }

            /* ---------- 舞台脱离/附加 ---------- */
            case "detachStage": {
                WindowActivity.show(caller, "detached-stage", null);
                notifyEditorEvent("stage-detached", null);
                reply(cbId, ok());
                return;
            }
            case "reattachStage": {
                NWActivity ds = findFirstByType("detached-stage");
                if (ds != null) ds.finish();
                notifyEditorEvent("stage-reattached", null);
                reply(cbId, ok());
                return;
            }

            /* ---------- Expands 本地扩展 ---------- */
            case "getNeowarpExpands": {
                reply(cbId, scanExpands().toString());
                return;
            }

            /* ---------- 主页 ---------- */
            case "homeOpenFile": {
                PendingSaf p = new PendingSaf();
                p.winType = winType; p.cbId = cbId; p.kind = "home-open";
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                int req = nextSaf(p);
                caller.startIntentForResult(Intent.createChooser(i, "打开项目"), req);
                return;
            }
            case "getRecentProjects": {
                JSONObject o = new JSONObject();
                o.put("show", settings().getBoolean("showRecentProjects", true));
                o.put("projects", recentList());
                reply(cbId, o.toString());
                return;
            }
            case "openRecentProject": {
                String path = args.optString("path", "");
                try {
                    Uri uri = Uri.parse(path);
                    FileHandle h = registerHandle(uri, nameOf(uri));
                    pendingInitialFile = uri;
                    WindowActivity.show(caller, "editor", null);
                    JSONObject o = new JSONObject().put("result", "opened");
                    reply(cbId, o.toString());
                } catch (Exception e) {
                    reply(cbId, new JSONObject().put("result", "not-found").toString());
                }
                return;
            }

            /* ---------- 加密保存（npnp / viewsb3） ---------- */
            case "encryptSaveBegin": {
                String key = "encryptSave:" + cbId;
                uploadBuffers.put(key, new Object[]{new ByteArrayOutputStream(),
                        new String[]{args.optString("fileId"), args.optString("password")},
                        args.optInt("total", 1), 0});
                // Chunk/End 的 cbId 与 Begin 不同，按 winType 记录最近一次 Begin 的 key
                uploadBuffers.put("lastBegin:editor", new Object[]{key});
                reply(cbId, ok());
                return;
            }
            case "encryptSaveChunk": {
                appendUpload("encryptSave:" + lastBeginKey(cbId), args.optString("b64"));
                reply(cbId, ok());
                return;
            }
            case "encryptSaveEnd": {
                finishEncryptSave(cbId, false);
                return;
            }
            case "encryptSaveViewsb3Begin": {
                uploadBuffers.put("encryptSaveViewsb3:" + cbId, new Object[]{new ByteArrayOutputStream(),
                        new String[]{args.optString("fileId"), ""},
                        args.optInt("total", 1), 0});
                uploadBuffers.put("lastBegin:" + winType, new Object[]{"encryptSaveViewsb3:" + cbId});
                reply(cbId, ok());
                return;
            }
            case "encryptSaveViewsb3Chunk": {
                appendUpload(lastUploadKey(winType), args.optString("b64"));
                reply(cbId, ok());
                return;
            }
            case "encryptSaveViewsb3End": {
                finishEncryptSaveViewsb3(winType, cbId);
                return;
            }
            case "imageEditorOpenFile": {
                PendingSaf p = new PendingSaf();
                p.winType = winType; p.cbId = cbId; p.kind = "image-open";
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("image/*");
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                int req = nextSaf(p);
                caller.startIntentForResult(Intent.createChooser(i, "打开图片"), req);
                return;
            }
            case "imageEditorSaveBegin": case "saveImageBegin": {
                String key = method + ":" + cbId;
                uploadBuffers.put(key, new Object[]{new ByteArrayOutputStream(),
                        new String[]{args.optString("isDataURL", "")}, args.optInt("total", 1), 0});
                uploadBuffers.put("lastBegin:" + winType, new Object[]{key});
                reply(cbId, ok());
                return;
            }
            case "imageEditorSaveChunk": case "saveImageChunk": {
                appendUpload(lastUploadKey(winType), args.optString("b64"));
                reply(cbId, ok());
                return;
            }
            case "imageEditorSaveFinish": case "saveImageFinish": {
                finishImageSave(caller, winType, method, cbId);
                return;
            }

            /* ---------- AI 助手 / SOLO / 扩展编辑器（经编辑器窗口） ---------- */
            case "requestEditorProjectCode": {
                requestEditor(winType, "project-json", new JSONObject().put("requestId", rid(cbId)), cbId, 30000);
                return;
            }
            case "editorApplyProject": {
                forwardToWindow(winType, "editor", "apply-project", args.opt("projectJSON"));
                reply(cbId, ok());
                return;
            }
            case "editorApplySprite": {
                JSONObject o = new JSONObject();
                o.put("spriteJSON", args.opt("spriteJSON"));
                o.put("targetId", args.opt("targetId"));
                forwardToWindow(winType, "editor", "apply-sprite", o);
                reply(cbId, ok());
                return;
            }
            case "requestEditorSpriteLibrary": {
                requestEditor(winType, "sprite-library", new JSONObject(), cbId, 5000);
                return;
            }
            case "requestEditorAITool": {
                JSONObject o = new JSONObject();
                o.put("requestId", rid(cbId));
                o.put("toolName", args.optString("toolName"));
                o.put("params", args.opt("params"));
                requestEditor(winType, "ai-tool", o, cbId, 180000);
                return;
            }
            case "requestEditorSpriteStats": {
                requestEditor(winType, "sprite-stats", new JSONObject(), cbId, 3000);
                return;
            }
            case "requestEditorProjectData": {
                requestEditor(winType, "project-json", new JSONObject(), cbId, 30000);
                return;
            }
            case "webSearch": {
                JSONObject o = new JSONObject();
                o.put("success", false);
                o.put("error", "网页搜索在 Android 版暂不可用");
                reply(cbId, o.toString());
                return;
            }
            case "getAiModelConfigs": {
                String raw = settings().getString("aiModelConfigs", "[]");
                reply(cbId, raw);
                return;
            }
            case "saveAiModelConfigs": {
                settings().edit().putString("aiModelConfigs", args.opt("payload") == null ? "[]" : args.opt("payload").toString()).apply();
                broadcastSetting("ai-model-configs-changed", settings().getString("aiModelConfigs", "[]"));
                reply(cbId, ok());
                return;
            }
            case "getLocale": {
                reply(cbId, jstr(effectiveLocale()));
                return;
            }
            case "collabOpenSb3": {
                // 主页「发起协作 → 打开 sb3」：先走系统文件选择器，再带路径开协作窗口
                PendingSaf p = new PendingSaf();
                p.winType = winType; p.cbId = cbId; p.kind = "collab-open";
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                        "application/octet-stream", "application/zip", "application/x-zip-compressed"});
                int req = nextSaf(p);
                caller.startIntentForResult(Intent.createChooser(i, "选择要协作的 sb3 文件"), req);
                return;
            }
            case "getPhoneLink": { reply(cbId, jstr(null)); return; }
            case "phoneSyncBroadcast": { reply(cbId, ok()); return; }
            case "mobilePreviewStart": case "mobilePreviewStop": {
                JSONObject o = new JSONObject();
                o.put("success", false);
                o.put("error", "手机预览在 Android 版暂不可用");
                reply(cbId, o.toString());
                return;
            }
            case "mobilePreviewQR": { reply(cbId, jstr(null)); return; }

            /* ---------- 协作（桌面局域网特性，Android 降级） ---------- */
            case "collabGetMode": {
                String payload = windowPayloads.get(winType);
                String mode = "host";
                try { mode = new JSONObject(payload == null ? "{}" : payload).optString("mode", "host"); } catch (Exception ignored) {}
                reply(cbId, jstr(mode));
                return;
            }
            case "collabGetLocalIP": { reply(cbId, jstr(null)); return; }
            case "collabDiscover": { reply(cbId, "[]"); return; }
            case "collabStartHost": case "collabJoin": {
                JSONObject o = new JSONObject();
                o.put("success", false);
                o.put("error", "多人协作依赖桌面版局域网服务，Android 版暂不支持");
                reply(cbId, o.toString());
                return;
            }
            case "collabEnd": {
                NWActivity c = findFirstByType("collaboration");
                if (c != null && c != caller) c.finish();
                if (c == caller) caller.finish();
                reply(cbId, ok());
                return;
            }
            case "collabChat": { reply(cbId, ok()); return; }

            /* ---------- 扩展编辑器 ---------- */
            case "extGetInitial": {
                String payload = windowPayloads.get("extension-editor");
                JSONObject o = parse(payload);
                if (o == null) o = new JSONObject();
                if (!o.has("locale")) o.put("locale", effectiveLocale()); // 页面 initial.locale 必须存在
                reply(cbId, o.toString());
                return;
            }
            case "extSave": {
                String content = args.optString("content", "");
                String path = args.optString("path", "");
                if (path.length() > 0) {
                    try {
                        Uri uri = Uri.parse(path);
                        FileHandle h = handleByUri(uri);
                        if (h == null) h = registerHandle(uri, nameOf(uri));
                        writeToUri(h.uri, content.getBytes(StandardCharsets.UTF_8));
                        JSONObject o = new JSONObject();
                        o.put("path", h.uri.toString());
                        o.put("name", h.name);
                        reply(cbId, o.toString());
                    } catch (Exception e) {
                        reply(cbId, err(e));
                    }
                } else {
                    promptSaveText(caller, winType, cbId, args.optString("suggestedName", "extension.js"), content);
                }
                return;
            }
            case "extSaveAs": {
                promptSaveText(caller, winType, cbId, args.optString("suggestedName", "extension.js"), args.optString("content", ""));
                return;
            }
            case "extOpenDialog": {
                PendingSaf p = new PendingSaf();
                p.winType = winType; p.cbId = cbId; p.kind = "ext-open"; p.multi = true;
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                int req = nextSaf(p);
                caller.startIntentForResult(Intent.createChooser(i, "打开扩展"), req);
                return;
            }
            case "extConfirmClose": {
                // 主进程原为三选项对话框：保存 / 放弃 / 取消
                final JSONObject fargs = args;
                main.post(() -> {
                    new AlertDialog.Builder(caller)
                            .setTitle(fargs.optString("fileName", "文件"))
                            .setMessage("是否保存更改？")
                            .setPositiveButton("保存", (d, w) -> reply(cbId, jstr("save")))
                            .setNegativeButton("放弃", (d, w) -> reply(cbId, jstr("discard")))
                            .setNeutralButton("取消", (d, w) -> reply(cbId, jstr("cancel")))
                            .setOnCancelListener(d -> reply(cbId, jstr("cancel")))
                            .show();
                });
                return;
            }
            case "extRename": {
                reply(cbId, err(new Exception("Android 版不支持重命名磁盘文件")));
                return;
            }
            case "extListProjects": {
                JSONArray arr = new JSONArray();
                try {
                    arr.put(new JSONObject().put("id", "editor").put("title", activeProjectTitle()));
                } catch (Exception ignored) {}
                reply(cbId, arr.toString());
                return;
            }
            case "extAddToProject": {
                JSONObject o = new JSONObject();
                o.put("name", args.optString("name"));
                o.put("code", args.optString("code"));
                NWActivity ed = getEditor();
                if (ed == null) { reply(cbId, err(new Exception("编辑器未打开"))); return; }
                String rid = rid(cbId);
                o.put("requestId", rid);
                PendingHub ph = new PendingHub();
                ph.fromType = winType;
                pendingHubReqs.put(rid, ph);
                hubCbIds.put(rid, cbId);
                ed.evaluateJs("__nw.evt('editor','extension-add-to-project'," + jq(o) + ")");
                main.postDelayed(() -> {
                    if (pendingHubReqs.remove(rid) != null) {
                        hubCbIds.remove(rid);
                        reply(cbId, err(new Exception("扩展添加超时")));
                    }
                }, 30000);
                return;
            }
            case "extAddToMyExtensions": {
                try {
                    JSONArray arr = new JSONArray(settings().getString("myExtensions", "[]"));
                    String id = args.optString("id", "");
                    JSONObject entry = new JSONObject()
                            .put("id", id.length() > 0 ? id : "myext" + System.currentTimeMillis())
                            .put("name", args.optString("name"))
                            .put("code", args.optString("code"));
                    JSONArray out = new JSONArray();
                    boolean replaced = false;
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject it = arr.getJSONObject(i);
                        if (it.optString("id").equals(entry.getString("id"))) {
                            out.put(entry); replaced = true;
                        } else out.put(it);
                    }
                    if (!replaced) out.put(entry);
                    settings().edit().putString("myExtensions", out.toString()).apply();
                    reply(cbId, ok());
                } catch (Exception e) {
                    reply(cbId, err(e));
                }
                return;
            }
            case "extGetPopoutInitial": {
                String payload = windowPayloads.get("ai-assistant");
                reply(cbId, payload == null ? jstr(null) : payload);
                return;
            }
            case "extPopoutGetCode": case "extPopoutInsertCode": case "extPopoutReplaceCode": {
                NWActivity ee = findFirstByType("extension-editor");
                String ev = "extPopoutGetCode".equals(method) ? "popout-get-code" : ("popout-insert-code");
                if (ee != null) ee.evaluateJs("__nw.evt('ext','editor-request'," + jq(new JSONObject().put("type", ev).put("requestId", rid(cbId)).put("code", args.optString("code"))) + ")");
                else reply(cbId, jstr(null));
                return;
            }
            case "extReportPopoutState": case "extDockBack": {
                NWActivity ee = findFirstByType("extension-editor");
                if (ee != null) ee.evaluateJs("__nw.evt('ext','ai-popout-closed'," + jq(args.opt("state")) + ")");
                caller.finish();
                reply(cbId, ok());
                return;
            }

            /* ---------- SOLO ---------- */
            case "soloGetState": {
                JSONObject o = new JSONObject();
                o.put("hasProject", false);
                o.put("projectName", "");
                reply(cbId, o.toString());
                return;
            }
            case "soloPickProject": {
                PendingSaf p = new PendingSaf();
                p.winType = winType; p.cbId = cbId; p.kind = "solo-pick";
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                int req = nextSaf(p);
                caller.startIntentForResult(Intent.createChooser(i, "选择项目"), req);
                return;
            }
            case "soloSaveProject": {
                promptSaveSb3(caller, winType, cbId, "solo-project.sb3", pendingInitialFile);
                return;
            }
            case "soloOpenStageWindow": {
                WindowActivity.show(caller, "solo-stage", null);
                reply(cbId, ok());
                return;
            }

            /* ---------- SOLO 舞台 / 脱离舞台 ---------- */
            case "soloStageReady": case "detachedStageReady": {
                reply(cbId, ok());
                return;
            }
            case "soloStageControl": {
                forwardToWindow(winType, "editor", "solo-stage-control", args.opt("action"));
                reply(cbId, ok());
                return;
            }
            case "soloStageAnswer": {
                JSONObject o = new JSONObject().put("text", args.optString("text"));
                forwardToWindow(winType, "editor", "solo-stage-answer", o);
                reply(cbId, ok());
                return;
            }
            case "detachedStageInput": {
                forwardToWindow(winType, "editor", "detached-stage-input", args.opt("inputData"));
                reply(cbId, ok());
                return;
            }

            /* ---------- 待办提醒 ---------- */
            case "todoSetReminder": {
                try {
                    JSONArray arr = new JSONArray(settings().getString("reminders", "[]"));
                    JSONObject o = new JSONObject()
                            .put("todoId", args.optString("todoId"))
                            .put("todoText", args.optString("todoText"))
                            .put("reminderTime", args.optString("reminderTime"));
                    arr.put(o);
                    settings().edit().putString("reminders", arr.toString()).apply();
                    reply(cbId, ok());
                } catch (Exception e) { reply(cbId, err(e)); }
                return;
            }
            case "todoCancelReminder": {
                try {
                    JSONArray arr = new JSONArray(settings().getString("reminders", "[]"));
                    JSONArray out = new JSONArray();
                    for (int i = 0; i < arr.length(); i++) {
                        if (!arr.getJSONObject(i).optString("todoId").equals(args.optString("todoId"))) out.put(arr.getJSONObject(i));
                    }
                    settings().edit().putString("reminders", out.toString()).apply();
                    reply(cbId, ok());
                } catch (Exception e) { reply(cbId, err(e)); }
                return;
            }
            case "todoGetReminders": {
                reply(cbId, settings().getString("reminders", "[]"));
                return;
            }

            /* ---------- 其他 ---------- */
            case "addonsExportSettings": {
                settings().edit().putString("addonsSettings", args.opt("settings") == null ? "{}" : args.opt("settings").toString()).apply();
                reply(cbId, ok());
                return;
            }
            case "updateDownload": case "updateIgnore": {
                reply(cbId, ok());
                return;
            }
            case "requestEditorExport": {
                NWActivity ed = getEditor();
                if (ed == null) { reply(cbId, err(new Exception("编辑器未打开"))); return; }
                String rid = rid(cbId);
                PendingHub ph = new PendingHub();
                ph.fromType = winType;
                ph.deadline = System.currentTimeMillis() + 120000;
                pendingHubReqs.put(rid, ph);
                hubCbIds.put(rid, cbId);
                exportBuffers.put(rid, new Object[]{"project.sb3", new ByteArrayOutputStream(), 0, 0});
                ed.evaluateJs("__nw.editorExport(" + jq(rid) + ")");
                main.postDelayed(() -> {
                    if (pendingHubReqs.remove(rid) != null) {
                        hubCbIds.remove(rid);
                        exportBuffers.remove(rid);
                        reply(cbId, err(new Exception("导出超时")));
                    }
                }, 120000);
                return;
            }

            /* ============================================================
             * 2.3.0 新增 RPC
             * ============================================================ */

            /* --- 语言/引导 --- */
            case "applyLocale": {
                String loc = args.optString("locale", "");
                if (!loc.isEmpty()) settings().edit().putString("locale", loc).apply();
                reply(cbId, ok());
                return;
            }
            case "onboardingDone": {
                settings().edit().putBoolean("onboardingDone", true).apply();
                if (caller != null) caller.finish();
                reply(cbId, ok());
                return;
            }

            /* --- 更新检测（Android 走应用内检查，无自动下载） --- */
            case "getAvailableUpdate": {
                JSONObject u = availableUpdate();
                reply(cbId, u == null ? "null" : u.toString());
                return;
            }

            /* --- 文件关联（仅 Windows 支持） --- */
            case "getFileAssociations": {
                reply(cbId, new JSONObject().put("available", false).put("sb3", false).put("js", false).toString());
                return;
            }
            case "setFileAssociation": {
                reply(cbId, ok());
                return;
            }

            /* --- AI 工作流设置 --- */
            case "aiGetSettings": {
                reply(cbId, new JSONObject().put("settings", readJsonPref("aiSettings", new JSONObject())).toString());
                return;
            }
            case "aiSaveSettings": {
                saveJsonPref("aiSettings", args);
                reply(cbId, ok());
                return;
            }
            case "aiGetWorkflowSettings": {
                reply(cbId, readJsonPref("aiWorkflowSettings", new JSONObject()).toString());
                return;
            }
            case "aiSaveWorkflowSettings": {
                saveJsonPref("aiWorkflowSettings", args);
                reply(cbId, ok());
                return;
            }
            case "aiPickWorkflowDocsDir": case "aiOpenWorkflowDocsDir": {
                reply(cbId, jstr(null)); // Android 用 SAF，无固定目录选择
                return;
            }
            case "aiSaveWorkflowDoc": {
                reply(cbId, ok());
                return;
            }
            case "aiListenersReady": {
                reply(cbId, ok());
                return;
            }

            /* --- 图片编辑器 2.3.0「导出到 Scratch 项目」 --- */
            case "imageEditorListProjects": {
                NWActivity ed = getEditor();
                JSONArray arr = new JSONArray();
                if (ed != null) {
                    JSONObject p = new JSONObject();
                    p.put("id", 1);
                    p.put("title", "当前项目");
                    p.put("sprites", new JSONArray());
                    arr.put(p);
                }
                reply(cbId, arr.toString());
                return;
            }
            case "imageEditorImportToProject": {
                NWActivity ed = getEditor();
                if (ed == null) { reply(cbId, new JSONObject().put("success", false).put("error", "没有已打开的 Scratch 项目").toString()); return; }
                String rid = rid(cbId);
                PendingHub ph = new PendingHub();
                ph.fromType = winType;
                ph.deadline = System.currentTimeMillis() + 30000;
                pendingHubReqs.put(rid, ph);
                hubCbIds.put(rid, cbId);
                JSONObject payload = new JSONObject();
                try {
                    payload.put("requestId", rid)
                            .put("targetId", args.opt("spriteId"))
                            .put("name", args.optString("name", "图片"))
                            .put("dataUrl", args.optString("dataUrl", ""))
                            .put("asNewSprite", args.optBoolean("asNewSprite", false));
                } catch (Exception ignored) {}
                ed.evaluateJs("__nw.evt('editor','import-costume'," + jqRaw(payload) + ")");
                final String timeoutResult = "{\"success\":false,\"error\":\"导入超时\"}";
                main.postDelayed(() -> {
                    if (pendingHubReqs.remove(rid) != null) {
                        hubCbIds.remove(rid);
                        reply(cbId, timeoutResult);
                    }
                }, 30000);
                return;
            }
            case "importCostumeResult": {
                String rid2 = args.optString("requestId", "");
                String cb = hubCbIds.remove(rid2);
                pendingHubReqs.remove(rid2);
                if (cb != null) {
                    JSONObject result = new JSONObject();
                    try {
                        result.put("success", args.optBoolean("success", false));
                        result.put("error", args.opt("error"));
                    } catch (Exception ignored) {}
                    reply(cb, result.toString());
                }
                reply(cbId, ok());
                return;
            }

            /* --- 移动端预览复制 --- */
            case "mobilePreviewCopy": {
                android.content.ClipboardManager cm = (android.content.ClipboardManager) app.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("NeoWarp", args.optString("text", "")));
                reply(cbId, ok());
                return;
            }

            default:
                reply(cbId, jstr(null));
        }
    }

    /* ============================================================
     * SAF 结果 / 文件句柄
     * ============================================================ */

    private int nextSaf(PendingSaf p) {
        int req = ++safSeq;
        pendingSaf.put(req, p);
        return req;
    }

    public void handleActivityResult(NWActivity activity, int requestCode, int resultCode, Intent data) {
        PendingSaf p = pendingSaf.remove(requestCode);
        if (p == null) return;
        Uri uri = (data != null && data.getData() != null) ? data.getData() : null;
        try {
            switch (p.kind) {
                case "save": {
                    if (uri == null) { reply(p.cbId, jstr(null)); return; }
                    persist(uri);
                    FileHandle h = registerHandle(uri, p.suggestedName != null ? p.suggestedName : nameOf(uri));
                    JSONObject o = new JSONObject().put("id", handleId(h)).put("name", h.name);
                    reply(p.cbId, o.toString());
                    return;
                }
                case "open": case "home-open": case "solo-pick": {
                    if (uri == null) { reply(p.cbId, jstr(null)); return; }
                    persist(uri);
                    FileHandle h = registerHandle(uri, nameOf(uri));
                    JSONObject o = new JSONObject().put("id", handleId(h)).put("name", h.name);
                    if ("home-open".equals(p.kind)) {
                        pendingInitialFile = uri;
                        WindowActivity.show(activity, "editor", null);
                        o = new JSONObject().put("result", "opened");
                    }
                    reply(p.cbId, o.toString());
                    return;
                }
                case "image-open": {
                    if (uri == null) { reply(p.cbId, jstr(null)); return; }
                    persist(uri);
                    byte[] bytes = readAll(uri);
                    String b64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
                    // 按实际文件类型返回 mime：SAF 选的是 image/*，硬编码 image/png
                    // 会让 JPG/WebP/GIF 的 data URL 声明类型与内容不符
                    String mime = null;
                    try { mime = app.getContentResolver().getType(uri); } catch (Exception ignored) {}
                    if (mime == null || !mime.startsWith("image/")) mime = "image/png";
                    String dataUrl = "data:" + mime + ";base64," + b64;
                    JSONObject o = new JSONObject()
                            .put("name", nameOf(uri))
                            .put("mime", mime)
                            // 前端 dataUrlFromPayload 读 dataUrl；保留 dataURL 兼容旧版
                            .put("dataUrl", dataUrl)
                            .put("dataURL", dataUrl);
                    reply(p.cbId, o.toString());
                    return;
                }
                case "ext-open": {
                    if (uri == null) { reply(p.cbId, jstr(null)); return; }
                    JSONArray arr = new JSONArray();
                    List<Uri> uris = new ArrayList<>();
                    if (data.getClipData() != null) {
                        for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                            uris.add(data.getClipData().getItemAt(i).getUri());
                        }
                    } else if (uri != null) {
                        uris.add(uri);
                    }
                    for (Uri u : uris) {
                        persist(u);
                        JSONObject f = new JSONObject()
                                .put("path", u.toString())
                                .put("name", nameOf(u))
                                .put("content", new String(readAll(u), StandardCharsets.UTF_8));
                        arr.put(f);
                    }
                    reply(p.cbId, arr.toString());
                    return;
                }
                case "chooser": {
                    // <input type=file> 的 WebView 回调（必须在主线程，onActivityResult 即主线程）
                    if (p.chooserCallback != null) {
                        p.chooserCallback.onReceiveValue(uri == null ? null : new Uri[]{uri});
                    }
                    return;
                }
                case "collab-open": {
                    if (uri == null) { reply(p.cbId, jstr(null)); return; }
                    persist(uri);
                    String name = nameOf(uri);
                    JSONObject payload = new JSONObject()
                            .put("mode", "host")
                            .put("path", uri.toString())
                            .put("name", name);
                    pendingInitialFile = uri;
                    WindowActivity.show(activity, "collaboration", payload.toString());
                    reply(p.cbId, new JSONObject().put("result", "opened").toString());
                    return;
                }
                case "save-text": case "save-image": {
                    handleDeferredSave(activity, p, uri);
                    return;
                }
                default: reply(p.cbId, jstr(null));
            }
        } catch (Exception e) {
            reply(p.cbId, err(e));
        }
    }

    /** <input type=file> 的 SAF 选择器 */
    public void startFileChooser(NWActivity activity, android.webkit.ValueCallback<Uri[]> callback, String[] acceptTypes) {        PendingSaf p = new PendingSaf();
        p.winType = activity.getWindowType();
        p.kind = "chooser";
        p.chooserCallback = callback;
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(guessMime(acceptTypes));
        if ("*/*".equals(guessMime(acceptTypes))) {
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                    "image/*", "audio/*", "application/zip", "application/octet-stream"});
        }
        int req = nextSaf(p);
        activity.startIntentForResult(Intent.createChooser(i, "选择文件"), req);
    }

    /** accept 类型 → SAF MIME 过滤（支持扩展名形式 .sb3/.png/...） */
    private static String guessMime(String[] types) {
        if (types == null || types.length == 0) return "*/*";
        String t = types[0] == null ? "" : types[0].trim().toLowerCase();
        if (t.isEmpty() || t.equals("*/*") || t.equals("*")) return "*/*";
        if (t.contains("image")) return "image/*";
        if (t.contains("audio")) return "audio/*";
        if (t.contains("video")) return "video/*";
        if (t.contains("json")) return "application/json";
        if (t.contains("javascript")) return "text/javascript";
        if (t.contains("zip") || t.contains("octet-stream")) return "application/zip";
        if (t.startsWith(".")) {
            switch (t) {
                case ".sb3": case ".zip": case ".sb2": return "application/zip";
                case ".png": return "image/png";
                case ".jpg": case ".jpeg": return "image/jpeg";
                case ".gif": return "image/gif";
                case ".svg": return "image/svg+xml";
                case ".webp": return "image/webp";
                case ".js": return "text/javascript";
                case ".json": return "application/json";
                case ".wav": return "audio/wav";
                case ".mp3": return "audio/mpeg";
                case ".txt": case ".md": return "text/plain";
                default: return "*/*";
            }
        }
        return "*/*";
    }

    private void persist(Uri uri) {
        try {
            app.getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception ignored) {
        }
    }

    private FileHandle registerHandle(Uri uri, String name) {
        FileHandle h = new FileHandle();
        h.uri = uri;
        h.name = name;
        handles.put(handleId(h), h);
        return h;
    }

    private String handleId(FileHandle h) {
        return "h" + h.uri.hashCode() + "_" + h.uri.toString().length();
    }

    private FileHandle handle(String id) {
        for (FileHandle h : handles.values()) {
            if (handleId(h).equals(id)) return h;
        }
        return null;
    }

    private FileHandle handleByUri(Uri uri) {
        for (FileHandle h : handles.values()) {
            if (h.uri.equals(uri)) return h;
        }
        return registerHandle(uri, nameOf(uri));
    }

    private String nameOf(Uri uri) {
        try {
            ContentResolver cr = app.getContentResolver();
            try (Cursor c = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (c != null && c.moveToFirst()) return c.getString(0);
            }
        } catch (Exception ignored) {
        }
        String last = uri.getLastPathSegment();
        return last == null ? "project.sb3" : last.substring(last.lastIndexOf('/') + 1);
    }

    /* ---------------- 写流（SAF 另存为） ---------------- */

    public void writeChunk(String handleId, String b64) {
        try {
            OutputStream os = activeWrites.get(handleId);
            if (os == null) {
                FileHandle h = handle(handleId);
                if (h == null) return;
                os = app.getContentResolver().openOutputStream(h.uri, "w");
                activeWrites.put(handleId, os);
            }
            byte[] chunk = Base64.decode(b64, Base64.NO_WRAP);
            os.write(chunk);
        } catch (Exception e) {
            android.util.Log.e("NeoWarp", "writeChunk", e);
        }
    }

    public void writeFinish(String handleId) {
        try {
            OutputStream os = activeWrites.remove(handleId);
            if (os != null) os.close();
        } catch (Exception e) {
            android.util.Log.e("NeoWarp", "writeFinish", e);
        }
    }

    public void writeAbort(String handleId) {
        try {
            OutputStream os = activeWrites.remove(handleId);
            if (os != null) os.close();
            FileHandle h = handle(handleId);
            if (h != null) {
                try {
                    DocumentsContract.deleteDocument(app.getContentResolver(), h.uri);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            android.util.Log.e("NeoWarp", "writeAbort", e);
        }
    }

    private void writeToUri(Uri uri, byte[] data) throws Exception {
        try (OutputStream os = app.getContentResolver().openOutputStream(uri, "w")) {
            os.write(data);
        }
    }

    /* ---------------- 加密保存分块上传 ---------------- */

    private String lastUploadKey(String winType) {
        Object[] v = uploadBuffers.get("lastBegin:" + winType);
        return v == null ? "" : String.valueOf(v[0]);
    }

    private String lastBeginKey(String cbId) {
        // encryptSave Begin 的 cbId 与 Chunk 的 cbId 不同：按 winType 的 lastBegin 记录
        // chunk 调用没有独立上下文，这里退化为最近一次 Begin（单窗口串行保存，够用）
        return lastUploadKey("editor");
    }

    private void appendUpload(String key, String b64) {
        Object[] buf = uploadBuffers.get(key);
        if (buf == null) return;
        try {
            ((ByteArrayOutputStream) buf[0]).write(Base64.decode(b64, Base64.NO_WRAP));
            buf[3] = ((Integer) buf[3]) + 1;
        } catch (Exception e) {
            android.util.Log.e("NeoWarp", "appendUpload", e);
        }
    }

    private void finishEncryptSave(String chunkCbId, boolean unused) {
        String key = lastUploadKey("editor");
        Object[] buf = uploadBuffers.remove(key);
        uploadBuffers.remove("lastBegin:editor");
        if (buf == null) { reply(chunkCbId, err(new Exception("no upload"))); return; }
        try {
            byte[] data = ((ByteArrayOutputStream) buf[0]).toByteArray();
            String[] meta = (String[]) buf[1];
            FileHandle h = handle(meta[0]);
            if (h == null) { reply(chunkCbId, err(new Exception("file handle missing"))); return; }
            byte[] enc = encryptNpnp(data, meta[1]);
            writeToUri(h.uri, enc);
            reply(chunkCbId, ok());
        } catch (Exception e) {
            reply(chunkCbId, err(e));
        }
    }

    private void finishEncryptSaveViewsb3(String winType, String endCbId) {
        String key = lastUploadKey(winType);
        Object[] buf = uploadBuffers.remove(key);
        uploadBuffers.remove("lastBegin:" + winType);
        if (buf == null) { reply(endCbId, err(new Exception("no upload"))); return; }
        try {
            byte[] data = ((ByteArrayOutputStream) buf[0]).toByteArray();
            String[] meta = (String[]) buf[1];
            FileHandle h = handle(meta[0]);
            if (h == null) { reply(endCbId, err(new Exception("file handle missing"))); return; }
            byte[] enc = encryptViewSb3(data);
            writeToUri(h.uri, enc);
            reply(endCbId, ok());
        } catch (Exception e) {
            reply(endCbId, err(e));
        }
    }

    /** Begin 调用同时记录 key（bridge.js 的 Begin 与 Chunk 用不同 cbId） */
    // 说明：bridge.js 的 uploadBytes() 三段调用共享同一 method 前缀；
    // Begin 的 cbId 即 key 的一部分。为对齐 Chunk 的检索，这里在 Begin 时
    // 保存 lastBegin，Chunk 直接沿用。（单次保存只有一个并发流，安全）

    private void finishImageSave(NWActivity caller, String winType, String method, String cbId) {
        String key = lastUploadKey(winType);
        Object[] buf = uploadBuffers.remove(key);
        uploadBuffers.remove("lastBegin:" + winType);
        if (buf == null) { reply(cbId, err(new Exception("no upload"))); return; }
        try {
            byte[] data = ((ByteArrayOutputStream) buf[0]).toByteArray();
            // dataURL 前缀剥离：data:image/png;base64,....
            byte[] png = data;
            if (data.length > 4 && data[0] == 'd' && data[1] == 'a' && data[2] == 't' && data[3] == 'a') {
                String s = new String(data, StandardCharsets.UTF_8);
                int comma = s.indexOf(',');
                String b64 = comma >= 0 ? s.substring(comma + 1) : s;
                png = Base64.decode(b64, Base64.NO_WRAP);
            }
            PendingSaf p = new PendingSaf();
            p.winType = winType; p.cbId = cbId; p.kind = "save-image";
            p.suggestedName = "image-" + System.currentTimeMillis() + ".png";
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("image/png");
            i.putExtra(Intent.EXTRA_TITLE, p.suggestedName);
            // 字节先暂存，SAF 选择结果在 handleActivityResult("save-image") 中写文件并 reply
            saveImageBytes.put("save-image:" + p.cbId, png);
            int req = nextSaf(p);
            caller.startIntentForResult(Intent.createChooser(i, "保存图片"), req);
        } catch (Exception e) {
            reply(cbId, err(e));
        }
    }

    private final Map<String, byte[]> saveImageBytes = new ConcurrentHashMap<>();

    /* ============================================================
     * 编辑器服务端请求 hub
     * ============================================================ */

    public void requestEditor(String fromType, String reqType, JSONObject payload, String cbId, long timeoutMs) {
        NWActivity ed = getEditor();
        if (ed == null) { reply(cbId, jstr(null)); return; }
        String rid = rid(cbId);
        PendingHub ph = new PendingHub();
        ph.fromType = fromType;
        ph.deadline = System.currentTimeMillis() + timeoutMs;
        pendingHubReqs.put(rid, ph);
        hubCbIds.put(rid, cbId); // hubRespond 直达回调时定位原始 cbId
        ed.evaluateJs("__nw.editorRequest(" + jq(reqType) + "," + jqRaw(payload) + "," + jq(rid) + ")");
        // 超时兜底
        main.postDelayed(() -> {
            if (pendingHubReqs.remove(rid) != null) {
                hubCbIds.remove(rid);
                reply(cbId, jstr(null));
            }
        }, timeoutMs);
    }

    /** 编辑器窗口回传 hub 请求结果 → 直达发起方回调 */
    public void hubRespond(String fromType, String requestId, String json) {
        PendingHub ph = pendingHubReqs.remove(requestId);
        if (ph == null) return;
        String cb = hubCbIds.remove(requestId);
        if (cb == null) return;
        NWActivity caller = findFirstByType(ph.fromType);
        if (caller == null) return;
        // __nw.cb(cbId, jsonStr)：第二参数为 JSON 字符串，bridge.js 内部 JSON.parse
        caller.evaluateJs("__nw.cb(" + jq(cb) + "," + (json == null ? "null" : jq(json)) + ")");
    }

    private String rid(String cbId) {
        return "req" + Math.abs((cbId + System.nanoTime()).hashCode());
    }

    /* 打包器导出分块 */
    public void exportBegin(String winType, String requestId, String name, int totalChunks) {
        exportBuffers.put(requestId, new Object[]{name, new ByteArrayOutputStream(), totalChunks, 0});
    }

    public void exportChunk(String requestId, String b64) {
        Object[] buf = exportBuffers.get(requestId);
        if (buf == null) return;
        try {
            ((ByteArrayOutputStream) buf[1]).write(Base64.decode(b64, Base64.NO_WRAP));
        } catch (Exception e) {
            android.util.Log.e("NeoWarp", "exportChunk", e);
        }
    }

    public void exportEnd(String requestId) {
        Object[] buf = exportBuffers.remove(requestId);
        PendingHub ph = pendingHubReqs.remove(requestId);
        if (buf == null || ph == null) return;
        try {
            byte[] bytes = ((ByteArrayOutputStream) buf[1]).toByteArray();
            String token = cacheToken(bytes);
            String name = String.valueOf(buf[0]);
            NWActivity caller = findFirstByType(ph.fromType);
            if (caller == null) return;
            JSONObject o = new JSONObject().put("token", token).put("name", name.endsWith(".sb3") ? name : name + ".sb3");
            caller.evaluateJs("__nw.cb(" + jq(hubCbId(requestId)) + "," + jq(o.toString()) + ")");
        } catch (Exception e) {
            android.util.Log.e("NeoWarp", "exportEnd", e);
        }
    }

    private final Map<String, String> hubCbIds = new ConcurrentHashMap<>();

    private String hubCbId(String requestId) {
        String id = hubCbIds.remove(requestId);
        return id == null ? ("cb-unknown-" + requestId) : id;
    }

    /* ============================================================
     * 窗口间转发
     * ============================================================ */

    public void forwardToWindow(String fromType, String targetType, String event, Object data) {
        NWActivity target = findFirstByType(targetType);
        if (target == null) return;
        target.evaluateJs("__nw.evt(" + jq(targetType) + "," + jq(event) + "," + jqRaw(data) + ")");
    }

    private void notifyEditorEvent(String event, Object data) {
        NWActivity ed = getEditor();
        if (ed != null) ed.evaluateJs("__nw.evt('editor'," + jq(event) + "," + jqRaw(data) + ")");
    }

    private void notifyWindows(String type, String event, Object data) {
        NWActivity w = findFirstByType(type);
        if (w != null) w.evaluateJs("__nw.evt(" + jq(type) + "," + jq(event) + "," + jqRaw(data) + ")");
    }

    private void broadcastSetting(String event, String jsonRaw) {
        for (String t : new String[]{"editor", "desktop-settings", "ai-assistant", "solo", "extension-editor"}) {
            NWActivity w = findFirstByType(t);
            if (w != null) w.evaluateJs("__nw.evt('settings'," + jq(event) + "," + jsonRaw + ")");
        }
    }

    private void broadcastTheme(String theme) {
        Map<String, String[]> events = new LinkedHashMap<>();
        events.put("editor", new String[]{"global-ui-theme-changed", "theme-changed"});
        events.put("home", new String[]{"theme-changed"});
        events.put("desktop-settings", new String[]{"theme-changed"});
        events.put("task-manager", new String[]{"theme-changed"});
        events.put("contact", new String[]{"theme-changed"});
        events.put("todo-list", new String[]{"theme-changed"});
        events.put("ai-assistant", new String[]{"theme-changed"});
        events.put("solo", new String[]{"theme-changed"});
        events.put("collaboration", new String[]{"theme-changed"});
        events.put("project-analysis", new String[]{"theme-changed"});
        events.put("image-editor", new String[]{"theme-changed"});
        events.put("onboarding", new String[]{"theme-changed"});
        events.put("mobile-preview", new String[]{"theme-changed"});
        for (Map.Entry<String, String[]> e : events.entrySet()) {
            NWActivity w = findFirstByType(e.getKey());
            if (w == null) continue;
            w.applySystemBarTheme(); // 状态栏/导航栏随主题切换
            for (String ev : e.getValue()) {
                w.evaluateJs("__nw.evt(" + jq(e.getKey()) + "," + jq(ev) + "," + jq(theme) + ")");
            }
        }
    }

    /* ============================================================
     * 最近项目
     * ============================================================ */

    private void addRecent(Uri uri, String name) {
        try {
            JSONArray list = new JSONArray(settings().getString("recentProjects", "[]"));
            JSONArray out = new JSONArray();
            JSONObject entry = new JSONObject()
                    .put("path", uri.toString())
                    .put("type", name.endsWith(".js") ? "extension" : "scratch")
                    .put("name", name)
                    .put("openedAt", System.currentTimeMillis());
            out.put(entry);
            for (int i = 0; i < list.length() && out.length() < MAX_RECENT; i++) {
                JSONObject it = list.getJSONObject(i);
                if (!it.optString("path").equals(uri.toString())) out.put(it);
            }
            while (out.length() > MAX_RECENT) {
                JSONArray tmp = new JSONArray();
                for (int i = 0; i < out.length() - 1; i++) tmp.put(out.get(i));
                out = tmp;
            }
            settings().edit().putString("recentProjects", out.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private JSONArray recentList() {
        try {
            JSONArray list = new JSONArray(settings().getString("recentProjects", "[]"));
            JSONArray out = new JSONArray();
            for (int i = 0; i < list.length() && out.length() < 6; i++) {
                JSONObject it = list.getJSONObject(i);
                Uri u = Uri.parse(it.optString("path"));
                if (fileExists(u)) out.put(it);
            }
            return out;
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private boolean fileExists(Uri uri) {
        try {
            InputStream is = app.getContentResolver().openInputStream(uri);
            if (is != null) { is.close(); return true; }
        } catch (Exception ignored) {
        }
        return false;
    }

    public void setPendingInitialFile(Uri uri) {
        this.pendingInitialFile = uri;
    }

    /* ============================================================
     * 设置
     * ============================================================ */

    private SharedPreferences settings() {
        return app.getSharedPreferences(SETTINGS_PREF, Context.MODE_PRIVATE);
    }

    private Object settingsOpt(String key) {
        // 设置项可能以 Boolean 或 String 写入，统一按 Object 读取避免 ClassCastException
        try {
            if (!settings().contains(key)) return JSONObject.NULL;
            Object v = settings().getAll().get(key);
            if (v == null) return JSONObject.NULL;
            if (v instanceof String && ((String) v).isEmpty()) return JSONObject.NULL;
            return v;
        } catch (Exception e) {
            return JSONObject.NULL;
        }
    }

    private void setSettingValue(String key, Object value) {
        SharedPreferences.Editor ed = settings().edit();
        if (value == null || value == JSONObject.NULL) {
            ed.putString(key, "");
        } else if (value instanceof Boolean) {
            ed.putBoolean(key, (Boolean) value);
        } else {
            ed.putString(key, value.toString());
        }
        ed.apply();
    }

    /** 2.3.0：以 JSON 字符串形式持久化一组设置（AI 设置 / 工作流设置） */
    private JSONObject readJsonPref(String key, JSONObject fallback) {
        String raw = settings().getString(key, "");
        if (raw == null || raw.isEmpty()) return fallback;
        try { return new JSONObject(raw); } catch (Exception e) { return fallback; }
    }

    private void saveJsonPref(String key, JSONObject value) {
        settings().edit().putString(key, value == null ? "{}" : value.toString()).apply();
    }

    /* ============================================================
     * 语言（l10n）—— 与桌面端 src-main/l10n/index.js 同构
     * ============================================================ */

    /** 生效语言：优先应用设置，否则跟随系统；归一化（zh* → zh / zh-tw） */
    public String effectiveLocale() {
        String saved = settings().getString("locale", "");
        String loc = (saved == null || saved.length() == 0) ? systemLocale() : saved;
        return normalizeLocale(loc);
    }

    private static String systemLocale() {
        try {
            java.util.Locale l = java.util.Locale.getDefault();
            String lang = l.getLanguage();
            if (lang == null || lang.isEmpty()) return "en";
            String country = l.getCountry();
            return country == null || country.isEmpty() ? lang : lang + "-" + country;
        } catch (Exception e) {
            return "en";
        }
    }

    private static String normalizeLocale(String loc) {
        if (loc == null) return "en";
        String l = loc.toLowerCase(java.util.Locale.ROOT).replace('_', '-');
        if (l.startsWith("zh")) {
            return (l.contains("hant") || l.contains("tw") || l.contains("hk") || l.contains("mo")) ? "zh-tw" : "zh";
        }
        int dash = l.indexOf('-');
        String base = dash > 0 ? l.substring(0, dash) : l;
        return base.isEmpty() ? "en" : base;
    }

    private final Map<String, JSONObject> stringsCache = new ConcurrentHashMap<>();

    /** 读取 assets/www/l10n/<locale>.json（缺失回退 en），结构同桌面端 getStrings() */
    private JSONObject loadStrings(String locale) {
        JSONObject cached = stringsCache.get(locale);
        if (cached != null) return cached;
        JSONObject out = null;
        String base = locale != null && locale.contains("-") ? locale.substring(0, locale.indexOf('-')) : locale;
        for (String cand : new String[]{locale, base, "en"}) {
            if (cand == null || cand.isEmpty()) continue;
            try {
                InputStream is = app.getAssets().open("www/l10n/" + cand + ".json");
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
                is.close();
                out = new JSONObject(new String(bos.toByteArray(), StandardCharsets.UTF_8));
                break;
            } catch (Exception ignored) {
            }
        }
        if (out == null) out = new JSONObject();
        stringsCache.put(locale, out);
        return out;
    }

    private static Object optOrNull(String v) {
        return v == null || v.isEmpty() ? JSONObject.NULL : v;
    }

    /** 桌面设置窗口 init：{locale, strings, settings, version}（字段名与桌面端一致） */
    private JSONObject desktopSettingsInit() {
        JSONObject o = new JSONObject();
        try {
            o.put("locale", effectiveLocale());
            o.put("strings", loadStrings(effectiveLocale()));
            o.put("version", versionName());
            JSONObject s = new JSONObject();
            s.put("updateCheckerAllowed", settings().getBoolean("updateChecker", true));
            s.put("updateChecker", settings().getBoolean("updateChecker", true));
            s.put("uiTheme", settings().getString("uiTheme", "system"));
            s.put("microphone", optOrNull(settings().getString("microphone", "")));
            s.put("camera", optOrNull(settings().getString("camera", "")));
            s.put("hardwareAcceleration", true);
            s.put("backgroundThrottling", true);
            s.put("bypassCORS", true);
            s.put("spellchecker", settings().getBoolean("spellchecker", true));
            s.put("exitFullscreenOnEscape", true);
            s.put("richPresenceAvailable", false);
            s.put("richPresence", false);
            s.put("codeAreaBackgroundImage", optOrNull(settings().getString("codeAreaBackgroundImage", "")));
            s.put("stageAreaBackgroundImage", optOrNull(settings().getString("stageAreaBackgroundImage", "")));
            s.put("homeLogo", optOrNull(settings().getString("homeLogo", "")));
            s.put("homeLogoText", optOrNull(settings().getString("homeLogoText", "")));
            s.put("homeBackground", optOrNull(settings().getString("homeBackground", "")));
            s.put("showRecentProjects", settings().getBoolean("showRecentProjects", true));
            // ---- 2.3.0 新增设置项 ----
            s.put("toolbarHeight", settings().getString("toolbarHeight", "normal"));
            s.put("compactWindowStyle", settings().getBoolean("compactWindowStyle", false));
            s.put("contextMenuGlass", settings().getBoolean("contextMenuGlass", false));
            s.put("showSourceCodeButton", settings().getBoolean("showSourceCodeButton", true));
            s.put("editorLayout", settings().getString("editorLayout", "horizontal"));
            s.put("fileAssociationsAvailable", false); // 文件关联仅 Windows 支持
            s.put("fileAssociations", new JSONObject());
            o.put("settings", s);
        } catch (Exception ignored) {
        }
        return o;
    }

    private JSONObject settingsSnapshot() {        JSONObject o = new JSONObject();
        try {
            o.put("locale", effectiveLocale());
            o.put("version", versionName());
            o.put("updateChecker", settings().getBoolean("updateChecker", true));
            o.put("uiTheme", settings().getString("uiTheme", "system"));
            String mic = settings().getString("microphone", "");
            o.put("microphone", mic.isEmpty() ? JSONObject.NULL : mic);
            String cam = settings().getString("camera", "");
            o.put("camera", cam.isEmpty() ? JSONObject.NULL : cam);
            o.put("hardwareAcceleration", settings().getBoolean("hardwareAcceleration", true));
            o.put("backgroundThrottling", true);
            o.put("bypassCORS", settings().getBoolean("bypassCORS", true));
            o.put("spellchecker", settings().getBoolean("spellchecker", true));
            o.put("exitFullscreenOnEscape", true);
            o.put("richPresence", false);
            o.put("codeAreaBackgroundImage", settingsOpt("codeAreaBackgroundImage"));
            o.put("stageAreaBackgroundImage", settingsOpt("stageAreaBackgroundImage"));
            o.put("topBarDeviceStats", false);
            o.put("homeLogo", settingsOpt("homeLogo"));
            o.put("homeLogoText", settingsOpt("homeLogoText"));
            o.put("homeBackground", settingsOpt("homeBackground"));
            o.put("showRecentProjects", settings().getBoolean("showRecentProjects", true));
        } catch (Exception ignored) {
        }
        return o;
    }

    public static boolean isDarkTheme(Context ctx) {
        return "dark".equals(get(ctx).effectiveTheme());
    }

    private String effectiveTheme() {
        String uiTheme = settings().getString("uiTheme", "system");
        if ("light".equals(uiTheme) || "dark".equals(uiTheme)) return uiTheme;
        int mode = app.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES ? "dark" : "light";
    }

    private String versionName() {
        try {
            return app.getPackageManager().getPackageInfo(app.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "0.0.2";
        }
    }

    /* ============================================================
     * 检查更新
     *   仓库：NeoWarpEditor/NeoWarp-for-Android
     *   频率：每天第一次打开软件时自动检查一次；设置页「立即检查」不受限制
     * ============================================================ */

    /** 本地时区的日期键 yyyy-MM-dd，用于「每天一次」门控 */
    private static String todayKey() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(new java.util.Date());
    }

    private static String normalizeVersion(String v) {
        if (v == null) return "0";
        String s = v.trim();
        if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1);
        int dash = s.indexOf('-');
        if (dash >= 0) s = s.substring(0, dash);
        int plus = s.indexOf('+');
        if (plus >= 0) s = s.substring(0, plus);
        return s.isEmpty() ? "0" : s;
    }

    private static int parseIntSafe(String s) {
        try {
            String digits = s.replaceAll("[^0-9]", "");
            return digits.isEmpty() ? 0 : Integer.parseInt(digits);
        } catch (Exception e) {
            return 0;
        }
    }

    /** 语义化版本比较：latest 比 current 新时返回 true */
    private static boolean isNewerVersion(String current, String latest) {
        String[] a = normalizeVersion(current).split("\\.");
        String[] b = normalizeVersion(latest).split("\\.");
        int n = Math.max(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int x = i < a.length ? parseIntSafe(a[i]) : 0;
            int y = i < b.length ? parseIntSafe(b[i]) : 0;
            if (y != x) return y > x;
        }
        return false;
    }

    private String fetchJson(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("User-Agent", "NeoWarp/" + versionName() + " (Android)");
            int code = conn.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            InputStream is = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            is.close();
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            conn.disconnect();
        }
    }

    /** 读取 Release 列表，取版本号最大的非草稿发布（正式版与预发布均可） */
    private JSONObject fetchLatestRelease() throws Exception {
        JSONArray releases = new JSONArray(fetchJson(UPDATE_API_URL));
        JSONObject best = null;
        String bestVersion = "";
        for (int i = 0; i < releases.length(); i++) {
            JSONObject j = releases.optJSONObject(i);
            if (j == null || j.optBoolean("draft", false)) continue;
            String tag = j.optString("tag_name", "");
            if (tag.isEmpty()) continue;
            String v = normalizeVersion(tag);
            if (best == null || isNewerVersion(bestVersion, v)) {
                best = j;
                bestVersion = v;
            }
        }
        if (best == null) throw new IOException("仓库暂无 Release");
        return new JSONObject()
                .put("version", bestVersion)
                .put("url", best.optString("html_url", UPDATE_RELEASES_URL))
                .put("notes", best.optString("body", ""));
    }

    /** 已检测到的新版本（首页胶囊读取），无则 null */
    private JSONObject availableUpdate() {
        String raw = settings().getString(PREF_AVAILABLE_UPDATE, "");
        if (raw == null || raw.isEmpty()) return null;
        try {
            JSONObject o = new JSONObject(raw);
            return o.optString("version", "").isEmpty() ? null : o;
        } catch (Exception e) {
            return null;
        }
    }

    private void setAvailableUpdate(String version, String url) {
        try {
            settings().edit().putString(PREF_AVAILABLE_UPDATE,
                    new JSONObject().put("version", version).put("url", url).toString()).apply();
        } catch (Exception ignored) {}
        broadcastAvailableUpdate();
    }

    private void clearAvailableUpdate() {
        settings().edit().remove(PREF_AVAILABLE_UPDATE).apply();
        broadcastAvailableUpdate();
    }

    /** 广播给首页；__nw.evt 第三参数必须是 JSON 字符串，故用 jq 包一层 */
    private void broadcastAvailableUpdate() {
        JSONObject u = availableUpdate();
        final String payload = u == null ? "null" : u.toString();
        main.post(() -> {
            NWActivity w = findFirstByType("home");
            if (w != null) {
                w.evaluateJs("__nw.evt('home','update-available'," + jq(payload) + ")");
            }
        });
    }

    /** 执行一次检查：返回设置页所需字段，并同步首页胶囊 */
    private JSONObject performUpdateCheck() {
        JSONObject result = new JSONObject();
        String current = versionName();
        try {
            result.put("currentVersion", current);
            JSONObject latest = fetchLatestRelease();
            String lv = latest.getString("version");
            String url = latest.optString("url", UPDATE_RELEASES_URL);
            result.put("latestVersion", lv);
            result.put("releaseUrl", url);
            result.put("releaseNotes", latest.optString("notes", ""));
            boolean hasUpdate = isNewerVersion(current, lv);
            result.put("hasUpdate", hasUpdate);
            if (hasUpdate) setAvailableUpdate(lv, url);
            else clearAvailableUpdate();
        } catch (Exception e) {
            try {
                result.put("hasUpdate", false);
                result.put("error", e.getMessage() == null ? "网络请求失败" : e.getMessage());
            } catch (Exception ignored) {}
        }
        return result;
    }

    /**
     * 每天第一次打开软件时自动检查一次更新。
     * 由 MainActivity.onCreate 调用；同一天内再次启动直接跳过。
     * 设置里关闭「自动检查」时不执行；设置页手动「立即检查」不受此限制。
     */
    public void checkForUpdatesOnStartup() {
        try {
            if (!settings().getBoolean("updateChecker", true)) return;
            String today = todayKey();
            if (today.equals(settings().getString(PREF_LAST_UPDATE_CHECK, ""))) return;
            settings().edit().putString(PREF_LAST_UPDATE_CHECK, today).apply();
            new Thread(() -> performUpdateCheck(), "nw-update-daily").start();
        } catch (Exception ignored) {}
    }

    private String activeProjectTitle() {
        return "Editor";
    }

    /* ============================================================
     * 系统统计
     * ============================================================ */

    private JSONObject systemStats() {
        JSONObject o = new JSONObject();
        try {
            long[] times = cpuTimes();
            long now = System.currentTimeMillis();
            long totalDiff = times[1] - lastCpuTotal;
            long idleDiff = times[0] - lastCpuIdle;
            long dt = now - lastCpuTime;
            int cpuPercent = 0;
            if (lastCpuTime != 0 && dt > 0 && totalDiff > 0) {
                // 按时间比例归一化到百分比
                cpuPercent = (int) Math.round((1.0 - (double) idleDiff / totalDiff) * 100);
            }
            cpuPercent = Math.max(0, Math.min(100, cpuPercent));
            lastCpuIdle = times[0];
            lastCpuTotal = times[1];
            lastCpuTime = now;

            android.app.ActivityManager am = (android.app.ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
            android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            long totalMB = mi.totalMem / (1024 * 1024);
            long usedMB = (mi.totalMem - mi.availMem) / (1024 * 1024);
            o.put("cpuPercent", cpuPercent);
            o.put("ramUsedMB", usedMB);
            o.put("ramTotalMB", totalMB);
            // 任务管理器/悬浮层使用字节口径的字段，一并提供避免出现 NaN
            o.put("usedMemory", mi.totalMem - mi.availMem);
            o.put("totalMemory", mi.totalMem);
        } catch (Exception ignored) {
        }
        return o;
    }

    /** {idle, total} 采样自 /proc/stat */
    private long[] cpuTimes() {
        long idle = 0, total = 0;
        try {
            String[] lines = readProcStat();
            if (lines != null && lines.length > 0) {
                String[] parts = lines[0].trim().split("\\s+");
                // cpu  user nice system idle iowait irq softirq steal ...
                long user = Long.parseLong(parts[1]);
                long nice = Long.parseLong(parts[2]);
                long system = Long.parseLong(parts[3]);
                idle = Long.parseLong(parts[4]);
                long iowait = parts.length > 5 ? Long.parseLong(parts[5]) : 0;
                long irq = parts.length > 6 ? Long.parseLong(parts[6]) : 0;
                long softirq = parts.length > 7 ? Long.parseLong(parts[7]) : 0;
                long steal = parts.length > 8 ? Long.parseLong(parts[8]) : 0;
                idle += iowait;
                total = user + nice + system + idle + irq + softirq + steal;
            }
        } catch (Exception ignored) {
        }
        return new long[]{idle, total};
    }

    private String[] readProcStat() {
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.FileReader("/proc/stat"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            r.close();
            return sb.toString().split("\n");
        } catch (Exception e) {
            return null;
        }
    }

    private JSONObject aiSystemInfo() {
        JSONObject info = new JSONObject();
        JSONObject o = new JSONObject();
        try {
            info.put("model", Build.HARDWARE);
            info.put("cores", Runtime.getRuntime().availableProcessors());
            info.put("speed", "—");
            info.put("architecture", Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "unknown");
            info.put("platform", "android");
            info.put("release", Build.VERSION.RELEASE);
            info.put("hostname", Build.MODEL);
            android.app.ActivityManager am = (android.app.ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
            android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            info.put("totalMemory", String.format("%.2f GB", mi.totalMem / 1024.0 / 1024 / 1024));
            info.put("freeMemory", String.format("%.2f GB", mi.availMem / 1024.0 / 1024 / 1024));
            info.put("uptime", String.format("%.2f hours", android.os.SystemClock.elapsedRealtime() / 3600000.0));
            info.put("userInfo", "android");
            info.put("homedir", app.getFilesDir().getAbsolutePath());
            info.put("endianness", "LE");
            o.put("success", true);
            o.put("data", info);
        } catch (Exception e) {
            try {
                o.put("success", false);
                o.put("error", String.valueOf(e));
            } catch (Exception ignored) {
            }
        }
        return o;
    }

    /* ============================================================
     * Expands 本地扩展扫描
     * ============================================================ */

    private static final java.util.Set<String> IMAGE_EXT = new java.util.HashSet<>(
            java.util.Arrays.asList("png", "jpg", "jpeg", "webp", "gif", "svg", "bmp", "avif"));

    private JSONArray scanExpands() {
        JSONArray out = new JSONArray();
        try {
            String[] exts = app.getAssets().list("www/expands");
            if (exts == null) return out;
            for (String ext : exts) {
                String base = "www/expands/" + ext;
                String logo = null, bigImage = null, author = null, description = null;
                String[] subs = app.getAssets().list(base);
                if (subs == null) continue;
                for (String sub : subs) {
                    if (isImage(sub) && logo == null) logo = "/expands/" + ext + "/" + sub;
                }
                for (String sub : subs) {
                    String subPath = base + "/" + sub;
                    String[] files = app.getAssets().list(subPath);
                    if (files == null) continue;
                    for (String f : files) {
                        if (f.endsWith(".js")) {
                            author = sub;
                            description = f.substring(0, f.length() - 3);
                            String url = "/expands/" + ext + "/" + sub + "/" + f;
                            String extensionId = readExtensionId(url);
                            JSONObject item = new JSONObject();
                            item.put("name", ext);
                            item.put("extensionId", extensionId == null ? ext.toLowerCase() : extensionId);
                            item.put("extensionURL", url);
                            item.put("iconURL", bigImage == null ? JSONObject.NULL : bigImage);
                            item.put("insetIconURL", logo == null ? JSONObject.NULL : logo);
                            item.put("description", description);
                            item.put("author", author);
                            out.put(item);
                        } else if (isImage(f) && bigImage == null) {
                            bigImage = "/expands/" + ext + "/" + sub + "/" + f;
                        }
                    }
                }
            }
        } catch (Exception e) {
            android.util.Log.e("NeoWarp", "scanExpands", e);
        }
        return out;
    }

    private boolean isImage(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) return false;
        return IMAGE_EXT.contains(name.substring(dot + 1).toLowerCase());
    }

    private String readExtensionId(String assetPath) {
        try {
            InputStream is = app.getAssets().open(assetPath.replaceFirst("^/", ""));
            byte[] head = new byte[2048];
            int n = is.read(head);
            is.close();
            if (n <= 0) return null;
            String s = new String(head, 0, n, StandardCharsets.UTF_8);
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("^//\\s*ID:\\s*(\\S+)", java.util.regex.Pattern.MULTILINE).matcher(s);
            return m.find() ? m.group(1) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /* ============================================================
     * 加密（npnp / viewsb3 —— 与 src-main/windows/editor.js 格式一致）
     * ============================================================ */

    private static byte[] MAGIC_NPNP = {'N', 'P', 'N', 'P', 1};
    private static byte[] MAGIC_VSB3 = {'V', 'S', 'B', '3', 1};

    private boolean magicEquals(byte[] head, String magic) {
        if (head == null || head.length < magic.length()) return false;
        return magic.equals(new String(head, 0, magic.length(), StandardCharsets.UTF_8));
    }

    private byte[] readHead(Uri uri, int n) {
        try (InputStream is = app.getContentResolver().openInputStream(uri)) {
            byte[] buf = new byte[n];
            int read = is.read(buf);
            return read == buf.length ? buf : java.util.Arrays.copyOf(buf, Math.max(read, 0));
        } catch (Exception e) {
            return new byte[0];
        }
    }

    private byte[] readAll(Uri uri) throws Exception {
        InputStream is = app.getContentResolver().openInputStream(uri);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return bos.toByteArray();
    }

    private String cacheToken(byte[] data) {
        String token = "d" + Integer.toHexString(data.hashCode()) + System.nanoTime();
        decryptCache.put(token, data);
        return token;
    }

    private static byte[] pbkdf2(String password, byte[] salt, int iterations, int keyLen, String algo) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, keyLen * 8);
        SecretKeyFactory f = SecretKeyFactory.getInstance(algo);
        return f.generateSecret(spec).getEncoded();
    }

    /** NPNP\x01 | salt(16) | iv(12) | tag(16) | AES-256-GCM */
    private byte[] encryptNpnp(byte[] data, String password) throws Exception {
        byte[] salt = new byte[16];
        byte[] iv = new byte[12];
        random.nextBytes(salt);
        random.nextBytes(iv);
        byte[] key = pbkdf2(password, salt, 100000, 32, "PBKDF2WithHmacSHA512");
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        byte[] enc = c.doFinal(data); // doFinal 输出含 16 字节 tag
        byte[] tag = java.util.Arrays.copyOfRange(enc, enc.length - 16, enc.length);
        byte[] cipherText = java.util.Arrays.copyOfRange(enc, 0, enc.length - 16);
        return concat(concat(concat(concat(MAGIC_NPNP, salt), iv), tag), cipherText);
    }

    private byte[] decryptNpnp(byte[] data, String password) throws Exception {
        if (data.length < 5 + 16 + 12 + 16 || !magicEquals(data, "NPNP\u0001")) {
            throw new Exception("Not a valid .npnp file");
        }
        byte[] salt = slice(data, 5, 21);
        byte[] iv = slice(data, 21, 33);
        byte[] tag = slice(data, 33, 49);
        byte[] cipherText = slice(data, 49, data.length);
        byte[] key = pbkdf2(password, salt, 100000, 32, "PBKDF2WithHmacSHA512");
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        c.update(cipherText);
        return c.doFinal(tag);
    }

    private static final byte[] VSB3_SALT = "NeoWarpViewSb3FixedSalt2024".getBytes(StandardCharsets.UTF_8);
    private static final byte[] VSB3_IV = "ViewSb3FixIV".getBytes(StandardCharsets.UTF_8);

    private byte[] encryptViewSb3(byte[] data) throws Exception {
        byte[] key = pbkdf2("neowarp-viewsb3-viewonly-v1", VSB3_SALT, 10000, 32, "PBKDF2WithHmacSHA256");
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, VSB3_IV));
        byte[] enc = c.doFinal(data);
        byte[] tag = java.util.Arrays.copyOfRange(enc, enc.length - 16, enc.length);
        byte[] cipherText = java.util.Arrays.copyOfRange(enc, 0, enc.length - 16);
        return concat(concat(concat(MAGIC_VSB3, VSB3_IV), tag), cipherText);
    }

    private byte[] decryptViewSb3(byte[] data) throws Exception {
        if (data.length < 5 + 12 + 16 || !magicEquals(data, "VSB3\u0001")) {
            throw new Exception("Not a valid .viewsb3 file");
        }
        byte[] iv = slice(data, 5, 17);
        byte[] tag = slice(data, 17, 33);
        byte[] cipherText = slice(data, 33, data.length);
        byte[] key = pbkdf2("neowarp-viewsb3-viewonly-v1", VSB3_SALT, 10000, 32, "PBKDF2WithHmacSHA256");
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        c.update(cipherText);
        return c.doFinal(tag);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static byte[] slice(byte[] data, int from, int to) {
        return java.util.Arrays.copyOfRange(data, from, to);
    }

    /* ============================================================
     * 资产伺服 & 代理
     * ============================================================ */

    public WebResourceResponse serveAsset(String path) {
        WebResourceResponse r = serveAssetNullable(path);
        if (r == null) r = errorResponse(404, "not found: " + path);
        return r;
    }

    public WebResourceResponse serveAssetNullable(String path) {
        try {
            String clean = normalizeAssetPath(path);
            if (clean == null) return null;
            AssetStream as = openAsset(clean);
            if (as == null) return null;
            String mime = mimeOf(clean);
            WebResourceResponse resp = new WebResourceResponse(mime, mime.startsWith("text/") || mime.contains("javascript") || mime.contains("json") ? "utf-8" : null, as.stream);
            Map<String, String> headers = new HashMap<>();
            headers.put("Access-Control-Allow-Origin", "*");
            // 只有实际打开的是 .br 文件才声明 br（clean 是原始路径，永远不带 .br，
            // 旧代码用 clean.endsWith(".br") 判断是永不命中的死代码）。
            if (as.brotli) headers.put("Content-Encoding", "br");
            resp.setResponseHeaders(headers);
            return resp;
        } catch (Exception e) {
            return null;
        }
    }

    private String normalizeAssetPath(String path) {
        if (path == null) return null;
        if (path.indexOf("..") >= 0) return null; // 防目录穿越
        if (path.endsWith("/")) path = path + "index.html";
        return path;
    }

    /** 资源流 + 是否来自 .br 预压缩文件 */
    private static class AssetStream {
        final InputStream stream;
        final boolean brotli;
        AssetStream(InputStream stream, boolean brotli) {
            this.stream = stream;
            this.brotli = brotli;
        }
    }

    private AssetStream openAsset(String path) throws IOException {
        try {
            // 优先未压缩原件。素材库/扩展资源在打包阶段已从 .br 展开为原件：
            // WebView 的 shouldInterceptRequest 响应不经过 Chromium 的
            // Content-Encoding 解压层，直接返回 brotli 字节会被当成 SVG/PNG/JS
            // 原格式解析而失败，表现为从角色/造型库添加的素材全部显示问号。
            return new AssetStream(app.getAssets().open(path), false);
        } catch (java.io.FileNotFoundException e) {
            // 仅当原件缺失时才回退到 .br（兜底，并声明 Content-Encoding）
            try {
                return new AssetStream(app.getAssets().open(path + ".br"), true);
            } catch (Exception e2) {
                return null;
            }
        }
    }

    private static String mimeOf(String path) {
        String p = path.toLowerCase();
        String ext = p.substring(p.lastIndexOf('.') + 1);
        switch (ext) {
            case "html": case "htm": return "text/html";
            case "js": case "mjs": return "text/javascript";
            case "css": return "text/css";
            case "json": case "map": return "application/json";
            case "svg": return "image/svg+xml";
            case "png": return "image/png";
            case "jpg": case "jpeg": return "image/jpeg";
            case "webp": return "image/webp";
            case "gif": return "image/gif";
            case "bmp": return "image/bmp";
            case "avif": return "image/avif";
            case "ico": case "cur": return "image/x-icon";
            case "mp3": return "audio/mpeg";
            case "wav": return "audio/wav";
            case "ogg": return "audio/ogg";
            case "mp4": return "video/mp4";
            case "ttf": return "font/ttf";
            case "otf": return "font/otf";
            case "woff": return "font/woff";
            case "woff2": return "font/woff2";
            case "txt": return "text/plain";
            case "md": return "text/markdown";
            case "zip": return "application/zip";
            case "xml": return "text/xml";
            case "hex": return "application/octet-stream";
            default: return "application/octet-stream";
        }
    }

    /** SAF 文件句柄内容流（编辑器 getFile 的大文件通道） */
    public WebResourceResponse serveFileHandle(String handleId) {
        try {
            InputStream is;
            String mime = "application/octet-stream";
            if (handleId.startsWith("d")) {
                byte[] data = decryptCache.remove(handleId);
                if (data == null) return errorResponse(404, "expired");
                is = new ByteArrayInputStream(data);
            } else {
                FileHandle h = handle(handleId);
                if (h == null) return errorResponse(404, "no handle");
                is = app.getContentResolver().openInputStream(h.uri);
                String name = h.name.toLowerCase();
                if (name.endsWith(".json")) mime = "application/json";
                else if (name.endsWith(".js")) mime = "text/javascript";
            }
            WebResourceResponse resp = new WebResourceResponse(mime, null, is);
            Map<String, String> headers = new HashMap<>();
            headers.put("Access-Control-Allow-Origin", "*");
            resp.setResponseHeaders(headers);
            return resp;
        } catch (Exception e) {
            return errorResponse(404, String.valueOf(e));
        }
    }

    /** Java 网络代理（免 CORS，对应 Electron 主进程 fetch / tw-ai-proxy） */
    public WebResourceResponse proxyFetch(String urlStr) {
        try {
            if (urlStr == null || urlStr.isEmpty()) return errorResponse(400, "missing u");
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "NeoWarp/0.0.1 (Android)");
            int code = conn.getResponseCode();
            String mime = conn.getContentType();
            String charset = null;
            if (mime != null) {
                String[] parts = mime.split(";");
                mime = parts[0].trim();
                for (String p : parts) {
                    p = p.trim();
                    if (p.startsWith("charset=")) charset = p.substring(8);
                }
            }
            InputStream body = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (body == null) body = new ByteArrayInputStream(new byte[0]);
            WebResourceResponse resp = new WebResourceResponse(
                    mime == null ? "application/octet-stream" : mime, charset, body);
            Map<String, String> headers = new HashMap<>();
            headers.put("Access-Control-Allow-Origin", "*");
            headers.put("Access-Control-Allow-Headers", "*");
            resp.setResponseHeaders(headers);
            return resp;
        } catch (Exception e) {
            return errorResponse(502, "proxy: " + e);
        }
    }

    public static WebResourceResponse errorResponse(int code, String message) {
        try {
            byte[] body = message.getBytes(StandardCharsets.UTF_8);
            WebResourceResponse resp = new WebResourceResponse("text/plain", "utf-8",
                    new ByteArrayInputStream(body));
            resp.setStatusCodeAndReasonPhrase(code, code == 404 ? "Not Found" : "Error");
            return resp;
        } catch (Exception e) {
            return null;
        }
    }

    private String assetDataUrl(String assetPath) {
        try {
            InputStream is = app.getAssets().open(assetPath);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            is.close();
            String mime = mimeOf(assetPath);
            return "data:" + mime + ";base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }

    /* ============================================================
     * 小工具
     * ============================================================ */

    private static JSONObject parse(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            return new JSONObject(json);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 把 Java 值序列化为 JSON。
     * Boolean / Number 必须原样输出为 JSON 字面量（false / 0），
     * 否则 JSON.parse 得到的将是字符串 "false"，在 JS 中恒为 truthy，
     * 会导致 isInitiallyFullscreen / getTopBarDeviceStats 之类的开关全部失效。
     * 其余类型按字符串处理。
     */
    private static String jstr(Object v) {
        if (v == null) return "null";
        if (v instanceof Boolean) return v.toString();
        if (v instanceof Number) return String.valueOf(v);
        try {
            return JSONObject.quote(String.valueOf(v));
        } catch (Exception e) {
            return "null";
        }
    }

    private static String ok() {
        return "{}";
    }

    private static String err(Exception e) {
        try {
            return new JSONObject().put("__nwError", true).put("message", String.valueOf(e)).toString();
        } catch (Exception ignored) {
            return "{\"__nwError\":true,\"message\":\"error\"}";
        }
    }

    private static String jq(Object v) {
        try {
            return v == null ? "null" : JSONObject.quote(String.valueOf(v));
        } catch (Exception e) {
            return "null";
        }
    }

    private static String jqRaw(Object v) {
        if (v == null) return "null";
        if (v instanceof String) {
            String s = (String) v;
            s = s.trim();
            if (s.startsWith("{") || s.startsWith("[") || s.startsWith("\"")) return s;
            return JSONObject.quote(s);
        }
        return String.valueOf(v);
    }

    private void toast(Activity a, String msg) {
        if (a == null) return;
        a.runOnUiThread(() -> android.widget.Toast.makeText(a, msg, android.widget.Toast.LENGTH_SHORT).show());
    }

    private void promptSaveText(NWActivity caller, String winType, String cbId, String suggestedName, String content) {
        PendingSaf p = new PendingSaf();
        p.winType = winType;
        p.cbId = cbId;
        p.kind = "save-text";
        p.suggestedName = suggestedName;
        saveTextBytes.put("save-text:" + cbId, content.getBytes(StandardCharsets.UTF_8));
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/javascript");
        i.putExtra(Intent.EXTRA_TITLE, suggestedName);
        int req = nextSaf(p);
        caller.startIntentForResult(Intent.createChooser(i, "另存为"), req);
    }

    private final Map<String, byte[]> saveTextBytes = new ConcurrentHashMap<>();

    private void promptSaveSb3(NWActivity caller, String winType, String cbId, String suggestedName, Uri contentUri) {
        // SOLO 保存：当前无后台工程宿主，提示
        toast(caller, "SOLO 工程保存：请先在主页打开一个项目");
        reply(cbId, jstr(null));
    }

    /* handleActivityResult 里 save-text / save-image / save 补充处理 */
    void handleDeferredSave(NWActivity activity, PendingSaf p, Uri uri) throws Exception {
        String key = p.kind + ":" + p.cbId;
        byte[] bytes;
        if ("save-text".equals(p.kind)) {
            bytes = saveTextBytes.remove(key);
        } else {
            bytes = saveImageBytes.remove(key);
        }
        if (uri == null || bytes == null) {
            reply(p.cbId, jstr(null));
            return;
        }
        persist(uri);
        writeToUri(uri, bytes);
        JSONObject o = new JSONObject();
        o.put("path", uri.toString());
        o.put("name", nameOf(uri));
        o.put("uri", uri.toString());
        reply(p.cbId, o.toString());
    }
}
