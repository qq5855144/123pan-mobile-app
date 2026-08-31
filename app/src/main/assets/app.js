/* 123云盘移动端 SPA 逻辑
 * 通过 NativeBridge 调用原生网络层（复刻 123pan-open API）
 * 底部导航：文件 / 传输 / 我的
 */
(function () {
  'use strict';

  var bridge = window.NativeBridge;
  var _currentList = [];   // 当前文件列表项（多选 toggle 时按 FileId 精准刷新用）
  var state = {
    token: '',
    user: '',
    view: 'files',
    currentDir: 0,          // 当前文件夹 parentFileId（0 = 根目录）
    breadcrumb: [],          // [{id, name}]
    currentItem: null,        // 操作浮层对应的文件对象
    shareItem: null,          // 正在配置分享的文件对象
    confirmOk: null,          // 自定义确认弹窗的确定回调
    qrTimer: null,            // 二维码轮询定时器
    qrUniID: '',              // 当前二维码的 uniID
    qrTimeout: null,          // 二维码过期定时器
    qrPaused: false,          // App 在后台时 true，暂停轮询
    qrExpired: false,          // 二维码是否已过期
    transfers: loadTransfers(), // 下载任务列表 [{name,size,status,time}]
    progTimer: null,          // 下载进度轮询定时器
    searching: false,         // 是否处于全局搜索态
    searchKeyword: '',        // 当前搜索关键词
    searchTotal: 0,           // 搜索命中总数
    selectMode: false,        // 是否处于多选（整理）模式
    selectedMap: {},          // 多选模式下选中的文件/文件夹 fileId -> item
    pickerState: null         // 文件夹选择器状态 {dir, path:[{id,name}]}
  };

  var API = {
    list: 'https://api.123pan.cn/b/api/file/list/new',
    rename: 'https://api.123pan.cn/a/api/file/rename',
    trash: 'https://api.123pan.cn/a/api/file/trash',      // 移入回收站 / 从回收站恢复
    trashDeleteAll: 'https://api.123pan.cn/a/api/file/trash_delete_all', // 清空回收站
    trashDelete: 'https://api.123pan.cn/a/api/file/delete', // 从回收站彻底删除单个
    download: 'https://api.123pan.cn/a/api/file/download_info',      // 文件
    batchDownload: 'https://api.123pan.cn/a/api/file/batch_download_info', // 文件夹
    mkdir: 'https://api.123pan.cn/a/api/file/upload_request',        // 新建文件夹（123pan 用 upload_request 创建文件夹）
    userInfo: 'https://api.123pan.cn/b/api/user/info',
    shareCreate: 'https://api.123pan.cn/a/api/share/create',          // 创建分享（123pan 原生分享）
    move: 'https://api.123pan.cn/b/api/file/mod_pid',                // 移动文件/文件夹到指定目录
    signIn: 'https://login.123pan.com/b/api/user/sign_in',
    qrGenerate: 'https://login.123pan.com/api/user/qr-code/generate',
    qrResult: 'https://login.123pan.com/api/user/qr-code/result',
    // 验证码（短信）登录接口（走 user.123pan.cn 域）
    getVcode: 'https://user.123pan.cn/api/user/get_vcode',      // 获取短信验证码
    vcodeSignIn: 'https://user.123pan.cn/api/user/sign_in'      // 验证码登录（type:3）
  };
  // 回收站操作 event 值（统一走 POST /a/api/file/trash，通过 event 区分）
  var RECYCLE_EVENT = {
    restore: 'recycleRestore', // 从回收站恢复
    clear: 'recycleClear',     // 清空回收站
    deleteP: 'recycleDelete'   // 从回收站彻底删除
  };

  // ---------- 工具 ----------
  function $(id) { return document.getElementById(id); }
  function show(el) { if (el) el.classList.remove('hidden'); }
  function hide(el) { if (el) el.classList.add('hidden'); }
  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;')
      .replace(/>/g, '&gt;').replace(/"/g, '"');
  }
  function fmtSize(b) {
    if (b == null) return '';
    b = Number(b);
    if (b < 1024) return b + ' B';
    if (b < 1048576) return (b / 1024).toFixed(1) + ' KB';
    if (b < 1073741824) return (b / 1048576).toFixed(1) + ' MB';
    return (b / 1073741824).toFixed(2) + ' GB';
  }
  // 从对象中按候选字段名顺序取第一个有效数值（转为非负整数，取不到返回 0）
  function numOf(obj) {
    for (var i = 1; i < arguments.length; i++) {
      var v = obj && obj[arguments[i]];
      if (v != null) {
        var n = Number(v);
        if (!isNaN(n) && n > 0) return n;
      }
    }
    return 0;
  }
  function iconFor(item) {
    if (item && (item.Type === 1 || item.Type === '1')) return 'folder';
    return iconForName(item && (item.FileName || item.fileName));
  }
  // 根据文件名扩展名判断文件类型图标（传输列表也复用此逻辑）
  function iconForName(fname) {
    var ext = (fname || '').split('.').pop().toLowerCase();
    var img = { jpg:1, jpeg:1, png:1, gif:1, webp:1, bmp:1, heic:1 };
    var vid = { mp4:1, mkv:1, avi:1, mov:1, rmvb:1, flv:1, wmv:1, webm:1, ts:1 };
    var aud = { mp3:1, wav:1, flac:1, aac:1, ogg:1, m4a:1, ape:1 };
    var arc = { zip:1, rar:1, '7z':1, tar:1, gz:1, bz2:1, xz:1, iso:1, apk:0 };
    var tab = { xls:1, xlsx:1, ppt:1, pptx:1, doc:1, docx:1, pdf:1 };
    if (img[ext]) return 'image';
    if (vid[ext]) return 'video';
    if (aud[ext]) return 'audio';
    if (ext === 'apk') return 'apk';
    if (arc[ext]) return 'archive';
    if (tab[ext]) return 'table';
    if (ext === 'txt') return 'text';
    if (ext === 'js' || ext === 'json' || ext === 'html' || ext === 'css' || ext === 'java' || ext === 'py' || ext === 'xml' || ext === 'sh') return 'code';
    return 'doc';
  }

  // 图标内联 SVG 内容映射（不依赖 <use> 引用外部 symbol，规避部分 WebView 无法渲染 use 图标的问题）
  var ICON_SVG = {
    upload: '<path d="M4 17v2a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-2M12 3v12M7 8l5-5 5 5" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>',
    'folder-plus': '<path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2zM12 11v6M9 14h6" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>',
    folder: '<path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round"/>',
    'arrow-down': '<path d="M12 3v12M6 9l6 6 6-6" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>',
    user: '<path d="M20 21a8 8 0 0 0-16 0M12 13a5 5 0 1 0 0-10 5 5 0 0 0 0 10z" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>',
    download: '<path d="M12 3v12M6 11l6 6 6-6M4 21h16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>',
    rename: '<path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7M18.5 2.5a2.1 2.1 0 0 1 3 3L12 15l-4 1 1-4z" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>',
    trash: '<path d="M3 6h18M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2m3 0v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6zM10 11v6M14 11v6" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>',
    share: '<path d="M4 12v8a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-8M16 6l-4-4-4 4M12 2v13" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>',
    copy: '<rect x="9" y="9" width="13" height="13" rx="2" fill="none" stroke="currentColor" stroke-width="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>',
    hdd: '<path d="M3 13v3a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-3M3 13l2.5-7A2 2 0 0 1 7.4 5h9.2a2 2 0 0 1 1.9 1.4L21 13M3 13h18" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round"/><path d="M8 17h8" stroke="currentColor" stroke-width="2" stroke-linecap="round" fill="none"/>',
    info: '<circle cx="12" cy="12" r="10" fill="none" stroke="currentColor" stroke-width="2"/><path d="M12 16v-4M12 8h.01" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>',
    doc: '<path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round"/><path d="M14 2v6h6M8 13h8M8 17h8" stroke="currentColor" stroke-width="2" stroke-linecap="round" fill="none"/>',
    image: '<rect x="3" y="3" width="18" height="18" rx="2" fill="none" stroke="currentColor" stroke-width="2"/><circle cx="8.5" cy="8.5" r="1.5" fill="currentColor"/><path d="M21 15l-5-5L5 21" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>',
    video: '<rect x="2" y="6" width="14" height="12" rx="2" fill="none" stroke="currentColor" stroke-width="2"/><path d="M16 10l6-4v12l-6-4" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round"/>',
    audio: '<path d="M9 18V5l12-2v13" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round"/><circle cx="6" cy="18" r="3" fill="none" stroke="currentColor" stroke-width="2"/><circle cx="18" cy="16" r="3" fill="none" stroke="currentColor" stroke-width="2"/>',
    archive: '<path d="M21 8l-9-5-9 5 9 5 9-5z" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round"/><path d="M3 8v8l9 5 9-5V8M12 13v8" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round"/>',
    table: '<rect x="3" y="3" width="18" height="18" rx="2" fill="none" stroke="currentColor" stroke-width="2"/><path d="M3 9h18M3 15h18M9 3v18" fill="none" stroke="currentColor" stroke-width="2"/>',
    text: '<path d="M4 6V4h16v2M12 4v16M9 20h6" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>',
    code: '<path d="M8 6l-6 6 6 6M16 6l6 6-6 6" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>',
    apk: '<circle cx="5.5" cy="10.5" r="1.5" fill="currentColor" stroke="none"/><circle cx="18.5" cy="10.5" r="1.5" fill="currentColor" stroke="none"/><path d="M6.5 7h11a4 4 0 0 1 4 4v4.5a3 3 0 0 1-3 3H5.5a3 3 0 0 1-3-3V11a4 4 0 0 1 4-4z" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round"/><path d="M7.7 6V3.8M16.3 6V3.8" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>',
    search: '<circle cx="11" cy="11" r="7" fill="none" stroke="currentColor" stroke-width="2"/><path d="M21 21l-4.3-4.3" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>',
    'x-circle': '<circle cx="12" cy="12" r="10" fill="none" stroke="currentColor" stroke-width="2"/><path d="M15 9l-6 6M9 9l6 6" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>',
    'folder-move': '<path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round"/><path d="M8 13h6M11 10l-3 3 3 3" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>'
  };
  function applySvg(el, name) {
    var inner = ICON_SVG[name];
    if (!inner) { el.innerHTML = ''; return; }
    el.innerHTML = '<svg viewBox="0 0 24 24" aria-hidden="true">' + inner + '</svg>';
  }
  // 图标注入：直接写入内联 SVG（不依赖外部 symbol 引用），确保各类 WebView 都能渲染
  function injectIcons(root) {
    var scope = root || document;
    scope.querySelectorAll('[data-icon]').forEach(function (el) {
      var name = el.getAttribute('data-icon');
      applySvg(el, name);
    });
  }
  // 生成一个 icon 元素（用于动态创建的 DOM）
  function makeIcon(name, cls) {
    var s = document.createElement('span');
    if (cls) s.className = cls;
    s.setAttribute('data-icon', name);
    applySvg(s, name);
    return s;
  }

  // ---------- 原生桥调用 ----------
  function toast(msg) {
    if (bridge && bridge.toast) bridge.toast(String(msg));
  }
  function api(method, url, body, withAuth, cb) {
    var cbName = '_cb' + Date.now() + '_' + Math.floor(Math.random() * 1e6);
    window[cbName] = function (json) {
      var data;
      try { data = (typeof json === 'string') ? JSON.parse(json) : json; } catch (e) { data = { ok: false, error: '解析失败: ' + (e && e.message ? e.message : '') }; }
      delete window[cbName];
      cb(data);
    };
    bridge.apiRequest(cbName, method, url, body || '', !!withAuth);
  }
  function loadToken() { return bridge && bridge.loadToken ? bridge.loadToken() : ''; }

  // ---------- 页面切换 ----------
  function switchView(v) {
    state.view = v;
    ['files', 'transfers', 'recycle', 'mine'].forEach(function (k) {
      var sec = $('view-' + k);
      var tab = null;
      document.querySelectorAll('#tabbar .tab').forEach(function (t) {
        if (t.getAttribute('data-view') === k) tab = t;
      });
      if (sec) sec.classList.toggle('hidden', k !== v);
      if (tab) tab.classList.toggle('active', k === v);
    });
    // 离开文件视图时退出多选（整理）模式，避免状态残留
    if (v !== 'files' && state.selectMode) {
      state.selectMode = false;
      state.selectedMap = {};
      hide($('select-toolbar'));
      var ft = $('file-toolbar');
      if (ft && ft.classList.contains('hidden')) show(ft);
    }
    if (v === 'mine') loadMine();
    if (v === 'recycle') loadRecycle();
    if (v === 'transfers') { renderTransfers(); startProgressPolling(); }
    else { stopProgressPolling(); }
    if (v === 'files' && !$('file-list').dataset.loaded) loadList();
  }

  // ---------- 下载任务（传输列表） ----------
  function loadTransfers() {
    try {
      var raw = localStorage.getItem('pan_transfers');
      var arr = raw ? JSON.parse(raw) : [];
      return Array.isArray(arr) ? arr : [];
    } catch (e) { return []; }
  }
  function saveTransfers() {
    try { localStorage.setItem('pan_transfers', JSON.stringify(state.transfers)); } catch (e) {}
  }
  function addTransfer(t) {
    if (!state.transfers) state.transfers = [];
    state.transfers.unshift({ id: t.id || -1, name: t.name || '', size: t.size, status: t.status || 'downloading', done: 0, total: t.total || 0, stream: !!t.stream, time: Date.now() });
    saveTransfers();
  }
  function statusLabel(st, done, total) {
    st = Number(st);
    if (st === 8) return '已完成';
    if (st === 16) return '失败';
    // 下载中（1）/ 其他挂起态：显示进度百分比
    var tot = Number(total);
    if (tot > 0) {
      var p = Math.floor((Number(done) || 0) / tot * 100);
      if (p > 100) p = 100;
      return '下载中 ' + p + '%';
    }
    return '下载中';
  }
  function startProgressPolling() {
    if (state.progTimer) return;
    pollDownloadProgress();
    state.progTimer = setInterval(pollDownloadProgress, 2000);
  }
  function stopProgressPolling() {
    if (state.progTimer) { clearInterval(state.progTimer); state.progTimer = null; }
  }
  // 轮询下载进度：同时支持 DownloadManager 任务与自研流式任务（stream）。
  // 关键修复：不再无条件把 status===8 当作"完成"——对 DownloadManager 任务，
  // 若状态为成功但实际字节数 < total，视为"下载中/异常"而非完成，避免"未下完就显示完成"。
  function pollDownloadProgress() {
    if (!(bridge && bridge.queryDownloads)) return;
    try {
      var list = JSON.parse(bridge.queryDownloads() || '[]');
      // 自研流式任务列表（id >= 900000000）
      var slist = [];
      if (bridge.streamingTasks) {
        try { slist = JSON.parse(bridge.streamingTasks() || '[]'); } catch (e) {}
      }
      if ((!Array.isArray(list) || !list.length) && (!Array.isArray(slist) || !slist.length)) return;
      if (!state.transfers) return;
      var nameToStatus = {};
      (list).forEach(function (dl) { nameToStatus[dl.name] = dl; });
      var changed = false;
      state.transfers.forEach(function (t) {
        var hit = null;
        // 自研流式任务优先按 id 匹配 streaming 列表
        if (t.stream && slist.length) {
          slist.forEach(function (x) { if (Number(x.id) === Number(t.id)) hit = x; });
        }
        if (!hit && t.id >= 0 && list.length) {
          list.forEach(function (x) { if (Number(x.id) === Number(t.id)) hit = x; });
        }
        if (!hit && t.name) hit = nameToStatus[t.name] || null;
        if (hit) {
          if (t.status === 'completed') return;
          var st = Number(hit.status);
          var done = Number(hit.done) || 0;
          var total = Number(hit.total) || 0;
          // 用请求时记录的期望大小兜底（stream 任务的 total 以后端为准，避免被 0 覆盖）
          var expect = Number(t.total) || Number(t.size) || 0;
          t.done = done;
          t.total = total;
          if (st === 8) {
            if (t.stream) {
              // 流式任务：必须实际大小>0 且 done>=期望大小才标记完成，杜绝"未下完显完成"
              // 期望大小取后端返回的 total；若后端未知则用前端 fsize 兜底；再未知则保守不完成
              var ref = (total > 0 ? total : expect);
              if (ref > 0 && done >= ref) { t.status = 'completed'; }
              else { t.status = 'downloading'; } // 字节不足或大小未知 -> 仍视为下载中
            } else {
              // DownloadManager 任务：严格用实际 done>=total 才完成，未知大小不判完成
              if (total > 0 && done >= total) { t.status = 'completed'; }
              else { t.status = 'downloading'; }
            }
          }
          else if (st === 16) { t.status = 'failed'; }
          else { t.status = 'downloading'; }
          changed = true;
        }
      });
      if (changed) {
        saveTransfers();
        if (state.view === 'transfers') renderTransfers();
      }
    } catch (e) { /* 忽略轮询解析错误 */ }
  }
  function renderTransfers() {
    var box = $('transfer-list');
    var empty = $('transfer-empty');
    var arr = state.transfers || loadTransfers();
    state.transfers = arr;
    if (!box) return;
    if (!arr.length) {
      if (empty) show(empty);
      box.innerHTML = '';
      return;
    }
    if (empty) hide(empty);
    var html = '';
    for (var i = 0; i < arr.length; i++) {
      var t = arr[i];
      var nm = t.name || '';
      var sz = fmtSize(t.size);
      var label = t.status === 'downloading'
        ? statusLabel(1, t.done, t.total)
        : (t.status === 'completed' ? '已完成' : (t.status === 'failed' ? '失败' : mapStatusText(t.status)));
      var doneOk = (t.status === 'completed');
      // 已完成任务显示真实文件类型图标，未完成任务显示下载图标
      var icName = doneOk ? iconForName(nm) : 'download';
      html += '<div class="transfer-item">'
        + '<div class="transfer-ic ic-' + icName + '" data-icon="' + icName + '"></div>'
        + '<div class="transfer-info"><div class="transfer-name">' + esc(nm) + '</div>'
        + '<div class="transfer-sub">' + esc(sz) + ' · ' + esc(label) + '</div></div>'
        + '<button class="transfer-open' + (doneOk ? '' : ' disabled') + '" data-i="' + i + '">打开</button>'
        + '<button class="transfer-del" data-i="' + i + '" title="删除记录">×</button>'
        + '</div>';
    }
    box.innerHTML = html;
    // 打开按钮：apk 走安装程序，其他走系统推荐打开方式
    box.querySelectorAll('.transfer-open').forEach(function (btn) {
      btn.addEventListener('click', function () {
        var idx = Number(btn.getAttribute('data-i'));
        var t = state.transfers[idx];
        if (!t) return;
        if (t.status !== 'completed') { toast('文件未下载完成，暂不能打开'); return; }
        if (bridge && bridge.openFile) { bridge.openFile(t.name); }
        else {
          var p = '/sdcard/Download/' + t.name;
          if (/\.apk$/i.test(t.name)) { bridge.openApk && bridge.openApk(p); }
        }
      });
    });
    // 删除按钮：确认后从传输列表移除该条记录
    box.querySelectorAll('.transfer-del').forEach(function (btn) {
      btn.addEventListener('click', function (e) {
        e.stopPropagation();
        var idx = Number(btn.getAttribute('data-i'));
        var t = state.transfers && state.transfers[idx];
        if (!t) return;
        state.transfers.splice(idx, 1);
        saveTransfers();
        renderTransfers();
        toast('已删除传输记录「' + (t.name || '') + '」');
      });
    });
  }
  function mapStatusText(s) {
    return (s === 'completed') ? '已完成' : (s === 'failed' ? '失败' : String(s || '下载中'));
  }

  // ---------- 登录（统一采用官方 123 云盘登录页） ----------
  // App 未登录时，原生主 WebView 直接加载官方登录页（支持账号密码 / 手机验证码，含阿里云安全滑块），
  // 登录成功由原生捕获 sso-token 存会话，并自动切回本地 SPA 主界面（注入 __restoreSession 恢复态）。
  // 打开官方登录页（登录页兜底按钮）
  function openOfficialLogin() {
    var msg = $('official-login-msg');
    if (bridge && bridge.openOfficialLogin) {
      if (msg) msg.textContent = '正在打开官方登录页...';
      bridge.openOfficialLogin();
    } else if (msg) {
      msg.textContent = '当前环境不支持官方登录';
    }
  }
  // 原生在官方登录成功后回调（token 已由原生写入会话；本地 SPA 加载时 __restoreSession 自动恢复）
  window.__onOfficialLogin = function (token, username) {
    if (token && !state.token) {
      state.token = token;
      state.user = username || '';
      toast('登录成功');
      enterMain();
    }
  };

  function enterMain() {
    hide($('page-login'));
    show($('page-main'));
    switchView('files');
  }

  // App 切后台钩子（由原生 onPause 调用；已移除扫码登录，无需额外处理）
  window.__onAppPause = function () {};
  // App 回前台钩子（由原生 onResume 调用）
  window.__onAppResume = function () {};


  // ---------- 会话恢复 ----------
  window.__restoreSession = function (token, user) {
    if (token) {
      state.token = token;
      state.user = user || '';
      enterMain();
    }
  };

  // ---------- 文件列表 ----------
  function renderBreadcrumb() {
    var box = $('breadcrumb');
    box.innerHTML = '';
    var root = document.createElement('span');
    root.className = 'crumb' + (state.currentDir === 0 ? ' active' : '');
    root.textContent = '全部文件';
    root.addEventListener('click', function () {
      if (state.currentDir !== 0) { state.currentDir = 0; state.breadcrumb = []; loadList(); }
    });
    box.appendChild(root);
    state.breadcrumb.forEach(function (c, i) {
      var sep = document.createElement('span'); sep.className = 'sep'; sep.textContent = '›';
      var crumb = document.createElement('span');
      crumb.className = 'crumb' + (i === state.breadcrumb.length - 1 ? ' active' : '');
      crumb.textContent = c.name;
      crumb.addEventListener('click', function () {
        if (i < state.breadcrumb.length - 1) {
          state.breadcrumb = state.breadcrumb.slice(0, i + 1);
          state.currentDir = c.id;
          loadList();
        }
      });
      box.appendChild(sep);
      box.appendChild(crumb);
    });
  }

  function loadList() {
    renderBreadcrumb();
    var box = $('file-list');
    box.dataset.loaded = '1';
    box.innerHTML = '<div class="loading-dot">加载中...</div>';
    var params = 'driveId=0&limit=200&next=0&orderBy=file_id&orderDirection=desc'
      + '&parentFileId=' + state.currentDir + '&trashed=false&Page=1&OnlyLookAbnormalFile=0';
    api('GET', API.list + '?' + params, '', true, function (d) {
      if (d && d.data && d.data.InfoList) {
        renderList(d.data.InfoList, d.data.Total);
      } else {
        box.innerHTML = '<div class="panel-empty"><div class="panel-icon" data-icon="folder"></div><p>加载失败或需重新登录</p></div>';
        injectIcons(box);
      }
    });
  }

  function renderList(list, total) {
    var box = $('file-list');
    _currentList = list || [];
    box.innerHTML = '';
    if (!list || !list.length) {
      box.innerHTML = '<div class="panel-empty"><div class="panel-icon" data-icon="folder"></div><p>此目录为空</p></div>';
      injectIcons(box);
      return;
    }
    var isSelect = state.selectMode;
    var ckIcon = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M20 6L9 17l-5-5" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"/></svg>';
    list.forEach(function (item) {
      var isSel = !!state.selectedMap[item.FileId];
      var card = document.createElement('div');
      card.className = 'file-card' + (isSel ? ' selected' : '');
      card.setAttribute('data-fid', item.FileId);
      // 多选模式：卡片左侧显示复选框
      if (isSelect) {
        var ck = document.createElement('div');
        ck.className = 'file-check' + (isSel ? ' checked' : '');
        if (isSel) ck.innerHTML = ckIcon;
        card.appendChild(ck);
      }
      // 图标区（40px 圆角色块，按类型着色更形象）
      var iconWrap = document.createElement('div');
      iconWrap.className = 'file-icon-wrap fi-' + iconFor(item);
      iconWrap.appendChild(makeIcon(iconFor(item), 'file-icon'));
      // 正文区
      var body = document.createElement('div'); body.className = 'file-body';
      var name = document.createElement('div'); name.className = 'file-name'; name.textContent = item.FileName || '未命名';
      var meta = document.createElement('div'); meta.className = 'file-meta';
      meta.textContent = item.Type === 1 ? '文件夹' : (fmtSize(item.Size) + ' · ' + (item.ModifyTime || ''));
      body.appendChild(name); body.appendChild(meta);
      // 快捷方式按钮已移除：文件/文件夹的下载、删除等操作统一点击卡片后经操作浮层执行
      card.appendChild(iconWrap); card.appendChild(body);
      // 事件：多选模式下点击切换选中态，否则弹出操作浮层（文件夹浮层含"打开"入口）
      card.addEventListener('click', function (e) {
        if (state.selectMode) {
          toggleSelect(item);
        } else {
          openActionSheet(item);
        }
      });
      box.appendChild(card);
    });
    // 更新多选操作栏的选中计数
    if (isSelect) refreshSelectBar();
  }

  // ---------- 多选（文件整理） ----------
  function enterSelectMode() {
    state.selectMode = true;
    state.selectedMap = {};
    show($('select-toolbar'));
    hide($('file-toolbar'));
    loadList();
  }
  function exitSelectMode() {
    state.selectMode = false;
    state.selectedMap = {};
    hide($('select-toolbar'));
    var ft = $('file-toolbar');
    if (ft) {
      ft.classList.remove('toolbar-hidden');  // 确保回归正常工具栏可见
      show(ft);
    }
    loadList();
  }
  function toggleSelect(item) {
    var id = item.FileId;
    if (state.selectedMap[id]) delete state.selectedMap[id];
    else state.selectedMap[id] = item;
    refreshSelectBar();
    // 按 data-fid 精准定位并刷新对应卡片（不重渲整表，保留选中动画）
    var box = $('file-list');
    var cards = box.querySelectorAll('.file-card');
    for (var i = 0; i < cards.length; i++) {
      if (Number(cards[i].getAttribute('data-fid')) !== Number(id)) continue;
      var sel = !!state.selectedMap[id];
      cards[i].classList.toggle('selected', sel);
      var ck = cards[i].querySelector('.file-check');
      if (ck) {
        ck.classList.toggle('checked', sel);
        ck.innerHTML = sel ? '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M20 6L9 17l-5-5" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"/></svg>' : '';
      }
    }
  }
  function refreshSelectBar() {
    var n = Object.keys(state.selectedMap).length;
    var cnt = $('select-count');
    if (cnt) cnt.textContent = '已选 ' + n + ' 项';
    var mv = $('select-move');
    if (mv) mv.classList.toggle('disabled', n === 0);
  }

  // ---------- 文件夹选择器（移动目标） ----------
  // 打开移动选择面板：从根目录开始浏览目录以选择目标文件夹
  function openMovePicker() {
    if (Object.keys(state.selectedMap).length === 0) { toast('请先选择要移动的文件'); return; }
    var selItems = [];
    for (var k in state.selectedMap) selItems.push(state.selectedMap[k]);
    state.pickerState = { dir: 0, path: [] };   // 从根目录开始
    show($('move-picker'));
    loadPickerDir(0, []);
  }
  function closeMovePicker() {
    hide($('move-picker'));
    state.pickerState = null;
  }
  // 加载选择器指定目录下的子文件夹（供选择移动目标）
  function loadPickerDir(pid, path) {
    state.pickerState = state.pickerState || { dir: 0, path: [] };
    state.pickerState.dir = pid;
    state.pickerState.path = path || [];
    // 渲染面包屑
    var bc = $('picker-crumb');
    bc.innerHTML = '';
    var root = document.createElement('span');
    root.className = 'pcrumb' + (pid === 0 ? ' active' : '');
    root.textContent = '全部文件';
    root.addEventListener('click', function () {
      if (state.pickerState.dir !== 0) loadPickerDir(0, []);
    });
    bc.appendChild(root);
    (path || []).forEach(function (c, i) {
      var sep = document.createElement('span'); sep.className = 'psep'; sep.textContent = '›';
      var cr = document.createElement('span');
      cr.className = 'pcrumb' + (i === path.length - 1 ? ' active' : '');
      cr.textContent = c.name;
      cr.addEventListener('click', function () {
        if (i < (path || []).length - 1) loadPickerDir(c.id, (path || []).slice(0, i + 1));
      });
      bc.appendChild(sep); bc.appendChild(cr);
    });
    var listEl = $('picker-list');
    listEl.innerHTML = '<div class="loading-dot">加载中...</div>';
    // 复用列表接口：仅取文件夹（Type===1）作为移动目标候选
    var params = 'driveId=0&limit=200&next=0&orderBy=file_id&orderDirection=desc'
      + '&parentFileId=' + pid + '&trashed=false&Page=1&OnlyLookAbnormalFile=0';
    api('GET', API.list + '?' + params, '', true, function (d) {
      var list = (d && d.data && d.data.InfoList) ? d.data.InfoList : [];
      var dirs = list.filter(function (x) { return x.Type === 1; });
      listEl.innerHTML = '';
      if (!dirs.length) {
        listEl.innerHTML = '<div class="p-empty">此目录下没有可选择的子文件夹</div>';
        return;
      }
      dirs.forEach(function (dir) {
        var row = document.createElement('div');
        row.className = 'pdir-row';
        // 图标
        var ic = document.createElement('div');
        ic.className = 'pdir-icon';
        ic.appendChild(makeIcon('folder', ''));
        row.appendChild(ic);
        var nm = document.createElement('div');
        nm.className = 'pdir-name'; nm.textContent = dir.FileName || '未命名';
        row.appendChild(nm);
        var badge = document.createElement('div');
        badge.className = 'pdir-badge';
        badge.textContent = '进入';
        row.appendChild(badge);
        row.addEventListener('click', function () {
          loadPickerDir(dir.FileId, (state.pickerState.path || []).concat([{ id: pid, name: dir.FileName }]));
        });
        listEl.appendChild(row);
      });
    });
  }
  // 确认：把选中的文件移动到当前选择器停留的目录
  function confirmMove() {
    var p = state.pickerState;
    if (!p) return;
    var targetId = Number(p.dir) || 0;
    // 拦截：目标不能是任一选中文件夹自身或其子目录
    var paths = p.path || [];
    for (var k in state.selectedMap) {
      var it = state.selectedMap[k];
      var itId = Number(it.FileId);
      if (it.Type === 1 && itId === targetId) {
        toast('不能移动到自身所在文件夹'); return;
      }
      // 检查 target 是否为选中的文件夹子目录（当前选择路径中已包含该文件夹）
      var inSel = paths.some(function (c) { return Number(c.id) === itId; });
      if (it.Type === 1 && inSel) {
        toast('不能移动到所选文件夹的子目录'); return;
      }
    }
    // 移动请求体：123pan 原生协议 mod_pid -> {parentFileId 目标, fileIdList:[{FileId: id}]}
    var ids = [];
    for (var kk in state.selectedMap) ids.push(Number(state.selectedMap[kk].FileId) || 0);
    var fileIdList = ids.map(function (fid) { return { FileId: fid }; });
    var body = { parentFileId: targetId, fileIdList: fileIdList };
    api('POST', API.move, JSON.stringify(body), true, function (d) {
      if (d && d.code === 0) {
        closeMovePicker();
        exitSelectMode();
        toast('已移动 ' + ids.length + ' 项');
        loadList();
      } else {
        toast((d && d.message) || '移动失败');
      }
    });
  }

  // ---------- 全局搜索（全盘文件） ----------
  function doSearch(keyword) {
    keyword = (keyword || '').trim();
    if (!keyword) { exitSearch(); return; }
    state.searching = true;
    state.searchKeyword = keyword;
    var box = $('file-list');
    box.innerHTML = '<div class="loading-dot">加载中...</div>';
    hide($('breadcrumb'));
    // 全盘搜索：parentFileId=0，用 SearchData 传关键词（123pan 全局搜索协议）
    var params = 'driveId=0&limit=200&next=0&orderBy=file_id&orderDirection=desc'
      + '&parentFileId=0&trashed=false&Page=1&OnlyLookAbnormalFile=0'
      + '&SearchData=' + encodeURIComponent(keyword);
    api('GET', API.list + '?' + params, '', true, function (d) {
      if (d && d.data) {
        state.searchTotal = d.data.Total || 0;
        renderSearchResult(d.data.InfoList || [], state.searchTotal, keyword);
      } else {
        box.innerHTML = '<div class="panel-empty"><div class="panel-icon" data-icon="search"></div><p>搜索失败或需重新登录</p></div>';
        injectIcons(box);
      }
    });
  }
  function renderSearchResult(list, total, kw) {
    var box = $('file-list');
    box.innerHTML = '';
    var head = document.createElement('div');
    head.className = 'search-summary';
    head.textContent = (list.length ? ('搜索「' + kw + '」共 ' + total + ' 项') : ('未找到「' + kw + '」相关文件'));
    box.appendChild(head);
    if (!list || !list.length) {
      var empty = document.createElement('div');
      empty.className = 'panel-empty';
      var ic = document.createElement('div'); ic.className = 'panel-icon'; ic.setAttribute('data-icon', 'search'); applySvg(ic, 'search');
      empty.appendChild(ic);
      var p = document.createElement('p'); p.textContent = '没有匹配的文件';
      empty.appendChild(p);
      box.appendChild(empty);
      return;
    }
    list.forEach(function (item) {
      var card = document.createElement('div');
      card.className = 'file-card';
      var iconWrap = document.createElement('div');
      iconWrap.className = 'file-icon-wrap fi-' + iconFor(item);
      iconWrap.appendChild(makeIcon(iconFor(item), 'file-icon'));
      var body = document.createElement('div'); body.className = 'file-body';
      var name = document.createElement('div'); name.className = 'file-name'; name.textContent = item.FileName || '未命名';
      var meta = document.createElement('div'); meta.className = 'file-meta';
      // 搜索结果：额外显示文件所在位置（NewParentName / ParentName）
      var loc = item.NewParentName || item.ParentName || '';
      meta.textContent = (item.Type === 1 ? '文件夹' : fmtSize(item.Size)) + (loc ? ' · ' + loc : '');
      body.appendChild(name); body.appendChild(meta);
      card.appendChild(iconWrap); card.appendChild(body);
      card.addEventListener('click', function () {
        openActionSheet(item);   // 文件/文件夹均弹出操作浮层（文件夹含"打开"入口）
      });
      box.appendChild(card);
    });
  }
  function exitSearch() {
    state.searching = false;
    state.searchKeyword = '';
    var input = $('search-input');
    if (input) input.value = '';
    var sc = $('search-clear');
    if (sc) hide(sc);
    show($('breadcrumb'));
    loadList();
  }

  // ---------- 操作浮层（九宫格） ----------
  // 打开文件夹：进入目录
  function openDir(item) {
    closeSheet();
    // 若从搜索结果进入文件夹，先退出搜索态，恢复面包屑（保留进入的目录）
    if (state.searching) {
      state.searching = false;
      state.searchKeyword = '';
      show($('breadcrumb'));
    }
    state.breadcrumb.push({ id: item.FileId, name: item.FileName });
    state.currentDir = item.FileId;
    loadList();
  }
  function openActionSheet(item) {
    state.currentItem = item;
    $('sheet-title').textContent = item.FileName || '未命名';
    var grid = $('sheet-grid');
    grid.innerHTML = '';
    // 点击菜单项按类型区分：
    //   - 文件夹 (Type===1)：打开 / 分享 / 重命名 / 删除
    //   - 文件   (Type!==1)：下载 / 分享 / 重命名 / 删除
    var isDir = item.Type === 1;
    var items;
    if (isDir) {
      items = [
        { icon: 'open', label: '打开', cls: 'primary', fn: function () { closeSheet(); openDir(item); } },
        { icon: 'share', label: '分享', cls: '', fn: function () { closeSheet(); doShare(item); } },
        { icon: 'rename', label: '重命名', cls: '', fn: function () { closeSheet(); onAction('rename', item); } },
        { icon: 'trash', label: '删除', cls: 'warn', fn: function () { closeSheet(); onAction('delete', item); } }
      ];
    } else {
      items = [
        { icon: 'download', label: '下载', cls: 'primary', fn: function () { closeSheet(); doDownload(item); } },
        { icon: 'share', label: '分享', cls: '', fn: function () { closeSheet(); doShare(item); } },
        { icon: 'rename', label: '重命名', cls: '', fn: function () { closeSheet(); onAction('rename', item); } },
        { icon: 'trash', label: '删除', cls: 'warn', fn: function () { closeSheet(); onAction('delete', item); } }
      ];
    }
    items.forEach(function (it) {
      var el = document.createElement('div');
      el.className = 'sheet-grid-item ' + it.cls;
      // 文字图标：功能名称直接置于方块内，不再使用 SVG 图标、不在方块下方单独显示名称
      var ic = document.createElement('div'); ic.className = 'sgi-icon';
      ic.textContent = it.label;
      el.appendChild(ic);
      el.title = it.label;
      el.addEventListener('click', it.fn);
      grid.appendChild(el);
    });
    show($('action-sheet'));
  }
  function closeSheet() { hide($('action-sheet')); }

  // ---------- 自定义确认弹窗（替代原生 confirm） ----------
  function showConfirm(message, onOk) {
    $('cf-message').textContent = message || '';
    state.confirmOk = onOk || null;
    show($('confirm-modal'));
  }
  function onCfOk() {
    hide($('confirm-modal'));
    var cb = state.confirmOk;
    state.confirmOk = null;
    if (cb) cb();
  }

  // ---------- 操作处理 ----------
  function onAction(act, item) {
    state.currentItem = item;
    if (act === 'rename') {
      closeSheet();
      $('rename-input').value = item.FileName || '';
      show($('rename-modal'));
    } else if (act === 'download') {
      doDownload(item);
    } else if (act === 'delete') {
      closeSheet();
      showConfirm('确认删除「' + (item.FileName || '') + '」？', function () { doDelete(item); });
    }
  }

  function doRename() {
    var item = state.currentItem;
    if (!item) return;
    var newName = $('rename-input').value.trim();
    if (!newName) { toast('名称不能为空'); return; }
    // 123pan 重命名：POST /a/api/file/rename，请求体 {driveId, fileId, fileName(新名), duplicate}
    api('POST', API.rename,
      JSON.stringify({ driveId: 0, fileId: item.FileId, fileName: newName, duplicate: 1 }),
      true,
      function (d) {
        if (d && d.code === 0) { hide($('rename-modal')); toast('重命名成功'); loadList(); }
        else toast((d && d.message) || '重命名失败');
      });
  }

  function doDelete(item) {
    // 123pan 删除：POST /a/api/file/trash，请求体 {RequestSource, driveId, event:"intoRecycle", fileTrashInfoList:[{FileId}], operatePlace, operation}
    api('POST', API.trash,
      JSON.stringify({
        RequestSource: null,
        driveId: 0,
        event: 'intoRecycle',
        fileTrashInfoList: [{ FileId: item.FileId }],
        operatePlace: 1,
        operation: true
      }),
      true,
      function (d) {
        if (d && d.code === 0) { toast('已移入回收站'); loadList(); }
        else toast((d && d.message) || '删除失败');
      });
  }

  // ---------- 回收站 ----------
  // 回收站列表：复用文件列表接口，trashed=true 表示回收站文件（parentFileId=0 全量扁平）
  function loadRecycle() {
    var box = $('recycle-list');
    var empty = $('recycle-empty');
    if (!box) return;
    box.dataset.loaded = '1';
    box.innerHTML = '<div class="loading-dot">加载中...</div>';
    var params = 'driveId=0&limit=500&next=0&orderBy=file_id&orderDirection=desc'
      + '&parentFileId=0&trashed=true&Page=1&OnlyLookAbnormalFile=0';
    api('GET', API.list + '?' + params, '', true, function (d) {
      var list = d && d.data && (d.data.InfoList || d.data.Info);
      if (list && list.length) {
        if (empty) hide(empty);
        renderRecycle(list);
      } else {
        if (empty) show(empty);
        box.innerHTML = '';
      }
    });
  }
  function renderRecycle(list) {
    var box = $('recycle-list');
    box.innerHTML = '';
    if (!list || !list.length) { box.innerHTML = '<div class="panel-empty"><div class="panel-icon" data-icon="trash"></div><p>回收站为空</p></div>'; injectIcons(box); return; }
    list.forEach(function (item) {
      var card = document.createElement('div');
      card.className = 'file-card';
      var iconWrap = document.createElement('div');
      iconWrap.className = 'file-icon-wrap fi-' + iconFor(item);
      iconWrap.appendChild(makeIcon(iconFor(item), 'file-icon'));
      var body = document.createElement('div'); body.className = 'file-body';
      var name = document.createElement('div'); name.className = 'file-name'; name.textContent = item.FileName || '未命名';
      var meta = document.createElement('div'); meta.className = 'file-meta';
      meta.textContent = item.Type === 1 ? '文件夹' : (fmtSize(item.Size) + ' · ' + (item.TrashTime || item.ModifyTime || ''));
      body.appendChild(name); body.appendChild(meta);
      card.appendChild(iconWrap); card.appendChild(body);
      card.addEventListener('click', function () { openRecycleSheet(item); });
      box.appendChild(card);
    });
  }
  // 回收站文件操作浮层：恢复 / 彻底删除
  function openRecycleSheet(item) {
    state.currentItem = item;
    $('sheet-title').textContent = (item.FileName || '未命名') + '（回收站）';
    var grid = $('sheet-grid');
    grid.innerHTML = '';
    var items = [
      { icon: 'restore', label: '恢复', cls: 'primary', fn: function () {
          closeSheet();
          doRecycleOp(item, RECYCLE_EVENT.restore);
        } },
      { icon: 'trash', label: '彻底删除', cls: 'warn', fn: function () {
          closeSheet();
          doRecycleOp(item, RECYCLE_EVENT.deleteP);
        } }
    ];
    items.forEach(function (it) {
      var el = document.createElement('div');
      el.className = 'sheet-grid-item ' + it.cls;
      var ic = document.createElement('div'); ic.className = 'sgi-icon';
      ic.textContent = it.label;
      el.appendChild(ic);
      el.title = it.label;
      el.addEventListener('click', it.fn);
      grid.appendChild(el);
    });
    show($('action-sheet'));
  }
  // 通用回收站操作（恢复 / 彻底删除）
  // 恢复：POST /a/api/file/trash（event=recycleRestore，operation=false）
  // 彻底删除：POST /a/api/file/delete（event=recycleDelete，fileIdList）
  function doRecycleOp(item, ev) {
    var isRestore = (ev === RECYCLE_EVENT.restore);
    var url = isRestore ? API.trash : API.trashDelete;
    var body = isRestore
      ? { RequestSource: null, driveId: 0, event: ev, fileTrashInfoList: [{ FileId: item.FileId }], operatePlace: 1, operation: false, safeBox: false }
      : { RequestSource: null, event: ev, fileIdList: [item.FileId], operatePlace: 1 };
    api('POST', url, JSON.stringify(body), true, function (d) {
      if (d && d.code === 0) {
        toast(isRestore ? '已恢复' : '已彻底删除');
        loadRecycle();
      } else toast((d && d.message) || '操作失败');
    });
  }
  // 清空回收站：走专用接口 file/trash_delete_all（event=recycleClear），清空后 code 为 7301 视为成功
  function recycleClearAll() {
    api('POST', API.trashDeleteAll,
      JSON.stringify({ RequestSource: null, event: RECYCLE_EVENT.clear }),
      true,
      function (d) {
        // 清空接口成功返回 code=7301（"已清空，系统释放空间需要一段时间"），code=0 或 7301 均算成功
        if (d && (d.code === 0 || d.code === 7301)) { toast('回收站已清空'); loadRecycle(); }
        else toast((d && d.message) || '清空失败');
      });
  }

  // 下载：文件走 download_info，文件夹走 batch_download_info
  // 修复①：download_info 请求体必须携带真实字节数，否则接口返回"请输入size"。
  //     列表项尺寸字段可能是 Size / FileSize / size，全面兜底，且 type 需为数字。
  // 修复②：body 同时携带 size 与 fileSize 两个字段，兼容 123pan 接口不同字段名。
  function buildDownloadBody(item) {
    var sz = Number(item.Size) || Number(item.FileSize) || Number(item.size) || 0;
    return {
      driveId: 0,
      etag: item.Etag || item.etag || '',
      fileId: item.FileId || item.fileId,
      size: sz,
      fileSize: sz,
      s3keyFlag: item.S3KeyFlag || item.s3keyFlag || item.s3KeyFlag || '',
      fileName: item.FileName || item.fileName || '',
      fileNameType: (item.Type !== undefined ? item.Type : 0),
      type: 'download'
    };
  }
  function pickDownloadUrl(d) {
    var dl = d && d.data;
    if (!dl) return '';
    return (dl.DownloadUrl || dl.downloadUrl || dl.url
      || (dl[0] && (dl[0].DownloadUrl || dl[0].url)) || '');
  }
  function doDownload(item) {
    var url, body;
    if (item.Type === 1) {
      url = API.batchDownload;
      body = JSON.stringify({ fileIdList: [{ fileId: item.FileId || item.fileId }] });
    } else {
      url = API.download;
      body = JSON.stringify(buildDownloadBody(item));
    }
    toast('正在获取下载链接...');
    api('POST', url, body, true, function (d) {
      if (!d || !d.data) {
        // 接口明确报缺 size 时给出可理解的提示，避免用户看到乱码般的原始错误
        var msg = (d && (d.message || d.error)) || '获取下载链接失败';
        if (/size/i.test(msg)) msg = '下载失败：该文件缺少大小信息，请刷新列表后重试';
        toast(msg);
        return;
      }
      var link = pickDownloadUrl(d);
      if (link) {
        var fname = item.FileName || item.fileName || (Date.now() + '');
        var fsize = Number(item.Size) || Number(item.size) || Number(item.FileSize) || 0;
        var started = false;
        var genId = -1;
        var isStream = false;
        // 自研流式下载（带认证头 + 多级直链解析 + 严格字节校验，杜绝"未下完就显示完成"）
        // 注意：不再回退到 DownloadManager —— 其用默认 UA 直连会被服务端拦截返回错误小文件
        //       （5344 字节 HTML），且自身也会把"部分下载"误标为成功，制造损坏 apk。宁可明确失败让用户重试。
        if (bridge && bridge.downloadStream) {
          try {
            genId = Number(bridge.downloadStream(link, fname, fsize));
            started = genId >= 0;
            isStream = started;
          } catch (e) { started = false; }
        }
        if (!started) {
          toast('下载启动失败，请重试');
          return; // 不回退，避免 DownloadManager 假完成造成损坏文件
        }
        addTransfer({ id: genId, name: fname, size: fsize, total: fsize, status: 'downloading', stream: isStream });
        startProgressPolling();
        toast('已加入下载任务');
      } else {
        toast('暂无法获取直链，请查看返回信息');
      }
    });
  }

  // 分享：弹出配置浮层（选有效期 + 提取码方式），确认后调用 123盘原生分享接口
  function doShare(item) {
    if (!item || !item.FileId) { toast('无法分享该对象'); return; }
    state.shareItem = item;                         // 记住当前要分享的对象
    // 每次打开配置弹窗时重置为默认（永久有效 + 随机提取码）
    var expireRadios = document.getElementsByName('sc-expire');
    for (var e = 0; e < expireRadios.length; e++) expireRadios[e].checked = (expireRadios[e].value === '4');
    var pwdRadios = document.getElementsByName('sc-pwd');
    for (var p = 0; p < pwdRadios.length; p++) pwdRadios[p].checked = (pwdRadios[p].value === '1');
    var inp = $('sc-pwd-input'); if (inp) inp.value = '';
    hide($('sc-custom'));
    show($('share-config-modal'));
  }
  // 根据有效期选项生成到期 ISO 时间字符串（东八区）
  function shareExpiration(expireValue) {
    if (expireValue == null || Number(expireValue) === 4) return '2099-12-12T08:00:00+08:00'; // 永久
    var hours = Number(expireValue) === 1 ? 24 : Number(expireValue) === 2 ? 168 : 720;   // 1天/7天/30天
    var now = Date.now();
    var d = new Date(now + hours * 3600 * 1000);
    function p(n) { return (n < 10 ? '0' : '') + n; }
    return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate())
      + 'T' + p(d.getHours()) + ':' + p(d.getMinutes()) + ':' + p(d.getSeconds()) + '+08:00';
  }
  // 直连分享：不调用官方分享接口，改用 download_info 获取下载直链直接分享（旧方案）
  // 说明：走 123pan 文件直链，无提取码、无需对方登录，链接通常有时效性；仅文件适用。
  function doShareDirect(item) {
    if (!item || !item.FileId) { toast('无法分享该对象'); return; }
    if (item.Type === 1) { toast('文件夹暂不支持直连分享，请改用官方方式'); return; }
    toast('正在获取直连链接...');
    api('POST', API.download, JSON.stringify(buildDownloadBody(item)), true, function (d) {
      if (!d || !d.data) {
        var msg = (d && (d.message || d.error)) || '获取直链失败';
        if (/size/i.test(msg)) msg = '获取直链失败：文件缺少大小信息，请刷新列表后重试';
        toast(msg);
        return;
      }
      var link = pickDownloadUrl(d);
      if (!link) { toast('暂无法获取直链，请刷新后重试'); return; }
      showShareModal(item.FileName || '直链', link, '');
    });
  }
  // 点击"创建分享"：读取配置并调用分享创建接口
  function doCreateShare() {
    var item = state.shareItem;
    if (!item) { hide($('share-config-modal')); return; }
    // 选择的有效期
    var expireVal = '4';
    var expireRadios = document.getElementsByName('sc-expire');
    for (var e = 0; e < expireRadios.length; e++) if (expireRadios[e].checked) { expireVal = expireRadios[e].value; break; }
    // 选择的提取码方式
    var pwdType = '1';
    var pwdRadios = document.getElementsByName('sc-pwd');
    for (var p = 0; p < pwdRadios.length; p++) if (pwdRadios[p].checked) { pwdType = pwdRadios[p].value; break; }
    // 直连分享：不调用官方分享接口，而是获取下载直链直接分享（旧方案）
    if (pwdType === '4') {
      hide($('share-config-modal'));
      doShareDirect(item);
      return;
    }
    // sharePwd：随机(1)时留空由服务端生成；无提取码(2)时留空；自定义(3)时用用户输入
    var sharePwd = '';
    if (pwdType === '3') {
      sharePwd = ($('sc-pwd-input') && $('sc-pwd-input').value || '').trim().toUpperCase();
      if (!/^[A-Z0-9]{4}$/.test(sharePwd)) { toast('请输入4位提取码（字母/数字）'); return; }
    }
    hide($('share-config-modal'));
    var shareBody = {
      driveId: 0,
      expiration: shareExpiration(expireVal),
      fileIdList: String(item.FileId),          // 123pan 分享接口要求逗号拼接的 fileId 字符串
      shareName: item.FileName || item.fileName || '分享',
      sharePwd: sharePwd,
      event: 'shareCreate',
      fileNum: 1,
      renameVisible: false,
      shareTypeValue: Number(pwdType),           // 1=随机提取码 2=无提取码 3=自定义提取码
      shareModality: Number(expireVal),          // 1=1天 2=7天 3=30天 4=永久
      operatePlace: 1,
      trafficSwitch: true
    };
    toast('正在创建分享...');
    api('POST', API.shareCreate, JSON.stringify(shareBody), true, function (d) {
      if (!d || d.code !== 0 || !d.data) {
        toast((d && (d.message || d.error)) || '创建分享失败');
        return;
      }
      var dt = d.data;
      // ShareKey 形如 "key-pwd"（- 后为提取码，可能为空）
      var shareKey = dt.ShareKey || '';
      var key = shareKey, pwd = '';
      var dash = shareKey.indexOf('-');
      if (dash >= 0) { key = shareKey.slice(0, dash); pwd = shareKey.slice(dash + 1); }
      // 官方标准分享访问链接，若提供了 shareLinkList（实际可用域名）则优先
      var link = 'https://www.123pan.com/s/' + key;
      var sl = dt.shareLinkList;
      if (sl && sl.list && sl.list.length) { link = sl.list[0]; }
      else if (sl && sl.standBy) { link = sl.standBy; }
      // 无提取码(shareTypeValue=2)或接口未返回提取码后缀时，不显示提取码
      var showPwd = !(pwdType === '2') && pwd;
      showShareModal(item.FileName || '分享', link, showPwd ? pwd : '');
    });
  }
  function showShareModal(title, link, pwd) {
    $('share-title').textContent = '分享 · ' + title;
    $('share-link').textContent = link;
    $('share-link').value = link;
    var pwdEl = $('share-pwd');
    var row = $('share-pwd-row');
    if (pwd) {
      pwdEl.textContent = pwd;
      if (row) row.style.display = '';
    } else {
      pwdEl.textContent = '';
      if (row) row.style.display = 'none';
    }
    show($('share-modal'));
  }
  // 复制分享链接到剪贴板
  function doCopyLink() {
    var link = $('share-link') && $('share-link').value;
    if (!link) { toast('无可复制链接'); return; }
    function fallback() {
      var ta = document.createElement('textarea');
      ta.value = link; ta.style.position = 'fixed'; ta.style.opacity = '0';
      document.body.appendChild(ta); ta.select();
      try { document.execCommand('copy'); toast('链接已复制'); } catch (e) { toast('复制失败，请手动复制'); }
      document.body.removeChild(ta);
    }
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(link).then(function () { toast('链接已复制'); },
        function () { fallback(); });
    } else { fallback(); }
  }

  // 复制（"复制"操作）：进入抽屉展示链接并提示可复制，等价于分享的文件链接
  function doCopy(item) { doShare(item); }

  // 详情：显示文件大小/时间等元数据
  function doInfo(item) {
    var msg = (item.FileName || '') + '\n大小：' + fmtSize(item.Size) + '\n修改时间：' + (item.ModifyTime || '-');
    toast(msg);
  }

  // 新建文件夹
  function doNewFolder() {
    var name = $('newfolder-input').value.trim();
    if (!name) { toast('请输入文件夹名称'); return; }
    // 123pan 新建文件夹：POST /a/api/file/upload_request，type=1 表示文件夹，size=0
    api('POST', API.mkdir,
      JSON.stringify({
        driveId: 0,
        etag: '',
        fileName: name,
        parentFileId: state.currentDir,
        size: 0,
        type: 1,
        duplicate: 1,
        NotReuse: true
      }),
      true,
      function (d) {
        if (d && d.code === 0) {
          hide($('newfolder-modal')); $('newfolder-input').value = '';
          toast('文件夹已创建'); loadList();
        } else {
          toast((d && d.message) || '创建失败');
        }
      });
  }

  // 上传：触发隐藏的 <input type=file>（需原生 setShowFileChooser 支持）
  function doUpload() {
    var inp = $('upload-input');
    if (!inp) return;
    toast('请选择要上传的文件');
    inp.click();
  }

  // 原生侧完成文件选择后回调：paths 为本地临时文件路径数组。
  // 流程：file/upload_request 获取预签名地址 -> 上传字节 -> 结束确认
  window.__onFilesPicked = function (paths) {
    if (!paths || !paths.length) return;
    var list = (typeof paths === 'string') ? JSON.parse(paths) : paths;
    for (var i = 0; i < list.length; i++) {
      var p = list[i];
      doUploadOne(p);
    }
  };

  var _upBusy = false;
  function doUploadOne(path) {
    if (!path) return;
    var fname = String(path).split('/').pop() || ('file_' + Date.now());
    // 原生上传通道：由 NativeBridge.uploadFiles 读取本地文件并完成 123pan 上传
    if (bridge && bridge.uploadFiles) {
      bridge.uploadFiles(path, state.currentDir, '_cb_up_' + Date.now() + '_' + Math.floor(Math.random()*1e6));
      return;
    }
    // 兜底：仅提示（不应到达）
    toast('上传通道未就绪：' + fname);
  }

  // 原生上传结果回调
  window.__onUploadDone = function (ok, msg) {
    if (ok) {
      toast(msg || '上传成功');
      if (state.view === 'files') loadList();
    } else {
      toast('上传失败：' + (msg || '未知错误'));
    }
  };

  // ---------- 我的页 ----------
  function loadMine() {
    renderAccountList();
    updateCacheSize();
    $('mine-version').textContent = bridge && bridge.getVersion ? bridge.getVersion() : '1.6.0';
    api('GET', API.userInfo, '', true, function (d) {
      if (d && (d.data || d.Data)) {
        var u = d.data || d.Data;
        // 兼容：部分响应的用户信息嵌套在 user 对象中
        if (u.user && typeof u.user === 'object') u = u.user;
        // 123pan /b/api/user/info 真实字段：SpaceUsed（已用）、SpacePermanent（永久空间）、SpaceTemp（临时空间）
        var used = numOf(u, 'SpaceUsed', 'UsedSize', 'usedSize', 'space_used', 'used');
        var permanent = numOf(u, 'SpacePermanent', 'TotalSize', 'totalSize', 'space_total', 'total');
        var temp = numOf(u, 'SpaceTemp', 'freeSize', 'FreeSize', 'space_temp', 'free');
        // 总额 = 永久空间 + 临时空间；备用取 used + free
        var total = (permanent > 0 || temp > 0) ? (permanent + temp) : 0;
        if (!(total > 0)) total = used + (temp > 0 ? temp : 0);
        if (total > 0) {
          var usedV = used > 0 ? used : Math.max(0, total - temp);
          $('mine-quota-val').textContent =
            '已用 ' + fmtSize(usedV) + ' / 共 ' + fmtSize(total);
        } else {
          $('mine-quota-val').textContent = '容量不可用';
        }
      } else {
        $('mine-quota-val').textContent = '容量获取失败';
      }
    });
  }

  function doLogout() {
    if (bridge.clearSession) bridge.clearSession();
    state.token = '';
    state.user = '';
    state.currentDir = 0;
    state.breadcrumb = [];
    show($('page-login'));
    hide($('page-main'));
    toast('已退出登录');
  }

  // ---------- 多账号系统 ----------
  var ACCT_KEY = 'pan_accounts';
  var ACCT_CUR = 'pan_current_user';
  function loadAccounts() {
    try {
      var arr = JSON.parse(localStorage.getItem(ACCT_KEY) || '[]');
      return Array.isArray(arr) ? arr : [];
    } catch (e) { return []; }
  }
  function saveAccounts(list) {
    try { localStorage.setItem(ACCT_KEY, JSON.stringify(list)); } catch (e) {}
  }
  function currentAccountUser() {
    try { return localStorage.getItem(ACCT_CUR) || ''; } catch (e) { return ''; }
  }
  function setCurrentAccountUser(u) {
    try { localStorage.setItem(ACCT_CUR, u || ''); } catch (e) {}
  }
  // 登录成功后加入账号列表（去重），并设为当前账号
  function addAccount(user, token, pass) {
    if (!user) return;
    var list = loadAccounts();
    var exists = false;
    list.forEach(function (a) {
      if (a.user === user) { a.token = token; a.pass = pass || a.pass; exists = true; }
    });
    if (!exists) list.unshift({ user: user, token: token, pass: pass || '' });
    saveAccounts(list);
    setCurrentAccountUser(user);
  }
  // 切换账号：更新原生会话 + 前端状态，重新加载视图数据
  function switchAccount(user) {
    var list = loadAccounts();
    var target = null;
    list.forEach(function (a) { if (a.user === user) target = a; });
    if (!target) { toast('账号不存在'); return; }
    state.token = target.token || '';
    state.user = target.user || '';
    state.currentDir = 0;
    state.breadcrumb = [];
    if (bridge.saveSession) bridge.saveSession(state.token, state.user, target.pass || '');
    setCurrentAccountUser(user);
    // 清除文件列表已加载标记，强制刷新
    var fl = $('file-list');
    if (fl) delete fl.dataset.loaded;
    toast('已切换到账号 ' + user);
    switchView('files');
  }
  // 删除账号
  function removeAccount(user) {
    var list = loadAccounts().filter(function (a) { return a.user !== user; });
    saveAccounts(list);
    var cur = currentAccountUser();
    if (cur === user) {
      setCurrentAccountUser('');
      if (list.length > 0) {
        switchAccount(list[0].user);
      } else {
        if (bridge.clearSession) bridge.clearSession();
        state.token = ''; state.user = '';
        state.currentDir = 0; state.breadcrumb = [];
        show($('page-login')); hide($('page-main'));
        toast('账号已删除');
      }
    } else {
      toast('账号已删除');
    }
    loadMine();
  }
  // 渲染"我的"页的账号列表
  function renderAccountList() {
    var box = $('account-list');
    if (!box) return;
    var list = loadAccounts();
    // 兼容：列表为空但当前已登录（升级前场景），把当前账号纳入列表
    if (list.length === 0 && state.token && state.user) {
      list = [{ user: state.user, token: state.token, pass: '' }];
      saveAccounts(list);
      setCurrentAccountUser(state.user);
    }
    var cur = currentAccountUser();
    if (list.length === 0) {
      box.innerHTML = '<div class="panel-empty" style="padding:20px 10px"><p>暂无保存的账号</p></div>';
      return;
    }
    var html = '';
    list.forEach(function (a) {
      var isCur = a.user === cur || (!cur && a.user === state.user);
      var name = a.user || '';
      var letter = (name.charAt(0) || '用').toUpperCase();
      html += '<div class="acct-item" data-user="' + esc(name) + '">'
        + '<div class="acct-avatar">' + esc(letter) + '</div>'
        + '<div class="acct-info">'
        + '<div class="acct-user">' + esc(name) + '</div>'
        + '<div class="acct-meta">' + (isCur ? '当前账号' : '点击切换') + '</div>'
        + '</div>'
        + (isCur ? '<span class="acct-current"><span class="mi-icon" data-icon="check"></span>当前</span>'
                  : '<span class="acct-more">›</span>')
        + '</div>';
    });
    box.innerHTML = html;
    if (cur === '' && list.length > 0) setCurrentAccountUser(list[0].user);
    // 注入列表内动态图标
    injectIcons(box);
    // 绑定点击：打开账号操作菜单
    box.querySelectorAll('.acct-item').forEach(function (item) {
      item.addEventListener('click', function () {
        openAccountAction(item.getAttribute('data-user'));
      });
    });
    box.addEventListener('click', function (e) {
      e.stopPropagation();
    });
  }
  // 账号操作菜单：切换 / 删除
  function openAccountAction(user) {
    var title = $('account-action-title');
    var body = $('account-actions-body');
    var cur = currentAccountUser();
    var isCur = user === cur || (!cur && user === state.user);
    title.textContent = user;
    var html = ''
      + '<div class="account-action-item" data-act="switch">'
      + (isCur ? '<span>切换到此账号</span><span class="aa-tag">当前</span>'
               : '<span>切换到该账号</span><span class="aa-tag">›</span>')
      + '</div>'
      + '<div class="account-action-item danger" data-act="del">删除该账号</div>';
    body.innerHTML = html;
    body.querySelectorAll('.account-action-item').forEach(function (item) {
      item.addEventListener('click', function () {
        var act = item.getAttribute('data-act');
        hide($('account-action'));
        if (act === 'switch') { switchAccount(user); if (state.view === 'mine') loadMine(); }
        else if (act === 'del') {
          showConfirm('确认删除账号「' + user + '」？', function () {
            removeAccount(user);
          });
        }
      });
    });
    show($('account-action'));
  }
  // 添加账号：弹出添加账号弹窗并登录
  function openAddAccount() {
    $('account-user').value = '';
    $('account-pass').value = '';
    $('account-msg').textContent = '';
    show($('account-modal'));
  }
  function submitAddAccount() {
    var u = $('account-user').value.trim();
    var p = $('account-pass').value;
    if (!u || !p) { $('account-msg').textContent = '请输入账号和密码'; return; }
    var btn = $('account-ok');
    btn.disabled = true;
    $('account-msg').textContent = '登录中...';
    api('POST', API.signIn,
      JSON.stringify({ type: 1, passport: u, password: p }),
      false,
      function (d) {
        btn.disabled = false;
        var tok = d && d.data ? (d.data.token || d.data.authorization || '') : '';
        if (tok) {
          if (tok.indexOf('Bearer ') === 0) tok = tok.slice(7);
          state.token = tok;
          state.user = u;
          bridge.saveSession(tok, u, p);
          addAccount(u, tok, p);
          hide($('account-modal'));
          var fl = $('file-list'); if (fl) delete fl.dataset.loaded;
          enterMain();
          toast('账号已添加：' + u);
          loadMine();
        } else {
          $('account-msg').textContent = (d && d.message) ? d.message
            : ('登录失败[' + (d && d.code != null ? d.code : '') + ']，请检查账号密码');
        }
      });
  }
  // 读取并显示应用缓存大小
  function updateCacheSize() {
    var el = $('mine-cache-size');
    if (!el) return;
    try {
      var sz = (bridge && bridge.getCacheSize) ? Number(bridge.getCacheSize() || 0) : 0;
      el.textContent = fmtSize(sz);
    } catch (e) { el.textContent = '0 B'; }
  }
  // 清除缓存：清本地存储记录 + 调用原生清除 WebView/应用缓存
  function clearCache() {
    try { localStorage.removeItem('pan_transfers'); } catch (e) {}
    state.transfers = state.transfers || [];
    state.transfers.length = 0;
    try { saveTransfers(); } catch (e) {}
    try { localStorage.removeItem('pan_download_cache'); } catch (e) {}
    if (bridge && bridge.clearCache) {
      try { bridge.clearCache(); } catch (e) {}
    }
    toast('缓存已清除');
    setTimeout(updateCacheSize, 50);
  }

  // ---------- Android 返回键 ----------
  window.__handleBack = function () {
    // 优先关闭弹出的浮层/弹窗
    if (!$('confirm-modal').classList.contains('hidden')) { hide($('confirm-modal')); state.confirmOk = null; return true; }
    if (!$('move-picker').classList.contains('hidden')) { hide($('move-picker')); state.pickerState = null; return true; }
    if (!$('share-config-modal').classList.contains('hidden')) { hide($('share-config-modal')); return true; }
    if (!$('newfolder-modal').classList.contains('hidden')) { hide($('newfolder-modal')); return true; }
    if (!$('share-modal').classList.contains('hidden')) { hide($('share-modal')); return true; }
    if (!$('rename-modal').classList.contains('hidden')) { hide($('rename-modal')); return true; }
    if (!$('action-sheet').classList.contains('hidden')) { hide($('action-sheet')); return true; }
    // 多选（整理）模式：返回先退出多选
    if (state.selectMode) { exitSelectMode(); return true; }
    // 再回退文件目录
    if (state.view === 'files' && state.currentDir !== 0) {
      var last = state.breadcrumb.pop() || { id: 0 };
      state.currentDir = last.id;
      loadList();
      return true;
    }
    // 无更多可回退：退出
    if (bridge && bridge.exitApp) bridge.exitApp();
    else {
      // 兜底：用 History API
      if (window.history && window.history.back) window.history.back();
    }
    return true;
  };

  // ---------- 初始化 ----------
  function init() {
    // 注入所有静态 data-icon 图标（含搜索栏 search/x-circle、tab、工具栏等）
    injectIcons();
    // 底部标签切换
    document.querySelectorAll('#tabbar .tab').forEach(function (tab) {
      tab.addEventListener('click', function () {
        switchView(tab.getAttribute('data-view'));
      });
    });
    // 登录（统一官方登录页）
    var officialLoginBtn = $('official-login-btn');
    if (officialLoginBtn) officialLoginBtn.addEventListener('click', openOfficialLogin);
    // 重命名
    $('rename-ok').addEventListener('click', doRename);
    // 自定义确认弹窗：点"确定"执行回调
    $('cf-ok').addEventListener('click', onCfOk);
    // 新建文件夹
    $('tool-newfolder').addEventListener('click', function () {
      $('newfolder-input').value = '';
      show($('newfolder-modal'));
    });
    $('newfolder-ok').addEventListener('click', doNewFolder);
    $('newfolder-input').addEventListener('keydown', function (e) { if (e.key === 'Enter') doNewFolder(); });
    // 上传
    $('tool-upload').addEventListener('click', doUpload);
    $('upload-input').addEventListener('change', function () {
      var files = this.files;
      if (!files || !files.length) return;
      var names = [];
      for (var i = 0; i < Math.min(files.length, 5); i++) names.push(files[i].name);
      toast('已选择 ' + files.length + ' 个文件（' + names.join('、') + '…）\n原生上传通道待接入');
      this.value = '';
    });
    // 整理（多选）：进入多选模式
    var toolOrganize = $('tool-organize');
    if (toolOrganize) toolOrganize.addEventListener('click', enterSelectMode);
    // 多选操作栏：取消 / 移动
    $('select-cancel').addEventListener('click', exitSelectMode);
    $('select-move').addEventListener('click', openMovePicker);
    // 移动文件夹选择器：取消 / 确定移动
    $('picker-cancel').addEventListener('click', closeMovePicker);
    $('picker-confirm').addEventListener('click', confirmMove);
    // 滚动时隐藏/显示底部"上传/新建"工具栏，避免遮挡文件列表
    var scrollEl = $('content');
    (function () {
      var lastScrollTop = scrollEl.scrollTop || 0;
      scrollEl.addEventListener('scroll', function () {
        var st = scrollEl.scrollTop || 0;
        var tb = $('file-toolbar');
        if (!tb) return;
        if (st > lastScrollTop + 2) {
          tb.classList.add('toolbar-hidden');   // 向下滚动：隐藏工具栏（不遮挡列表）
        } else if (st < lastScrollTop - 2) {
          tb.classList.remove('toolbar-hidden'); // 向上滚动：显示工具栏
        }
        lastScrollTop = st;
        if (st <= 0) tb.classList.remove('toolbar-hidden'); // 回顶：确保显示
      });
    })();
    // 全盘搜索
    var searchInput = $('search-input');
    var searchClear = $('search-clear');
    if (searchInput) {
      // 回车触发搜索
      searchInput.addEventListener('keydown', function (e) {
        if (e.key === 'Enter') { doSearch(searchInput.value); }
      });
      // 输入变化：非空时显示清除按钮，清空时隐藏并退出搜索
      searchInput.addEventListener('input', function () {
        if (searchClear) {
          if (searchInput.value.trim()) show(searchClear);
          else hide(searchClear);
        }
      });
    }
    if (searchClear) {
      searchClear.addEventListener('click', function () {
        exitSearch();
        if (searchInput) searchInput.focus();
      });
    }
    // 分享弹窗复制链接
    $('share-copy').addEventListener('click', doCopyLink);
    // 创建分享：确认按钮 + 自定义提取码切换
    $('sc-create').addEventListener('click', doCreateShare);
    document.querySelectorAll('input[name="sc-pwd"]').forEach(function (rd) {
      rd.addEventListener('change', function () {
        var showCustom = rd.value === '3';
        if (showCustom) show($('sc-custom'));
        else hide($('sc-custom'));
      });
    });
    // 清空回收站
    var clearRecycleBtn = $('recycle-clear');
    if (clearRecycleBtn) clearRecycleBtn.addEventListener('click', recycleClearAll);
    // 退出
    $('logout-btn').addEventListener('click', doLogout);
    // 多账号：添加账号入口 + 添加账号弹窗确认
    var accountAdd = $('account-add');
    if (accountAdd) accountAdd.addEventListener('click', openAddAccount);
    var accountOk = $('account-ok');
    if (accountOk) accountOk.addEventListener('click', submitAddAccount);
    var accountPass = $('account-pass');
    if (accountPass) accountPass.addEventListener('keydown', function (e) { if (e.key === 'Enter') submitAddAccount(); });
    // 清除缓存
    var clearCacheBtn = $('mine-clear-cache');
    if (clearCacheBtn) clearCacheBtn.addEventListener('click', clearCache);
    // 关闭浮层/弹窗（data-close）
    document.querySelectorAll('[data-close]').forEach(function (el) {
      el.addEventListener('click', function () {
        el.closest && el.closest('.sheet') && hide(el.closest('.sheet'));
        el.closest && el.closest('.modal') && hide(el.closest('.modal'));
      });
    });
    // 初始：检查登录态
    var t = loadToken();
    if (t) {
      state.token = t;
      enterMain();
    } else {
      show($('page-login'));
      hide($('page-main'));
    }
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();