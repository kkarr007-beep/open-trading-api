// 업무 일정·사규 화면에서 쓰는 작은 동작. 이 파일이 돌지 않아도 단추와 양식만으로 모두 쓸 수 있다.
(function () {
  // 윗쪽 큰 메뉴: 누르면 왼쪽에 그 메뉴의 세부 목록이 나온다
  document.addEventListener("click", function (event) {
    var b = event.target.closest && event.target.closest(".tm-btn[data-show]");
    if (!b) return;
    var g = b.getAttribute("data-show");
    document.querySelectorAll(".sn-group").forEach(function (n) { n.hidden = n.getAttribute("data-group") !== g; });
    document.querySelectorAll(".tm-btn[data-show]").forEach(function (x) { x.classList.toggle("sel", x === b); x.setAttribute("aria-pressed", x === b ? "true" : "false"); });
    var first = document.querySelector('.sn-group[data-group="' + g + '"] a');
    if (first && event.detail === 0) first.focus();
  });

  // (예전) 윗쪽 큰 메뉴 팝업
  function closeMenus(except) {
    document.querySelectorAll(".tm-btn[aria-expanded=true]").forEach(function (b) {
      if (b === except) return;
      b.setAttribute("aria-expanded", "false");
      var pop = b.parentElement.querySelector(".tm-pop");
      if (pop) pop.hidden = true;
    });
  }
  document.addEventListener("click", function (event) {
    var btn = event.target.closest && event.target.closest(".tm-btn");
    if (btn) {
      var pop = btn.parentElement.querySelector(".tm-pop");
      var open = btn.getAttribute("aria-expanded") !== "true";
      closeMenus(btn);
      btn.setAttribute("aria-expanded", open ? "true" : "false");
      if (pop) {
        pop.hidden = !open;
        if (open) {
          var r = pop.getBoundingClientRect();
          pop.style.left = "";
          if (r.right > window.innerWidth - 8) pop.style.left = Math.min(0, window.innerWidth - 8 - r.right) + "px";
          var first = pop.querySelector("a[aria-current=page]") || pop.querySelector("a");
          if (first && event.detail === 0) first.focus();
        }
      }
      return;
    }
    if (!(event.target.closest && event.target.closest(".tm-pop"))) closeMenus(null);
  });
  document.addEventListener("keydown", function (event) {
    if (event.key === "Escape") {
      var open = document.querySelector(".tm-btn[aria-expanded=true]");
      closeMenus(null);
      if (open) open.focus();
    }
  });

  // 지우기처럼 되돌릴 수 없는 단추는 한 번 더 묻는다
  document.addEventListener("submit", function (event) {
    var form = event.target;
    if (!(form instanceof HTMLFormElement) || !form.hasAttribute("data-confirm")) return;
    if (!window.confirm(form.getAttribute("data-confirm"))) {
      event.preventDefault();
      event.stopImmediatePropagation();
    }
  }, true);

  // 모두 고르기
  document.addEventListener("change", function (event) {
    var all = event.target.closest && event.target.closest("[data-check-all]");
    if (!all) return;
    var form = all.form;
    document.querySelectorAll('input[type=checkbox][name="' + all.getAttribute("data-check-all") + '"]').forEach(function (c) {
      if ((!form || c.form === form) && !c.disabled) c.checked = all.checked;
    });
  });

  // 업무 일정 화면에서 Ctrl+Z: 마지막 작업을 되돌린다
  document.addEventListener("keydown", function (event) {
    if (!(event.ctrlKey || event.metaKey) || event.shiftKey || event.key.toLowerCase() !== "z") return;
    if (location.pathname.indexOf("/work") !== 0) return;
    var a = document.activeElement;
    if (a && (a.tagName === "INPUT" || a.tagName === "TEXTAREA" || a.tagName === "SELECT" || a.isContentEditable)) return;
    event.preventDefault();
    fetch("/work/undo", { method: "POST", headers: { "X-Audit-Fetch": "1" } })
      .then(function (r) { return r.json(); })
      .then(function (p) {
        if (window.auditToast) window.auditToast(p.message || "");
        if (p.ok) setTimeout(function () { location.reload(); }, 500);
      }).catch(function () { });
  });

  // 고르면 바로 보낸다
  document.addEventListener("change", function (event) {
    var el = event.target;
    if (!el.matches || !el.matches("[data-autosubmit]")) return;
    var form = el.form;
    if (!form) return;
    if (typeof form.requestSubmit === "function") form.requestSubmit(); else form.submit();
  });

  // 칸반: 카드를 다른 칸으로 끌어 놓으면 상태를 바꾼다
  var dragId = null;
  document.addEventListener("dragstart", function (event) {
    var card = event.target.closest && event.target.closest(".bcard[data-id]");
    if (!card) return;
    dragId = card.getAttribute("data-id");
    try { event.dataTransfer.setData("text/plain", dragId); } catch (e) { }
    event.dataTransfer.effectAllowed = "move";
  });
  document.addEventListener("dragover", function (event) {
    var col = event.target.closest && event.target.closest(".bcol[data-status]");
    if (!col || !dragId) return;
    event.preventDefault();
    document.querySelectorAll(".bcol.drop-on").forEach(function (c) { if (c !== col) c.classList.remove("drop-on"); });
    col.classList.add("drop-on");
  });
  document.addEventListener("dragleave", function (event) {
    var col = event.target.closest && event.target.closest(".bcol");
    if (col && !col.contains(event.relatedTarget)) col.classList.remove("drop-on");
  });
  document.addEventListener("drop", function (event) {
    var col = event.target.closest && event.target.closest(".bcol[data-status]");
    if (!col || !dragId) return;
    event.preventDefault();
    col.classList.remove("drop-on");
    var card = document.querySelector('.bcard[data-id="' + dragId + '"]');
    var select = card ? card.querySelector("select[name=status]") : null;
    dragId = null;
    if (!select || select.value === col.getAttribute("data-status")) return;
    select.value = col.getAttribute("data-status");
    if (typeof select.form.requestSubmit === "function") select.form.requestSubmit(); else select.form.submit();
  });
  document.addEventListener("dragend", function () {
    dragId = null;
    document.querySelectorAll(".bcol.drop-on").forEach(function (c) { c.classList.remove("drop-on"); });
  });
})();

// 사규 읽기: 조의 '수정' 단추 → 그 조의 메모·수정 칸을 열고 제목 칸으로
document.addEventListener("click", function (event) {
  var b = event.target.closest && event.target.closest("[data-open]");
  if (!b) return;
  var d = document.getElementById(b.getAttribute("data-open"));
  if (!d) return;
  d.open = true;
  d.scrollIntoView({ behavior: "smooth", block: "center" });
  var f = d.querySelector("form[action$='/edit'] input[name=title]");
  if (f) setTimeout(function () { f.focus(); }, 250);
});

// 감사 건 '관련 항목': 종류를 바꾸면 추천 목록도 그 종류로 바꾼다.
(function () {
    document.querySelectorAll('[data-link-kind]').forEach(function (sel) {
        var form = sel.closest('form');
        var input = form && form.querySelector('[data-link-value]');
        if (!input) return;
        function sync() { input.setAttribute('list', 'pick-' + sel.value); }
        sel.addEventListener('change', function () { input.value = ''; sync(); input.focus(); });
        sync();
    });
})();

// 일괄 처리 줄(data-bulk): 목록에서 하나라도 고르면 나타나고, 몇 건 골랐는지 보여 준다.
(function () {
    document.querySelectorAll('form[data-bulk]').forEach(function (form) {
        var id = form.getAttribute('id'); // form.id는 name="id" 칸과 겹친다
        function boxes() { return Array.prototype.slice.call(document.querySelectorAll('input[type=checkbox][name][form="' + id + '"]')); }
        function sync() {
            var n = boxes().filter(function (b) { return b.checked; }).length;
            form.hidden = n === 0;
            var c = form.querySelector('[data-bulk-count]');
            if (c) c.textContent = (c.getAttribute('data-bulk-label') || '고른 {n}건 삭제').replace('{n}', n);
        }
        document.addEventListener('change', function (e) {
            var t = e.target;
            if (t && t.getAttribute && t.getAttribute('form') === id) setTimeout(sync, 0);
        });
        sync();
    });
})();

// 연간 정기 업무 추가: 분기말·반기말 단추로 달을 한 번에 고른다.
document.addEventListener('click', function (e) {
    var b = e.target.closest && e.target.closest('[data-months]');
    if (!b) return;
    var form = b.closest('form');
    var on = (b.getAttribute('data-months') || '').split(',').filter(Boolean);
    for (var m = 1; m <= 12; m++) {
        var c = form.querySelector('input[name="m' + m + '"]');
        if (c) c.checked = on.indexOf(String(m)) >= 0;
    }
});

// 상세 분석 단추: 몇 건 골랐는지 보여 준다.
(function () {
    document.querySelectorAll('[data-pick-count]').forEach(function (btn) {
        var form = btn.closest('form'), id = form && form.getAttribute('id');
        if (!id) return;
        var base = btn.textContent;
        function sync() {
            var n = document.querySelectorAll('input[type=checkbox][name][form="' + id + '"]:checked').length;
            btn.textContent = n ? '고른 ' + n + '건 상세 분석' : base;
            btn.classList.toggle('btn-primary', n > 0);
        }
        document.addEventListener('change', function (e) { if (e.target && e.target.getAttribute && e.target.getAttribute('form') === id) setTimeout(sync, 0); });
        sync();
    });
})();

// 상세 분석: 판정을 고르면 바로 저장하고 줄 색을 바꾼다.
document.addEventListener('change', function (e) {
    var sel = e.target.closest && e.target.closest('select[data-verdict]');
    if (!sel) return;
    sel.className = 'v-' + sel.value.replace(/\s+/g, '.');
});

// 새 창의 링크(data-to-opener): 본 창이 있으면 본 창을 그 화면으로 바꾸고 이 창은 그대로 둔다.
document.addEventListener('click', function (e) {
    var a = e.target.closest && e.target.closest('a[data-to-opener]');
    if (!a || !window.opener || window.opener.closed) return;
    try { window.opener.location.href = a.href; window.opener.focus(); e.preventDefault(); } catch (err) { /* 다른 창이면 그냥 새 창으로 */ }
});


// 자료 한꺼번에 지우기: 확인 뒤 같은 양식을 '지우기'로 보낸다.
document.addEventListener('click', function (e) {
    var b = e.target.closest && e.target.closest('[data-bulk-delete]');
    if (!b) return;
    var form = b.form;
    if (!form || !window.confirm(b.getAttribute('data-bulk-delete'))) return;
    form.querySelector('input[name=op]').value = 'delete';
    if (typeof form.requestSubmit === 'function') form.requestSubmit(); else form.submit();
    setTimeout(function () { form.querySelector('input[name=op]').value = 'move'; }, 0);
});

// 자료 제목: 누르면 이 컴퓨터의 프로그램으로 원본을 연다. 열 수 없으면 내려받는다.
document.addEventListener('click', function (e) {
    var a = e.target.closest && e.target.closest('a[data-open-local]');
    if (!a || e.ctrlKey || e.metaKey || e.shiftKey) return;
    e.preventDefault();
    var xhr = new XMLHttpRequest();
    xhr.open('POST', a.getAttribute('data-open-local'));
    xhr.setRequestHeader('X-Audit-Fetch', '1');
    xhr.onload = function () {
        var msg = '';
        try { msg = JSON.parse(xhr.responseText).message || ''; } catch (err) { /* 무시 */ }
        if (xhr.status >= 200 && xhr.status < 300) { if (window.auditToast) window.auditToast(msg || '원본을 열었습니다.'); }
        else {
            if (window.auditToast) window.auditToast('이 컴퓨터에서 바로 열지 못해 내려받습니다.');
            var href = a.getAttribute('href') + '?dl=1';
            if (window.auditApp && window.auditApp.download) window.auditApp.download(href); else location.href = href;
        }
    };
    xhr.send();
});

// ───────────── 자료: 탐색기 방식 ─────────────
(function () {
    var root = document.getElementById('lib-root');
    var tree = document.getElementById('folder-tree');
    if (!root && !tree) return;
    var say = function (m) { if (window.auditToast && m) window.auditToast(m); };
    // 다시 읽은 뒤에 보여 줄 알림
    try { var pend = sessionStorage.getItem('libToast'); if (pend) { sessionStorage.removeItem('libToast'); setTimeout(function () { say(pend); }, 300); } } catch (e) { /* 무시 */ }
    function post(url, data, go) {
        var xhr = new XMLHttpRequest();
        xhr.open('POST', url);
        xhr.setRequestHeader('X-Audit-Fetch', '1');
        xhr.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded; charset=UTF-8');
        xhr.onload = function () {
            var r = {};
            try { r = JSON.parse(xhr.responseText); } catch (e) { r = { ok: false, message: '처리하지 못했습니다.' }; }
            if (r.ok) {
                try { sessionStorage.setItem('libToast', r.message || '처리했습니다.'); } catch (e) { /* 무시 */ }
                if (r.go || go) location.href = r.go || go; else location.reload();
            } else say(r.message || '처리하지 못했습니다.');
        };
        var body = [];
        Object.keys(data).forEach(function (k) {
            var v = data[k];
            (Array.isArray(v) ? v : [v]).forEach(function (x) { body.push(encodeURIComponent(k) + '=' + encodeURIComponent(x == null ? '' : x)); });
        });
        xhr.send(body.join('&'));
    }

    // 폴더 접기·펴기 (이 컴퓨터에 기억)
    var KEY = 'libFolded';
    var folded = {};
    try { folded = JSON.parse(localStorage.getItem(KEY) || '{}'); } catch (e) { folded = {}; }
    if (tree) {
        tree.querySelectorAll('.ft-node').forEach(function (n) {
            if (folded[n.getAttribute('data-folder-id')] && !n.querySelector('.ft-row.on')) n.classList.add('folded');
        });
        // 지금 폴더의 위 폴더는 항상 편다
        var on = tree.querySelector('.ft-row.on');
        for (var p = on && on.parentElement; p && p !== tree; p = p.parentElement) if (p.classList.contains('ft-node')) p.classList.remove('folded');
        tree.addEventListener('click', function (e) {
            var t = e.target.closest('[data-ft-toggle]');
            if (!t) return;
            var n = t.closest('.ft-node');
            n.classList.toggle('folded');
            folded[n.getAttribute('data-folder-id')] = n.classList.contains('folded');
            try { localStorage.setItem(KEY, JSON.stringify(folded)); } catch (err) { /* 무시 */ }
        });
    }

    // 이름 묻는 작은 칸 (프로그램 창에서는 브라우저 입력 창이 안 뜨므로 화면 안에 그린다)
    function ask(anchor, label, value, done) {
        var old = document.querySelector('.mini-ask');
        if (old) old.remove();
        var box = document.createElement('form');
        box.className = 'mini-ask';
        box.innerHTML = '<label><span></span><input maxlength="40" required></label><div><button type="submit" class="btn btn-primary btn-sm">확인</button> <button type="button" class="btn btn-sm">취소</button></div>';
        box.querySelector('span').textContent = label;
        var input = box.querySelector('input');
        input.value = value || '';
        document.body.appendChild(box);
        var r = anchor.getBoundingClientRect();
        box.style.left = Math.max(8, Math.min(window.innerWidth - 300, r.left)) + 'px';
        box.style.top = (r.bottom + window.scrollY + 4) + 'px';
        input.focus(); input.select();
        box.addEventListener('submit', function (e) { e.preventDefault(); var v = input.value.trim(); if (!v) return; box.remove(); done(v); });
        box.querySelector('button[type=button]').addEventListener('click', function () { box.remove(); });
        input.addEventListener('keydown', function (e) { if (e.key === 'Escape') box.remove(); });
    }

    // 새 폴더 단추
    document.addEventListener('click', function (e) {
        var b = e.target.closest && e.target.closest('[data-folder-new]');
        if (!b) return;
        var parent = b.getAttribute('data-parent') || '';
        ask(b, parent ? '새 하위 폴더 이름' : '새 폴더 이름', '', function (name) { post('/library/folder/new', { name: name, parentId: parent }); });
    });

    // 폴더 메뉴: 오른쪽 클릭 또는 ⋯
    var ctx = document.getElementById('folder-ctx');
    var target = null;
    function holder(el) { return el.closest && el.closest('[data-folder-id]'); }
    function openMenu(h, x, y) {
        if (!ctx) return;
        target = h;
        var depth = +h.getAttribute('data-depth'), arch = h.getAttribute('data-archive') === 'true', top = !h.getAttribute('data-parent');
        ctx.querySelector('[data-ctx=new]').hidden = depth >= 3;
        ctx.querySelector('[data-ctx=top]').hidden = top;
        ctx.querySelector('[data-ctx=archive]').hidden = arch;
        ctx.hidden = false;
        ctx.style.left = Math.min(window.innerWidth - ctx.offsetWidth - 8, x) + 'px';
        ctx.style.top = (y + window.scrollY) + 'px';
        var first = ctx.querySelector('button:not([hidden])');
        if (first) first.focus();
    }
    document.addEventListener('contextmenu', function (e) {
        var h = holder(e.target);
        if (!h || !h.matches('.ft-node, tr.fd')) return;
        e.preventDefault();
        openMenu(h, e.clientX, e.clientY);
    });
    document.addEventListener('click', function (e) {
        var m = e.target.closest && e.target.closest('[data-folder-menu]');
        if (m) { e.preventDefault(); var r = m.getBoundingClientRect(); openMenu(holder(m), r.left, r.bottom); return; }
        if (ctx && !ctx.hidden && !e.target.closest('#folder-ctx')) ctx.hidden = true;
    });
    document.addEventListener('keydown', function (e) {
        if (e.key === 'Escape' && ctx) ctx.hidden = true;
        if (e.key === 'F2') {
            var h = document.activeElement && holder(document.activeElement);
            if (h) { e.preventDefault(); target = h; act('rename', document.activeElement); }
        }
    });
    function act(what, anchor) {
        var h = target;
        if (!h) return;
        var id = h.getAttribute('data-folder-id'), name = h.getAttribute('data-folder-name');
        var current = root ? root.getAttribute('data-current') : '';
        if (ctx) ctx.hidden = true;
        if (what === 'new') ask(anchor, "'" + name + "' 안에 만들 폴더 이름", '', function (n) { post('/library/folder/new', { name: n, parentId: id }); });
        else if (what === 'rename') ask(anchor, '새 이름', name, function (n) { post('/library/folder/' + id + '/rename', { name: n }); });
        else if (what === 'top') post('/library/folder/' + id + '/move', { parentId: '' });
        else if (what === 'archive') post('/library/folder/' + id + '/archive', {});
        else if (what === 'delete') {
            if (window.confirm("폴더 '" + name + "'를 지울까요? 안에 든 자료와 폴더는 한 단계 위로 올립니다.")) post('/library/folder/' + id + '/delete', { current: current });
        }
    }
    if (ctx) ctx.addEventListener('click', function (e) {
        var b = e.target.closest('[data-ctx]');
        if (b) act(b.getAttribute('data-ctx'), (target && (target.querySelector('.ft-name') || target)) || b);
    });

    // 끌어 놓기: 자료 줄·폴더를 폴더(왼쪽 나무·폴더 줄·위치 표시)에 놓으면 옮긴다
    var drag = null;
    document.addEventListener('dragstart', function (e) {
        var row = e.target.closest && e.target.closest('tr[data-doc-id]');
        var fold = e.target.closest && (e.target.closest('.ft-row') || e.target.closest('tr.fd'));
        if (row) {
            var id = row.getAttribute('data-doc-id');
            var box = row.querySelector('input[type=checkbox][name=id]');
            var ids = box && box.checked ? Array.prototype.map.call(document.querySelectorAll('input[name=id][form=lib-bulk]:checked'), function (c) { return c.value; }) : [id];
            drag = { kind: 'docs', ids: ids };
            try { e.dataTransfer.setData('text/plain', ids.length + '건'); e.dataTransfer.effectAllowed = 'move'; } catch (err) { /* 무시 */ }
            document.body.classList.add('lib-dragging');
        } else if (fold) {
            var h = holder(fold);
            drag = { kind: 'folder', id: h.getAttribute('data-folder-id') };
            try { e.dataTransfer.setData('text/plain', h.getAttribute('data-folder-name')); e.dataTransfer.effectAllowed = 'move'; } catch (err) { /* 무시 */ }
            document.body.classList.add('lib-dragging');
        }
    });
    document.addEventListener('dragend', function () { drag = null; document.body.classList.remove('lib-dragging'); clearDrop(); });
    function clearDrop() { document.querySelectorAll('.drop-on').forEach(function (x) { x.classList.remove('drop-on'); }); }
    function dropTarget(el) { return el.closest && (el.closest('[data-drop-folder]') || el.closest('[data-drop-star]')); }
    document.addEventListener('dragover', function (e) {
        if (!drag) return;
        var t = dropTarget(e.target);
        if (!t) return;
        if (t.hasAttribute('data-drop-star') && drag.kind !== 'docs') return;
        e.preventDefault();
        clearDrop();
        t.classList.add('drop-on');
    });
    document.addEventListener('drop', function (e) {
        if (!drag) return;
        var t = dropTarget(e.target);
        if (!t) return;
        e.preventDefault();
        var d = drag; drag = null; clearDrop();
        if (t.hasAttribute('data-drop-star')) { post('/library/bulk', { op: 'star', id: d.ids }); return; }
        var h = holder(t), fid = h ? h.getAttribute('data-folder-id') : t.getAttribute('data-folder-id');
        if (!fid) return;
        if (d.kind === 'docs') post('/library/bulk', { op: 'move', id: d.ids, categoryId: fid });
        else if (d.id !== fid) post('/library/folder/' + d.id + '/move', { parentId: fid });
    });

    // 윈도우에서 파일을 끌어 놓기 (브라우저로 열었을 때. 프로그램 창에서는 창이 직접 받는다)
    var cover = document.querySelector('[data-drop-cover]');
    if (root && cover) {
        var hasFiles = function (e) { return e.dataTransfer && Array.prototype.indexOf.call(e.dataTransfer.types || [], 'Files') >= 0; };
        document.addEventListener('dragenter', function (e) { if (!drag && hasFiles(e)) cover.hidden = false; });
        cover.addEventListener('dragover', function (e) { e.preventDefault(); });
        cover.addEventListener('dragleave', function (e) { if (e.target === cover) cover.hidden = true; });
        cover.addEventListener('drop', function (e) {
            e.preventDefault();
            cover.hidden = true;
            var files = e.dataTransfer.files;
            if (!files || !files.length) return;
            var fd = new FormData();
            for (var i = 0; i < files.length; i++) fd.append('upload', files[i]);
            fd.append('categoryId', root.getAttribute('data-folder'));
            var tags = document.querySelector('[data-tag-input]');
            if (tags) fd.append('tags', tags.value);
            say(files.length + '개 파일을 올리는 중…');
            var xhr = new XMLHttpRequest();
            xhr.open('POST', '/library/upload');
            xhr.setRequestHeader('X-Audit-Fetch', '1');
            xhr.onload = function () {
                var r = {};
                try { r = JSON.parse(xhr.responseText); } catch (err) { r = { message: '올리지 못했습니다.' }; }
                try { sessionStorage.setItem('libToast', r.message || ''); } catch (err) { /* 무시 */ }
                location.reload();
            };
            xhr.send(fd);
        });
    }

    // 올리기 칸 열고 닫기, 태그 칩
    document.addEventListener('click', function (e) {
        var u = e.target.closest && e.target.closest('[data-upload-toggle]');
        if (u) { var up = document.getElementById('upload'); if (up) { up.classList.toggle('open'); if (up.classList.contains('open')) up.scrollIntoView({ block: 'nearest' }); } return; }
        var t = e.target.closest && e.target.closest('[data-tag-pick]');
        if (t) {
            var input = t.closest('form').querySelector('[data-tag-input]');
            var list = input.value.split(/[,，]/).map(function (x) { return x.trim(); }).filter(Boolean);
            var v = t.textContent.trim(), i = list.indexOf(v);
            if (i >= 0) list.splice(i, 1); else list.push(v);
            input.value = list.join(', ');
            t.classList.toggle('on', i < 0);
        }
    });

    // 찾기 저장
    document.addEventListener('click', function (e) {
        var b = e.target.closest && e.target.closest('[data-save-search]');
        if (!b) return;
        var params = new URLSearchParams(location.search);
        params.delete('page'); params.delete('upload');
        ['q', 'tag', 'kind', 'f', 'view', 'scope', 'sort'].forEach(function (k) { if (params.get(k) === '') params.delete(k); });
        var q = params.get('q') || params.get('tag') || '찾기';
        ask(b, '저장할 이름', q, function (name) { post('/library/saved', { name: name, params: params.toString() }); });
    });

    // 고른 자료: 즐겨찾기·태그 붙이기
    document.addEventListener('click', function (e) {
        var b = e.target.closest && e.target.closest('[data-bulk-op]');
        if (!b) return;
        var form = b.form;
        if (b.getAttribute('data-bulk-op') === 'tag' && !form.querySelector('input[name=tags]').value.trim()) { say('붙일 태그를 적으세요.'); form.querySelector('input[name=tags]').focus(); return; }
        form.querySelector('input[name=op]').value = b.getAttribute('data-bulk-op');
        if (typeof form.requestSubmit === 'function') form.requestSubmit(); else form.submit();
        setTimeout(function () { form.querySelector('input[name=op]').value = 'move'; }, 0);
    });
})();

// ───────────── 카드 안 긴 목록: 처음 몇 줄만, '전체 보기'로 펼친다 ─────────────
(function () {
    var LIMIT = 6;
    var lists = document.querySelectorAll('.card ul.wlist, .card .rel-list, .card .set-list, .card .people-top, .card table.data > tbody, .card table.mini > tbody, .card .result-list');
    Array.prototype.forEach.call(lists, function (list) {
        if (list.closest('[data-nofold]')) return;
        // 입력칸이 든 목록(설정 표·판정 표 등)은 접지 않는다
        if (list.querySelector('input:not([type=checkbox]):not([type=hidden]), select, textarea')) return;
        var items = Array.prototype.filter.call(list.children, function (c) { return c.tagName === 'LI' || c.tagName === 'TR'; });
        if (items.length <= LIMIT + 2) return;
        // 화면 안에 이미 선택·편집 중인 줄이 있으면(예: 주소의 #) 그 줄은 보이게 둔다
        var keep = location.hash && list.querySelector(location.hash.replace(/[^#\w-]/g, ''));
        items.slice(LIMIT).forEach(function (it) { if (it !== keep) it.classList.add('fold-hide'); });
        var more = document.createElement('button');
        more.type = 'button';
        more.className = 'fold-more no-print';
        var rest = items.length - LIMIT;
        more.textContent = '전체 보기 (' + rest + '건 더)';
        more.setAttribute('aria-expanded', 'false');
        more.addEventListener('click', function () {
            var open = more.getAttribute('aria-expanded') === 'true';
            items.slice(LIMIT).forEach(function (it) { it.classList.toggle('fold-hide', open); });
            more.setAttribute('aria-expanded', open ? 'false' : 'true');
            more.textContent = open ? '전체 보기 (' + rest + '건 더)' : '접기';
            if (open) list.closest('.card').scrollIntoView({ block: 'nearest' });
        });
        var host = list.tagName === 'TBODY' ? list.closest('table') : list;
        (host.closest('.table-wrap') || host).insertAdjacentElement('afterend', more);
    });
})();
