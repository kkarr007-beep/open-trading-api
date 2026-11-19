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
            if (c) c.textContent = '고른 ' + n + '건 삭제';
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
