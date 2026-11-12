#!/usr/bin/env python3
"""
NeoWarp Android 资产打包脚本。

把 Electron 渲染层产物按 WebView URL 映射规则复制进 android-app/www/：
    /editor/**          <- dist-renderer-webpack/editor/**   (tw-editor://)
    /<page>/**          <- src-renderer/<page>/**             (tw-<page>://)
    /library/**         <- dist-library-files/**              (tw-library://)
    /extensions/**      <- dist-extensions/**                 (tw-extensions://)
    /expands/**         <- Expands/**                         (nw-expands://)
    /font/**            <- font/**                            (packager 页面字体)
    /bridge.js          <- 由 scripts/bridge.js 模板生成（EditorPreload 等 shim）

同时向 www 内所有 *.html 注入 <script src="/bridge.js"></script>，
保证任何窗口里 window.EditorPreload / HomePreload / ... 都先于页面脚本就位
（编辑器入口直接解引用全局 EditorPreload，注入必须确定性完成）。
不删改任何原始代码，只做增量注入。
"""
import json
import os
import re
import shutil
import sys

APP = os.path.dirname(os.path.abspath(__file__))                   # android-app
ROOT = os.path.dirname(APP)                                        # 项目根（NeoWarp）
WWW = os.path.join(APP, "www")

# (源目录(相对项目根), 目标目录(相对 www))
MAPPING = [
    ("dist-renderer-webpack/editor", "editor"),
    ("src-renderer/about", "about"),
    ("src-renderer/ai-assistant", "ai-assistant"),
    ("src-renderer/collaboration", "collaboration"),
    ("src-renderer/contact", "contact"),
    ("src-renderer/desktop-settings", "desktop-settings"),
    ("src-renderer/detached-stage", "detached-stage"),
    ("src-renderer/extension-editor", "extension-editor"),
    ("src-renderer/file-access", "file-access"),
    ("src-renderer/home", "home"),
    ("src-renderer/image-editor", "image-editor"),
    ("src-renderer/migrate", "migrate"),
    ("src-renderer/mobile-preview", "mobile-preview"),
    ("src-renderer/onboarding", "onboarding"),
    ("src-renderer/packager", "packager"),
    ("src-renderer/privacy", "privacy"),
    ("src-renderer/project-analysis", "project-analysis"),
    ("src-renderer/security-prompt", "security-prompt"),
    ("src-renderer/solo-stage", "solo-stage"),
    ("src-renderer/task-manager", "task-manager"),
    ("src-renderer/todo-list", "todo-list"),
    ("src-renderer/update", "update"),
    ("dist-library-files", "library"),
    ("dist-extensions", "extensions"),
    ("Expands", "expands"),
    ("font", "font"),
]

SCRIPT_TAG = '<script src="/bridge.js"></script>'


def copy_tree(src, dst):
    if not os.path.isdir(src):
        print(f"  ! 缺少目录，跳过: {src}")
        return 0
    n = 0
    for cur, _dirs, files in os.walk(src):
        rel = os.path.relpath(cur, src)
        out = os.path.join(dst, rel) if rel != "." else dst
        os.makedirs(out, exist_ok=True)
        for f in files:
            shutil.copy2(os.path.join(cur, f), os.path.join(out, f))
            n += 1
    return n


def inject_bridge(www):
    """向所有 html 注入 bridge.js（幂等）。"""
    count = 0
    for cur, _dirs, files in os.walk(www):
        for f in files:
            if not f.endswith(".html"):
                continue
            p = os.path.join(cur, f)
            with open(p, "r", encoding="utf-8", errors="surrogateescape") as fh:
                html = fh.read()
            if "/bridge.js" in html:
                continue
            if "<head" in html:
                # head 之后尽早注入，先于一切页面脚本
                html = re.sub(r"(<head[^>]*>)", r"\1" + SCRIPT_TAG, html, count=1)
            else:
                html = SCRIPT_TAG + html
            with open(p, "w", encoding="utf-8", errors="surrogateescape") as fh:
                fh.write(html)
            count += 1
    return count


def gen_l10n(www):
    """把 src-main/l10n 的翻译字典扁平化后生成到 www/l10n/<lang>.json。

    与桌面端 l10n/index.js 的 getStrings() 结构一致（key → 纯字符串）：
    英文 en.json 为底，generated-translations.json 的各语言覆盖。
    桌面设置窗口等页面依赖 strings['desktop-settings.*'] 才能显示文字。
    """
    src = os.path.join(ROOT, "src-main", "l10n")
    en_path = os.path.join(src, "en.json")
    tr_path = os.path.join(src, "generated-translations.json")
    if not (os.path.isfile(en_path) and os.path.isfile(tr_path)):
        print("  ! 缺少 src-main/l10n，跳过 l10n 生成")
        return 0
    with open(en_path, encoding="utf-8") as fh:
        en_raw = json.load(fh)
    base = {k: v.get("string", "") for k, v in en_raw.items()
            if isinstance(v, dict) and "string" in v}
    with open(tr_path, encoding="utf-8") as fh:
        others = json.load(fh)

    out_dir = os.path.join(www, "l10n")
    os.makedirs(out_dir, exist_ok=True)
    count = 0
    with open(os.path.join(out_dir, "en.json"), "w", encoding="utf-8") as fh:
        json.dump(base, fh, ensure_ascii=False)
    count += 1
    for lang, table in others.items():
        if not isinstance(table, dict):
            continue
        merged = dict(base)
        for k, v in table.items():
            if isinstance(v, str) and v:
                merged[k] = v
        name = str(lang)
        payload = json.dumps(merged, ensure_ascii=False)
        with open(os.path.join(out_dir, f"{name}.json"), "w", encoding="utf-8") as fh:
            fh.write(payload)
        count += 1
        # 中文别名：Java 侧 normalizeLocale() 把 zh-* 归一为 zh，需保证 zh.json 存在
        if name.lower().startswith("zh") and name.lower() != "zh":
            with open(os.path.join(out_dir, "zh.json"), "w", encoding="utf-8") as fh:
                fh.write(payload)
            count += 1
    print(f"  l10n                  {count:>5} locales")
    return count


def main():
    if os.path.isdir(WWW):
        shutil.rmtree(WWW)
    os.makedirs(WWW)

    total = 0
    for src, dst in MAPPING:
        n = copy_tree(os.path.join(ROOT, src), os.path.join(WWW, dst))
        print(f"  {dst:<20} {n:>5} files")
        total += n

    # bridge.js → /bridge.js：所有 html 注入的 shim 必须真实存在，
    # 否则 HomePreload/EditorPreload 未定义，页面初始化脚本直接崩溃
    # （主页文字/按钮失效的根因就是漏拷此文件）
    shutil.copy2(os.path.join(APP, "bridge.js"), os.path.join(WWW, "bridge.js"))
    total += 1
    print("  bridge.js copied")

    total += gen_l10n(WWW)

    # 壳入口：进入主页（与 Electron 启动行为一致：HomeWindow.show()）
    with open(os.path.join(WWW, "index.html"), "w", encoding="utf-8") as fh:
        fh.write(
            '<!DOCTYPE html><html><head><meta charset="utf-8">'
            f'{SCRIPT_TAG}'
            '<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">'
            '<style>html,body{margin:0;height:100%;background:#4c97ff}'
            '.splash{position:absolute;inset:0;display:flex;align-items:center;justify-content:center;'
            'color:#fff;font-family:sans-serif}</style>'
            f'<script>location.replace("/home/home.html");</script>'
            "</head><body><div class=splash>NeoWarp</div></body></html>"
        )

    n = inject_bridge(WWW)
    print(f"  bridge injected into {n} html files")
    print(f"  total {total} files -> {WWW}")


if __name__ == "__main__":
    sys.exit(main())
