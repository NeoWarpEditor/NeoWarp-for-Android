package org.neowarp.android;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

/**
 * 主 Activity（Capacitor 壳入口）。
 *
 * 启动即进入主页 home.html —— 与 Electron 版启动行为一致
 * （src-main/index.js：无命令行文件参数时 HomeWindow.show()）。
 *
 * MainActivity 仅作为 Capacitor 工程结构的 LAUNCHER 入口：
 * 记录可能的待打开文件（系统"打开方式"传入的 .sb3 等 content:// URI）
 * 后立即交由 WindowActivity 承载主页窗口。
 */
public class MainActivity extends BridgeActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        if (intent != null && Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            BridgeHub.get(this).setPendingInitialFile(intent.getData());
        }

        WindowActivity.show(this, "home", null);
        finish();
    }
}
