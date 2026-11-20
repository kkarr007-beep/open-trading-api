(function () {
  var navToggle = document.querySelector(".nav-toggle");
  function setNav(open) {
    document.body.classList.toggle("nav-open", open);
    if (navToggle) navToggle.setAttribute("aria-expanded", open ? "true" : "false");
  }
  if (navToggle) navToggle.addEventListener("click", function () { setNav(!document.body.classList.contains("nav-open")); });
  document.addEventListener("click", function (event) {
    if (!document.body.classList.contains("nav-open")) return;
    if (event.target.closest(".nav-toggle") || event.target.closest("#menu")) return;
    setNav(false);
  });

  // 고른 파일의 이름과 확장자, 크기를 그대로 보여 준다.
  // 표시 이름 칸이 비어 있으면 파일 이름을 넣어 둔다. 확장자가 있어야 내려받은 파일이 제 프로그램으로 열린다.
  function fileSize(bytes) {
    if (!bytes && bytes !== 0) return "";
    var units = ["B", "KB", "MB", "GB"];
    var n = bytes, i = 0;
    while (n >= 1024 && i < units.length - 1) { n = n / 1024; i++; }
    return (i === 0 ? n : n.toFixed(1)) + " " + units[i];
  }

  document.querySelectorAll(".drop").forEach(function (zone) {
    var input = zone.querySelector("input[type=file]");
    var label = zone.querySelector("[data-file-name]");
    if (!input) return;
    var form = input.closest("form");
    var nameField = form ? form.querySelector("input[name=displayName]") : null;
    var autoFilled = false;

    // 고른 파일 이름은 브라우저가 파일 칸에 이미 보여 줍니다.
    // 여기서는 크기를 덧붙이고 표시 이름 칸을 채워 둘 뿐입니다.
    // 이 코드가 돌지 않아도 사람은 파일 칸에서 이름을 볼 수 있습니다.
    function show() {
      var file = input.files && input.files.length ? input.files[0] : null;
      if (!file) {
        if (label) { label.textContent = ""; label.hidden = true; }
        return;
      }
      if (label) {
        var size = fileSize(file.size);
        label.textContent = size ? "고른 파일: " + file.name + " · " + size : "고른 파일: " + file.name;
        label.hidden = false;
        label.setAttribute("title", file.name);
      }
      if (nameField && (nameField.value === "" || autoFilled)) {
        nameField.value = file.name;
        autoFilled = true;
      }
    }

    if (nameField) {
      nameField.addEventListener("input", function () { autoFilled = false; });
    }
    ["dragenter", "dragover"].forEach(function (type) {
      zone.addEventListener(type, function (event) {
        event.preventDefault();
        zone.classList.add("drag");
      });
    });
    zone.addEventListener("dragleave", function () { zone.classList.remove("drag"); });
    zone.addEventListener("drop", function (event) {
      event.preventDefault();
      zone.classList.remove("drag");
      if (!event.dataTransfer || !event.dataTransfer.files.length) return;
      input.files = event.dataTransfer.files;
      show();
    });
    input.addEventListener("change", show);
    show();
  });

  // 찾기 칸으로 건너뛰는 / 단축키. 입력 중에는 가로채지 않는다.
  document.addEventListener("keydown", function (event) {
    if (event.key !== "/" || event.ctrlKey || event.metaKey || event.altKey) return;
    var active = document.activeElement;
    if (active && (active.tagName === "INPUT" || active.tagName === "TEXTAREA" || active.tagName === "SELECT" || active.isContentEditable)) return;
    var box = document.querySelector("[data-search-focus]");
    if (!box) return;
    event.preventDefault();
    box.focus();
    if (typeof box.select === "function") box.select();
  });

  document.addEventListener("click", function (event) {
    if (event.target instanceof HTMLDialogElement && event.target.open) event.target.close();
    var opener = event.target.closest("[data-dialog]");
    if (opener) {
      var dialog = document.getElementById(opener.getAttribute("data-dialog"));
      if (dialog && typeof dialog.showModal === "function") dialog.showModal();
    }
    var closer = event.target.closest("[data-close]");
    if (closer) {
      var parent = closer.closest("dialog");
      if (parent) parent.close();
    }
    var copy = event.target.closest("[data-copy]");
    if (copy && navigator.clipboard) {
      var text = copy.getAttribute("data-copy") || "";
      navigator.clipboard.writeText(text).then(function () {
        copy.setAttribute("data-copied", "yes");
      }).catch(function () { });
    }
  });

  var dirty = false;
  document.addEventListener("input", function (event) {
    if (event.target.closest("form[data-track]")) dirty = true;
  });
  document.addEventListener("click", function (event) {
    var link = event.target.closest("a[href]");
    if (!link || !dirty) return;
    if (!window.confirm("저장하지 않은 입력이 있습니다. 이동할까요?")) event.preventDefault();
  });

  function toast(message, tone) {
    var host = document.querySelector(".toasts");
    if (!host) return;
    var node = document.createElement("div");
    node.className = "toast " + (tone || "ok");
    node.setAttribute("role", tone === "danger" ? "alert" : "status");
    var text = document.createElement("p");
    text.textContent = message || "";
    node.appendChild(text);
    if (tone !== "ok") {
      var close = document.createElement("button");
      close.type = "button";
      close.className = "btn btn-quiet";
      close.textContent = "닫기";
      close.addEventListener("click", function () { node.remove(); });
      node.appendChild(close);
    }
    host.appendChild(node);
    if (tone === "ok") setTimeout(function () { node.remove(); }, 4200);
  }
  // 저장하지 않고 고치던 칸(다른 양식)을 기억해 두었다가, 화면을 새로 그린 뒤 그대로 돌려놓는다.
  document.addEventListener("input", function (e) { var f = e.target.closest && e.target.closest("main form"); if (f) f.dataset.touched = "1"; }, true);
  document.addEventListener("change", function (e) { var f = e.target.closest && e.target.closest("main form"); if (f) f.dataset.touched = "1"; }, true);
  function formKey(f, all) {
    var a = f.getAttribute("action") || "", same = all.filter(function (x) { return (x.getAttribute("action") || "") === a; });
    return a + "#" + same.indexOf(f);
  }
  function snapshot(main, except) {
    var forms = Array.prototype.slice.call(main.querySelectorAll("form"));
    var out = [];
    forms.forEach(function (f) {
      if (f === except || f.dataset.touched !== "1" || (f.method || "").toLowerCase() === "get") return;
      var fields = [];
      Array.prototype.forEach.call(f.elements, function (el) {
        if (!el.name || el.name === "version" || el.type === "file" || el.type === "submit" || el.type === "button") return;
        fields.push({ name: el.name, value: el.value, checked: el.checked, type: el.type, idx: fields.filter(function (x) { return x.name === el.name; }).length });
      });
      var rich = Array.prototype.map.call(f.querySelectorAll(".rte-body"), function (b) { return b.innerHTML; });
      out.push({ key: formKey(f, forms), fields: fields, rich: rich });
    });
    var open = Array.prototype.map.call(main.querySelectorAll("details[open][id]"), function (d) { return d.id; });
    return { forms: out, open: open };
  }
  function restoreSnap(main, snap) {
    var forms = Array.prototype.slice.call(main.querySelectorAll("form")), n = 0;
    snap.open.forEach(function (id) { var d = document.getElementById(id); if (d && d.tagName === "DETAILS") d.open = true; });
    if (window.richEditorInit) window.richEditorInit(main);
    snap.forms.forEach(function (s) {
      var f = forms.filter(function (x) { return formKey(x, forms) === s.key; })[0];
      if (!f) return;
      s.fields.forEach(function (fd) {
        var els = Array.prototype.filter.call(f.elements, function (el) { return el.name === fd.name; });
        var el = fd.type === "radio" || fd.type === "checkbox" ? els.filter(function (x) { return x.value === fd.value; })[0] : els[fd.idx];
        if (!el) return;
        if (fd.type === "radio" || fd.type === "checkbox") el.checked = fd.checked; else el.value = fd.value;
      });
      var bodies = f.querySelectorAll(".rte-body");
      s.rich.forEach(function (h, i) { if (bodies[i]) bodies[i].innerHTML = h; });
      f.querySelectorAll("textarea[data-rich-ready]").forEach(function (t) { if (t.__richSync) t.__richSync(); });
      f.dataset.touched = "1";
      n++;
    });
    return n;
  }
  function refreshMain(focusSelector, savedForm) {
    var y = window.scrollY;
    var main0 = document.querySelector("main");
    var snap = main0 ? snapshot(main0, savedForm) : { forms: [], open: [] };
    return fetch(location.href, { headers: { "X-Audit-Fetch": "page" } }).then(function (response) { return response.text(); }).then(function (html) {
      var doc = new DOMParser().parseFromString(html, "text/html");
      var next = doc.querySelector("main");
      var main = document.querySelector("main");
      if (next && main) main.innerHTML = next.innerHTML;
      var kept = main ? restoreSnap(main, snap) : 0;
      if (kept) toast("다른 칸에 쓰던 내용은 그대로 두었습니다. 그 칸도 저장 단추를 눌러 저장하세요.");
      window.scrollTo(0, y);
      var focus = focusSelector ? document.querySelector(focusSelector) : null;
      if (focus) focus.focus();
      bindPreview();
    });
  }
  function bindPreview() {
    var preview = document.getElementById("doc-preview");
    if (!preview) return;
    var form = preview.closest("form");
    if (!form || form.dataset.previewBound === "1") return;
    form.dataset.previewBound = "1";
    function refresh() {
      var year = form.querySelector("[name=year]");
      var category = form.querySelector("[name=category]");
      if (!year || !category) return;
      fetch("/cases/new?handler=Preview&year=" + encodeURIComponent(year.value) + "&category=" + encodeURIComponent(category.value))
        .then(function (response) { return response.text(); })
        .then(function (text) { if (text) preview.value = "자동 부여(예정: " + text + ")"; });
    }
    yearListen(form, "year", refresh);
    yearListen(form, "category", refresh);
  }
  function yearListen(form, name, fn) {
    var input = form.querySelector("[name=" + name + "]");
    if (input) input.addEventListener("change", fn);
  }
  function finishButton(button, label) {
    if (!button) return;
    button.disabled = false;
    button.textContent = label;
    var form = button.form;
    if (form) delete form.dataset.submitted;
  }
  function sendForm(form) {
    if (form.dataset.submitted === "1") return;
    form.dataset.submitted = "1";
    var button = form.querySelector("button[type=submit]");
    var label = button ? button.textContent : "";
    if (button) {
      button.disabled = true;
      button.textContent = "저장 중…";
    }
    var data = new FormData(form);
    var xhr = new XMLHttpRequest();
    xhr.open(form.method || "POST", form.action || location.href);
    xhr.setRequestHeader("X-Audit-Fetch", "1");
    var bar = form.querySelector(".progress span");
    if (!bar && form.querySelector("input[type=file]")) {
      var track = document.createElement("div");
      track.className = "progress";
      bar = document.createElement("span");
      track.appendChild(bar);
      form.appendChild(track);
    }
    if (xhr.upload && bar) {
      xhr.upload.onprogress = function (event) {
        if (event.lengthComputable) bar.style.width = Math.round(event.loaded * 100 / event.total) + "%";
      };
    }
    xhr.onload = function () {
      if (xhr.responseURL && xhr.responseURL.indexOf("/account/login") >= 0) {
        location.href = xhr.responseURL;
        return;
      }
      var payload = null;
      try { payload = JSON.parse(xhr.responseText); } catch (e) { payload = null; }
      if (xhr.status === 503 || (payload && payload.status === 503)) {
        toast((payload && payload.message) || "읽기 전용입니다. 변경은 저장되지 않습니다.", "warn");
        finishButton(button, label);
        return;
      }
      if (!payload) {
        toast(xhr.status === 400 ? "요청을 확인하지 못했습니다. 페이지를 다시 여세요." : "결과를 표시하지 못했습니다.", "danger");
        finishButton(button, label);
        return;
      }
      toast(payload.message || (payload.ok ? "저장했습니다." : "저장하지 못했습니다."), payload.tone || (payload.ok ? "ok" : "danger"));
      if (!payload.ok) {
        var old = form.querySelector(".field-error");
        if (old) old.remove();
        var note = document.createElement("p");
        note.className = "field-error";
        note.textContent = payload.message || "";
        form.appendChild(note);
        var field = form.querySelector("input:invalid, textarea:invalid, input, textarea");
        if (field) field.focus();
        finishButton(button, label);
        return;
      }
      if (payload.go) {
        // 같은 화면의 다른 위치(#…)로 가라는 답이면 주소만 바뀌고 새로 그려지지 않는다. 그때는 다시 읽는다.
        var to = new URL(payload.go, location.href);
        if (to.pathname === location.pathname && to.search === location.search) {
          if (to.hash) history.replaceState(null, '', to.hash);
          location.reload();
        } else location.href = to.href;
        return;
      }
      if (payload.reload) { location.reload(); return; }
      if (form.hasAttribute('data-reload')) { location.reload(); return; }
      var section = form.closest("section");
      var selector = section && section.id ? "#" + section.id + " button" : "h1";
      form.dataset.touched = "";
      refreshMain(selector, form).finally(function () { finishButton(button, label); });
    };
    xhr.onerror = function () {
      toast("저장하지 못했습니다.", "danger");
      finishButton(button, label);
    };
    xhr.send(data);
  }
  document.addEventListener("submit", function (event) {
    var form = event.target;
    if (!(form instanceof HTMLFormElement)) return;
    if (form.dataset.submitted === "1") {
      event.preventDefault();
      return;
    }
    if (form.hasAttribute("data-write")) {
      event.preventDefault();
      sendForm(form);
      return;
    }
    form.dataset.submitted = "1";
    form.setAttribute("aria-busy", "true");
  });
  // 예전 판이 브라우저에 남긴 '최근 본 건' 값을 치웁니다. 더 쓰지 않습니다.
  try { localStorage.removeItem("audit-recent"); } catch (e) { }
  bindPreview();

  function applyTheme(choice) {
    var dark = choice === "dark" || (choice !== "light" && window.matchMedia("(prefers-color-scheme: dark)").matches);
    document.documentElement.setAttribute("data-theme", dark ? "dark" : "light");
    document.documentElement.setAttribute("data-theme-choice", choice);
    try { localStorage.setItem("audit-theme", choice); } catch (e) { }
    document.querySelectorAll("[data-theme-set]").forEach(function (button) {
      button.setAttribute("aria-pressed", button.getAttribute("data-theme-set") === choice ? "true" : "false");
    });
  }
  document.querySelectorAll("[data-theme-set]").forEach(function (button) {
    button.addEventListener("click", function () { applyTheme(button.getAttribute("data-theme-set") || "system"); });
  });
  var stored = document.documentElement.getAttribute("data-theme-choice") || "system";
  try { stored = localStorage.getItem("audit-theme") || stored; } catch (e) { }
  applyTheme(stored);
  window.matchMedia("(prefers-color-scheme: dark)").addEventListener("change", function () {
    if ((document.documentElement.getAttribute("data-theme-choice") || "system") === "system") applyTheme("system");
  });

  document.addEventListener("click", function (event) {
    var link = event.target.closest("[data-preview]");
    if (!link) return;
    var kind = link.getAttribute("data-preview");
    if (!kind || kind === "download") return;
    var dialog = document.getElementById("preview-dialog");
    if (!dialog || typeof dialog.showModal !== "function") return;
    event.preventDefault();
    var title = document.getElementById("preview-title");
    var frame = document.getElementById("preview-frame");
    var img = document.getElementById("preview-img");
    if (title) title.textContent = link.getAttribute("data-title") || "파일 미리보기";
    var meta = document.getElementById("preview-meta");
    var ico = document.getElementById("preview-ico");
    var download = document.getElementById("preview-download");
    if (meta) meta.textContent = link.getAttribute("data-meta") || "";
    if (ico) {
      ico.textContent = link.getAttribute("data-tile") || "FILE";
      ico.className = "file-ico ico-" + (link.getAttribute("data-tile-kind") || "file");
    }
    if (frame) { frame.hidden = true; frame.removeAttribute("src"); }
    if (img) { img.hidden = true; img.removeAttribute("src"); }
    var url = link.getAttribute("href");
    if (download && url) {
      download.href = url + (url.indexOf("?") >= 0 ? "&" : "?") + "dl=1";
    }
    if (kind === "image" && img) {
      img.alt = link.getAttribute("data-title") || "";
      img.src = url;
      img.hidden = false;
    } else if (frame) {
      frame.src = url;
      frame.hidden = false;
    }
    dialog.showModal();
  });
  document.addEventListener("close", function (event) {
    if (event.target && event.target.id !== "preview-dialog") return;
    var frame = document.getElementById("preview-frame");
    var img = document.getElementById("preview-img");
    if (frame) frame.removeAttribute("src");
    if (img) img.removeAttribute("src");
  }, true);
})();

// ── 자체 창에서 쓰는 기능: 저장 창, 복사, 인쇄, 전체 찾기 ──
(function () {
  function app() { return window.auditApp || null; }
  function toast(message) {
    var host = document.querySelector(".toasts");
    if (!host) return;
    var node = document.createElement("div");
    node.className = "toast ok";
    node.setAttribute("role", "status");
    var text = document.createElement("p");
    text.textContent = message || "";
    node.appendChild(text);
    host.appendChild(node);
    setTimeout(function () { node.remove(); }, 4600);
  }
  window.auditToast = toast;

  function isDownload(url) {
    try {
      var u = new URL(url, location.href);
      return u.searchParams.has("dl") || /\/export$/.test(u.pathname);
    } catch (e) { return false; }
  }

  document.addEventListener("click", function (event) {
    var printer = event.target.closest("[data-print]");
    if (printer) {
      event.preventDefault();
      if (app()) app().print(); else window.print();
      return;
    }
    var copy = event.target.closest("[data-copy]");
    if (copy && app()) {
      app().copy(copy.getAttribute("data-copy") || "");
      return;
    }
    if (!app()) return;
    var link = event.target.closest("a[href]");
    if (!link) return;
    // 창 안에서는 PDF를 펼치지 못하므로 이 컴퓨터의 프로그램으로 연다.
    if (link.getAttribute("data-preview") === "pdf") {
      var m = /\/cases\/([0-9a-f]{32})\/files\/([0-9a-f]{32})/.exec(link.getAttribute("href") || "");
      if (m) {
        event.preventDefault();
        event.stopImmediatePropagation();
        var data = new FormData();
        data.append("attachmentId", m[2]);
        fetch("/cases/" + m[1] + "/open", { method: "POST", headers: { "X-Audit-Fetch": "1" }, body: data })
          .then(function (r) { return r.json(); }).then(function (p) { toast(p.message); }).catch(function () { toast("열지 못했습니다."); });
        return;
      }
    }
    if (isDownload(link.href)) {
      event.preventDefault();
      event.stopImmediatePropagation();
      app().download(link.href);
    }
  }, true);

  document.addEventListener("submit", function (event) {
    var form = event.target;
    if (!app() || !(form instanceof HTMLFormElement)) return;
    if ((form.method || "get").toLowerCase() !== "get" || !/\/export$/.test(form.getAttribute("action") || "")) return;
    event.preventDefault();
    event.stopImmediatePropagation();
    app().download(form.getAttribute("action") + "?" + new URLSearchParams(new FormData(form)).toString());
  }, true);

  // 전체 찾기로 건너뛰기
  document.addEventListener("keydown", function (event) {
    if (!(event.ctrlKey || event.metaKey) || event.key.toLowerCase() !== "k") return;
    var box = document.querySelector("[data-global-search]");
    if (!box) return;
    event.preventDefault();
    box.focus();
    box.select();
  });

  // 날짜 칸에 달력이 뜨지 않는 창에서는 적는 꼴을 알려 준다.
  function dateHints() {
    var probe = document.createElement("input");
    probe.setAttribute("type", "date");
    if (probe.type === "date") return;
    document.querySelectorAll("input[type=date]").forEach(function (input) {
      if (!input.placeholder) input.placeholder = "예: 2026-12-31";
    });
  }
  dateHints();
  window.addEventListener("audit-app-ready", dateHints);
  new MutationObserver(dateHints).observe(document.querySelector("main") || document.body, { childList: true });
})();

// 이전 화면으로
document.addEventListener("click", function (event) {
  if (!event.target.closest("[data-back]")) return;
  event.preventDefault();
  if (history.length > 1) history.back(); else location.href = "/todo";
});

// 머리 고정: 스크롤해도 '무슨 화면인지·어디서 찾는지·표의 어느 칸인지'가 보이게 한다.
// 위 줄(화면 제목·전체 찾기) 아래에 그 화면의 찾기·거르기·탭 줄을 붙이고, 표 제목 줄은 그 아래에 붙인다.
(function () {
  var BAR = ".sticky-tools, .hitbar, .lib-bulk, .section-tabs, .toolbar, .filters, .search-row, .seg-bar, .ex-head";
  var main = document.getElementById("main");
  if (!main) return;
  function headH() {
    var h = document.querySelector(".v2-head");
    if (!h) return 0;
    var p = getComputedStyle(h).position;
    return p === "sticky" || p === "fixed" ? Math.round(h.getBoundingClientRect().height) : 0;
  }
  function inFloating(el) { return !!el.closest("dialog, details, .v2-drawer, .ctx, [hidden], .rte, .modal, .rfind"); }
  function scrolls(el) { var cs = getComputedStyle(el); return /(auto|scroll|hidden|clip)/.test(cs.overflowX + cs.overflowY); }
  function pin() {
    var top = headH(), stuck = [];
    Array.prototype.forEach.call(main.querySelectorAll(BAR), function (b) {
      if (inFloating(b) || b.parentElement.closest(BAR)) return;            // 대화창·펼침 안, 다른 줄 안의 줄은 빼고
      if (b.closest("table") || b.offsetHeight === 0 || b.offsetHeight > window.innerHeight * 0.35) return;
      var off = top;
      stuck.forEach(function (s) { if (s.parentElement.contains(b) && s.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING) off += s.offsetHeight; });
      b.classList.add("pin-bar");
      b.style.top = off + "px";
      stuck.push(b);
      if (ro) { heights.set(b, b.offsetHeight); if (!b._pinWatch) { b._pinWatch = 1; ro.observe(b); } }
    });
    // 편집기 도구 줄도 고정된 줄 아래에 붙인다
    Array.prototype.forEach.call(main.querySelectorAll(".rte-bar, .reg-toc"), function (rb) {
      if (rb.closest("dialog, .v2-drawer")) return;
      var off = top;
      stuck.forEach(function (s) { if (s.parentElement.contains(rb) && s.compareDocumentPosition(rb) & Node.DOCUMENT_POSITION_FOLLOWING) off += s.offsetHeight; });
      rb.style.setProperty("--rte-top", off + "px");
      if (rb.classList.contains("reg-toc")) main.style.setProperty("--toc-top", off + "px");
    });
    Array.prototype.forEach.call(main.querySelectorAll("table"), function (t) {
      var head = t.tHead;
      if (!head || inFloating(t) || t.closest(".rich, .rte-body, .doc-view, .ctblw")) return;   // 글 본문 속 표는 빼고
      // 표를 감싼 틀이 스크롤 틀이면 붙지 않으므로, 표가 화면 너비에 들어가면 틀을 연다
      var ok = true;
      for (var a = t.parentElement; a && a !== main; a = a.parentElement) {
        if (!scrolls(a)) continue;
        if (a.scrollWidth <= a.clientWidth + 1) a.classList.add("pin-open"); else { ok = false; break; }
      }
      if (!ok) return;
      var off = top;
      stuck.forEach(function (s) { if (s.parentElement.contains(t) && s.compareDocumentPosition(t) & Node.DOCUMENT_POSITION_FOLLOWING) off += s.offsetHeight; });
      t.classList.add("pin-th");
      Array.prototype.forEach.call(head.rows[0] ? head.rows[0].cells : [], function (c) { c.style.top = off + "px"; });
      // 제목 줄이 두 줄 이상이면 다음 줄은 그 아래로
      for (var r = 1; r < head.rows.length; r++) {
        off += head.rows[r - 1].offsetHeight;
        Array.prototype.forEach.call(head.rows[r].cells, function (c) { c.style.top = off + "px"; });
      }
    });
  }
  var timer;
  function later() { clearTimeout(timer); timer = setTimeout(pin, 120); }
  var heights = new WeakMap();
  var ro = window.ResizeObserver ? new ResizeObserver(function (es) {
    var changed = es.some(function (e) { var h = e.target.offsetHeight, was = heights.get(e.target); heights.set(e.target, h); return was !== undefined && was !== h; });
    if (changed) later();
  }) : null;
  window.pinHeads = pin;
  // 본문 제목(건·사규·업무 이름)이 위로 사라지면 위 줄의 화면 이름 옆에 보여 준다
  var sub = document.getElementById("v2-sub"), seen = null, io = null;
  function watchTitle() {
    if (!sub || !window.IntersectionObserver) return;
    var h1 = main.querySelector("h1");
    if (h1 === seen) return;
    seen = h1;
    if (io) io.disconnect();
    sub.hidden = true;
    if (!h1) return;
    var top = headH();
    io = new IntersectionObserver(function (es) {
      var gone = !es[0].isIntersecting && es[0].boundingClientRect.top < top;
      var t = (h1.textContent || "").replace(/\s+/g, " ").trim();
      var name = (document.getElementById("v2-title") || {}).textContent || "";
      sub.textContent = t;
      sub.title = t;
      sub.hidden = !gone || !t || t === name.trim();
    }, { rootMargin: "-" + top + "px 0px 0px 0px", threshold: 0 });
    io.observe(h1);
  }
  var pin0 = pin;
  pin = function () { pin0(); watchTitle(); };
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", pin); else pin();
  window.addEventListener("load", later);
  window.addEventListener("resize", later);
  document.addEventListener("toggle", later, true);
  new MutationObserver(function (ms) {
    for (var i = 0; i < ms.length; i++) if (!ms[i].target.closest || !ms[i].target.closest("[contenteditable], .rte")) { later(); return; }
  }).observe(main, { childList: true, subtree: true });
})();

// 사규 목차: 지금 읽는 조를 목차에 표시하고, 목차 안에서 보이게 한다
(function () {
  var toc = document.querySelector(".reg-toc");
  if (!toc || !window.IntersectionObserver) return;
  var links = {}, current = null;
  Array.prototype.forEach.call(toc.querySelectorAll('a[href^="#a-"]'), function (a) { links[a.getAttribute("href").slice(1)] = a; });
  var arts = Array.prototype.slice.call(document.querySelectorAll(".reg-main article.art[id]"));
  if (!arts.length) return;
  var visible = {};
  function mark() {
    var best = null;
    arts.forEach(function (el) { if (visible[el.id] && (!best || el.offsetTop < best.offsetTop)) best = el; });
    var found = document.querySelector(".reg-main article.found");
    if (found && visible[found.id]) best = found;   // 찾기 목록에서 고른 조가 보이면 그 조를 표시
    if (!best || best === current) return;
    current = best;
    Array.prototype.forEach.call(toc.querySelectorAll("a.on"), function (a) { a.classList.remove("on"); });
    var a = links[best.id];
    if (!a) return;
    a.classList.add("on");
    var t = a.offsetTop - toc.offsetTop;
    if (t < toc.scrollTop + 30 || t > toc.scrollTop + toc.clientHeight - 30) toc.scrollTop = t - toc.clientHeight / 3;
  }
  var io = new IntersectionObserver(function (es) {
    es.forEach(function (e) { visible[e.target.id] = e.isIntersecting; });
    mark();
  }, { rootMargin: "-140px 0px -45% 0px", threshold: 0 });
  arts.forEach(function (el) { io.observe(el); });
})();

// 프로그램 안의 창(대화 상자): 제목 줄을 잡고 끌어 옮긴다. 닫으면 다음에는 다시 가운데에서 열린다.
(function () {
  var drag = null;
  function handleOf(dlg, target) {
    if (target.closest("input, select, textarea, button, a, label, [contenteditable], .rte, summary")) return null;
    var head = target.closest(".dialog-head, .preview-bar");
    if (head && dlg.contains(head)) return head;
    var first = dlg.querySelector("h2, h3");
    return first && first.contains(target) ? first : null;
  }
  document.addEventListener("pointerdown", function (e) {
    if (e.button !== 0) return;
    var dlg = e.target.closest && e.target.closest("dialog[open]");
    if (!dlg || !handleOf(dlg, e.target)) return;
    var r = dlg.getBoundingClientRect();
    dlg.style.margin = "0";
    dlg.style.left = r.left + "px";
    dlg.style.top = r.top + "px";
    dlg.style.right = "auto";
    dlg.style.bottom = "auto";
    dlg.classList.add("dlg-moved", "dlg-dragging");
    drag = { dlg: dlg, dx: e.clientX - r.left, dy: e.clientY - r.top, w: r.width };
    e.preventDefault();
  });
  document.addEventListener("pointermove", function (e) {
    if (!drag) return;
    // 제목 줄이 화면 밖으로 나가지 않게
    var x = Math.min(Math.max(e.clientX - drag.dx, 60 - drag.w), window.innerWidth - 60);
    var y = Math.min(Math.max(e.clientY - drag.dy, 0), window.innerHeight - 40);
    drag.dlg.style.left = x + "px";
    drag.dlg.style.top = y + "px";
  });
  function end() { if (drag) { drag.dlg.classList.remove("dlg-dragging"); drag = null; } }
  document.addEventListener("pointerup", end);
  document.addEventListener("pointercancel", end);
  document.addEventListener("close", function (e) {
    var d = e.target;
    if (!d || d.tagName !== "DIALOG" || !d.classList.contains("dlg-moved")) return;
    ["margin", "left", "top", "right", "bottom"].forEach(function (k) { d.style[k] = ""; });
    d.classList.remove("dlg-moved");
  }, true);
})();

// 사규 안에서 찾기: 적는 대로 찾은 곳을 목록(조 · 앞뒤 글)으로 보여 주고, 고르면 그 조의 그 자리로 간다.
(function () {
  var input = document.querySelector("[data-rule-find]"), box = document.getElementById("rfind");
  if (!input || !box) return;
  var arts = Array.prototype.slice.call(document.querySelectorAll(".reg-main article.art[id]"));
  var hits = [], sel = -1, timer;
  function esc(s) { return String(s).replace(/[&<>"]/g, function (c) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]; }); }
  function bodyOf(a) { return a.querySelector(".abody"); }
  function labelOf(a) { var h = a.querySelector("header h3"); return h ? h.textContent.trim() : ""; }
  function find(q) {
    var out = [], ql = q.toLowerCase();
    arts.forEach(function (a) {
      var b = bodyOf(a); if (!b) return;
      var label = labelOf(a), text = b.textContent, low = text.toLowerCase(), at = low.indexOf(ql), n = 0;
      if (label.toLowerCase().indexOf(ql) >= 0 && at < 0) out.push({ art: a, label: label, at: -1, snip: esc(label) });
      while (at >= 0 && out.length < 300) {
        var s = Math.max(0, at - 28), e = Math.min(text.length, at + q.length + 40);
        var snip = (s > 0 ? "…" : "") + esc(text.slice(s, at)) + "<mark>" + esc(text.slice(at, at + q.length)) + "</mark>" + esc(text.slice(at + q.length, e)) + (e < text.length ? "…" : "");
        out.push({ art: a, label: label, at: at, len: q.length, snip: snip.replace(/\s+/g, " ") });
        at = low.indexOf(ql, at + Math.max(1, q.length)); n++;
      }
    });
    return out;
  }
  function draw(q) {
    hits = q ? find(q) : []; sel = hits.length ? 0 : -1;
    if (!q) { close(); return; }
    var arts2 = hits.reduce(function (m, h) { m[h.art.id] = 1; return m; }, {});
    var html = '<div class="rf-head"><span>' + (hits.length ? Object.keys(arts2).length + "개 조에서 " + hits.length + (hits.length >= 300 ? "곳 이상" : "곳") + " 찾음" : "") + '</span><span>↑↓ 고르고 Enter · Esc 닫기</span></div>';
    if (!hits.length) html += '<div class="rf-none">「' + esc(q) + '」이(가) 들어간 조가 없습니다.</div>';
    else html += "<table><thead><tr><th>조</th><th>찾은 곳</th></tr></thead><tbody>" + hits.map(function (h, i) {
      return '<tr class="rf-row' + (i === sel ? " sel" : "") + '" role="option" data-i="' + i + '"><td class="rf-art">' + esc(h.label) + "</td><td>" + h.snip + "</td></tr>";
    }).join("") + "</tbody></table>";
    box.innerHTML = html; box.hidden = false; input.setAttribute("aria-expanded", "true");
  }
  function close() { box.hidden = true; input.setAttribute("aria-expanded", "false"); }
  function mark(sel2) {
    Array.prototype.forEach.call(box.querySelectorAll("tr.rf-row"), function (r) { r.classList.toggle("sel", +r.getAttribute("data-i") === sel2); });
    var r = box.querySelector('tr.rf-row[data-i="' + sel2 + '"]');
    if (r) { var top = r.offsetTop - 40, bottom = r.offsetTop + r.offsetHeight; if (top < box.scrollTop) box.scrollTop = top; else if (bottom > box.scrollTop + box.clientHeight) box.scrollTop = bottom - box.clientHeight; }
  }
  function unmark() {
    Array.prototype.forEach.call(document.querySelectorAll(".reg-main mark.fhit"), function (m) { var p = m.parentNode; while (m.firstChild) p.insertBefore(m.firstChild, m); p.removeChild(m); p.normalize(); });
    Array.prototype.forEach.call(document.querySelectorAll(".reg-main article.found"), function (a) { a.classList.remove("found"); });
  }
  function go(h) {
    if (!h) return;
    close(); unmark();
    h.art.classList.add("found");
    window.dispatchEvent(new Event("scroll"));
    var target = h.art;
    if (h.at >= 0) {
      var w = document.createTreeWalker(bodyOf(h.art), NodeFilter.SHOW_TEXT), pos = 0, node, start = null, end = null;
      while ((node = w.nextNode())) {
        var len = node.nodeValue.length;
        if (!start && h.at < pos + len) start = [node, h.at - pos];
        if (start && h.at + h.len <= pos + len) { end = [node, h.at + h.len - pos]; break; }
        pos += len;
      }
      if (start && end && start[0] === end[0]) {
        try { var rg = document.createRange(); rg.setStart(start[0], start[1]); rg.setEnd(end[0], end[1]); var mk = document.createElement("mark"); mk.className = "fhit"; rg.surroundContents(mk); target = mk; } catch (e) { }
      }
    }
    var y = target.getBoundingClientRect().top + window.scrollY - (target === h.art ? parseInt(getComputedStyle(document.getElementById("main")).getPropertyValue("--toc-top") || "60", 10) + 12 : window.innerHeight / 3);
    window.scrollTo({ top: Math.max(0, y), behavior: "smooth" });
    if (history.replaceState) history.replaceState(null, "", "#" + h.art.id);
  }
  input.addEventListener("input", function () { clearTimeout(timer); var q = input.value.trim(); timer = setTimeout(function () { draw(q); }, 120); });
  input.addEventListener("focus", function () { if (input.value.trim()) draw(input.value.trim()); });
  input.addEventListener("keydown", function (e) {
    if (e.key === "Escape") { close(); return; }
    if (box.hidden && e.key === "ArrowDown" && input.value.trim()) { draw(input.value.trim()); e.preventDefault(); return; }
    if (box.hidden || !hits.length) return;
    if (e.key === "ArrowDown") { sel = Math.min(hits.length - 1, sel + 1); mark(sel); e.preventDefault(); }
    else if (e.key === "ArrowUp") { sel = Math.max(0, sel - 1); mark(sel); e.preventDefault(); }
    else if (e.key === "Enter") { e.preventDefault(); go(hits[sel]); }
  });
  box.addEventListener("mousedown", function (e) { e.preventDefault(); });
  box.addEventListener("click", function (e) { var r = e.target.closest("tr.rf-row"); if (r) go(hits[+r.getAttribute("data-i")]); });
  document.addEventListener("click", function (e) { if (!box.hidden && !box.contains(e.target) && e.target !== input) close(); });
})();

// 개정 이력: 모두 펼치기·접기
document.addEventListener("click", function (e) {
  var b = e.target.closest && e.target.closest("[data-tl-all]");
  if (!b) return;
  var open = b.getAttribute("data-tl-all") === "open";
  Array.prototype.forEach.call(document.querySelectorAll(".vtl-card"), function (d) { d.open = open; });
});

// 사규 본문 속 표: 본문(A4) 너비를 넘으면 지금 열 너비 비율대로 본문 너비에 맞춘다. 긴 글은 칸 안에서 줄을 바꾼다.
(function () {
  function fit() {
    Array.prototype.forEach.call(document.querySelectorAll(".rule-page .abody table"), function (t) {
      if (t.closest("table") !== t && t.parentElement.closest("table")) return;   // 표 안의 표는 바깥 표를 따른다
      var box = t.closest(".abody");
      if (!box || t.getBoundingClientRect().width <= box.clientWidth + 1) return;
      // 칸이 가장 많고 합친 칸이 없는 줄로 열 너비를 잰다
      var best = null;
      Array.prototype.forEach.call(t.rows, function (r) {
        var merged = Array.prototype.some.call(r.cells, function (c) { return c.colSpan > 1; });
        if (!merged && (!best || r.cells.length > best.cells.length)) best = r;
      });
      var n = 0;
      Array.prototype.forEach.call(t.rows, function (r) { var k = 0; Array.prototype.forEach.call(r.cells, function (c) { k += c.colSpan; }); n = Math.max(n, k); });
      var widths = [];
      if (best && best.cells.length === n) widths = Array.prototype.map.call(best.cells, function (c) { return c.getBoundingClientRect().width; });
      else for (var i = 0; i < n; i++) widths.push(1);
      var sum = widths.reduce(function (a, b) { return a + b; }, 0) || 1;
      // 한 열이 너무 좁아지지 않게(긴 글 칸이 비율을 다 가져가는 것 막기): 열마다 최소 몫을 둔다
      var pct = widths.map(function (w) { return w / sum * 100; }), floor = Math.min(12, 70 / Math.max(1, n)), extra = 0;
      pct = pct.map(function (x) { if (x < floor) { extra += floor - x; return floor; } return x; });
      var big = pct.reduce(function (a, x) { return a + (x > floor ? x : 0); }, 0);
      if (extra > 0 && big > 0) pct = pct.map(function (x) { return x > floor ? x - extra * x / big : x; });
      Array.prototype.forEach.call(t.querySelectorAll(":scope > colgroup"), function (g) { g.remove(); });
      var cg = document.createElement("colgroup");
      pct.forEach(function (x) { var col = document.createElement("col"); col.style.width = x.toFixed(2) + "%"; cg.appendChild(col); });
      t.insertBefore(cg, t.firstChild);
      t.classList.add("fit-a4");
    });
  }
  if (!document.querySelector(".rule-page .abody table")) return;
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", fit); else fit();
  window.addEventListener("load", fit);
  var rt; window.addEventListener("resize", function () { clearTimeout(rt); rt = setTimeout(fit, 150); });
})();
