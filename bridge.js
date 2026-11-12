/**
 * NeoWarp Android 桥接层（bridge.js）
 *
 * 在 Android WebView 中复刻 Electron preload（contextBridge）契约：
 * 页面代码零改动，window.EditorPreload / HomePreload / ... 由本文件提供，
 * 底层经 addJavascriptInterface 注入的 window.NeoWarpAndroid 与 Java 侧
 * BridgeHub 通信（同步 rpc / 异步 rpc / Java→JS 事件 / 大文件分块传输）。
 *
 * URL 映射（Java WebViewClient.shouldInterceptRequest 同样遵守）：
 *   /editor/**      <- tw-editor://
 *   /home/**        <- tw-home://
 *   /library/**     <- tw-library://（素材 md5 本地缓存）
 *   /extensions/**  <- tw-extensions://
 *   /expands/**     <- nw-expands://
 *   /__file/<id>    <- SAF 文件内容流（读）
 *   /__fetch/?u=    <- Java 代理网络请求（免 CORS，等价 Electron 主进程 fetch）
 */
(function () {
  'use strict';

  if (window.__NeoWarpBridgeLoaded) return;
  window.__NeoWarpBridgeLoaded = true;

  /* ============================================================
   * 0. 窗口类型判定
   * ============================================================ */
  var WIN_TYPE = (function () {
    // solo 助手窗口：/ai-assistant/ai-assistant.html?nwwin=solo（Electron 独立窗口入口）
    if (location.search.indexOf('nwwin=solo') >= 0) return 'solo';
    var p = location.pathname;
    if (p.indexOf('/editor/') === 0) return 'editor';
    var m = p.match(/^\/([a-z-]+)\//);
    if (m) return m[1];
    return 'editor';
  })();

  window.__NW_WINDOW_TYPE = WIN_TYPE;

  /* 统一视口：显式声明 device-width。缺失时部分内核会用更宽的默认布局视口，
     导致 fixed 定位的元素（查找框/扩展/添加按钮等）相对错误的原点定位。 */
  (function () {
    try {
      if (!document.querySelector('meta[name="viewport"]')) {
        var mv = document.createElement('meta');
        mv.name = 'viewport';
        mv.content = 'width=device-width, initial-scale=1, viewport-fit=cover';
        (document.head || document.documentElement).appendChild(mv);
      }
    } catch (e) {}
  })();

  /* ============================================================
   * 1. 传输底层
   * ============================================================ */
  var java = window.NeoWarpAndroid || null;

  var callbacks = Object.create(null);
  var cbSeq = 1;
  var listeners = Object.create(null); // module -> event -> [fn]
  var editorRequests = Object.create(null); // editor 服务端请求回调

  function nextCbId() { return 'cb' + (cbSeq++); }

  function cb(id, json) {
    var entry = callbacks[id];
    if (!entry) return;
    delete callbacks[id];
    var value = null;
    try { value = json === null || json === undefined ? null : JSON.parse(json); }
    catch (e) { value = null; }
    if (entry.rejectWhenError && value && typeof value === 'object' && value.__nwError) {
      entry.reject(new Error(value.message || 'bridge error'));
    } else {
      entry.resolve(value);
    }
  }

  function evt(module, event, json) {
    var data = null;
    try { data = json === null || json === undefined ? null : JSON.parse(json); }
    catch (e) { data = null; }
    var list = listeners[module] && listeners[module][event];
    if (list) for (var i = 0; i < list.length; i++) {
      try { list[i](data); } catch (e) { console.error(e); }
    }
  }

  /** 异步调用 Java（Promise） */
  function inv(method, args) {
    if (!java) return Promise.resolve(null);
    var id = nextCbId();
    return new Promise(function (resolve, reject) {
      callbacks[id] = { resolve: resolve, reject: reject, rejectWhenError: true };
      try {
        java.rpcAsync(WIN_TYPE, method, args === undefined ? null : JSON.stringify(args), id);
      } catch (e) {
        delete callbacks[id];
        reject(e);
      }
    });
  }

  /** 同步调用 Java（仅用于轻量查询，与 Electron sendSync 语义对应） */
  function sync(method, args) {
    if (!java) return null;
    try {
      var raw = java.rpcSync(WIN_TYPE, method, args === undefined ? null : JSON.stringify(args));
      return raw === null || raw === undefined ? null : JSON.parse(raw);
    } catch (e) {
      return null;
    }
  }

  function on(module, event, fn) {
    if (!listeners[module]) listeners[module] = Object.create(null);
    if (!listeners[module][event]) listeners[module][event] = [];
    listeners[module][event].push(fn);
  }

  function off(module, event) {
    if (listeners[module]) listeners[module][event] = [];
  }

  window.__nw = {
    cb: cb,
    evt: evt,
    /** 关闭当前窗口（Android WebView 中 window.close() 无效，统一走宿主） */
    closeSelf: function () {
      inv('closeWindow');
      try { window.close(); } catch (e) {}
    },
    /** 上报未保存状态（宿主据此在返回/手势退出时弹确认框） */
    setDirty: function (dirty) {
      inv('setChanged', { changed: !!dirty });
    },
    /** Java 发起的编辑器服务端请求（AI 工具/项目 JSON/主题/角色统计等） */
    editorRequest: function (type, json, requestId) {
      var fn = editorRequests[type];
      var payload = null;
      try { payload = json ? JSON.parse(json) : null; } catch (e) { payload = null; }
      if (!fn) {
        // 无监听：按 Electron 语义回复空/失败
        if (type === 'theme') window.__nw.hubRespond(requestId, { theme: 'light' });
        else if (type === 'sprite-stats' || type === 'sprite-library') {
          window.__nw.hubRespond(requestId, { requestId: requestId, sprites: [], totalThreads: 0, data: null });
        }
        return;
      }
      Promise.resolve().then(function () { return fn(payload, requestId); })
        .then(function (result) { window.__nw.hubRespond(requestId, result); })
        .catch(function (e) { window.__nw.hubRespond(requestId, { error: String(e && e.message || e) }); });
    },
    /** 编辑器服务端响应回传 Java hub */
    hubRespond: function (requestId, data) {
      if (!java) return;
      try { java.hubRespond(WIN_TYPE, requestId, data === undefined ? null : JSON.stringify(data)); } catch (e) {}
    },
    /** packager 导入项目：Java 请求编辑器导出 */
    editorExport: function (requestId) {
      var fn = editorRequests['export-project'];
      if (!fn) { try { java.hubRespond(WIN_TYPE, requestId, JSON.stringify({ error: true })); } catch (e) {} return; }
      Promise.resolve().then(fn).then(function (r) {
        // r: {data: ArrayBuffer|Blob|Uint8Array, name}
        var p = r && r.data;
        var toBytes = (typeof Blob !== 'undefined' && p instanceof Blob) ? p.arrayBuffer()
          : Promise.resolve(p instanceof ArrayBuffer ? p : new Uint8Array(p).buffer);
        return toBytes.then(function (buf) {
          var bytes = new Uint8Array(buf);
          var CHUNK = 512 * 1024;
          var total = Math.max(1, Math.ceil(bytes.length / CHUNK));
          try { java.exportBegin(WIN_TYPE, requestId, String(r.name || 'project.sb3'), total); } catch (e) {}
          for (var i = 0; i < total; i++) {
            var slice = bytes.subarray(i * CHUNK, Math.min(bytes.length, (i + 1) * CHUNK));
            var bin = '';
            for (var j = 0; j < slice.length; j += 8192) {
              bin += String.fromCharCode.apply(null, slice.subarray(j, Math.min(slice.length, j + 8192)));
            }
            java.exportChunk(WIN_TYPE, requestId, btoa(bin));
          }
          java.exportEnd(WIN_TYPE, requestId);
        });
      }).catch(function (e) {
        try { java.hubRespond(WIN_TYPE, requestId, JSON.stringify({ error: true })); } catch (e2) {}
      });
    }
  };

  /* ============================================================
   * 2. 通用工具
   * ============================================================ */

  /** 分块上传大字节数据到 Java（返回 Promise） */
  function uploadBytes(method, meta, bytes) {
    var u8 = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
    var CHUNK = 512 * 1024;
    var total = Math.max(1, Math.ceil(u8.length / CHUNK));
    return inv(method + 'Begin', Object.assign({}, meta, { total: total, size: u8.length }))
      .then(function () {
        var p = Promise.resolve();
        for (var i = 0; i < total; i++) {
          (function (i) {
            p = p.then(function () {
              var slice = u8.subarray(i * CHUNK, Math.min(u8.length, (i + 1) * CHUNK));
              var bin = '';
              for (var j = 0; j < slice.length; j += 8192) {
                bin += String.fromCharCode.apply(null, slice.subarray(j, Math.min(slice.length, j + 8192)));
              }
              return inv(method + 'Chunk', { b64: btoa(bin) });
            });
          })(i);
        }
        return p;
      })
      .then(function () { return inv(method + 'End', null); });
  }

  /** 通过 /__file/<id> 从 Java 流式读取 SAF 文件内容 */
  function readFileBytes(id) {
    return fetch('/__file/' + id).then(function (r) {
      if (!r.ok) throw new Error('read file failed: ' + r.status);
      return r.arrayBuffer();
    });
  }

  /* 保存写流协议：GUI 的 createWritable() 会 postMessage({ipcStartWriteStream:id}, origin, [port])
     由这里接管并转发到 Java（对应 Electron 主进程 'start-write-stream'）。 */
  window.addEventListener('message', function (e) {
    if (e.source !== window || !e.data || typeof e.data.ipcStartWriteStream !== 'string') return;
    var handleId = e.data.ipcStartWriteStream;
    var port = e.ports && e.ports[0];
    if (!port) return;
    port.onmessage = function (me) {
      var data = me.data;
      if (data.write) {
        var u8 = data.write instanceof Uint8Array ? data.write : new Uint8Array(data.write);
        var bin = '';
        for (var j = 0; j < u8.length; j += 8192) {
          bin += String.fromCharCode.apply(null, u8.subarray(j, Math.min(u8.length, j + 8192)));
        }
        java.writeChunk(handleId, btoa(bin));
        port.postMessage({ response: { id: data.id, result: null } });
      } else if (data.finish) {
        java.writeFinish(handleId);
        port.postMessage({ response: { id: data.id, result: null } });
      } else if (data.abort) {
        java.writeAbort(handleId);
        port.postMessage({ response: { id: data.id, result: null } });
      } else {
        port.postMessage({ error: { message: 'Unknown write stream message' } });
      }
    };
  }, { capture: true });

  /* ============================================================
   * 3. tw-ai-proxy:// 与 nw-expands:// 的 fetch 重写
   *    （WebView 不支持自定义 scheme 的 fetch，映射到 Java 代理）
   * ============================================================ */
  var _origFetch = window.fetch.bind(window);
  window.fetch = function (input, init) {
    try {
      var url = typeof input === 'string' ? input : (input && input.url) || '';
      if (url.indexOf('tw-ai-proxy://') === 0) {
        var m = url.match(/[?&]u=([^&]+)/);
        var u = m ? m[1] : '';
        return _origFetch('/__fetch/?u=' + u, init);
      }
      if (url.indexOf('nw-expands://') === 0) {
        var rest = url.replace(/^nw-expands:\/\/[^/]+\//, '');
        return _origFetch('/expands/' + rest, init);
      }
    } catch (e) { /* fallthrough */ }
    return _origFetch(input, init);
  };

  /* ============================================================
   * 3.1 全面屏安全区适配（刘海/挖孔状态栏）
   *     Java 侧仅页面型窗口（home 等）开启 LAYOUT_FULLSCREEN，
   *     其余窗口 env(safe-area-inset-*) 恒为 0，以下规则天然无副作用。
   * ============================================================ */
  (function () {
    if (WIN_TYPE === 'editor' || WIN_TYPE === 'detached-stage' || WIN_TYPE === 'solo-stage') return;
    function apply() {
      // viewport-fit=cover：让 env(safe-area-inset-*) 在 WebView 中生效
      var mv = document.querySelector('meta[name="viewport"]');
      if (!mv) {
        mv = document.createElement('meta');
        mv.setAttribute('name', 'viewport');
        (document.head || document.documentElement).appendChild(mv);
      }
      var c = mv.getAttribute('content') || 'width=device-width, initial-scale=1';
      if (c.indexOf('viewport-fit') < 0) mv.setAttribute('content', c + ', viewport-fit=cover');
      // 内容避开状态栏/手势条（背景层 fixed inset:0 仍铺满全屏）
      var css = ':root{--nw-sat:env(safe-area-inset-top,0px);--nw-sab:env(safe-area-inset-bottom,0px)}'
        + 'body{padding-top:var(--nw-sat) !important;padding-bottom:var(--nw-sab) !important}';
      if (WIN_TYPE === 'home') {
        // 右下角悬浮按钮与提示气泡避让手势条
        css += '#github-button,#settings-button{bottom:calc(26px + var(--nw-sab)) !important}'
          + '#toast{bottom:calc(48px + var(--nw-sab)) !important}';
      }
      if (WIN_TYPE === 'image-editor') {
        // 竖屏适配：工具栏转横排、属性面板转底部面板、顶栏换行
        css += '@media (max-width:620px){'
          + '.topbar{flex-wrap:wrap;row-gap:4px;padding:6px 8px !important}'
          + '.main{flex-direction:column !important}'
          + '.toolbar{width:auto !important;height:52px;flex-direction:row !important;'
          + 'border-right:none !important;border-bottom:1px solid var(--border);'
          + 'overflow-x:auto;overflow-y:hidden;padding:6px !important;gap:6px !important}'
          + '.tool-sep{width:1px !important;height:28px;flex-shrink:0}'
          + '.side-panel{width:auto !important;max-height:46%;flex:0 0 auto;'
          + 'border-left:none !important;border-top:1px solid var(--border)}'
          + '.canvas-wrap{min-height:0 !important}'
          + '}';
      }
      if (WIN_TYPE === 'desktop-settings') {
        // 竖屏适配：主页 logo 行的控件区含图片预览 + HTML预览 + 两个按钮，
        // 加上 flex-shrink:0 会在窄屏把左侧标题挤成竖排单字，故整行改为纵向堆叠
        css += '@media (max-width:620px){'
          + '.setting-row{flex-wrap:wrap;align-items:flex-start;row-gap:8px}'
          + '.setting-row .setting-info{flex:1 1 100%;min-width:0}'
          + '.setting-row .setting-control{flex:1 1 100%;width:100%;flex-wrap:wrap;gap:8px}'
          + '.setting-row .setting-control>*{flex:0 0 auto}'
          + '.home-text-input{width:100% !important;flex:1 1 auto !important}'
          + '.home-logo-preview,.home-logo-html-preview{margin-right:0}'
          + '.setting-row + .setting-row::before{left:16px}'
          + '.setting-label{word-break:keep-all;overflow-wrap:normal}'
          + '}';
      }
      var st = document.createElement('style');
      st.textContent = css;
      (document.head || document.documentElement).appendChild(st);
    }
    if (document.readyState === 'loading') {
      document.addEventListener('DOMContentLoaded', apply);
    } else {
      apply();
    }
  })();

  /* ============================================================
   * 3.2 编辑器专项修正
   *   (1) 隐藏菜单栏 CPU/内存胶囊（该节点无条件渲染，只能用 CSS 隐藏）
   *   (2) 兜底移除右上角系统统计浮层
   *   (3) 旋转后强制重排，避免页面按旧尺寸放大
   * ============================================================ */
  if (WIN_TYPE === 'editor') {
    (function () {
      // 菜单栏胶囊 class 由 CSS Modules 生成，形如 menu-bar_system-stats_xxxxx
      var css = '[class*="menu-bar_system-stats_"]{display:none !important}'
        + '[class*="stat-label_"],[class*="stat-value_"],[class*="stat-divider_"]{display:none !important}';
      var st = document.createElement('style');
      st.textContent = css;
      (document.head || document.documentElement).appendChild(st);

      // 右上角浮层由 EditorPreload.getTopBarDeviceStats() 直接创建在 body 上，
      // 无类名可依赖，用观察器兜底移除（正常情况下 Java 侧已返回 false 不会创建）
      function purgeTopBarStats() {
        var all = document.body ? document.body.children : [];
        for (var i = all.length - 1; i >= 0; i--) {
          var el = all[i];
          if (el.tagName !== 'DIV') continue;
          if (el.style && el.style.zIndex === '99999' && /CPU:|RAM:/.test(el.textContent || '')) {
            el.parentNode && el.parentNode.removeChild(el);
          }
        }
      }
      if (window.MutationObserver) {
        new MutationObserver(purgeTopBarStats).observe(document.documentElement, { childList: true, subtree: true });
      }
      setInterval(purgeTopBarStats, 1000);
    })();

    /* ---- 舞台尺寸：确保不处于 full（全屏舞台）模式 ---- */
    (function () {
      var forced = false;
      function ensureNotFullStage() {
        if (forced) return;
        var store = window.ReduxStore;
        if (!store || typeof store.dispatch !== 'function' || !store.getState) return;
        try {
          var st = store.getState();
          var gui = st && st.scratchGui;
          if (!gui) return;
          var mode = gui.mode || {};
          var size = gui.stageSize || {};
          if (mode.isFullScreen || size.stageSize === 'full') {
            store.dispatch({ type: 'scratch-gui/mode/SET_FULL_SCREEN', isFullScreen: false });
            store.dispatch({ type: 'scratch-gui/StageSize/SET_STAGE_SIZE', stageSize: 'large' });
          }
          forced = true;
        } catch (e) { /* store 尚未就绪，下轮重试 */ }
      }
      var tries = 0;
      var timer = setInterval(function () {
        ensureNotFullStage();
        if (forced || ++tries > 40) clearInterval(timer);
      }, 250);
    })();

    /* ---- 退出确认点"保存"：触发 GUI 保存，成功后关闭窗口 ---- */
    // scratch-gui 的保存入口被 webpack 混淆，无稳定 action 可派发；
    // 菜单栏的 "Save Now" 链接仅在 projectChanged 为 true 时渲染，点击它即等价于保存。
    (function () {
      var closing = false;
      window.__nwOnExitSave = function () {
        if (closing) return;
        closing = true;
        var saveLink = document.querySelector('[class*="save-status_save-now_"]');
        if (!saveLink) {
          // 没有可点的保存入口（如刚打开空项目），直接退出避免卡住
          window.__nw.closeSelf();
          return;
        }
        var closed = false;
        var closeIfSaved = function () {
          if (closed) return;
          closed = true;
          window.__nw.closeSelf();
        };
        // 保存成功后 projectChanged 归零；兜底 8s 后无论如何不卡在后台
        var poll = setInterval(function () {
          try {
            var st = window.ReduxStore && window.ReduxStore.getState && window.ReduxStore.getState();
            if (st && st.scratchGui && st.scratchGui.projectChanged === false) {
              clearInterval(poll);
              closeIfSaved();
            }
          } catch (e) { /* ignore */ }
        }, 300);
        setTimeout(function () { clearInterval(poll); closeIfSaved(); }, 8000);
        saveLink.click();
      };
    })();

    /* ============================================================
     * 3.2.1 竖屏 Scratch 编辑器重排（仅竖屏生效；横屏移除 class 即完全恢复桌面原样式）
     *
     *   代码模式（自上而下）：Tab 条(代码/造型/声音 + 舞台) → 积木区(上) +
     *   代码区(下) → 角色/背景选择横条 → 底部仅图标工具栏。
     *   造型/声音模式：素材缩略图列表移到编辑器上方横条，工具在上、画布/波形在下。
     *
     *   积木区与代码区同属一个 Blockly injectionDiv（绝对定位 SVG），不能靠 CSS
     *   硬拆，否则拖积木落点会错位。这里不拆 DOM、不重写布局引擎，只在竖屏下：
     *     - CSS 把分类栏 / 积木抽屉限制在上部 33%
     *     - 覆盖主 workspace.getMetrics().absoluteTop = 积木区高度，让代码画布
     *       内容整体下移；Blockly 的 getOriginOffsetInPixels() 读的正是同一份
     *       translate（getInjectionDivXY_ 累加 DOM transform），因此从抽屉拖出
     *       积木的落点换算会自动抵消该偏移，与鼠标位置天然对齐。
     *     - 水平方向完全沿用桌面逻辑（分类列在左、积木抽屉在右），零改动。
     * ============================================================ */
    (function () {
      var CLS = 'nw-portrait';
      var P = 'html.' + CLS + ' ';
      var P0 = 'html.' + CLS;
      /* 编辑器排列方式（仅竖屏生效，由桌面设置 editorLayout 驱动）：
         nw-layout-horizontal = 代码列表在左、画布在右（Scratch 原生竖排），
           按钮沿用原生位置：扩展按钮在左下角、放大/缩小/等号在右下角；
         nw-layout-vertical   = 代码列表压到上方 2/5、画布移到下方 3/5 横跨
           全宽，按钮沿用竖屏改造位置：扩展按钮在顶部、缩放控件在左下角。
         所有「把扩展按钮/缩放控件搬离原生位置」的规则都必须用 PV 限定，
         horizontal 下不生效即为原生位置。 */
      var PV = 'html.' + CLS + '.nw-layout-vertical ';

      function portrait() { return window.innerHeight > window.innerWidth; }
      function active() { return document.documentElement.classList.contains(CLS); }

      /* ---------------- 竖屏 CSS ---------------- */
      var css = [
        P0 + '{--nw-tab-h:40px;--nw-target-h:132px;--nw-menu-h:46px;--nw-zoom-y:400px;'
          + '--nw-top-right:172px}',
        /* (1) 去掉桌面端 1024×640 最小尺寸——这是竖屏页面被整体缩放的根因 */
        P0 + ', ' + P0 + ' body{width:100% !important;height:100% !important;'
          + 'min-width:0 !important;min-height:0 !important;overflow:hidden !important}',
        P + '#app, ' + P + '[class*="gui_page-wrapper_"]{min-width:0 !important;min-height:0 !important;'
          + 'width:100% !important;height:100% !important}',

        /* (2) 顶部工具栏移到底部、仅显示图标、横向可滑动 */
        P + '[class*="gui_body-wrapper_"]{order:1;flex:1 1 auto !important;min-height:0 !important;height:auto !important}',
        P + '[class*="gui_menu-bar-position_"]{order:2;position:relative !important;'
          + 'flex:0 0 auto !important;z-index:40}',
        P + '[class*="menu-bar_menu-bar_"]{height:var(--nw-menu-h) !important;'
          + 'min-height:var(--nw-menu-h) !important;box-sizing:border-box;font-size:0 !important;'
          + 'overflow-x:hidden !important;overflow-y:hidden !important;flex-wrap:nowrap !important;'
          + 'border-radius:0 !important;margin:0 !important;width:100% !important;'
          + 'justify-content:flex-start !important;padding:0 46px 0 6px !important}',
        /* #11 工具栏平均分配：主菜单 flex:1 铺满整行 + space-between，
           9 个按钮（7 图标 + 2 添加）均匀分布；左侧 6px / 右侧 46px（右端
           留出问号按钮区且不与其贴脸）。内容超宽时 nw-add-fixed 兜底不变 */
        P + '[class*="menu-bar_main-menu_"] > *{padding:0 !important;min-width:0 !important;margin:0 !important}',
        /* 孙级 item（file-group 里的按钮）压掉原生 8px×2 padding */
        P + '[class*="menu-bar_main-menu_"] [class*="menu-bar_menu-bar-item_"]{padding:2px !important;min-width:0 !important;margin:0 !important}',
        /* 图标 16→22px：真机上 16px 太小，触控目标不足 */
        P + '[class*="menu-bar_main-menu_"] img, '
          + P + '[class*="menu-bar_main-menu_"] svg{width:22px !important;height:22px !important;margin:0 !important}',
        /* NeoWarp 定制的 CPU/RAM 监视器在 file-group 里占 ~120px 文字宽，
           手机竖屏无意义——隐藏（隐藏后剩 ~2px 宽的空壳，由 JS tidyMenuBar 清理） */
        P + '[class*="menu-bar_system-stats_"]{display:none !important}',
        /* file-group 不再自身成组：display:contents 把组内 7 个按钮上提进
           主菜单的 flex 序列参与均分——否则 space-between 只会在「组与添加
           按钮之间」拉开，组内 7 个按钮仍然 gap 2px 挤成一坨 */
        P + '[class*="menu-bar_file-group_"]{display:contents !important}',
        /* 下拉箭头：原生是 8×5 的裸 img（无任何类名，CSS 按属性/类选不中），
           会被上面的 22px 规则放大成 22×22 占位——由 JS tidyMenuBar 按
           naturalWidth=8 && naturalHeight=5 特征隐藏。本条兜底带类名变体 */
        P + '[class*="menu-bar_dropdown-caret-icon_"]{display:none !important;margin:0 !important}',
        P + '[class*="menu-bar_main-menu_"]{margin:0 !important;flex:1 1 auto !important;'
          + 'justify-content:space-between !important}',
        /* menu-bar 末尾还有个直属菜单项（问号），它参与 flex 分配会把
           均分行整体推偏；改为脱离流贴右 */
        P + '[class*="menu-bar_menu-bar_"] > [class*="menu-bar_menu-bar-item_"]{'
          + 'position:absolute !important;right:2px !important;top:1px !important;'
          + 'z-index:2 !important;max-height:44px !important}',
        /* 按钮触控目标 26×34：图标 22px + padding 2px×2 */
        P + '[class*="menu-bar_menu-bar-item_"], ' + P + '[class*="menu-bar_menu-bar-button_"]{'
          + 'min-width:26px;min-height:34px;max-height:40px !important;margin:0 !important;padding:2px !important}',
        /* 下拉菜单：外层 wrapper 是 static 改了无效，必须钉住内层 ul.menu_。
           工具栏在屏幕底部，菜单原生向下弹出会整体落到屏幕外（表现为点不开），
           这里统一固定到工具栏上方。 */
        P + '[class*="menu-bar_menu-bar-menu_"] > [class*="menu_menu_"]{'
          + 'position:fixed !important;left:8px !important;right:auto !important;'
          + 'top:auto !important;bottom:calc(var(--nw-menu-h) + 4px) !important;'
          + 'max-height:52vh !important;overflow-y:auto !important;z-index:130 !important;margin:0 !important;'
          /* 原生只有下方 8px 圆角（菜单挂在工具栏下沿）；改到工具栏上方后需四角圆角 */
          + 'border-radius:12px !important}',
        P + '[class*="menu-bar_menu-bar-menu_"], ' + P + '[class*="menu-bar_menu-bar-menu_"] *{font-size:13px !important}',
        P + '[class*="menu-bar_collapsible-label_"],'
          + P + '[class*="menu-bar_tutorials-label_"],'
          + P + '[class*="menu-bar_divider_"],'
          + P + '[class*="menu-bar_account-info-group_"],'
          + P + '[class*="menu-bar_feedback-button_"],'
          + P + '[class*="menu-bar_mystuff_"],'
          + P + '[class*="menu-bar_remix-button_"],'
          + P + '[class*="menu-bar_author-info_"],'
          + P + '[class*="menu-bar_coming-soon_"],'
          + P + '[class*="menu-bar_title-field-growable__"]{display:none !important}',

        /* (3) 桌面横向布局改为竖向堆叠 */
        P + '[class*="gui_flex-wrapper_"]{flex-direction:column !important;height:100% !important;min-height:0 !important}',
        P + '[class*="gui_editor-wrapper_"]{flex:1 1 auto !important;flex-basis:auto !important;'
          + 'min-height:0 !important;width:100% !important}',
        P + '[class*="gui_tabs"]{flex:1 1 auto;min-height:0;display:flex;flex-direction:column}',

        /* (4) 顶部 Tab 条：三个 Tab 均分左侧区域；右侧固定放查找/扩展/舞台，
           四段互不重叠（旧版查找框 x137-265 直接压在「造型/声音」两个 Tab 上，
           就是用户说的「太挤」的根因）。舞台按钮也改 fixed，不再参与 tab 流挤压。 */
        P + '[class*="gui_tab-list_"]{width:100% !important;height:var(--nw-tab-h) !important;'
          + 'flex:0 0 var(--nw-tab-h) !important;display:flex !important;align-items:center !important;'
          + 'position:relative !important;z-index:50 !important;'
          + 'margin:0 !important;padding:0 4px !important;padding-right:var(--nw-top-right) !important;'
          + 'gap:2px !important;border:0 !important;box-sizing:border-box;overflow:hidden !important}',
        P + '.react-tabs__tab, ' + P + '[class*="gui_tab_"]{flex:1 1 0 !important;min-width:0 !important;'
          + 'height:var(--nw-tab-h) !important;line-height:var(--nw-tab-h) !important;'
          + 'padding:0 2px !important;font-size:12px !important;text-align:center !important;'
          + 'white-space:nowrap !important;overflow:hidden !important}',
        /* 舞台：纯文字、无黄色底；fixed 钉在最右，避免和 Tab / 查找互相挤压 */
        P + '.nw-stage-btn{position:fixed !important;top:4px !important;'
          + 'left:calc(var(--nw-vw,360px) - 40px) !important;right:auto !important;bottom:auto !important;'
          + 'z-index:60 !important;width:36px !important;height:32px !important;margin:0 !important;'
          + 'padding:0 !important;border:0 !important;border-radius:0 !important;'
          + 'background:transparent !important;color:#ff8c1a !important;font-size:13px !important;'
          + 'font-weight:600 !important;line-height:32px !important;text-align:center !important;cursor:pointer}',
        /* 选中面板沿用 GUI 自带 flex-grow:1 + display:flex，只需补 min-height:0 让 flex 链可收缩 */
        P + '[class*="gui_tab-panel_"], ' + P + '[class*="react-tabs__tab-panel--selected"]{'
          + 'min-height:0 !important;flex:1 1 auto !important}',

        /* (5) Blocks 容器填满编辑区；分类栏只占上部积木区 */
        P + '[class*="gui_blocks-wrapper_"]{flex:1 1 auto !important;min-height:0 !important;position:relative !important}',
        P + '[class*="gui_blocks-wrapper_"] > *{height:100% !important;min-height:0 !important}',
        P + '.injectionDiv{height:100% !important;position:relative !important}',
        /* 竖版：分类列在左（原生位置），紧凑排版；纵向可滚（分类多时），
           touch-action:pan-y 交给 Blockly 自己处理触摸（浏览器不抢手势） */
        P + '.blocklyToolboxDiv{overflow-x:hidden !important;overflow-y:auto !important;'
          + 'touch-action:pan-y !important;z-index:40 !important;box-sizing:border-box !important}',
        P + '.scratchCategoryMenuItem{padding:3px 1px !important;font-size:11px !important}',
        P + '.scratchCategoryMenuItem .scratchCategoryItemIcon, '
          + P + '.scratchCategoryMenuItem .scratchCategoryItemBubble{width:14px !important;height:14px !important}',

        /* (6) 舞台平时收起；角色/背景选择变成可左右滑动的横条 */
        P + '[class*="gui_stage-and-target-wrapper_"]{flex:0 0 auto !important;'
          + 'height:var(--nw-target-h) !important;flex-direction:column !important;'
          + 'padding:3px 6px var(--nw-sab,0px) !important;min-height:0 !important;box-sizing:border-box}',
        P + '[class*="gui_stage-and-target-wrapper_"] > [class*="stage-wrapper_"]:not([class*="stage-wrapper_full-screen"]){'
          + 'height:0 !important;min-height:0 !important;flex:0 0 0 !important;overflow:hidden !important}',
        P + '[class*="gui_target-wrapper_"]{flex:1 1 auto !important;min-height:0 !important;padding:0 !important}',
        /* 角色区重排（用户要求）：
           ① 最左侧「背景」做成与角色卡片同款 72×72 正方形（上方黑底舞台画面 +
              下方红底「背景」名字条），放到角色列表同一行的最左边；
           ② 背景方块固定不滑动，与滚动列表同处 "list" 格且 z-index 更高，
              左右滑动时角色卡片从背景「下面」穿过（实测 stageOnTop:true）；
           ③ 角色属性（角色名/x/y/显示隐藏/大小/方向）由两行合并为一行、横向铺满；
           ④ 「属性行不要那么拥挤」：横向只有 348px，不能靠加大间距变美观——
              先压小 label/数值框腾出宽度，再用 space-between 把剩余宽度均分成
              组间距（实测 gaps 3~4px、overflow 0），5 个输入框统一 9px 圆角矩形、
              标签淡色降权、显示隐藏组做成圆角分段控件；
           ⑤ 卡片行上下留白由 24px 收到 12px，整块 --nw-target-h 156→132，
              省下的 24px 自动回到代码编写区（injectionDiv 523→547）。 */
        P + '[class*="target-pane_target-pane_"]{display:grid !important;'
          + 'position:relative !important;'
          + 'grid-template-columns:minmax(0,1fr) !important;'
          + 'grid-template-rows:34px minmax(0,1fr) !important;'
          + 'grid-template-areas:"info" "list" !important;'
          + 'width:100% !important;min-width:0 !important;overflow:hidden !important}',
        /* 背景方块：与列表同格、靠左垂直居中，保证和角色卡片同一水平线 */
        P + '[class*="target-pane_stage-selector-wrapper_"]{'
          + 'grid-area:list !important;position:relative !important;'
          + 'align-self:center !important;justify-self:start !important;'
          + 'width:72px !important;height:72px !important;min-height:0 !important;'
          + 'margin-left:2px !important;z-index:5 !important;overflow:visible !important}',
        P + '[class*="stage-selector_stage-selector_"]{'
          + 'width:72px !important;height:72px !important;min-height:0 !important;'
          + 'flex:none !important;display:flex !important;flex-direction:column !important;'
          + 'box-sizing:border-box !important;border:4px solid #8e2b2b !important;'
          + 'border-radius:12px !important;background:#8e2b2b !important;'
          + 'overflow:hidden !important;padding:0 !important;margin:0 !important}',
        P + '[class*="stage-selector_header_"],'
          + P + '[class*="stage-selector_count_"],'
          + P + '[class*="stage-selector_add-button_"]{display:none !important}',
        P + '[class*="stage-selector_costume-canvas_"]{'
          + 'flex:1 1 auto !important;width:100% !important;min-height:0 !important;'
          + 'height:auto !important;max-width:none !important;max-height:none !important;'
          + 'object-fit:contain !important;margin:0 !important;border:0 !important;'
          + 'border-radius:8px 8px 0 0 !important;box-shadow:none !important;'
          + 'background:#111 !important}',
        P + '[class*="stage-selector_label_"]{'
          + 'display:block !important;flex:0 0 24px !important;height:24px !important;'
          + 'min-height:24px !important;width:100% !important;margin:0 !important;'
          + 'padding:0 2px !important;box-sizing:border-box !important;'
          + 'background:#ff4c4c !important;color:#fff !important;font-size:13px !important;'
          + 'line-height:24px !important;text-align:center !important;'
          + 'border-radius:0 0 8px 8px !important;overflow:hidden !important;'
          + 'white-space:nowrap !important}',
        /* 属性：两行合并一行、横向铺满（display:contents 让两组控件进同一 flex 序列） */
        /* 属性行：一行铺满、组间均匀留白、控件统一圆角矩形、标签淡色降权。
           space-between 在内容不溢出时才生效，所以先把 label 压到 9px、数值框
           28px、名字框 46px，腾出的宽度换成 5 组之间约 3~4px 的均匀间距。
           中文界面实测 scrollW==clientW（overflow 0）；英文等长 label 语言由
           overflow-x:auto 横滑兜底，不会挤成两行。 */
        P + '[class*="sprite-info_sprite-info_"]{grid-area:info !important;'
          + 'display:flex !important;flex-direction:row !important;flex-wrap:nowrap !important;'
          + 'align-items:center !important;justify-content:space-between !important;'
          + 'height:34px !important;min-height:0 !important;'
          + 'overflow-x:auto !important;overflow-y:hidden !important;touch-action:pan-x !important;'
          + 'box-sizing:border-box !important;padding:0 6px !important;gap:0 !important;'
          + 'border-bottom:1px solid rgba(128,128,128,.18) !important}',
        P + '[class*="sprite-info_row_"]{display:contents !important}',
        P + '[class*="sprite-info_sprite-info_"] > [class*="sprite-info_row_"] > *{'
          + 'flex:0 0 auto !important;min-width:0 !important;margin:0 !important}',
        P + '[class*="sprite-info_sprite-info_"] [class*="label_input-group_"]{'
          + 'display:inline-flex !important;align-items:center !important;gap:2px !important;'
          + 'min-width:0 !important;width:auto !important}',
        P + '[class*="sprite-info_sprite-info_"] [class*="label_"] span{'
          + 'font-size:9px !important;flex:0 0 auto !important;'
          + 'color:rgba(255,255,255,.55) !important;font-weight:500 !important}',
        P + '[class*="sprite-info_sprite-info_"] input{'
          + 'width:28px !important;min-width:0 !important;flex:0 0 28px !important;'
          + 'height:26px !important;min-height:26px !important;'
          + 'font-size:10px !important;text-align:center !important;'
          + 'padding:0 2px !important;box-sizing:border-box !important;'
          + 'border-radius:9px !important;'
          + 'background:rgba(255,255,255,.07) !important;'
          + 'border:1px solid rgba(255,255,255,.14) !important;'
          + 'color:#fff !important}',
        P + '[class*="sprite-info_row-primary_"] > *:first-child input{'
          + 'width:46px !important;flex:0 0 46px !important}',
        P + '[class*="sprite-info_sprite-info_"] [class*="toggle-buttons_row_"]{'
          + 'flex:0 0 auto !important}',
        P + '[class*="sprite-info_sprite-info_"] [class*="toggle-buttons_"] button{'
          + 'width:24px !important;height:24px !important;min-width:0 !important;min-height:0 !important}',
        /* 显示/隐藏组做成圆角分段控件，与 5 个输入框的圆角语言统一 */
        P + '[class*="sprite-info_sprite-info_"] [class*="toggle-buttons_"]{'
          + 'border-radius:9px !important;overflow:hidden !important}',
        P + '[class*="sprite-selector_sprite-selector_"]{display:contents !important}',
        P + '[class*="sprite-selector_scroll-wrapper_"]{grid-area:list !important;'
          + 'width:100% !important;min-width:0 !important;'
          + 'box-sizing:border-box !important;padding-left:80px !important;'
          + 'height:auto !important;min-height:0 !important;display:flex !important;'
          + 'align-items:center !important;overflow-x:auto !important;overflow-y:hidden !important;'
          + 'touch-action:pan-x !important;position:relative !important;z-index:1 !important}',
        P + '[class*="sprite-selector_items-wrapper_"]{flex:0 0 auto !important;'
          + 'flex-wrap:nowrap !important;height:auto !important;min-width:0 !important;'
          + 'align-items:center !important;padding:2px 10px !important;'
          + 'overflow:visible !important}',
        /* 列表项禁止被 flex 压缩：被压缩时无溢出、滑不动，手指一滑就变成拖动角色块 */
        P + '[class*="sprite-selector_items-wrapper_"] > *{flex-shrink:0 !important}',

        /* ★ 角色卡片「显示不全/文字与缩略图重叠」的真正根因（前几轮一直没修这里）：
           原生 .sprite-wrapper 只给 min-height:4rem、没有 height，于是
           .sprite{height:100%} 解析为 auto → sprite-image-outer{height:100%}
           再解析为 0（flex item 无内容高）→ 缩略图区整块消失，卡片只剩
           sprite-info 的 19px，被压成图一那种「红条 + 浮出缩略图」。
           修法：不依赖百分比高度链，给卡片各层显式尺寸 + flex 分区，
           还原图二的「上方黑底缩略图 + 下方红底大字 + 右上圆形删除」。
           图2 反馈「卡片方块太大不好看」：整块 168→156、卡片 96×96→72×72，
           名字区 32→24、缩略图 46→36、删除 32→26、字号 16→13，等比收紧。 */
        P + '[class*="sprite-selector_sprite-wrapper_"]{'
          + 'width:72px !important;min-width:72px !important;max-width:72px !important;'
          + 'height:72px !important;min-height:72px !important;'
          + 'margin:2px 5px !important;box-sizing:border-box !important;'
          + 'transition:filter .12s ease !important}',
        P + '[class*="sprite-selector-item_sprite-selector-item_"]{'
          + 'height:100% !important;min-height:100% !important;'
          + 'display:flex !important;flex-direction:column !important;'
          + 'box-sizing:border-box !important;border:4px solid #8e2b2b !important;'
          + 'border-radius:12px !important;overflow:visible !important;'
          + 'background:#8e2b2b !important}',
        /* 缩略图区：flex 吃掉上方全部剩余高度（不再用塌陷的 height:100%） */
        P + '[class*="sprite-selector-item_sprite-image-outer_"]{'
          + 'flex:1 1 auto !important;height:auto !important;min-height:0 !important;'
          + 'width:100% !important;position:relative !important;'
          + 'background:#111 !important;border-radius:8px 8px 0 0 !important;'
          + 'overflow:hidden !important}',
        P + '[class*="sprite-selector-item_sprite-image-inner_"]{'
          + 'position:absolute !important;top:0 !important;left:0 !important;'
          + 'width:100% !important;height:100% !important;'
          + 'display:flex !important;align-items:center !important;justify-content:center !important}',
        P + '[class*="sprite-selector-item_sprite-image_"]{'
          + 'max-width:36px !important;max-height:36px !important;'
          + 'width:auto !important;height:auto !important}',
        /* 名字区：下方固定 24px 红底 + 白色字，不再与缩略图叠在一起 */
        P + '[class*="sprite-selector-item_sprite-info_"]{'
          + 'flex:0 0 24px !important;height:24px !important;min-height:24px !important;'
          + 'box-sizing:border-box !important;'
          + 'display:flex !important;align-items:center !important;justify-content:center !important;'
          + 'background:#ff4c4c !important;color:#fff !important;'
          + 'border-radius:0 0 8px 8px !important;padding:0 3px !important}',
        P + '[class*="sprite-selector-item_sprite-name_"]{'
          + 'font-size:13px !important;line-height:1 !important;color:#fff !important;'
          + 'text-align:center !important;max-width:100% !important;'
          + 'overflow:hidden !important;text-overflow:ellipsis !important;white-space:nowrap !important}',
        /* 图3：造型/声音卡片复用了 sprite-selector-item 组件，上面 13px 的名字规则
           会误伤——造型卡片只有 64px 宽，名字区被挤到「造...」只显示一个字。
           用 selector_list-item_ 精确限定造型/声音卡片，把名字压回 10px 并允许两行。 */
        P + '[class*="selector_list-item_"] [class*="sprite-selector-item_sprite-name_"]{'
          + 'font-size:10px !important;line-height:1.15 !important;'
          + 'white-space:normal !important;max-height:24px !important;overflow:hidden !important;'
          + 'text-overflow:clip !important;word-break:break-all !important}',
        P + '[class*="selector_list-item_"] [class*="sprite-selector-item_sprite-info_"]{'
          + 'flex:0 0 26px !important;height:26px !important;min-height:26px !important;'
          + 'padding:0 2px !important}',
        /* 右上圆形删除按钮：放大并压在边框上，不与名字区重叠 */
        P + '[class*="delete-button_delete-button_"]{'
          + 'top:-7px !important;right:-7px !important;'
          + 'width:26px !important;height:26px !important;z-index:3 !important}',
        P + '[class*="delete-button_delete-button-visible_"]{'
          + 'width:26px !important;height:26px !important;'
          + 'border-radius:50% !important;background:#ff4c4c !important;'
          + 'display:flex !important;align-items:center !important;justify-content:center !important;'
          + 'box-shadow:0 2px 5px rgba(0,0,0,.35) !important}',
        P + '[class*="delete-button_delete-icon_"]{width:15px !important;height:15px !important}',
        /* 长按到点：卡片轻微提亮，提示「现在可以拖动换位」 */
        P + '[class*="sprite-selector_sprite-wrapper_"].nw-lp-armed{filter:brightness(1.3) !important}',

        /* (7) 造型/声音：素材缩略图列表移到编辑器上方横条 */
        P + '[class*="asset-panel_wrapper_"]{flex-direction:column !important;min-height:0 !important;min-width:0 !important}',
        P + '[class*="asset-panel_wrapper_"] > [class*="selector_wrapper_"]{width:100% !important;height:70px !important;'
          + 'flex:0 0 70px !important;flex-direction:row !important;min-width:0 !important}',
        P + '[class*="selector_list-area_"]{flex-direction:row !important;overflow-x:auto !important;'
          + 'overflow-y:hidden !important;height:100% !important}',
        P + '[class*="selector_items-wrapper_"]{flex-wrap:nowrap !important}',
        /* #1 底部显示不全的根因：detail-area 被设成 overflow:hidden，且
           canvas-controls 内缩放按钮组从 x160 伸到 x380，右侧几个按钮被切掉。 */
        P + '[class*="asset-panel_detail-area_"]{flex:1 1 auto !important;min-height:0 !important;'
          + 'min-width:0 !important;overflow:visible !important}',
        P + '[class*="paint-editor_canvas-controls_"]{overflow-x:auto !important;'
          + 'overflow-y:hidden !important;flex-wrap:nowrap !important;min-width:0 !important;'
          + 'max-width:100% !important;padding-bottom:2px !important;touch-action:pan-x !important}',
        /* #3 底部工具行在 360px 内仍超到 378px：压缩每个按钮内边距与图标尺寸，
           让「转换为位图 / 撤销 / 缩放」一整行完整显示 */
        P + '[class*="paint-editor_canvas-controls_"] button{min-width:0 !important;padding:1px 2px !important}',
        P + '[class*="paint-editor_canvas-controls_"] img, '
          + P + '[class*="paint-editor_canvas-controls_"] svg{width:16px !important;height:16px !important}',
        P + '[class*="paint-editor_canvas-controls_"] > *{flex:0 0 auto !important}',
        P + '.sa-paintEditorZoomControls-wrapper{flex:0 0 auto !important;min-width:0 !important}',
        P + '[class*="sound-editor_"] [class*="row"], '
          + P + '[class*="sound-editor_button-group_"]{overflow-x:auto !important;touch-action:pan-x !important}',
        P + '[class*="asset-panel_wrapper_"]{padding-bottom:2px !important}',
        /* 添加造型/添加声音：原生 .new-buttons 是 absolute + width:100% +
           align-items:center，按钮落在列表正中间还盖住缩略图。改成靠右收进
           素材横条内（64px 大圆压成 44px，不再向上顶进 Tab 条区域挡住查找/舞台） */
        P + '[class*="selector_new-buttons_"]{width:auto !important;left:auto !important;'
          + 'right:6px !important;top:auto !important;bottom:2px !important;'
          + 'align-items:flex-end !important;justify-content:flex-end !important;'
          + 'pointer-events:none !important;z-index:6 !important}',
        P + '[class*="selector_new-buttons_"] > *{pointer-events:auto !important}',
        P + '[class*="selector_new-buttons_"] button{width:44px !important;min-width:44px !important;'
          + 'height:44px !important;min-height:44px !important;max-height:44px !important;box-sizing:border-box}',
        P + '[class*="selector_new-buttons_"] button img{max-width:26px !important;max-height:26px !important}',
        P + '[class*="selector_new-buttons_"]::before{display:none !important}',
        /* 图3：添加造型/声音的「更多方式」列改为向下展开。
           原生 .more-buttons-outer 用 bottom:2.75rem + 负 margin 从主按钮往上长，
           而竖屏下主按钮已在 y≈53，展开的一列会顶到 y<0 冲出屏幕、盖住 Tab/查找框
           （表现为右上角那条从屏顶垂下的红色竖条）。这里翻成 top:44px 从主按钮
           下沿往下排，清掉向上衔接用的负 margin/padding，圆角也从顶部挪到底部。
           ★ 必须保留 overflow-y:hidden：原生靠 .more-buttons{max-height:0} +
           外层裁切实现「默认收起、hover/点击才展开」。若图省事改成 visible，
           max-height:0 的内容会直接溢出 → 不点就整列展开，圆角也因不裁切而失效。
           容器宽度对齐按钮的 44px，避免按钮横向溢出把圆角裁坏或引出滚动条。
           同时放开 new-buttons / selector_wrapper / menu-container 的 overflow 并抬高
           z-index，保证展开的这一列能盖在下方造型编辑器之上、不被父级裁切。
           声音页复用同一个 action-menu 组件，一条规则同时修好。 */
        P + '[class*="action-menu_more-buttons-outer_"]{'
          + 'top:44px !important;bottom:auto !important;'
          + 'margin:0 !important;padding:0 !important;'
          + 'width:44px !important;'
          + 'border-top-left-radius:0 !important;border-top-right-radius:0 !important;'
          + 'border-bottom-left-radius:18px !important;border-bottom-right-radius:18px !important;'
          + 'overflow-y:hidden !important;z-index:80 !important}',
        P + '[class*="action-menu_more-buttons_"]{'
          + 'border-bottom-left-radius:18px !important;border-bottom-right-radius:18px !important}',
        P + '[class*="action-menu_menu-container_"]{overflow:visible !important}',
        P + '[class*="selector_new-buttons_"]{overflow:visible !important;z-index:80 !important}',
        P + '[class*="selector_wrapper_"]{overflow:visible !important;z-index:80 !important}',
        P + '[class*="asset-panel_wrapper_"]{overflow:visible !important}',
        /* 造型顶部工具行（撤销/组合/前后/复制/删除…）在窄屏被裁，允许横向滑动 */
        P + '[class*="paint-editor_editor-container-top_"]{overflow-x:auto !important;'
          + 'overflow-y:hidden !important;flex-wrap:nowrap !important;min-width:0 !important;'
          + 'touch-action:pan-x !important}',
        P + '[class*="paint-editor_editor-container-top_"] > *{flex:0 0 auto !important}',

        /* (8) 造型编辑器：矩形/圆形/线等工具在上，画布在下 */
        P + '[class*="paint-editor_editor-container_"]{min-width:0 !important;padding:6px !important;'
          + 'height:100% !important;box-sizing:border-box}',
        P + '[class*="paint-editor_editor-container-top_"]{flex:0 0 auto !important}',
        P + '[class*="paint-editor_top-align-row_"]{flex-direction:column !important;min-width:0 !important;'
          + 'height:auto !important;flex:1 1 auto !important;padding-top:4px !important}',
        P + '[class*="paint-editor_mode-selector_"]{max-width:none !important;width:100% !important;'
          + 'flex-direction:row !important;flex-wrap:wrap !important;flex:0 0 auto !important}',
        P + '[class*="paint-editor_controls-container_"]{min-width:0 !important;width:100% !important;flex:1 1 auto !important}',
        P + '[class*="paint-editor_canvas-container_"]{min-width:0 !important}',

        /* (9) 声音编辑器：工具/效果在上，波形在下 */
        P + '[class*="sound-editor_editor-container_"]{min-width:0 !important;padding:6px !important;'
          + 'min-height:0 !important;flex:1 1 auto !important;box-sizing:border-box}',
        P + '[class*="sound-editor_row_"]{min-width:0 !important;flex-wrap:wrap !important}',
        P + '[class*="sound-editor_waveform-container_"]{min-width:0 !important;flex:1 1 auto !important}',
        P + '[class*="sound-editor_effects_"]{flex-wrap:wrap !important;justify-content:center !important}',

        /* (10) 查找框与扩展按钮移到顶部 Tab 条内、舞台按钮左侧。
           关键修复一：fixed 元素若只写 bottom（top 留 auto），会与静态位置叠加
           被拉伸成 253×780 的全屏透明层，把分类栏/积木/编程区的触摸全部挡掉
           （真机上表现为「点不动、拖不出」）。必须同时显式 top 与 height。
           关键修复二：fixed 的 right/bottom 相对「布局视口」解析，个别内核下
           该视口与可视区不一致会造成元素飞出屏幕，因此用 JS 写入的
           --nw-vw/--nw-vh（= innerWidth/innerHeight）计算 left/top。 */
        /* 查找框：fixed 定位框架在此，left/width 由 layoutFindBar() 按真实 DOM
           测量写入（下方 fallback 仅在 JS 未跑时兜底）。旧版固定偏移在真机
           字体下会盖住「声音」Tab */
        P + '.sa-find-bar{position:fixed !important;top:2px !important;bottom:auto !important;'
          + 'right:auto !important;left:calc(var(--nw-vw,360px) - 168px) !important;'
          + 'width:76px !important;min-width:0 !important;height:36px !important;'
          + 'max-height:36px !important;overflow:hidden !important;'
          + 'z-index:60 !important;font-size:12px !important}',
        P + '.sa-find-bar input{min-width:0 !important;width:100% !important;font-size:12px !important}',
        /* 扩展按钮：去掉红色方块图标，改纯文字「扩展」，钉到顶部。
           仅纵向排列生效；横向排列下这些规则不加载，扩展按钮保持原生
           左下角位置（用户明确要求「横向排列左下角换回原来的扩展按钮」）。 */
        /* #2 红框来自「容器」的 1px solid #ff4c4c（button 自身早已 border:0） */
        PV + '[class*="gui_extension-button-container_"]{position:fixed !important;top:2px !important;'
          + 'right:auto !important;left:calc(var(--nw-vw,360px) - 76px) !important;'
          + 'bottom:auto !important;width:32px !important;min-width:0 !important;'
          + 'z-index:60 !important;height:36px !important;background:transparent !important;'
          + 'border:0 !important;outline:0 !important;box-shadow:none !important;padding:0 !important}',
        PV + 'button[class*="gui_extension-button_"]{width:32px !important;height:36px !important;'
          + 'min-width:0 !important;padding:0 !important;margin:0 !important;border:0 !important;'
          + 'border-radius:0 !important;background:transparent !important;box-shadow:none !important;'
          + 'font-size:0 !important;display:flex !important;align-items:center !important;'
          + 'justify-content:center !important}',
        PV + '[class*="gui_extension-button-icon_"]{display:none !important}',
        PV + 'button[class*="gui_extension-button_"]::after{content:"扩展";font-size:12px !important;'
          + 'font-weight:600 !important;color:#ff8c1a !important;white-space:nowrap;line-height:1}',
        /* 书包保留原生位置（代码区底部），功能不丢 */
        /* #3 两个红色圆按钮不再显示；改由工具栏内两个「带下拉菜单」的文字按钮接管，
           下拉项复用原生 action-menu 的按钮（程序化 click，功能零损失） */
        P + '[class*="sprite-selector_add-button_"], '
          + P + '[class*="stage-selector_add-button_"]{display:none !important}',
        P + '.nw-add-btn{display:inline-flex !important;align-items:center !important;'
          + 'justify-content:center !important;height:32px !important;'
          + 'padding:0 2px !important;margin:0 1px !important;flex:0 0 auto !important;'
          + 'border:0 !important;outline:0 !important;background:transparent !important;'
          + 'color:#fff !important;font-size:10px !important;font-weight:600 !important;'
          + 'white-space:nowrap !important;line-height:1 !important;cursor:pointer;'
          + 'border-radius:6px !important}',
        P + '.nw-add-menu{position:fixed !important;z-index:140 !important;'
          + 'bottom:calc(var(--nw-menu-h) + 4px) !important;display:flex !important;'
          + 'flex-direction:column !important;min-width:120px !important;max-height:52vh !important;'
          + 'overflow-y:auto !important;padding:4px !important;margin:0 !important;'
          + 'background:#2f2f2f !important;border:1px solid rgba(255,255,255,.18) !important;'
          + 'border-radius:12px !important;box-shadow:0 4px 18px rgba(0,0,0,.45) !important}',
        P + '.nw-add-menu button{display:block !important;width:100% !important;'
          + 'text-align:left !important;padding:9px 12px !important;margin:0 !important;'
          + 'border:0 !important;background:transparent !important;color:#fff !important;'
          + 'font-size:13px !important;white-space:nowrap !important;cursor:pointer;'
          + 'border-radius:8px !important}',
        /* 纵向排列：Blockly 缩放控件（放大/缩小/等号）移到编程区左下角
           （y 由 JS 按实际布局写入 --nw-zoom-y）。横向排列不套用此规则，
           缩放控件保持原生右下角位置。 */
        PV + '.blocklyZoom{transform:translate(6px,var(--nw-zoom-y)) !important}',
        /* 纵向排列坐标分区：分类列与抽屉压到上方 2/5（高度 --nw-top-h 由 JS
           按 injectionDiv 实际高度写入），代码画布移到下方 3/5 横跨全宽。
           抽屉宽度铺满由 JS 覆盖 flyout.getWidth() 实现；这里只管高度与溢出，
           !important 压过 toolbox.position() 写下的内联 height:100%。 */
        PV + '.blocklyToolboxDiv{height:var(--nw-top-h,184px) !important;'
          + 'max-height:var(--nw-top-h,184px) !important;overflow-y:auto !important}',
        PV + '.blocklyFlyout{overflow:hidden !important}',
        /* 颜色全部沿用 TurboWarp 主题原生色：深色主题下 toolbox / flyout / 画布
           同为 #111111，浅色主题下同为白系，天然协调。之前硬编码 #f1f2f6 浅灰
           会在深色主题里把上方 2/5 刷成一块刺眼的白膏药（用户反馈「颜色有点问题」）。
           这里只补一条中性半透明分界线区分 2/5 与 3/5，深浅主题都不突兀；
           pointer-events:none 保证不拦拖拽。 */
        PV + '.injectionDiv::after{content:"" !important;position:absolute !important;'
          + 'left:0 !important;right:0 !important;top:var(--nw-top-h,184px) !important;'
          + 'height:1px !important;background:rgba(128,128,128,.5) !important;'
          + 'pointer-events:none !important;z-index:9 !important}',
        /* 触摸命中保障：Blockly 各层明确禁用浏览器手势接管（否则触摸拖动会被
           pointercancel 吃掉，表现为积木拖不出、画布拖不动）。抽屉例外：
           竖排抽屉靠纵向滑动浏览积木（pan-y），向右拖 = 拖出积木 */
        P + '.blocklySvg, ' + P + '.injectionDiv, '
          + P + '.blocklyFlyout, '
          + P + '.blocklyBlockDragSurface, ' + P + '.blocklyMainWorkspaceScrollbar{touch-action:none !important}',
        /* 注意：抽屉不能设 pan-y——那样浏览器会接管纵向手势并发 pointercancel，
           Blockly 的 FlyoutDragger（拖抽屉滚动）就收不到 move 事件；
           滚动完全由 Blockly 内部处理，touch-action 必须是 none */
        /* 防御：竖屏下隐藏「浏览器不受支持」的全屏透明遮罩——它一旦出现会拦下
           整个编辑器的点击（分类点不动、积木拖不出）。桌面/横屏不受影响 */
        P + '[class*="browser-modal_modal-overlay_"]{display:none !important}',

        /* (11) 扩展库：分类横向滑动、搜索框固定；卡片由单列大图改为一行两列。
           根因：.featured-item 是 flex-basis:300px（360px 屏只放得下 1 个），
           .tag-wrapper 是 flex-wrap:wrap + overflow:hidden（分类换行后被裁）。 */
        P + '[class*="library_filter-bar_"]{flex-wrap:nowrap !important;'
          + 'overflow:hidden !important;padding:0 8px !important;height:56px !important;'
          + 'min-height:0 !important;width:var(--nw-vw,360px) !important;'
          + 'max-width:var(--nw-vw,360px) !important;box-sizing:border-box !important}',
        P + '[class*="library_filter_"]{flex:0 0 108px !important;width:108px !important;'
          + 'min-width:0 !important;margin-right:6px !important}',
        P + '[class*="library_filter-input_"]{width:100% !important;min-width:0 !important;'
          + 'font-size:12px !important}',
        P + '[class*="library_divider_"]{flex:0 0 1px !important;margin-right:6px !important}',
        P + '[class*="library_tag-wrapper_"]{flex:1 1 auto !important;min-width:0 !important;'
          + 'width:auto !important;max-width:calc(var(--nw-vw,360px) - 140px) !important;'
          + 'flex-wrap:nowrap !important;overflow-x:auto !important;overflow-y:hidden !important;'
          + 'height:auto !important;align-items:center !important;touch-action:pan-x !important}',
        P + '[class*="tag-button_tag-button_"]{flex:0 0 auto !important;'
          + 'white-space:nowrap !important;margin-right:6px !important}',
        P + '[class*="library_library-scroll-grid_"]{padding:6px !important;'
          + 'justify-content:flex-start !important;align-content:flex-start !important;'
          + 'width:var(--nw-vw,360px) !important;max-width:var(--nw-vw,360px) !important;'
          + 'box-sizing:border-box !important}',
        /* 两个卡片 + 各自 5px 外边距 + 容器 6px padding 需 <= 360，故取 50%-16px */
        P + '[class*="library-item_featured-item_"]{flex-basis:calc(50% - 16px) !important;'
          + 'max-width:none !important;min-width:0 !important;margin:5px !important;'
          + 'height:auto !important;min-height:0 !important;border-radius:14px !important;'
          + 'align-self:flex-start !important}',
        P + '[class*="library-item_featured-image_"]{aspect-ratio:1.5 !important;'
          + 'height:auto !important}',
        P + '[class*="library-item_featured-text_"]{width:100% !important;'
          + 'padding:6px 4px !important;text-align:center !important;box-sizing:border-box}',
        P + '[class*="library-item_featured-extension-text_"]{text-align:center !important;'
          + 'padding:6px 4px !important;width:100% !important;box-sizing:border-box}',
        P + '[class*="library-item_library-item-name_"]{width:100% !important;font-size:12px !important}',
        P + '[class*="library-item_featured-description_"]{font-size:11px !important;'
          + 'padding:0 2px !important;overflow:hidden !important;display:-webkit-box !important;'
          + '-webkit-line-clamp:2 !important;-webkit-box-orient:vertical !important}',

        /* (11b) 素材库（选择一个角色 / 背景 / 造型 / 声音）：一行三个。
           原生 .library-item 是 flex-basis:160px + margin:8px + height:160px，
           360px 屏只能塞 2 个；这里改为三列并等比缩小卡片内部元素。
           注意排除扩展库的 featured-item / library-item-extension（保持两列不动）。 */
        P + '[class*="library_library-scroll-grid_"]{padding:4px !important;'
          + 'flex-direction:row !important;flex-wrap:wrap !important;'
          + 'justify-content:flex-start !important;align-content:flex-start !important}',
        P + '[class*="library-item_library-item_"]:not([class*="featured-item_"])'
          + ':not([class*="library-item-extension_"]){'
          + 'flex:0 0 calc(33.333% - 16px) !important;'
          + 'max-width:none !important;min-width:0 !important;'
          + 'height:auto !important;min-height:0 !important;'
          + 'margin:4px !important;padding:6px 4px !important;'
          + 'border-radius:12px !important;box-sizing:border-box !important;'
          + 'overflow:hidden !important;align-self:flex-start !important}',
        P + '[class*="library-item_library-item-image-container-wrapper_"]{'
          + 'width:100% !important;height:72px !important;min-height:0 !important;'
          + 'display:flex !important;align-items:center !important;justify-content:center !important}',
        P + '[class*="library-item_library-item-image-container_"]{'
          + 'position:relative !important;width:100% !important;height:72px !important;'
          + 'display:flex !important;align-items:center !important;justify-content:center !important}',
        P + '[class*="library-item_library-item-image_"]{'
          + 'max-width:100% !important;max-height:70px !important;'
          + 'width:auto !important;height:auto !important;'
          + 'border-radius:0 !important;box-shadow:none !important;'
          + 'object-fit:contain !important}',
        P + '[class*="library-item_library-item-name_"]{'
          + 'width:100% !important;font-size:10px !important;line-height:14px !important;'
          + 'margin:3px 0 0 !important;overflow:hidden !important;'
          + 'text-overflow:ellipsis !important;white-space:nowrap !important}',
        P + '[class*="library-item_favorite-container_"]{'
          + 'top:2px !important;left:2px !important;width:18px !important;height:18px !important}',
        P + '[class*="library-item_favorite-icon_"]{width:16px !important;height:16px !important}',
        P + '[class*="play-button_play-button_"]{width:28px !important;height:28px !important;'
          + 'min-width:0 !important;min-height:0 !important;flex:0 0 28px !important}',
        P + '[class*="play-button_play-button_"] img, '
          + P + '[class*="play-button_play-button_"] svg{width:16px !important;height:16px !important}',

        /* 兜底：某些皮肤/语言下主菜单过宽会把两个添加按钮挤出屏幕，此时钉到
           工具栏右端保证始终可点；背景保持透明（用户不要彩色底） */
        'html.' + CLS + '.nw-add-fixed .nw-add-btn{position:fixed !important;'
          + 'top:calc(var(--nw-vh,780px) - 39px) !important;z-index:82 !important;'
          + 'background:transparent !important;padding:0 6px !important}',
        'html.' + CLS + '.nw-add-fixed .nw-add-1{left:calc(var(--nw-vw,360px) - 100px) !important}',
        'html.' + CLS + '.nw-add-fixed .nw-add-2{left:calc(var(--nw-vw,360px) - 52px) !important}',
        'html.' + CLS + '.nw-add-fixed [class*="menu-bar_main-menu_"]{padding-right:104px !important}',

        /* #6 造型/声音页不显示扩展按钮（仅代码页显示），空出的宽度让查找框变长
           （查找框实际 left/width 由 layoutFindBar() 测量写入，自动利用该空间） */
        'html.' + CLS + '.nw-no-ext [class*="gui_extension-button-container_"]{display:none !important}',

        /* 横屏：新增的舞台按钮 / 工具栏添加按钮必须消失（其余规则已由 html.nw-portrait 限定） */
        'html:not(.' + CLS + ') .nw-stage-btn{display:none !important}',
        'html:not(.' + CLS + ') .nw-add-btn, html:not(.' + CLS + ') .nw-add-menu{display:none !important}'
      ].join('\n');

      function injectCss() {
        if (document.getElementById('nw-portrait-css')) return;
        var st = document.createElement('style');
        st.id = 'nw-portrait-css';
        st.textContent = css;
        (document.head || document.documentElement).appendChild(st);
      }

      /* ---------------- 舞台按钮（切换全屏舞台，复用 GUI 原生 action） ---------------- */
      var stageBtn = null;
      function toggleFullStage() {
        var store = window.ReduxStore;
        if (!store || !store.dispatch || !store.getState) return;
        try {
          var st = store.getState();
          var isFull = !!(st && st.scratchGui && st.scratchGui.mode && st.scratchGui.mode.isFullScreen);
          store.dispatch({ type: 'scratch-gui/mode/SET_FULL_SCREEN', isFullScreen: !isFull });
          if (!isFull) store.dispatch({ type: 'scratch-gui/StageSize/SET_STAGE_SIZE', stageSize: 'large' });
        } catch (e) { /* store 尚未就绪 */ }
      }
      function exitFullStage() {
        var store = window.ReduxStore;
        if (!store || !store.dispatch || !store.getState) return;
        try {
          var st = store.getState();
          if (st && st.scratchGui && st.scratchGui.mode && st.scratchGui.mode.isFullScreen) {
            store.dispatch({ type: 'scratch-gui/mode/SET_FULL_SCREEN', isFullScreen: false });
          }
        } catch (e) {}
      }
      function ensureStageButton() {
        var list = document.querySelector('ul[class*="gui_tab-list_"]');
        if (!list) return;
        if (!stageBtn) {
          stageBtn = document.createElement('button');
          stageBtn.type = 'button';
          stageBtn.className = 'nw-stage-btn';
          stageBtn.textContent = '舞台';
          stageBtn.addEventListener('click', function (e) {
            e.preventDefault(); e.stopPropagation();
            toggleFullStage();
          });
        }
        if (stageBtn.parentNode !== list) list.appendChild(stageBtn);
      }

      /* ---------------- #3 工具栏「添加角色/添加背景」下拉按钮 ----------------
         两个红色圆按钮由 CSS 隐藏（DOM 保留），这里在工具栏内建两个文字按钮，
         下拉项直接取自原生 action-menu 的按钮并程序化 click，功能零损失。 */
      var addBtns = [];
      var addMenu = null;
      var ADD_LABELS = {
        'Choose a Sprite': '选择角色', 'Upload Sprite': '上传角色',
        'Surprise': '随机角色', 'Paint': '绘制角色',
        'Choose a Backdrop': '选择背景', 'Upload Backdrop': '上传背景',
        'Surprise Backdrop': '随机背景', 'Paint a Backdrop': '绘制背景'
      };
      function closeAddMenu() {
        if (!addMenu) return;
        try { if (addMenu.parentNode) addMenu.parentNode.removeChild(addMenu); } catch (e) {}
        addMenu = null;
      }
      function openAddMenu(btn, addSel) {
        closeAddMenu();
        var root = document.querySelector(addSel);
        if (!root) return;
        var items = [], seen = {};
        var nodes = root.querySelectorAll('button');
        for (var i = 0; i < nodes.length; i++) {
          var el = nodes[i];
          var aria = el.getAttribute('aria-label') || el.title || '';
          if (!aria || seen[aria]) continue;
          seen[aria] = 1;
          items.push({ label: ADD_LABELS[aria] || aria, el: el });
        }
        if (!items.length) return;
        var m = document.createElement('div');
        m.className = 'nw-add-menu';
        m.setAttribute('data-nw-for', addSel);
        for (var j = 0; j < items.length; j++) {
          (function (it) {
            var b = document.createElement('button');
            b.type = 'button';
            b.textContent = it.label;
            b.addEventListener('click', function (e) {
              e.preventDefault(); e.stopPropagation();
              closeAddMenu();
              try { it.el.click(); } catch (err) {}
            });
            m.appendChild(b);
          })(items[j]);
        }
        document.body.appendChild(m);
        var r = btn.getBoundingClientRect();
        var left = Math.max(8, Math.min(r.left, window.innerWidth - m.offsetWidth - 8));
        m.style.left = Math.round(left) + 'px';
        addMenu = m;
      }
      function makeAddBtn(label, addSel, extra) {
        var b = document.createElement('button');
        b.type = 'button';
        b.className = 'nw-add-btn' + (extra ? ' ' + extra : '');
        b.textContent = label;
        b.addEventListener('click', function (e) {
          e.preventDefault(); e.stopPropagation();
          if (addMenu && addMenu.getAttribute('data-nw-for') === addSel) { closeAddMenu(); return; }
          openAddMenu(b, addSel);
        });
        return b;
      }
      function ensureAddButtons() {
        if (!active()) return;
        var mm = document.querySelector('[class*="menu-bar_main-menu_"]');
        if (!mm) return;
        if (!addBtns.length) {
          addBtns = [
            makeAddBtn('＋角色', '[class*="sprite-selector_add-button_"]', 'nw-add-1'),
            makeAddBtn('＋背景', '[class*="stage-selector_add-button_"]', 'nw-add-2')
          ];
        }
        for (var i = 0; i < addBtns.length; i++) {
          if (addBtns[i].parentNode !== mm) mm.appendChild(addBtns[i]);
        }
        /* 先按「流内布局」测量：若沿用 fixed 状态测量，按钮回到屏内会造成
           over=false -> 移除 class -> 按钮回流又超宽 -> 加回 class 的闪烁震荡 */
        var root = document.documentElement;
        if (root.classList.contains('nw-add-fixed')) root.classList.remove('nw-add-fixed');
        var over = false;
        for (var k = 0; k < addBtns.length; k++) {
          if (addBtns[k].getBoundingClientRect().right > window.innerWidth - 2) { over = true; break; }
        }
        root.classList.toggle('nw-add-fixed', over);
      }
      /* ---------------- #11 工具栏细节清理（React 重渲染后需重跑） ----------------
         1) 下拉箭头：原生 8×5 裸 img 无类名，CSS 选不中；唯一可靠特征是
            naturalWidth=8 && naturalHeight=5（data-uri svg 加载完成后生效）
         2) 空壳项：监视器/标题字段隐藏后剩 ~2px 宽的壳，参与 space-between
            均分会凭空多出两个间隙，一并隐藏 */
      function tidyMenuBar() {
        if (!active()) return;
        var mm = document.querySelector('[class*="menu-bar_main-menu_"]');
        if (!mm) return;
        var imgs = mm.querySelectorAll('img');
        for (var i = 0; i < imgs.length; i++) {
          var im = imgs[i];
          if (im.naturalWidth === 8 && im.naturalHeight === 5 && im.style.display !== 'none') {
            im.style.display = 'none';
          }
        }
        var kids = mm.children;
        for (var j = 0; j < kids.length; j++) {
          var k = kids[j];
          if (k.classList.contains('nw-add-btn') || k.style.display === 'none') continue;
          var gs = k.querySelectorAll('img,svg');
          var hasVis = false;
          for (var m = 0; m < gs.length; m++) {
            if (gs[m].getBoundingClientRect().width > 2) { hasVis = true; break; }
          }
          if (!hasVis && k.getBoundingClientRect().width < 6) k.style.display = 'none';
        }
      }

      document.addEventListener('click', function (e) {
        if (!addMenu) return;
        if (addMenu.contains(e.target)) return;
        if (e.target && e.target.closest && e.target.closest('.nw-add-btn')) return;
        closeAddMenu();
      }, true);

      /* ---------------- #6 按选中 Tab 决定查找框长度 ---------------- */
      function syncTabClass() {
        if (!active()) return;
        var tabs = document.querySelectorAll('ul[class*="gui_tab-list_"] > li, ul[class*="gui_tab-list_"] > [role="tab"]');
        if (!tabs.length) return;
        var f = tabs[0];
        var sel = f.getAttribute('aria-selected') === 'true' ||
                  (f.className && String(f.className).indexOf('selected') >= 0);
        document.documentElement.classList.toggle('nw-no-ext', !sel);
      }
      document.addEventListener('click', function (e) {
        if (e.target && e.target.closest && e.target.closest('ul[class*="gui_tab-list_"]')) {
          setTimeout(syncTabClass, 60);
          setTimeout(syncTabClass, 300);
        }
      }, true);

      /* ---------------- Blockly 竖屏接管 ---------------- */
      var patchedWs = null;
      /* 竖屏保持 Scratch 原生竖向排列（左侧分类列 + 竖排积木抽屉 + 右侧画布），
         只把抽屉从原生 250px 收窄到 140px：原生 310px 总宽会让 360px 屏的
         代码画布只剩 ~50px（积木拖出来没地方放）。
         收窄必须同时改两个「原型字段」——getMetrics().absoluteLeft 读的是
         toolbox.width（原型写死 310，不读 DOM），抽屉显示宽度读的是
         flyout.DEFAULT_WIDTH（原型 250）；只改一个，画布与抽屉位置就会错位 */
      function applyFlyoutWidth(ws, narrow) {
        if (!ws) return;
        try {
          var flyout = null, tb = null;
          try { flyout = ws.getFlyout ? ws.getFlyout() : ws.flyout_; } catch (e) {}
          try { tb = ws.getToolbox ? ws.getToolbox() : ws.toolbox_; } catch (e) {}
          if (narrow) {
            if (flyout && typeof flyout.DEFAULT_WIDTH === 'number') flyout.DEFAULT_WIDTH = 140;
            if (tb && typeof tb.width === 'number') tb.width = 200;   // 60 分类 + 140 抽屉
          } else {
            if (flyout && flyout.constructor && flyout.constructor.prototype &&
                typeof flyout.constructor.prototype.DEFAULT_WIDTH === 'number') {
              flyout.DEFAULT_WIDTH = flyout.constructor.prototype.DEFAULT_WIDTH;
            }
            if (tb && tb.constructor && tb.constructor.prototype &&
                typeof tb.constructor.prototype.width === 'number') {
              tb.width = tb.constructor.prototype.width;
            }
          }
          if (flyout && flyout.position) flyout.position();
        } catch (e) { /* 保底：极旧内核下不收窄，布局仍是原生竖排 */ }
      }
      function reflowBlockly() {
        if (!patchedWs) return;
        var SB = window.ScratchBlocks;
        // fixed 定位基准：写入可视区尺寸，避免布局视口与可视区不一致时元素飞出屏幕
        try {
          var dv = document.documentElement.style;
          dv.setProperty('--nw-vw', window.innerWidth + 'px');
          dv.setProperty('--nw-vh', window.innerHeight + 'px');
        } catch (e) {}
        // 用静态 setTopLevelWorkspaceMetrics_ 直接触发 translate，最可靠
        try {
          if (SB && SB.WorkspaceSvg && SB.WorkspaceSvg.setTopLevelWorkspaceMetrics_) {
            SB.WorkspaceSvg.setTopLevelWorkspaceMetrics_.call(patchedWs, {});
          }
        } catch (e) {}
        try { if (typeof patchedWs.setMetrics === 'function') patchedWs.setMetrics({}); } catch (e) {}
        try { patchedWs.resize(); } catch (e) {}
        try { if (SB && SB.svgResize) SB.svgResize(patchedWs); } catch (e) {}
        // 把缩放控件钉到编程区左下角：y = 编辑区底 - 控件高(约132)
        try {
          var inj = document.querySelector('.injectionDiv');
          if (inj && inj.clientHeight > 0) {
            var zy = Math.max(120, inj.clientHeight - 132);
            document.documentElement.style.setProperty('--nw-zoom-y', zy + 'px');
          }
        } catch (e) {}
      }
      /* ---------------- 编辑器排列方式（horizontal / vertical） ----------------
         仅竖屏生效。horizontal 为 Scratch 原生竖排（左分类列 + 竖抽屉在左、
         画布在右），按钮沿用原生位置；vertical 把代码列表（分类列 + 抽屉）
         压到上方 2/5、画布移到下方 3/5 横跨全宽。

         做法不是拆 DOM，而是可逆地覆盖 Blockly 的
           ws.getMetrics() / flyout.position() / flyout.getWidth() /
           flyout.isDragTowardWorkspace()
         让 absoluteTop 下移 topH、view 缩到下方，抽屉改从 (60,0) 起铺满
         (W-60, topH)。Blockly 的落点换算读同一份 metrics/translate，
         因此拖出的积木落点与手指位置天然对齐；垂直排列下「向下拖」才
         算拖向画布，故 isDragTowardWorkspace 改判 90° ± range。
         切回 horizontal / 横屏时把方法逐个还原，零残留。 */
      function readLayout() {
        var v = null;
        try { v = sync('getEditorLayout'); } catch (e) {}
        return v === 'vertical' ? 'vertical' : 'horizontal';
      }
      var curLayout = readLayout();
      var layoutFromEvent = false;   // 用户已在本次会话切换过则以事件值为准
      var layOrig = null;   // 纵向 patch 前的原生方法/属性快照（按 ws 缓存）
      function verticalEnabled() { return active() && curLayout === 'vertical'; }
      function syncLayoutClass() {
        var root = document.documentElement;
        root.classList.toggle('nw-layout-vertical', verticalEnabled());
        root.classList.toggle('nw-layout-horizontal', active() && curLayout === 'horizontal');
      }
      function saveLayoutOrig(ws, flyout, tb) {
        if (layOrig && layOrig.ws === ws) return layOrig;
        layOrig = {
          ws: ws, flyout: flyout, tb: tb,
          getMetrics: ws.getMetrics,
          position: flyout.position,
          getWidth: flyout.getWidth,
          isDragTowardWorkspace: flyout.isDragTowardWorkspace,
          DEFAULT_WIDTH: flyout.DEFAULT_WIDTH,
          tbWidth: tb ? tb.width : null
        };
        return layOrig;
      }
      function applyEditorLayout() {
        var SB = window.ScratchBlocks;
        var ws = patchedWs;
        syncLayoutClass();
        if (!SB || !ws) return;
        var flyout = null, tb = null;
        try { flyout = ws.getFlyout ? ws.getFlyout() : ws.flyout_; } catch (e) {}
        try { tb = ws.getToolbox ? ws.getToolbox() : ws.toolbox_; } catch (e) {}
        if (!flyout) return;
        var o = saveLayoutOrig(ws, flyout, tb);
        var inj = document.querySelector('.injectionDiv');
        var W = inj && inj.clientWidth ? inj.clientWidth : window.innerWidth;
        var H = inj && inj.clientHeight ? inj.clientHeight : window.innerHeight;
        var topH = Math.round(H * 0.4);   // 代码列表 2/5，代码放置区 3/5
        if (verticalEnabled()) {
          document.documentElement.style.setProperty('--nw-top-h', topH + 'px');
          flyout.getWidth = function () { return W - 60; };
          flyout.DEFAULT_WIDTH = W - 60;
          ws.getMetrics = function () {
            var m = o.getMetrics.call(this);
            m.absoluteLeft = 0;
            m.absoluteTop = topH;
            m.viewWidth = W;
            m.viewHeight = H - topH;
            m.toolboxWidth = 60;
            m.toolboxHeight = topH;
            return m;
          };
          flyout.position = function () {
            if (!this.isVisible()) return;
            this.width_ = this.getWidth();
            var x = 60, y = 0;
            this.height_ = topH;
            this.setBackgroundPath_(this.width_, this.height_);
            this.svgGroup_.setAttribute('width', this.width_);
            this.svgGroup_.setAttribute('height', this.height_);
            SB.utils.setCssTransform(this.svgGroup_, 'translate(' + x + 'px,' + y + 'px)');
            if (this.scrollbar_) { this.scrollbar_.setOrigin(x, y); this.scrollbar_.resize(); }
            this.svgGroup_.style.opacity = 1;
          };
          flyout.isDragTowardWorkspace = function (delta) {
            var dir = Math.atan2(delta.y, delta.x) / Math.PI * 180;
            var range = this.dragAngleRange_ || 70;
            return dir > (90 - range) && dir < (90 + range);
          };
          try { ws.scrollX = 0; ws.scrollY = 0; } catch (e) {}
        } else {
          // 还原原生方法，再按横/竖屏恢复抽屉宽度（横屏走原生 250px）
          ws.getMetrics = o.getMetrics;
          flyout.position = o.position;
          flyout.getWidth = o.getWidth;
          flyout.isDragTowardWorkspace = o.isDragTowardWorkspace;
          flyout.DEFAULT_WIDTH = o.DEFAULT_WIDTH;
          if (tb && typeof o.tbWidth === 'number') tb.width = o.tbWidth;
          applyFlyoutWidth(ws, active());
        }
        try { flyout.position(); } catch (e) {}
        reflowBlockly();
      }
      // 桌面设置切换：Java 持久化后广播 editor-layout-changed，这里即时生效
      on('editor', 'editor-layout-changed', function (v) {
        layoutFromEvent = true;
        curLayout = v === 'vertical' ? 'vertical' : 'horizontal';
        applyEditorLayout();
        if (active()) { tidyMenuBar(); ensureStageButton(); ensureAddButtons(); syncTabClass(); layoutFindBar(); }
      });

      /* 查找框测量式定位：读最后一个 Tab 的真实右缘、扩展/舞台按钮的真实左缘，
         写入 left/width。不用 calc(var(--nw-vw)-Xpx) 猜固定偏移——真机字体与
         内核差异曾让固定值盖住「声音」Tab。important 内联保证压过任何样式表 */
      function layoutFindBar() {
        try {
          var fb = document.querySelector('.sa-find-bar');
          if (!fb || !active()) return;
          var tabs = document.querySelector('ul[class*="gui_tab-list_"]');
          var st = document.querySelector('.nw-stage-btn');
          if (!tabs || !st) return;
          var kids = tabs.querySelectorAll('.react-tabs__tab, [class*="gui_tab_"]');
          var last = kids.length ? kids[kids.length - 1] : null;
          var right = last ? last.getBoundingClientRect().right : 184;
          var sr = st.getBoundingClientRect();
          var noExt = document.documentElement.classList.contains('nw-no-ext');
          var ext = document.querySelector('[class*="gui_extension-button-container_"]');
          var er = (!noExt && ext) ? ext.getBoundingClientRect() : null;
          /* 横向排列下扩展按钮在左下角（top 很大），不属于顶部行；此时顶部
             空出的宽度应让给查找框，右边界退到舞台按钮左缘（与 nw-no-ext
             的造型/声音页一致），避免查找框被挤回固定窄宽度盖住 Tab。 */
          var extOnTop = er && er.top < 60 && er.left > 0;
          var extL = extOnTop ? er.left : (sr && sr.left > 0 ? sr.left : window.innerWidth - 84);
          if (extL <= 0) extL = window.innerWidth - 84;
          var left = Math.max(Math.round(right) + 6, 8);
          var w = Math.round(extL) - 6 - left;
          if (w >= 56) {
            var s = fb.style;
            s.setProperty('position', 'fixed', 'important');
            s.setProperty('top', '2px', 'important');
            s.setProperty('height', '36px', 'important');
            s.setProperty('left', left + 'px', 'important');
            s.setProperty('width', w + 'px', 'important');
            s.setProperty('right', 'auto', 'important');
            s.setProperty('bottom', 'auto', 'important');
          }
        } catch (e) {}
      }
      function installBlockly() {
        var SB = window.ScratchBlocks;
        if (!SB) return false;
        var ws = null;
        try { ws = SB.getMainWorkspace ? SB.getMainWorkspace() : SB.mainWorkspace; } catch (e) {}
        if (!ws || !ws.getMetrics) return false;
        if (patchedWs !== ws) {
          patchedWs = ws;
          // Blockly 就绪时再取一次：兜底 bridge.js 顶层 sync 过早读不到 Java 注入对象，
          // 保证「上次保存的纵向排列」在冷启动后能恢复
          if (!layoutFromEvent) curLayout = readLayout();
          applyFlyoutWidth(ws, portrait());
        }
        applyEditorLayout();   // 按当前排列方式应用/还原坐标分区（内含 reflow）
        return true;
      }

      /* ---------------- 状态机 ---------------- */
      function refresh() {
        var on = portrait();
        var root = document.documentElement;
        var changed = root.classList.contains(CLS) !== on;
        if (on) root.classList.add(CLS); else root.classList.remove(CLS);
        if (on) {
          ensureStageButton();
          ensureAddButtons();
          syncTabClass();
          layoutFindBar();
          if (changed) applyFlyoutWidth(patchedWs, true);
          applyEditorLayout();     // 竖屏：按排列方式应用/还原坐标分区
        } else if (changed) {
          root.classList.remove('nw-no-ext');
          root.classList.remove('nw-add-fixed');
          closeAddMenu();
          syncLayoutClass();       // 横屏：移除两个排列 class，让所有布局规则失效
          applyEditorLayout();     // 还原原生 Blockly 方法与原生抽屉宽度
          reflowBlockly();
          exitFullStage();
        }
      }

      /* ---------------- 角色列表：长按才允许拖动换位 ----------------
         GUI 把 sprite-selector-item 的 onTouchStart 直接接到 onMouseDown，
         即 DragRecognizer.start()；它在 touchmove 里把横向移动（与水平夹角
         ≤70°）判成 DRAG 并 preventDefault()，于是「直接左右滑」既滑不动列表、
         又误触发「更换角色排列顺序」。这里在 document 捕获阶段做时间门控：
         按下后 380ms 内的 touchmove 一律 stopPropagation（绝不 preventDefault），
         DragRecognizer 绑在 window 冒泡阶段因此收不到移动，浏览器按
         touch-action:pan-x 正常横滑；按住超过阈值才放行 touchmove 开始拖换位。
         只作用于竖屏角色列表；鼠标拖拽、点击选中、其他区域触摸完全不受影响。 */
      (function () {
        var LONG_PRESS_MS = 380;
        var LIST_SEL = '[class*="sprite-selector_items-wrapper_"]';
        var CARD_SEL = '[class*="sprite-selector_sprite-wrapper_"]';
        var tracking = false, armed = false, timer = null, card = null;
        function nodeOf(e) {
          var t = e.target;
          if (t && t.nodeType === 3) t = t.parentElement;
          return t && t.closest ? t : null;
        }
        function cleanup() {
          if (timer) { clearTimeout(timer); timer = null; }
          if (card) { card.classList.remove('nw-lp-armed'); card = null; }
          tracking = false; armed = false;
        }
        document.addEventListener('touchstart', function (e) {
          cleanup();
          if (!active() || (e.touches && e.touches.length > 1)) return;
          var t = nodeOf(e);
          if (!t || !t.closest(LIST_SEL)) return;
          tracking = true;
          card = t.closest(CARD_SEL);
          timer = setTimeout(function () {
            armed = true;
            if (card) card.classList.add('nw-lp-armed');
          }, LONG_PRESS_MS);
        }, {capture: true, passive: true});
        document.addEventListener('touchmove', function (e) {
          if (!tracking || armed || !active()) return;
          // 只掐断 JS 传播链，不 preventDefault：浏览器仍按 pan-x 正常横滑
          e.stopPropagation();
        }, {capture: true, passive: true});
        document.addEventListener('touchend', cleanup, {capture: true, passive: true});
        document.addEventListener('touchcancel', cleanup, {capture: true, passive: true});
      })();

      injectCss();
      refresh();

      // GUI 异步挂载：轮询等待 tab-list 与 Blockly workspace 就绪
      var booted = false;
      var bootTries = 0;
      var bootTimer = setInterval(function () {
        if (active()) {
          tidyMenuBar();
          ensureStageButton();
          ensureAddButtons();
          syncTabClass();
          if (!booted) booted = installBlockly();
        }
        if (++bootTries > 100) clearInterval(bootTimer);
      }, 300);
      // 长期保活：React 重建 tab-list / main-menu 后补回自定义按钮（轻量）
      setInterval(function () {
        if (active()) { tidyMenuBar(); ensureStageButton(); ensureAddButtons(); syncTabClass(); layoutFindBar(); }
      }, 1500);

      var rzTimer;
      function onResize() {
        clearTimeout(rzTimer);
        rzTimer = setTimeout(function () {
          refresh();
          if (active()) { tidyMenuBar(); ensureStageButton(); ensureAddButtons(); syncTabClass(); layoutFindBar(); reflowBlockly(); }
        }, 120);
      }
      window.addEventListener('resize', onResize);
      window.addEventListener('orientationchange', onResize);
      if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', onResize);
      }
    })();
  }

  /* ============================================================
   * 3.3 旋转自适应：竖屏 -> 横屏后按新视口重新布局
   * ============================================================ */
  (function () {
    var lastW = window.innerWidth, lastH = window.innerHeight;
    function relayout() {
      // 等待 WebView 完成新尺寸测量后再修正
      requestAnimationFrame(function () {
        var w = window.innerWidth, h = window.innerHeight;
        if (w === lastW && h === lastH) return;
        lastW = w; lastH = h;
        document.documentElement.style.width = '100%';
        document.documentElement.style.height = '100%';
        if (document.body) {
          document.body.style.width = '100%';
          document.body.style.height = '100%';
        }
        window.dispatchEvent(new Event('resize'));
        window.dispatchEvent(new Event('orientationchange'));
        // scratch-gui 的 GUI 包一层 root，强制触发一次重排
        var root = document.getElementById('app');
        if (root) { root.style.width = '100%'; root.style.height = '100%'; }
      });
    }
    window.addEventListener('orientationchange', function () { setTimeout(relayout, 60); });
    window.addEventListener('resize', function () { setTimeout(relayout, 60); });
  })();

  /* ============================================================
   * 4. PromptsPreload（编辑器/打包器共用）
   * ============================================================ */
  window.PromptsPreload = {
    alert: function (message) { window.alert(message); },
    confirm: function (message) { return window.confirm(message); }
  };

  /* ============================================================
   * 5. EditorPreload（编辑器窗口，最完整契约）
   * ============================================================ */
  var editorListeners = {
    stageDetached: null, stageReattached: null, detachedStageInput: null
  };

  if (WIN_TYPE === 'editor') {
    // 编辑器作为服务端：响应 hub 转发来的请求
    editorRequests['ai-tool'] = function (payload) {
      return new Promise(function (resolve) {
        pendingAI[payload.requestId] = resolve;
        fire('ai-tool-call', payload);
      });
    };
    editorRequests['project-json'] = function (payload) {
      return new Promise(function (resolve) {
        pendingProjectJSONReply[payload && payload.requestId] = resolve;
        fire('request-project-json', payload);
      });
    };
    editorRequests['sprite-library'] = function (payload) {
      return new Promise(function (resolve) {
        pendingSpriteLibrary[payload && payload.requestId] = resolve;
        fire('request-sprite-library', payload);
      });
    };
    editorRequests['sprite-stats'] = function (payload) {
      return new Promise(function (resolve) {
        pendingSpriteStats[payload && payload.requestId] = resolve;
        fire('request-sprite-stats', payload);
      });
    };
    editorRequests['theme'] = function (payload) {
      return new Promise(function (resolve) {
        pendingTheme[payload && payload.requestId] = resolve;
        fire('request-theme', payload);
      });
    };
    editorRequests['solo-export-project'] = function (payload) {
      return new Promise(function (resolve) {
        pendingSoloExport[payload && payload.requestId] = resolve;
        fire('solo-export-project', payload);
      });
    };
    editorRequests['export-project'] = function () {
      return exportForPackager();
    };
  }

  var pendingAI = Object.create(null);
  var pendingProjectJSONReply = Object.create(null);
  var pendingSpriteLibrary = Object.create(null);
  var pendingSpriteStats = Object.create(null);
  var pendingTheme = Object.create(null);
  var pendingSoloExport = Object.create(null);
  var exportForPackager = function () { return Promise.reject(new Error('exportForPackager missing')); };

  // 页面侧注册的编辑器回调（onRequestProjectJSON / onAIToolCall / ...）
  var deliverQueue = Object.create(null);
  function deliver(event, payload) {
    var list = deliverQueue[event];
    if (list) for (var i = 0; i < list.length; i++) {
      try { list[i](payload); } catch (e) { console.error(e); }
    }
  }

  function fire(name, payload) {
    deliver(name, payload);
  }

  window.EditorPreload = {
    isInitiallyFullscreen: function () { return sync('isInitiallyFullscreen') || false; },
    getInitialFile: function () { return inv('getInitialFile'); },
    getFile: function (id) {
      return inv('getFileInfo', { id: id }).then(function (info) {
        if (!info) throw new Error('File not found');
        if (info.error) return info;
        return readFileBytes(info.id).then(function (buf) {
          return { name: info.name, type: info.type, data: new Uint8Array(buf), isViewOnly: !!info.isViewOnly };
        });
      });
    },
    openedFile: function (id) { return inv('openedFile', { id: id }); },
    closedFile: function () { return inv('closedFile'); },
    showSaveFilePicker: function (suggestedName) {
      return inv('showSaveFilePicker', { suggestedName: suggestedName == null ? null : String(suggestedName) });
    },
    showOpenFilePicker: function () { return inv('showOpenFilePicker'); },
    setLocale: function (locale) {
      sync('setLocale', { locale: locale });
      return { strings: {} };
    },
    setChanged: function (changed) { return inv('setChanged', { changed: !!changed }); },
    // ---- 2.3.0 编辑器新增契约 ----
    getToolbarHeight: function () { return sync('getToolbarHeight') || 'normal'; },
    onToolbarHeightChanged: function (cb) { on('editor', 'toolbar-height-changed', cb); },
    getEditorLayout: function () { return sync('getEditorLayout') || 'horizontal'; },
    onEditorLayoutChanged: function (cb) { on('editor', 'editor-layout-changed', cb); },
    getShowSourceCodeButton: function () { var v = sync('getShowSourceCodeButton'); return v === null ? true : !!v; },
    onShowSourceCodeButtonChanged: function (cb) { on('editor', 'show-source-code-button-changed', cb); },
    getContextMenuGlass: function () { var v = sync('getContextMenuGlass'); return v === null ? false : !!v; },
    onContextMenuGlassChanged: function (cb) { on('editor', 'context-menu-glass-changed', cb); },
    onTopBarDeviceStatsChanged: function (cb) { on('editor', 'top-bar-device-stats-changed', cb); },
    getWindowControlsOverlayState: function () { return sync('getWindowControlsOverlayState') || { hasOverlay: false, compact: false }; },
    onCompactWindowStyleChanged: function (cb) { on('editor', 'compact-window-style-changed', cb); },
    sendTitleBarOverlayColor: function () { /* Android 无原生窗口覆盖层，忽略 */ },
    onOSFullscreenChanged: function (cb) { on('editor', 'os-fullscreen-changed', cb); },
    sendAIListenersReady: function () { return inv('aiListenersReady'); },
    onImportCostume: function (cb) { on('editor', 'import-costume', cb); },
    sendImportCostumeResult: function (data) { return inv('importCostumeResult', data || {}); },
    openNewWindow: function () { return inv('openWindow', { type: 'editor' }); },
    openAddonSettings: function (search) { return inv('openWindow', { type: 'addons', search: search || '' }); },
    openPackager: function () { return inv('openWindow', { type: 'packager' }); },
    showEncryptedSaveFilePicker: function (suggestedName) {
      return inv('showSaveFilePicker', { suggestedName: suggestedName == null ? null : String(suggestedName), encrypted: true });
    },
    encryptAndSave: function (fileId, data, password) {
      var bytes = data instanceof Uint8Array ? data : new Uint8Array(data);
      return uploadBytes('encryptSave', { fileId: fileId, password: password == null ? '' : String(password) }, bytes);
    },
    decryptNpnpFile: function (fileId, password) {
      return inv('decryptFile', { fileId: fileId, password: password == null ? '' : String(password), kind: 'npnp' })
        .then(function (res) {
          if (!res || res.error) return res;
          return readFileBytes(res.token).then(function (buf) {
            return { data: new Uint8Array(buf) };
          });
        });
    },
    showViewsb3SaveFilePicker: function (suggestedName) {
      return inv('showSaveFilePicker', { suggestedName: suggestedName == null ? null : String(suggestedName), viewsb3: true });
    },
    encryptAndSaveViewsb3: function (fileId, data) {
      var bytes = data instanceof Uint8Array ? data : new Uint8Array(data);
      return uploadBytes('encryptSaveViewsb3', { fileId: fileId }, bytes);
    },
    openDesktopSettings: function () { return inv('openWindow', { type: 'desktop-settings' }); },
    openPrivacy: function () { return inv('openWindow', { type: 'privacy' }); },
    openAbout: function () { return inv('openWindow', { type: 'about' }); },
    openContact: function () { return inv('openWindow', { type: 'contact' }); },
    getPreferredMediaDevices: function () { return inv('getPreferredMediaDevices'); },
    getAdvancedCustomizations: function () { return inv('getAdvancedCustomizations'); },
    setExportForPackager: function (callback) { exportForPackager = callback; },
    setIsFullScreen: function (isFullScreen) { return inv('setIsFullScreen', { isFullScreen: !!isFullScreen }); },
    getSystemStats: function () { return inv('getSystemStats'); },
    getAISystemInfo: function () { return inv('getAISystemInfo'); },
    detachStage: function () { return inv('detachStage'); },
    reattachStage: function () { return inv('reattachStage'); },
    sendStageFrame: function (dataURL) {
      if (!java) return;
      try { java.forwardToWindow('detached-stage', 'stage-frame', JSON.stringify(dataURL)); } catch (e) {}
    },
    onStageDetached: function (callback) { editorListeners.stageDetached = callback; },
    onStageReattached: function (callback) { editorListeners.stageReattached = callback; },
    onDetachedStageInput: function (callback) { editorListeners.detachedStageInput = callback; },
    getCodeAreaBackgroundImage: function () { return sync('getCodeAreaBackgroundImage'); },
    setCodeAreaBackgroundImage: function (imageData) { return inv('setSetting', { key: 'codeAreaBackgroundImage', value: imageData }); },
    getStageAreaBackgroundImage: function () { return sync('getStageAreaBackgroundImage'); },
    setStageAreaBackgroundImage: function (imageData) { return inv('setSetting', { key: 'stageAreaBackgroundImage', value: imageData }); },
    getTopBarDeviceStats: function () { return sync('getTopBarDeviceStats'); },
    getNeowarpExpands: function () { return inv('getNeowarpExpands'); },
    openAI: function () { return inv('openWindow', { type: 'ai-assistant' }); },
    openTodoList: function () { return inv('openWindow', { type: 'todo-list' }); },
    openProjectAnalysis: function () { return inv('openWindow', { type: 'project-analysis' }); },
    openTaskManager: function () { return inv('openWindow', { type: 'task-manager' }); },
    openMobilePreview: function () { return inv('openWindow', { type: 'mobile-preview' }); },
    onRequestProjectJSON: function (callback) { deliverQueue['request-project-json'] = [callback]; },
    sendProjectJSON: function (data) {
      // 兼容两种路径：hub 请求（requestId 关联）或直接推送
      for (var k in pendingProjectJSONReply) {
        pendingProjectJSONReply[k](data);
      }
      pendingProjectJSONReply = Object.create(null);
    },
    onApplyProject: function (callback) { deliverQueue['apply-project'] = [callback]; },
    onApplySprite: function (callback) { deliverQueue['apply-sprite'] = [callback]; },
    onRequestSpriteLibrary: function (callback) { deliverQueue['request-sprite-library'] = [callback]; },
    sendSpriteLibrary: function (data) { replyPending(pendingSpriteLibrary, data); },
    fetchImage: function (url) {
      return fetch('/__fetch/?u=' + encodeURIComponent(url))
        .then(function (r) { if (!r.ok) throw new Error('fetchImage failed ' + r.status); return r.arrayBuffer(); })
        .then(function (buf) { return new Uint8Array(buf); });
    },
    onAIToolCall: function (callback) { deliverQueue['ai-tool-call'] = [callback]; },
    sendAIToolResponse: function (data) {
      if (data && pendingAI[data.requestId]) { pendingAI[data.requestId](data); delete pendingAI[data.requestId]; }
    },
    onAddExtension: function (callback) { deliverQueue['extension-add-to-project'] = [callback]; },
    sendAddExtensionResult: function (data) { window.__nw.hubRespond(WIN_TYPE, data && data.requestId, JSON.stringify(data)); },
    openExtensionInEditor: function (payload) { return inv('openWindow', { type: 'extension-editor', payload: payload }); },
    onMyExtensionsUpsert: function (callback) { deliverQueue['my-extensions-upsert'] = [callback]; },
    sendMyExtensionsUpsertResult: function (data) { window.__nw.hubRespond(WIN_TYPE, data && data.requestId, JSON.stringify(data)); },
    onSoloExportProject: function (callback) { deliverQueue['solo-export-project'] = [callback]; },
    sendSoloExportProject: function (data) { replyPending(pendingSoloExport, data); },
    onSoloStageStream: function (callback) { deliverQueue['solo-stage-stream'] = [callback]; },
    sendSoloStageFrame: function (dataURL) {
      if (!java) return;
      try { java.forwardToWindow('solo-stage', 'solo-stage-window-frame', JSON.stringify(dataURL)); } catch (e) {}
    },
    sendSoloStageOverlays: function (data) {
      if (!java) return;
      try { java.forwardToWindow('solo-stage', 'solo-stage-window-overlays', JSON.stringify(data)); } catch (e) {}
    },
    onSoloStageAnswer: function (callback) { deliverQueue['solo-stage-answer'] = [callback]; },
    onSoloStageControl: function (callback) { deliverQueue['solo-stage-control'] = [callback]; },
    sendSoloStageControl: function (data) { window.__nw.hubRespond(WIN_TYPE, data && data.requestId, JSON.stringify(data)); },
    sendSoloStageRunStatus: function (data) {
      if (!java) return;
      try { java.forwardToWindow('solo-stage', 'solo-stage-window-run-status', JSON.stringify(data)); } catch (e) {}
    },
    onRequestTheme: function (callback) { deliverQueue['request-theme'] = [callback]; },
    sendTheme: function (data) { replyPending(pendingTheme, data); },
    notifyThemeChanged: function (theme) { return inv('notifyThemeChanged', { theme: theme }); },
    getGlobalUITheme: function () { return inv('getGlobalUITheme'); },
    onGlobalUIThemeChanged: function (callback) { on('editor', 'global-ui-theme-changed', callback); },
    onRequestSpriteStats: function (callback) { deliverQueue['request-sprite-stats'] = [callback]; },
    sendSpriteStats: function (data) { replyPending(pendingSpriteStats, data); },
    removeAllAIListeners: function () {
      ['request-project-json', 'apply-project', 'apply-sprite', 'ai-tool-call', 'request-theme'].forEach(function (k) {
        deliverQueue[k] = [];
      });
      pendingAI = Object.create(null);
    },
    openCollaborationHost: function () { return inv('openWindow', { type: 'collaboration', payload: { mode: 'host' } }); },
    openCollaborationJoin: function () { return inv('openWindow', { type: 'collaboration', payload: { mode: 'join' } }); },
    endCollaboration: function () { return inv('collabEnd'); },
    leaveCollaboration: function () { return inv('collabEnd'); },
    openCollaborationChat: function () { return inv('openWindow', { type: 'collaboration', payload: { mode: 'chat' } }); },
    onCollaborationStateChange: function (callback) { on('editor', 'collaboration-state-changed', callback); },
    onCollabRequestProjectJSON: function (callback) { deliverQueue['collab-request-project-json'] = [callback]; },
    sendCollabProjectJSON: function (project, targetUsername) { return inv('collabSend', { project: project, targetUsername: targetUsername }); },
    onCollabProjectUpdate: function (callback) { on('editor', 'collab-project-update', callback); },
    sendCollabProjectUpdate: function (project) { return inv('collabSend', { project: project }); }
  };

  function replyPending(map, data) {
    var keys = Object.keys(map);
    if (!keys.length) return;
    // Electron 里响应带 requestId（data.requestId）以并发去串台
    var rid = data && data.requestId;
    if (rid != null && map[rid]) {
      map[rid](data); delete map[rid];
    } else if (keys.length === 1) {
      map[keys[0]](data); delete map[keys[0]];
    } else {
      for (var k in map) { try { map[k](data); } catch (e) {} }
      // 全部清空避免泄漏
      for (var k2 in map) delete map[k2];
    }
  }

  // editor 窗口被动接收的 Java 事件
  on('editor', 'stage-detached', function () { if (editorListeners.stageDetached) editorListeners.stageDetached(); });
  on('editor', 'stage-reattached', function () { if (editorListeners.stageReattached) editorListeners.stageReattached(); });
  on('editor', 'detached-stage-input', function (d) { if (editorListeners.detachedStageInput) editorListeners.detachedStageInput(d); });

  /* ============================================================
   * 6. HomePreload
   * ============================================================ */
  if (WIN_TYPE === 'home') {
    window.HomePreload = {
      // 兜底对象：sync 失败也不让页面初始化脚本崩掉
      getInfo: function () { var v = sync('homeGetInfo'); return v || { locale: 'zh-CN', version: '0.0.1' }; },
      getAvailableUpdate: function () { return inv('getAvailableUpdate'); },
      onUpdateAvailable: function (callback) { on('home', 'update-available', callback); },
      getTheme: function () { return inv('getTheme'); },
      themeApplied: function (theme) { return inv('themeApplied', { theme: theme }); },
      onThemeChanged: function (callback) { on('home', 'theme-changed', callback); },
      onBrandingChanged: function (callback) { on('home', 'branding-changed', callback); },
      newScratchProject: function () { return inv('openWindow', { type: 'editor' }); },
      newExtension: function () { return inv('openWindow', { type: 'extension-editor' }); },
      openFromFile: function () { return inv('homeOpenFile'); },
      openCollabJoin: function () { return inv('openWindow', { type: 'collaboration', payload: { mode: 'join' } }); },
      openCollabHostNew: function () { return inv('openWindow', { type: 'collaboration', payload: { mode: 'host' } }); },
      // 桌面版流程：先选 sb3 文件，再以该文件发起协作
      openCollabHostFromFile: function () { return inv('collabOpenSb3'); },
      openImageEditor: function () { return inv('openWindow', { type: 'image-editor' }); },
      openSettings: function () { return inv('openWindow', { type: 'desktop-settings' }); },
      openSolo: function () { return inv('openWindow', { type: 'ai-assistant', payload: { solo: true } }); },
      getRecentProjects: function () { return inv('getRecentProjects'); },
      openRecentProject: function (filePath) { return inv('openRecentProject', { path: filePath }); },
      onRecentChanged: function (callback) { on('home', 'recent-changed', callback); }
    };
  }

  /* ============================================================
   * 6.1 OnboardingPreload（2.3.0 新增引导页）
   * ============================================================ */
  if (WIN_TYPE === 'onboarding') {
    window.OnboardingPreload = {
      getInfo: function () {
        var v = sync('onboardingGetInfo');
        return v || { locale: 'zh-CN', uiTheme: 'system', version: '0.0.1', logo: null };
      },
      getTheme: function () { return inv('getTheme'); },
      themeApplied: function (theme) { return inv('themeApplied', { theme: theme }); },
      onThemeChanged: function (callback) { on('onboarding', 'theme-changed', callback); },
      setLocale: function (locale) { sync('setLocale', { locale: locale }); return inv('applyLocale', { locale: locale }); },
      setUiTheme: function (theme) { return inv('setUITheme', { value: theme }); },
      getModelConfigs: function () { return inv('getAiModelConfigs'); },
      saveModelConfigs: function (payload) { return inv('saveAiModelConfigs', { payload: payload }); },
      done: function () { return inv('onboardingDone'); }
    };
  }

  /* ============================================================
   * 7. 其余窗口 Preload
   * ============================================================ */
  if (WIN_TYPE === 'about') {
    window.AboutPreload = { getInfo: function () { var v = sync('aboutGetInfo'); return v || { version: '0.0.1', electron: '', chrome: '' }; } };
  }

  if (WIN_TYPE === 'privacy') {
    window.PrivacyPreload = {
      isUpdateCheckerAllowed: function () { return sync('isUpdateCheckerAllowed') || false; },
      openDesktopSettings: function () { return inv('openWindow', { type: 'desktop-settings' }); }
    };
  }

  if (WIN_TYPE === 'migrate') {
    window.MigratePreload = {
      getInfo: function () { return sync('migrateGetInfo'); },
      done: function () { return inv('migrateDone'); },
      continueAnyways: function () { return inv('migrateContinue'); }
    };
  }

  if (WIN_TYPE === 'contact') {
    window.ContactPreload = {
      getTheme: function () { return inv('getTheme'); },
      onThemeChanged: function (callback) { on('contact', 'theme-changed', callback); }
    };
  }

  if (WIN_TYPE === 'image-editor') {
    window.ImageEditorPreload = {
      getTheme: function () { return inv('getTheme'); },
      themeApplied: function (theme) { return inv('themeApplied', { theme: theme }); },
      openFile: function () { return inv('imageEditorOpenFile'); },
      saveFile: function (payload) {
        var data = payload && (payload.dataURL || payload.data || payload);
        if (typeof data === 'string' && data.length > 4096) {
          return uploadBytes('imageEditorSave', { isDataURL: true }, encBytes(data)).then(function () { return inv('imageEditorSaveFinish'); });
        }
        return inv('imageEditorSave', { payload: payload });
      },
      // 2.3.0「导出到 Scratch 项目」：列出已打开编辑器、把图片导入为造型
      listProjects: function () { return inv('imageEditorListProjects'); },
      importToProject: function (payload) { return inv('imageEditorImportToProject', payload || {}); },
      onProjectsChanged: function (callback) { on('image-editor', 'projects-changed', callback); }
    };
  }

  function encBytes(str) {
    var utf8 = unescape(encodeURIComponent(str));
    var u8 = new Uint8Array(utf8.length);
    for (var i = 0; i < utf8.length; i++) u8[i] = utf8.charCodeAt(i);
    return u8;
  }

  if (WIN_TYPE === 'security-prompt') {
    window.SecurityPromptPreload = {
      init: function () { return sync('securityInit'); },
      ready: function (options) { return inv('securityReady', { options: options }); },
      allow: function () { return inv('securityDone', { allowed: true }); },
      deny: function () { return inv('securityDone', { allowed: false }); }
    };
  }

  if (WIN_TYPE === 'update') {
    window.UpdatePreload = {
      getStrings: function () { return sync('updateGetStrings'); },
      getInfo: function () { return sync('updateGetInfo'); },
      download: function () { return inv('updateDownload'); },
      ignore: function (permanently) { return inv('updateIgnore', { permanently: !!permanently }); }
    };
  }

  if (WIN_TYPE === 'packager') {
    window.GlobalPackagerImporter = function () {
      return inv('requestEditorExport')
        .then(function (res) {
          if (!res || res.error) throw new Error('Failed to import');
          return readFileBytes(res.token).then(function (buf) {
            return { name: res.name, data: new Uint8Array(buf) };
          });
        });
    };
    window.IsDesktop = true;
  }

  if (WIN_TYPE === 'addons') {
    window.AddonsPreload = { exportSettings: function (settings) { return inv('addonsExportSettings', { settings: settings }); } };
  }

  if (WIN_TYPE === 'mobile-preview') {
    window.MobilePreviewPreload = {
      start: function () { return inv('mobilePreviewStart'); },
      stop: function () { return inv('mobilePreviewStop'); },
      getQRCode: function (url) { return inv('mobilePreviewQR', { url: url }); },
      copyText: function (text) { return inv('mobilePreviewCopy', { text: String(text) }); },
      getTheme: function () { return inv('getTheme'); },
      onThemeChanged: function (callback) { on('mobile-preview', 'theme-changed', callback); }
    };
  }

  if (WIN_TYPE === 'file-access') {
    window.FileAccessPreload = {
      init: function () { return sync('fileAccessInit'); },
      onNewPath: function (callback) { on('file-access', 'new-path', callback); }
    };
  }

  if (WIN_TYPE === 'project-analysis') {
    window.ProjectAnalysisPreload = {
      getProjectData: function () { return inv('requestEditorProjectData'); },
      getTheme: function () { return inv('getTheme'); },
      closeWindow: function () { return inv('closeWindow'); },
      saveImage: function (dataUrl) {
        return uploadBytes('saveImage', {}, encBytes(dataUrl)).then(function () { return inv('saveImageFinish'); });
      },
      onThemeChanged: function (callback) { on('project-analysis', 'theme-changed', callback); },
      removeThemeListener: function () { off('project-analysis', 'theme-changed'); }
    };
  }

  if (WIN_TYPE === 'task-manager') {
    window.TaskManagerPreload = {
      getSystemStats: function () { return inv('getSystemStats'); },
      getSpriteStats: function () { return inv('requestEditorSpriteStats'); },
      getTheme: function () { return inv('getTheme'); },
      closeWindow: function () { return inv('closeWindow'); },
      onThemeChanged: function (callback) { on('task-manager', 'theme-changed', callback); },
      removeThemeListener: function () { off('task-manager', 'theme-changed'); }
    };
  }

  if (WIN_TYPE === 'todo-list') {
    window.TodoListPreload = {
      getTheme: function () { return inv('getTheme'); },
      closeWindow: function () { return inv('closeWindow'); },
      onThemeChanged: function (callback) { on('todo-list', 'theme-changed', callback); },
      removeThemeListener: function () { off('todo-list', 'theme-changed'); },
      setReminder: function (todoId, todoText, reminderTime) { return inv('todoSetReminder', { todoId: todoId, todoText: todoText, reminderTime: reminderTime }); },
      cancelReminder: function (todoId) { return inv('todoCancelReminder', { todoId: todoId }); },
      getReminders: function () { return inv('todoGetReminders'); },
      onReminderTriggered: function (callback) { on('todo-list', 'reminder-triggered', callback); },
      removeReminderTriggeredListener: function () { off('todo-list', 'reminder-triggered'); }
    };
  }

  if (WIN_TYPE === 'desktop-settings') {
    window.DesktopSettingsPreload = {
      init: function () { return sync('settingsInit'); },
      setUpdateChecker: function (v) { return inv('setSetting', { key: 'updateChecker', value: v }); },
      setUITheme: function (v) { return inv('setUITheme', { value: v }); },
      checkForUpdates: function () { return inv('checkForUpdates'); },
      enumerateMediaDevices: function () { return inv('enumerateMediaDevices'); },
      setMicrophone: function (v) { return inv('setSetting', { key: 'microphone', value: v }); },
      setCamera: function (v) { return inv('setSetting', { key: 'camera', value: v }); },
      setHardwareAcceleration: function (v) { return inv('setSetting', { key: 'hardwareAcceleration', value: v }); },
      setBackgroundThrottling: function (v) { return inv('setBackgroundThrottling', { value: v }); },
      setBypassCORS: function (v) { return inv('setSetting', { key: 'bypassCORS', value: v }); },
      setSpellchecker: function (v) { return inv('setSetting', { key: 'spellchecker', value: v }); },
      setExitFullscreenOnEscape: function (v) { return inv('setSetting', { key: 'exitFullscreenOnEscape', value: v }); },
      setRichPresence: function (v) { return inv('setSetting', { key: 'richPresence', value: v }); },
      openUserData: function () { return inv('openUserData'); },
      setCodeAreaBackgroundImage: function (v) { return inv('setSetting', { key: 'codeAreaBackgroundImage', value: v }); },
      setStageAreaBackgroundImage: function (v) { return inv('setSetting', { key: 'stageAreaBackgroundImage', value: v }); },
      setTopBarDeviceStats: function (v) { return inv('setSetting', { key: 'topBarDeviceStats', value: v }); },
      // 2.3.0 新增设置项
      setToolbarHeight: function (v) { return inv('setSetting', { key: 'toolbarHeight', value: v }); },
      setCompactWindowStyle: function (v) { return inv('setSetting', { key: 'compactWindowStyle', value: v }); },
      setContextMenuGlass: function (v) { return inv('setSetting', { key: 'contextMenuGlass', value: v }); },
      setShowSourceCodeButton: function (v) { return inv('setSetting', { key: 'showSourceCodeButton', value: v }); },
      setEditorLayout: function (v) { return inv('setSetting', { key: 'editorLayout', value: v }); },
      setLocale: function (v) { sync('setLocale', { locale: v }); return inv('applyLocale', { locale: v }); },
      getFileAssociations: function () { return inv('getFileAssociations'); },
      setFileAssociation: function (ext, enabled) { return inv('setFileAssociation', { ext: ext, enabled: enabled }); },
      setHomeLogo: function (v) { return inv('setBranding', { key: 'homeLogo', value: v }); },
      setHomeLogoText: function (v) { return inv('setBranding', { key: 'homeLogoText', value: v }); },
      setHomeBackground: function (v) { return inv('setBranding', { key: 'homeBackground', value: v }); },
      resetHomeBranding: function () { return inv('resetHomeBranding'); },
      setShowRecentProjects: function (v) { return inv('setSetting', { key: 'showRecentProjects', value: v }); },
      getDefaultLogo: function () { return inv('getDefaultLogo'); },
      getAiModelConfigs: function () { return inv('getAiModelConfigs'); },
      saveAiModelConfigs: function (payload) { return inv('saveAiModelConfigs', { payload: payload }); },
      onAiModelConfigsChanged: function (callback) { on('settings', 'ai-model-configs-changed', callback); },
      getTheme: function () { return inv('getTheme'); },
      onThemeChanged: function (callback) { on('desktop-settings', 'theme-changed', callback); }
    };
  }

  if (WIN_TYPE === 'ai-assistant' || WIN_TYPE === 'solo') {
    var AIPreload = {
      getProjectCode: function () { return inv('requestEditorProjectCode'); },
      applyProject: function (projectJSON) { return inv('editorApplyProject', { projectJSON: projectJSON }); },
      applySprite: function (spriteJSON, targetId) { return inv('editorApplySprite', { spriteJSON: spriteJSON, targetId: targetId }); },
      getSpriteLibrary: function () { return inv('requestEditorSpriteLibrary'); },
      getModelConfigs: function () { return inv('getAiModelConfigs'); },
      saveModelConfigs: function (payload) { return inv('saveAiModelConfigs', { payload: payload }); },
      // 2.3.0 AI 工作流设置
      getSettings: function () { return inv('aiGetSettings'); },
      saveSettings: function (payload) { return inv('aiSaveSettings', payload || {}); },
      getWorkflowSettings: function () { return inv('aiGetWorkflowSettings'); },
      saveWorkflowSettings: function (payload) { return inv('aiSaveWorkflowSettings', payload || {}); },
      pickWorkflowDocsDir: function () { return inv('aiPickWorkflowDocsDir'); },
      saveWorkflowDoc: function (payload) { return inv('aiSaveWorkflowDoc', payload || {}); },
      openWorkflowDocsDir: function () { return inv('aiOpenWorkflowDocsDir'); },
      onWorkflowSettingsChanged: function (callback) { on('ai', 'workflow-settings-changed', callback); },
      openDesktopSettings: function () { return inv('openWindow', { type: 'desktop-settings' }); },
      callTool: function (toolName, params) { return inv('requestEditorAITool', { toolName: toolName, params: params }); },
      webSearch: function (query) { return inv('webSearch', { query: query }); },
      getTheme: function () { return inv('getTheme'); },
      getLocale: function () { return inv('getLocale'); },
      closeWindow: function () { return inv('closeWindow'); },
      getPhoneLink: function () { return inv('getPhoneLink'); },
      phoneSyncBroadcast: function (payload) { return inv('phoneSyncBroadcast', { payload: payload }); },
      onPhoneClientsChanged: function (callback) { on('ai', 'phone-clients', callback); },
      onProjectCodeResponse: function (callback) { on('ai', 'project-code-response', callback); },
      onThemeChanged: function (callback) { on('ai', 'theme-changed', callback); },
      onAiModelConfigsChanged: function (callback) { on('settings', 'ai-model-configs-changed', callback); },
      onLocaleChanged: function (callback) { on('ai', 'locale-changed', callback); },
      removeThemeListener: function () { off('ai', 'theme-changed'); },
      removeLocaleListener: function () { off('ai', 'locale-changed'); }
    };
    window.AIAssistantPreload = AIPreload;
    if (WIN_TYPE === 'solo') {
      window.SoloPreload = {
        getState: function () { return inv('soloGetState'); },
        pickProject: function (type) { return inv('soloPickProject', { type: type }); },
        saveProject: function () { return inv('soloSaveProject'); },
        openStageWindow: function () { return inv('openWindow', { type: 'solo-stage' }); },
        onProjectChanged: function (callback) { on('solo', 'project-changed', callback); },
        onStageFrame: function (callback) { on('solo', 'stage-frame', callback); },
        removeStageFrameListener: function () { off('solo', 'stage-frame'); }
      };
    }
  }

  if (WIN_TYPE === 'collaboration') {
    window.CollaborationPreload = {
      getMode: function () { return inv('collabGetMode'); },
      getTheme: function () { return inv('getTheme'); },
      getLocalIP: function () { return inv('collabGetLocalIP'); },
      discoverSessions: function (port) { return inv('collabDiscover', { port: port }); },
      startHost: function (password, port, permissions, nickname, avatar) { return inv('collabStartHost', { password: password, port: port, permissions: permissions, nickname: nickname, avatar: avatar }); },
      endHost: function () { return inv('collabEnd'); },
      joinConnect: function (ip, port, password, nickname, avatar) { return inv('collabJoin', { ip: ip, port: port, password: password, nickname: nickname, avatar: avatar }); },
      leave: function () { return inv('collabEnd'); },
      sendMessage: function (text) { return inv('collabChat', { text: text }); },
      closeWindow: function () { return inv('closeWindow'); },
      onHostStarted: function (callback) { on('collab', 'host-started', callback); },
      onJoinConnected: function (callback) { on('collab', 'join-connected', callback); },
      onChatMessage: function (callback) { on('collab', 'chat-message', callback); },
      onClientJoin: function (callback) { on('collab', 'client-join', callback); },
      onClientLeave: function (callback) { on('collab', 'client-leave', callback); },
      onMemberJoined: function (callback) { on('collab', 'member-joined', callback); },
      onMemberLeft: function (callback) { on('collab', 'member-left', callback); },
      onCollaborationEnded: function (callback) { on('collab', 'collaboration-ended', callback); },
      onFocusChat: function (callback) { on('collab', 'focus-chat', callback); },
      onEndRequested: function (callback) { on('collab', 'end-requested', callback); },
      onLeaveRequested: function (callback) { on('collab', 'leave-requested', callback); },
      onThemeChanged: function (callback) { on('collab', 'theme-changed', callback); }
    };
  }

  if (WIN_TYPE === 'extension-editor') {
    window.ExtensionEditorPreload = {
      getInitial: function () { return inv('extGetInitial'); },
      save: function (path, content) { return inv('extSave', { path: path, content: content }); },
      saveAs: function (suggestedName, content) { return inv('extSaveAs', { suggestedName: suggestedName, content: content }); },
      openDialog: function () { return inv('extOpenDialog'); },
      confirmClose: function (fileName) { return inv('extConfirmClose', { fileName: fileName }); },
      rename: function (path, newName) { return inv('extRename', { path: path, newName: newName }); },
      listProjects: function () { return inv('extListProjects'); },
      addToProject: function (targetId, name, code) { return inv('extAddToProject', { targetId: targetId, name: name, code: code }); },
      onProjectsChanged: function (callback) { on('ext', 'projects-changed', callback); },
      addToMyExtensions: function (id, name, code) { return inv('extAddToMyExtensions', { id: id, name: name, code: code }); },
      onOpenMemoryFiles: function (callback) { on('ext', 'open-memory-file', callback); },
      onOpenPaths: function (callback) { on('ext', 'open-paths', callback); },
      getAiModelConfigs: function () { return inv('getAiModelConfigs'); },
      saveAiModelConfigs: function (payload) { return inv('saveAiModelConfigs', { payload: payload }); },
      onAiModelConfigsChanged: function (callback) { on('settings', 'ai-model-configs-changed', callback); },
      openDesktopSettings: function () { return inv('openWindow', { type: 'desktop-settings' }); },
      popoutAi: function (state) { return inv('openWindow', { type: 'ai-assistant', payload: { popout: true, state: state } }); },
      onPopoutClosed: function (callback) { on('ext', 'ai-popout-closed', callback); },
      onEditorRequest: function (callback) { on('ext', 'editor-request', callback); },
      sendEditorResponse: function (requestId, result) { window.__nw.hubRespond(WIN_TYPE, requestId, JSON.stringify(result)); },
      getPopoutInitial: function () { return inv('extGetPopoutInitial'); },
      onPopoutInit: function (callback) { on('ext', 'popout-init', callback); },
      popoutGetCode: function () { return inv('extPopoutGetCode'); },
      popoutInsertCode: function (code) { return inv('extPopoutInsertCode', { code: code }); },
      popoutReplaceCode: function (code) { return inv('extPopoutReplaceCode', { code: code }); },
      popoutGetAiModelConfigs: function () { return inv('getAiModelConfigs'); },
      popoutSaveAiModelConfigs: function (payload) { return inv('saveAiModelConfigs', { payload: payload }); },
      reportPopoutState: function (state) { return inv('extReportPopoutState', { state: state }); },
      dockBack: function () { return inv('extDockBack'); }
    };
  }

  if (WIN_TYPE === 'detached-stage') {
    var dsListeners = {};
    window.DetachedStagePreload = {
      onFrame: function (cb2) { dsListeners.frame = cb2; },
      onClose: function (cb2) { dsListeners.close = cb2; },
      onDimensions: function (cb2) { dsListeners.dimensions = cb2; },
      sendInput: function (inputData) { return inv('detachedStageInput', { inputData: inputData }); },
      ready: function () { return inv('detachedStageReady'); }
    };
    on('detached-stage', 'stage-frame', function (d) { if (dsListeners.frame) dsListeners.frame(typeof d === 'string' ? d : d && d.value); });
    on('detached-stage', 'close', function () { if (dsListeners.close) dsListeners.close(); });
    on('detached-stage', 'dimensions', function (d) { if (dsListeners.dimensions) dsListeners.dimensions(d); });
  }

  if (WIN_TYPE === 'solo-stage') {
    var ssListeners = {};
    window.SoloStagePreload = {
      onFrame: function (cb2) { ssListeners.frame = cb2; },
      onRunStatus: function (cb2) { ssListeners.runStatus = cb2; },
      onState: function (cb2) { ssListeners.state = cb2; },
      onOverlays: function (cb2) { ssListeners.overlays = cb2; },
      sendControl: function (action) { return inv('soloStageControl', { action: action }); },
      sendAnswer: function (text) { return inv('soloStageAnswer', { text: String(text == null ? '' : text) }); },
      ready: function () { return inv('soloStageReady'); }
    };
    on('solo-stage', 'solo-stage-window-frame', function (d) { if (ssListeners.frame) ssListeners.frame(typeof d === 'string' ? d : d && d.value); });
    on('solo-stage', 'solo-stage-window-run-status', function (d) { if (ssListeners.runStatus) ssListeners.runStatus(d); });
    on('solo-stage', 'solo-stage-window-state', function (d) { if (ssListeners.state) ssListeners.state(d); });
    on('solo-stage', 'solo-stage-window-overlays', function (d) { if (ssListeners.overlays) ssListeners.overlays(d); });
  }
})();
