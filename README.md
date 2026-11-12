# NeoWarp Android

NeoWarp 桌面版（Electron / TurboWarp fork）的 Android 移植。APK 内置完整编辑器产物，
用 Capacitor 6 工程结构 + 自研 Java 桥替代 Electron 主进程，页面代码零改动。

- 应用名：**NeoWarp**（`org.neowarp.android`）
- 版本：**2.0.0**（versionCode 200）
- 启动行为：打开 APK 直接进入主页（home.html），与桌面版无参启动一致
- 图标：`art/icon.png` 生成全密度 mipmap + 自适应图标（foreground/background）
- 全面屏：`resizeableActivity` + `maxAspectRatio=3.0` + 刘海区 `shortEdges` 绘制

## 架构

Electron 的进程模型在 Android 上的对应实现：

| Electron | Android |
| --- | --- |
| 主进程（窗口/IPC/文件/协议） | `BridgeHub`（Java 单例中枢） |
| preload（contextBridge） | `bridge.js`（构建期注入到每个 html 的 `<head>`） |
| 每个窗口一个 BrowserWindow | 每个窗口一个 `WindowActivity`（独立 WebView） |
| `ipcRenderer.invoke/sendSync` | `@JavascriptInterface` `rpcAsync/rpcSync` + 回调 ID |
| 自定义协议 tw-editor:// 等 20+ | 统一 origin `https://localhost/**`（`shouldInterceptRequest` 伺服 assets） |
| 素材本地重定向（dist-library-files） | `assets.scratch.mit.edu` 请求按 md5ext 命中 `www/library/` 即本地返回 |
| 主进程 fetch / tw-ai-proxy:// | `/__fetch/?u=` Java 代理（免 CORS） |
| 系统"另存为"对话框 | SAF `ACTION_CREATE_DOCUMENT` |
| `<input type=file>` | `onShowFileChooser` → SAF `ACTION_GET_CONTENT` |
| .npnp / .viewsb3 加密 | Java AES-256-GCM 逐字节复刻（PBKDF2 格式与桌面版一致） |

### Java 侧（`android/app/src/main/java/org/neowarp/android/`）

- `BridgeHub` — 中枢：窗口注册表、60+ 个 RPC method 分发、SAF 文件句柄与写流、
  SharedPreferences 设置、主题广播、最近项目、CPU/内存统计、npnp/viewsb3 加密、
  Expands 扫描、编辑器"服务端请求"hub、资产伺服（.br brotli 透传）与网络代理
- `NWActivity` — 窗口基类：WebView 配置（JS+DOM storage+多窗口+自动播放）、
  SAF 结果路由、返回键、全面屏 cutout
- `WindowActivity` — 多窗口承载（编辑器/主页/任务管理器/AI 助手/打包器等 23 种窗口类型）
- `MainActivity` — LAUNCHER 入口，承接系统"打开方式"传入的 .sb3，随即转入主页窗口
- `NeoWarpBridge` — 注入为 `window.NeoWarpAndroid` 的 JS 桥薄壳
- `BridgeWebViewClient` — 协议拦截：本地资产、SAF 流（`/__file/`）、代理（`/__fetch/`）、
  素材本地重定向、tw-* scheme 映射、外链交系统浏览器
- `BridgeWebChromeClient` — 文件选择器、JS 对话框、麦克风/摄像头授权、window.open 多窗口

### JS 侧（`bridge.js`）

复刻全部 24 个 preload 契约：`EditorPreload`（保存/打开/加密/写流/AI/协作接口）、
`HomePreload`、`PromptsPreload` 等。关键协议：

- 保存写流：接管 GUI 的 `postMessage({ipcStartWriteStream})` + MessageChannel
  `{write}/{finish}/{abort}` 协议 → Java SAF `OutputStream`
- 大文件：`uploadBytes()` 分块上传（512KB/chunk，base64）→ Java 加密/落盘
- 窗口类型 `WIN_TYPE` 由 URL 判定（`/editor/**` → editor，`?nwwin=solo` → solo）
- 全面屏：`viewport-fit=cover` + `body` 安全区 padding（背景层仍铺满全屏）；
  图片编辑器窗口额外注入竖屏布局（工具栏转横排、属性面板转底部）
- i18n：`www/l10n/<locale>.json` 由 `src-main/l10n` 扁平化生成，Java 侧按应用语言
  （未设置时跟随系统）加载后随 `settingsInit` 下发

## Android 窗口形态

| 窗口 | 呈现方式 |
| --- | --- |
| 主页 home | 全屏铺满（含状态栏/导航栏区域），内容按安全区避让 |
| 编辑器 editor | 独立 Activity 全屏，菜单栏完整显示，旋转自适应重排 |
| AI 助手 / SOLO / 协作 / 任务管理器 / 项目分析 / 待办 | **浮动卡片**（94% × 82%，居中，背景变暗，触摸窗外穿透到底层编辑器） |
| 图片编辑器 | 全屏 + 竖屏适配样式 |

## 构建

```bash
cd android-app
npm ci                 # @capacitor/core|cli|android@6
python3 prepare-www.py # 桌面版产物 → www/（2478 文件，注入 bridge.js）
mkdir -p android/app/src/main/assets
cp -r www android/app/src/main/assets/www
cd android
./gradlew assembleDebug   # JDK 17 + Android SDK 34
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

## CI

`.github/workflows/android-build.yml`：推送到 `feature/android` 分支（或手动
workflow_dispatch）自动构建 debug APK 并上传 artifact（NeoWarp-2.0.0-debug-apk）。
若仓库已提交 `www/` 则直接使用，否则需要仓库同时包含桌面版产物
（`dist-renderer-webpack/`、`src-renderer/` 等）供 `prepare-www.py` 现场打包。

## 与桌面版的行为差异（优雅降级）

| 功能 | 桌面版 | Android 版 |
| --- | --- | --- |
| 另存为 / 打开 / 导出 sb3 | 系统对话框 | SAF 系统对话框 ✓ |
| .npnp 加密保存 / .viewsb3 只读工程 | ✓ | ✓（格式逐字节兼容） |
| 素材库离线命中 | ✓ | ✓（md5ext 本地重定向） |
| 本地扩展（Expands） | ✓ | ✓（assets 扫描 `// ID:` 头） |
| 麦克风 / 摄像头扩展 | ✓ | ✓（系统权限） |
| 多窗口（AI 助手/打包器/任务管理器…） | BrowserWindow | 独立 Activity ✓ |
| 局域网多人协作 | ✓ | 提示暂不支持（单机降级） |
| 手机预览（二维码投屏） | ✓ | 提示暂不支持 |
| 网页搜索（AI 助手内置） | ✓ | 提示暂不可用 |
| SOLO 工程后台宿主 | ✓ | 简化（提示先在主页打开项目） |
| 自动更新 | ✓ | 检查降级为不可用（用应用商店更新） |

其余功能（编辑器、扩展加载、打包器导出、任务管理器、桌面设置、最近项目、
主题跟随系统深色模式等）与桌面版一致。
