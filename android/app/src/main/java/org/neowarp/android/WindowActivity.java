package org.neowarp.android;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;

/**
 * NeoWarp 多窗口 Activity。
 *
 * 对应 Electron 的 AbstractWindow 体系：编辑器里的「桌面设置 / 任务管理器 /
 * AI 助手 / 扩展编辑器 / 打包器…」每一个都是一个独立窗口。
 * Android 上以独立 Activity（独立 WebView）承载，编辑器窗口保持存活于返回栈，
 * 主题/项目数据等经 BridgeHub 在窗口间路由。
 */
public class WindowActivity extends NWActivity {

    private static final String EXTRA_TYPE = "nw.type";
    private static final String EXTRA_PAYLOAD = "nw.payload";

    /** 以浮动卡片形式叠在编辑器之上的窗口（对应桌面版的非模态小窗） */
    private static boolean isFloatingType(String type) {
        switch (type) {
            case "ai-assistant":
            case "solo":
            case "collaboration":
            case "task-manager":
            case "project-analysis":
            case "todo-list":
                return true;
            default:
                return false;
        }
    }

    private boolean floatSized = false;

    /** 打开（或前置已存在的）窗口，对应 Electron AbstractWindow.show() */
    public static void show(Context ctx, String type, @Nullable String payloadJson) {
        NWActivity existing = BridgeHub.get(ctx).findFirstByType(type);
        if (existing != null) {
            // 单例窗口已存在：前置即可（数据类 payload 通过 hub 事件另行同步）
            Intent i = new Intent(existing, WindowActivity.class);
            i.setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            existing.startActivity(i);
            return;
        }
        Intent i = new Intent(ctx, WindowActivity.class);
        i.putExtra(EXTRA_TYPE, type);
        if (payloadJson != null) i.putExtra(EXTRA_PAYLOAD, payloadJson);
        if (!(ctx instanceof android.app.Activity)) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        ctx.startActivity(i);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        String type = getIntent().getStringExtra(EXTRA_TYPE);
        if (type == null) type = "home";
        if (isFloatingType(type)) {
            // 浮动卡片主题必须在 super.onCreate 之前设置
            setTheme(R.style.AppTheme_Floating);
        }
        super.onCreate(savedInstanceState);
        windowType = type;

        createWebView();

        String payload = getIntent().getStringExtra(EXTRA_PAYLOAD);
        BridgeHub.get(this).onWindowCreated(this, payload);

        // Android 13+ 全面屏手势返回（编辑器未保存时弹确认框）
        registerBackGestureCallback();

        loadMain();
    }

    /** 浮动窗口尺寸：居中卡片（需在窗口获得焦点后设置才生效） */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && !floatSized && isFloatingType(windowType)) {
            floatSized = true;
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            android.view.Window w = getWindow();
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            // 触摸窗口外穿透到底层编辑器，保持其可继续操作
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
            android.view.WindowManager.LayoutParams lp = w.getAttributes();
            lp.width = (int) (dm.widthPixels * 0.94f);
            lp.height = (int) (dm.heightPixels * 0.82f);
            lp.gravity = android.view.Gravity.CENTER;
            w.setAttributes(lp);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // 前置既有窗口；payload 类参数（如插件文档路径）此场景忽略
    }

    /** 主页（页面型布局）内容延伸进状态栏；编辑器等 app 布局保持状态栏下方 */
    @Override
    protected boolean extendIntoStatusBar() {
        return "home".equals(windowType);
    }
}
