/* ============================================================
   影厅 Cinematheque — 前端逻辑
   数据来自原生 MediaStore（经 window.Native 桥），播放在原生 PlayerActivity
   ============================================================ */
(function () {
  'use strict';

  var state = {
    all: [],
    view: [],
    heroIdx: 0,
    heroList: [],
    heroTimer: null,
    query: '',
    sort: 'date',      // date | name | size | dur
    favs: {},
    progress: {},      // id -> {ms, dur, at}
    thumbCache: {},
    sheetItem: null
  };

  var $ = function (id) { return document.getElementById(id); };
  var NATIVE = window.Native || null;

  /* ---------------- 工具 ---------------- */
  function fmtDur(ms) {
    if (!ms || ms < 0) return '--:--';
    var s = Math.floor(ms / 1000), h = Math.floor(s / 3600),
        m = Math.floor((s % 3600) / 60), sec = s % 60;
    function p(n) { return n < 10 ? '0' + n : '' + n; }
    return h > 0 ? h + ':' + p(m) + ':' + p(sec) : m + ':' + p(sec);
  }
  function fmtSize(b) {
    if (!b) return '';
    var g = b / 1073741824;
    if (g >= 1) return g.toFixed(2) + ' GB';
    return (b / 1048576).toFixed(0) + ' MB';
  }
  function fmtDate(ms) {
    if (!ms) return '';
    var d = new Date(ms);
    return d.getFullYear() + '/' + (d.getMonth() + 1) + '/' + d.getDate();
  }
  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }
  function toast(msg) {
    var t = $('toast');
    t.textContent = msg;
    t.classList.add('on');
    clearTimeout(t._tm);
    t._tm = setTimeout(function () { t.classList.remove('on'); }, 1900);
  }
  function saveLocal() {
    try { localStorage.setItem('ct_favs', JSON.stringify(state.favs)); } catch (e) {}
  }
  function loadLocal() {
    try { state.favs = JSON.parse(localStorage.getItem('ct_favs') || '{}'); }
    catch (e) { state.favs = {}; }
    // 观看进度由原生持久化（ProgressStore）提供
    state.progress = {};
    if (NATIVE && NATIVE.progress) {
      try { state.progress = JSON.parse(NATIVE.progress() || '{}'); }
      catch (e) { state.progress = {}; }
    }
  }

  /* ---------------- 原生桥 ---------------- */
  function loadLibrary() {
    if (!NATIVE || !NATIVE.listVideos) {
      // 浏览器预览模式：用内置样片占位，便于设计校验
      usePreviewData();
      return;
    }
    var raw = NATIVE.listVideos();
    var list = [];
    try { list = JSON.parse(raw || '[]'); } catch (e) { list = []; }
    ingest(list);
  }

  function usePreviewData() {
    var demo = [
      { id: 1, title: '银翼杀手 2049', folder: 'Movies', dur: 9840000, size: 2684354560, w: 1920, h: 1080, date: Date.now() - 86400000, path: '/storage/emulated/0/Movies/blade2049.mp4' },
      { id: 2, title: '星际穿越', folder: 'Movies', dur: 10140000, size: 3221225472, w: 1920, h: 800, date: Date.now() - 172800000, path: '/storage/emulated/0/Movies/interstellar.mkv' },
      { id: 3, title: '爱死机 S03E01', folder: 'Series', dur: 1080000, size: 536870912, w: 1920, h: 1080, date: Date.now() - 259200000, path: '/storage/emulated/0/Series/love3e1.mp4' },
      { id: 4, title: '地球脉动 II', folder: 'Docs', dur: 3120000, size: 1610612736, w: 3840, h: 2160, date: Date.now() - 345600000, path: '/storage/emulated/0/Docs/planet2.mp4' }
    ];
    ingest(demo);
    toast('预览模式：未连接原生桥');
  }

  function ingest(list) {
    // 归一化 + 合并播放进度
    list = (list || []).map(function (v) {
      v.title = v.title || '未命名';
      v.folder = v.folder || '根目录';
      v.dur = v.dur || 0;
      v.size = v.size || 0;
      v.fav = !!state.favs[v.id];
      var p = state.progress[v.id];
      v.pct = (p && p.dur) ? Math.min(100, Math.round(p.ms / p.dur * 100)) : 0;
      v.progMs = p ? p.ms : 0;
      return v;
    });
    state.all = list;

    var total = list.length;
    var totalSize = list.reduce(function (a, b) { return a + b.size; }, 0);
    var totalDur = list.reduce(function (a, b) { return a + b.dur; }, 0);
    $('libStat').textContent = total + ' 部 · ' + fmtSize(totalSize) + ' · ' + Math.round(totalDur / 3600000) + ' 小时';

    if (!total) {
      $('empty').hidden = false;
      $('hero').style.display = 'none';
      $('allCount').textContent = '';
      return;
    }
    $('empty').hidden = true;
    $('hero').style.display = '';

    buildHero();
    renderResume();
    renderRails();
    applyFilter();
  }

  /* ---------------- Hero ---------------- */
  function buildHero() {
    var sorted = state.all.slice().sort(function (a, b) { return (b.date || 0) - (a.date || 0); });
    state.heroList = sorted.slice(0, 5);
    var dots = $('heroDots');
    dots.innerHTML = state.heroList.map(function (_, i) {
      return '<i class="' + (i === 0 ? 'on' : '') + '"></i>';
    }).join('');
    showHero(0);
    clearInterval(state.heroTimer);
    if (state.heroList.length > 1) {
      state.heroTimer = setInterval(function () {
        showHero((state.heroIdx + 1) % state.heroList.length);
      }, 7000);
    }
  }

  function showHero(i) {
    if (!state.heroList.length) return;
    state.heroIdx = i;
    var v = state.heroList[i];
    var art = $('heroArt');
    art.classList.remove('on');
    setTimeout(function () {
      var img = state.thumbCache[v.id];
      if (img) {
        art.style.backgroundImage = 'url(' + img + ')';
        art.classList.add('on');
      } else {
        requestThumb(v.id, function (data) {
          art.style.backgroundImage = 'url(' + data + ')';
          art.classList.add('on');
        });
      }
    }, 120);

    $('heroTitle').textContent = v.title;
    $('heroBadges').innerHTML =
      (v.folder ? '<span class="badge acc">' + esc(v.folder) + '</span>' : '') +
      (v.w ? '<span class="badge">' + v.w + '×' + v.h + '</span>' : '') +
      '<span class="badge">' + fmtDur(v.dur) + '</span>' +
      (v.size ? '<span class="badge gold">' + fmtSize(v.size) + '</span>' : '');
    $('heroSub').textContent = (v.path || '') ;
    var p = state.progress[v.id];
    $('heroResume').querySelector('span').textContent = p ? '从头播放' : '加入片单';
    $('heroPlay').onclick = function () { play(v.id, false); };
    $('heroResume').onclick = function () {
      if (p) { play(v.id, true); }
      else { toggleFav(v.id); showHero(state.heroIdx); }
    };
    var dots = $('heroDots').children;
    for (var k = 0; k < dots.length; k++) dots[k].className = (k === i ? 'on' : '');
  }

  /* ---------------- 缩略图惰性加载 ---------------- */
  var thumbObserver = null;
  function requestThumb(id, cb) {
    if (state.thumbCache[id]) { cb(state.thumbCache[id]); return; }
    if (!NATIVE || !NATIVE.thumb) return;
    var data = NATIVE.thumb(String(id));
    if (data && data.length > 32) {
      state.thumbCache[id] = data;
      cb(data);
    }
  }

  function observeThumbs(root) {
    if (!('IntersectionObserver' in window)) return;
    if (thumbObserver) thumbObserver.disconnect();
    thumbObserver = new IntersectionObserver(function (entries) {
      entries.forEach(function (en) {
        if (!en.isIntersecting) return;
        var el = en.target;
        var id = el.getAttribute('data-id');
        thumbObserver.unobserve(el);
        var img = el.querySelector('img');
        if (!img) return;
        requestThumb(id, function (d) { img.src = d; img.classList.add('on'); });
      });
    }, { rootMargin: '300px 0px' });
    var nodes = root ? root.querySelectorAll('.thumb[data-id]') : document.querySelectorAll('.thumb[data-id]');
    for (var i = 0; i < nodes.length; i++) thumbObserver.observe(nodes[i]);
  }

  /* ---------------- 卡片渲染 ---------------- */
  function cardHTML(v, opts) {
    opts = opts || {};
    var badge = '';
    if (v.pct > 0 && v.pct < 97) badge = '<span class="badge-tr">已看 ' + v.pct + '%</span>';
    else if (v.fav) badge = '<span class="badge-tr" style="background:rgba(176,108,255,.92);color:#fff">★</span>';
    var pm = (v.pct > 0 && v.pct < 97)
      ? '<span class="pmask"><i style="width:' + v.pct + '%"></i></span>' : '';
    return '' +
      '<div class="card" data-vid="' + v.id + '">' +
        '<div class="thumb" data-id="' + v.id + '">' +
          '<span class="ph"><svg viewBox="0 0 24 24"><rect x="3" y="5" width="18" height="14" rx="2"/><path d="M10 9l5 3-5 3z"/></svg></span>' +
          '<img alt="">' +
          badge +
          '<span class="dur">' + fmtDur(v.dur) + '</span>' +
          pm +
        '</div>' +
        '<div class="name">' + esc(v.title) + '</div>' +
        '<div class="sub">' + esc(v.folder) + (v.size ? ' · ' + fmtSize(v.size) : '') + '</div>' +
      '</div>';
  }

  function bindCards(container) {
    container.querySelectorAll('.card').forEach(function (c) {
      c.onclick = function () { openSheet(parseInt(c.getAttribute('data-vid'), 10)); };
    });
  }

  /* ---------------- 继续观看 ---------------- */
  function renderResume() {
    var items = state.all.filter(function (v) { return v.pct > 0 && v.pct < 97; })
      .sort(function (a, b) {
        var pa = state.progress[a.id], pb = state.progress[b.id];
        return ((pb && pb.at) || 0) - ((pa && pa.at) || 0);
      }).slice(0, 12);
    var wrap = $('railResumeWrap');
    if (!items.length) { wrap.hidden = true; return; }
    wrap.hidden = false;
    var rail = $('railResume');
    rail.innerHTML = items.map(function (v) { return cardHTML(v); }).join('');
    bindCards(rail);
    observeThumbs(rail);
  }

  /* ---------------- 分类栏 ---------------- */
  function renderRails() {
    var byFolder = {};
    state.all.forEach(function (v) {
      (byFolder[v.folder] = byFolder[v.folder] || []).push(v);
    });
    var folders = Object.keys(byFolder).sort(function (a, b) {
      return byFolder[b].length - byFolder[a].length;
    }).slice(0, 6);

    var host = $('rails');
    host.innerHTML = folders.map(function (f, i) {
      var items = byFolder[f].slice().sort(function (a, b) { return (b.date || 0) - (a.date || 0); });
      return '' +
        '<section class="rail-wrap">' +
          '<div class="rail-head"><h2>' + esc(f) + '</h2>' +
          '<span class="count">' + items.length + ' 部</span></div>' +
          '<div class="rail" data-rail="' + i + '">' +
            items.slice(0, 20).map(function (v) { return cardHTML(v); }).join('') +
          '</div>' +
        '</section>';
    }).join('');

    host.querySelectorAll('.rail').forEach(function (r) {
      bindCards(r);
      observeThumbs(r);
    });
  }

  /* ---------------- 全部影片 + 搜索 + 排序 ---------------- */
  function applyFilter() {
    var q = state.query.trim().toLowerCase();
    var list = state.all.filter(function (v) {
      if (!q) return true;
      return (v.title || '').toLowerCase().indexOf(q) >= 0 ||
             (v.folder || '').toLowerCase().indexOf(q) >= 0;
    });

    if (state.sort === 'name') {
      list.sort(function (a, b) { return (a.title || '').localeCompare(b.title || '', 'zh'); });
    } else if (state.sort === 'size') {
      list.sort(function (a, b) { return b.size - a.size; });
    } else if (state.sort === 'dur') {
      list.sort(function (a, b) { return b.dur - a.dur; });
    } else {
      list.sort(function (a, b) { return (b.date || 0) - (a.date || 0); });
    }

    var favFirst = list.filter(function (v) { return v.fav; });
    var others = list.filter(function (v) { return !v.fav; });
    var ordered = favFirst.concat(others);

    $('allCount').textContent = ordered.length + ' 部' + (q ? '（匹配）' : '');
    var grid = $('grid');
    grid.innerHTML = ordered.map(function (v) { return cardHTML(v); }).join('');
    bindCards(grid);
    observeThumbs(grid);

    var empty = $('empty');
    if (!ordered.length && state.all.length) {
      empty.hidden = false;
      empty.querySelector('h3').textContent = '没有匹配的影片';
      empty.querySelector('p').textContent = '换个关键词，或清空搜索框再看全部。';
    } else if (ordered.length) {
      empty.hidden = true;
    }
  }

  /* ---------------- 详情弹层 ---------------- */
  function openSheet(id) {
    var v = state.all.filter(function (x) { return x.id === id; })[0];
    if (!v) return;
    state.sheetItem = v;

    $('sheetTitle').textContent = v.title;
    $('sheetMeta').innerHTML =
      (v.w ? '<span class="badge">' + v.w + '×' + v.h + '</span>' : '') +
      '<span class="badge">' + fmtDur(v.dur) + '</span>' +
      (v.size ? '<span class="badge gold">' + fmtSize(v.size) + '</span>' : '') +
      (v.date ? '<span class="badge">' + fmtDate(v.date) + '</span>' : '');

    $('sheetPath').textContent = v.path || '';
    $('sheetArt').style.backgroundImage = '';

    var art = $('sheetArt');
    requestThumb(id, function (d) { art.style.backgroundImage = 'url(' + d + ')'; });

    var p = state.progress[v.id];
    if (p && p.dur) {
      $('sheetProgress').hidden = false;
      $('sheetProgressBar').style.width = Math.min(100, (p.ms / p.dur * 100)) + '%';
      $('sheetProgressTime').textContent = fmtDur(p.ms) + ' / ' + fmtDur(p.dur);
      $('sheetPlayLabel').textContent = '继续播放';
    } else {
      $('sheetProgress').hidden = true;
      $('sheetPlayLabel').textContent = '播放';
    }

    $('sheetFav').querySelector('span').textContent = v.fav ? '已收藏' : '收藏';
    $('sheetFav').onclick = function () {
      toggleFav(v.id);
      openSheet(v.id);
    };
    $('sheetPlay').onclick = function () { closeSheet(); play(id, !!p); };

    $('sheetMask').hidden = false;
    $('sheet').hidden = false;
  }
  function closeSheet() {
    $('sheetMask').hidden = true;
    $('sheet').hidden = true;
  }
  function toggleFav(id) {
    state.favs[id] ? delete state.favs[id] : (state.favs[id] = 1);
    var v = state.all.filter(function (x) { return x.id === id; })[0];
    if (v) v.fav = !!state.favs[id];
    saveLocal();
    toast(state.favs[id] ? '已加入收藏' : '已取消收藏');
    renderRails();
    applyFilter();
  }

  /* ---------------- 播放（交给原生） ---------------- */
  function play(id, resume) {
    if (!NATIVE || !NATIVE.play) { toast('原生播放器不可用'); return; }
    var p = state.progress[id];
    var startMs = (resume && p) ? p.ms : 0;
    NATIVE.play(String(id), String(startMs));
    showPlayerOverlay(id);
  }

  var pcTimer = null;
  function showPlayerOverlay(id) {
    var v = state.all.filter(function (x) { return x.id === id; })[0];
    $('playerCtl').hidden = false;
    $('playerCtl').classList.remove('hide');
    $('pcTitle').textContent = v ? v.title : '';
    startPcPolling();
  }
  function hidePlayerOverlay() {
    $('playerCtl').hidden = true;
    stopPcPolling();
  }

  function startPcPolling() {
    stopPcPolling();
    pcTimer = setInterval(function () {
      if (!NATIVE || !NATIVE.playerState) return;
      var st;
      try { st = JSON.parse(NATIVE.playerState() || '{}'); } catch (e) { return; }
      if (!st.active) { hidePlayerOverlay(); refreshAfterPlayback(); return; }
      var pos = st.pos || 0, dur = st.dur || 0;
      var pct = dur ? (pos / dur * 100) : 0;
      $('pcFill').style.width = pct + '%';
      $('pcKnob').style.left = pct + '%';
      $('pcBuf').style.width = ((st.buf || 0) / (dur || 1) * 100) + '%';
      $('pcTime').textContent = fmtDur(pos) + ' / ' + fmtDur(dur);
      $('pcPlayIcon').innerHTML = st.playing
        ? '<path d="M7 5h4v14H7zM13 5h4v14h-4z"/>'
        : '<path d="M8 5v14l11-7z"/>';
      $('pcSpeed').textContent = (st.speed || 1).toFixed(1) + '×';

      // 记录进度
      if (dur > 0) {
        state.progress[id_of(st)] = { ms: pos, dur: dur, at: Date.now() };
        if (Math.floor(Date.now() / 1000) % 5 === 0) saveLocal();
      }
    }, 500);
  }
  function id_of(st) { return st.id; }
  function stopPcPolling() { if (pcTimer) { clearInterval(pcTimer); pcTimer = null; } }
  function refreshAfterPlayback() {
    saveLocal();
    loadLibrary();
  }

  /* ---------------- 事件绑定 ---------------- */
  function bindUI() {
    $('btnSearch').onclick = function () {
      $('searchbar').classList.add('on');
      $('searchInput').focus();
    };
    $('btnSearchClose').onclick = function () {
      $('searchbar').classList.remove('on');
      $('searchInput').value = '';
      state.query = '';
      applyFilter();
    };
    var si = $('searchInput');
    si.oninput = function () { state.query = si.value; applyFilter(); };

    $('btnSort').onclick = function () {
      var order = ['date', 'name', 'size', 'dur'];
      var label = { date: '按时间', name: '按名称', size: '按大小', dur: '按时长' };
      state.sort = order[(order.indexOf(state.sort) + 1) % order.length];
      toast(label[state.sort]);
      applyFilter();
    };

    $('btnRescan').onclick = function () {
      if (NATIVE && NATIVE.rescan) NATIVE.rescan();
      toast('正在重新扫描…');
      setTimeout(loadLibrary, 600);
    };

    $('sheetMask').onclick = closeSheet;

    // 播放控制层
    $('pcBack').onclick = function () { if (NATIVE && NATIVE.stop) NATIVE.stop(); hidePlayerOverlay(); refreshAfterPlayback(); };
    $('pcPlay').onclick = function () { if (NATIVE && NATIVE.toggle) NATIVE.toggle(); };
    $('pcBack10').onclick = function () { if (NATIVE && NATIVE.seekBy) NATIVE.seekBy('-10000'); };
    $('pcFwd10').onclick = function () { if (NATIVE && NATIVE.seekBy) NATIVE.seekBy('10000'); };
    $('pcRotate').onclick = function () { if (NATIVE && NATIVE.rotate) NATIVE.rotate(); };
    $('pcMute').onclick = function () { if (NATIVE && NATIVE.mute) NATIVE.mute(); };
    $('pcSpeed').onclick = function () {
      if (!NATIVE || !NATIVE.cycleSpeed) return;
      var s = NATIVE.cycleSpeed();
      $('pcSpeed').textContent = s + '×';
      toast('倍速 ' + s + '×');
    };

    // 拖动进度条
    var scrub = $('pcScrub');
    var dragging = false;
    function seekFromEvent(ev) {
      var r = scrub.getBoundingClientRect();
      var x = ((ev.touches ? ev.touches[0].clientX : ev.clientX) - r.left) / r.width;
      x = Math.max(0, Math.min(1, x));
      $('pcFill').style.width = (x * 100) + '%';
      $('pcKnob').style.left = (x * 100) + '%';
      return x;
    }
    scrub.addEventListener('touchstart', function (e) { dragging = true; seekFromEvent(e); e.preventDefault(); }, { passive: false });
    scrub.addEventListener('touchmove', function (e) { if (dragging) { seekFromEvent(e); e.preventDefault(); } }, { passive: false });
    scrub.addEventListener('touchend', function (e) {
      if (!dragging) return;
      dragging = false;
      if (NATIVE && NATIVE.seekTo) NATIVE.seekTo(String(seekFromEvent(e)));
    });
    scrub.addEventListener('click', function (e) {
      var x = seekFromEvent(e);
      if (NATIVE && NATIVE.seekTo) NATIVE.seekTo(String(x));
    });

    // 点击画面切换控制层显隐
    document.addEventListener('click', function (e) {
      var ctl = $('playerCtl');
      if (ctl.hidden) return;
      if (e.target.closest('.pc-big,.pc-jump,.pc-mini,.pc-scrub,.ic-btn')) return;
      ctl.classList.toggle('hide');
    });

    document.addEventListener('visibilitychange', function () {
      if (!document.hidden) loadLibrary();
    });
  }

  /* ---------------- 原生回调入口 ---------------- */
  window.MC = {
    onLibraryChanged: function () { loadLibrary(); },
    onProgress: function () { refreshAfterPlayback(); },
    onPlayerClosed: function () { hidePlayerOverlay(); refreshAfterPlayback(); },
    toast: toast
  };

  /* ---------------- 启动 ---------------- */
  function boot() {
    loadLocal();
    bindUI();
    loadLibrary();
  }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else { boot(); }
})();
