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

  document.querySelectorAll(".drop").forEach(function (zone) {
    var input = zone.querySelector("input[type=file]");
    var label = zone.querySelector("[data-file-name]");
    if (!input) return;
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
      if (label && input.files[0]) label.textContent = input.files[0].name;
    });
    input.addEventListener("change", function () {
      if (label) label.textContent = input.files && input.files[0] ? input.files[0].name : "선택된 파일 없음";
    });
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
  function refreshMain(focusSelector) {
    var y = window.scrollY;
    return fetch(location.href, { headers: { "X-Audit-Fetch": "page" } }).then(function (response) { return response.text(); }).then(function (html) {
      var doc = new DOMParser().parseFromString(html, "text/html");
      var next = doc.querySelector("main");
      var main = document.querySelector("main");
      if (next && main) main.innerHTML = next.innerHTML;
      window.scrollTo(0, y);
      var focus = focusSelector ? document.querySelector(focusSelector) : null;
      if (focus) focus.focus();
      noteRecent();
      bindPreview();
    });
  }
  function noteRecent() {
    var box = document.getElementById("recent-cases");
    var title = document.querySelector("h1");
    var match = location.pathname.match(/^\/cases\/([0-9a-f]{32})$/);
    if (match && title) {
      var items = [];
      try { items = JSON.parse(localStorage.getItem("audit-recent") || "[]"); } catch (e) { items = []; }
      items = [{ id: match[1], title: title.textContent.trim() }].concat(items.filter(function (item) { return item.id !== match[1]; })).slice(0, 5);
      localStorage.setItem("audit-recent", JSON.stringify(items));
    }
    if (!box) return;
    var saved = [];
    try { saved = JSON.parse(localStorage.getItem("audit-recent") || "[]"); } catch (e) { saved = []; }
    if (!saved.length) return;
    box.hidden = false;
    box.innerHTML = "";
    saved.forEach(function (item) {
      var link = document.createElement("a");
      link.className = "chip";
      link.href = "/cases/" + item.id;
      link.textContent = item.title;
      box.appendChild(link);
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
      if (payload.go) { location.href = payload.go; return; }
      if (payload.reload) { location.reload(); return; }
      var section = form.closest("section");
      var selector = section && section.id ? "#" + section.id + " button" : "h1";
      refreshMain(selector).finally(function () { finishButton(button, label); });
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
  noteRecent();
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
