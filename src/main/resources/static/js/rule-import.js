// 사규 등록: 파일 읽기 → 분석 미리보기(조 고치기·병합·분할·순서·삭제) → 확정 저장. 예전 '사규 관리 시스템'의 등록 흐름을 따른다.
(function () {
  "use strict";
  var root = document.getElementById("rule-import");
  if (!root) return;
  var revise = root.getAttribute("data-revise") || "";
  var prev = document.getElementById("imPrev");
  var DRAFT = null;
  function $(id) { return document.getElementById(id); }
  function esc(s) { return String(s == null ? "" : s).replace(/[&<>"]/g, function (c) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]; }); }
  function toast(m) { if (window.auditToast) window.auditToast(m); }
  function post(url, body) {
    return fetch(url, { method: "POST", headers: { "Content-Type": "application/json", "X-Audit-Fetch": "1" }, body: JSON.stringify(body) }).then(function (r) { return r.json(); });
  }
  function counts(c) {
    var p = 0, h = 0;
    String(c || "").split("\n").forEach(function (l) {
      var t = l.trim().replace(/^\*\*|^__/, "");
      if (/^[①-⑳]/.test(t) || /^\(\d+\)/.test(t)) p++;
      else if (/^\d+\.\s/.test(t)) h++;
    });
    return "항 " + p + " · 호 " + h;
  }
  function warnOf(a) { return /^\s*(이|동|같은)\s*조/.test(a.content || "") || (!a.title && !a.supp && !a.attach && /^제\s*\d/.test(a.number || "")); }

  function analyzeText(t) {
    if (!t || !t.trim()) { toast("분석할 글이 없습니다."); return; }
    post("/rules/parse", { text: t, title: $("imT").value, reviseOf: revise }).then(function (r) {
      if (!r.ok) { toast(r.message || "조문을 찾지 못했습니다."); return; }
      DRAFT = r.articles; draw(r.dup); toast(r.message);
      prev.scrollIntoView({ behavior: "smooth", block: "start" });
    });
  }

  $("imF").addEventListener("change", function () { if (this.files && this.files[0]) $("imP").click(); });
  $("imP").addEventListener("click", function () {
    var f = $("imF").files && $("imF").files[0];
    if (!f) { analyzeText($("imX").value); return; }
    var fd = new FormData(); fd.append("upload", f);
    $("imP").disabled = true; $("imP").textContent = "읽는 중…";
    fetch("/rules/analyze", { method: "POST", body: fd, headers: { "X-Audit-Fetch": "1" } }).then(function (r) { return r.json(); }).then(function (r) {
      $("imP").disabled = false; $("imP").textContent = "분석 미리보기";
      if (!r.ok) { toast(r.message); return; }
      if (!revise && !$("imT").value.trim()) $("imT").value = r.name || "";
      // 문서 모양(HTML)이 있으면 표 안의 표까지 칸을 살려 사규 글 꼴로 바꾼다
      var t = r.text;
      if (r.html && window.sagyuPaste) { try { t = window.sagyuPaste.htmlToText(r.html) || r.text; } catch (e) { t = r.text; } }
      $("imX").value = t;
      analyzeText(t);
    }).catch(function () { $("imP").disabled = false; $("imP").textContent = "분석 미리보기"; toast("파일을 읽지 못했습니다."); });
  });

  function sync() {
    if (!DRAFT) return;
    prev.querySelectorAll(".dr").forEach(function (d) {
      var a = DRAFT[+d.getAttribute("data-i")];
      a.number = d.querySelector(".drn").value; a.title = d.querySelector(".drt").value; a.content = d.querySelector(".drc").value;
    });
  }

  function draw(dup) {
    if (!DRAFT) { prev.innerHTML = ""; return; }
    var nArt = DRAFT.filter(function (a) { return !a.supp && !a.attach; }).length, warn = DRAFT.filter(warnOf).length;
    var h = '<div class="card"><div class="card-head"><h2>분석 미리보기 — ' + DRAFT.length + '개 (조 ' + nArt + ' · 부칙·별첨 ' + (DRAFT.length - nArt) + ')</h2>'
      + (warn ? '<span class="pill warn">확인할 조 ' + warn + '</span>' : '') + '<button type="button" class="btn btn-primary" id="imOK">확정 저장</button></div>'
      + '<p class="hint">노란 줄은 제목이 없거나 ‘이 조’로 시작해 잘못 나뉘었을 수 있는 조입니다. 내용 칸에서 커서를 둔 자리에서 ‘분할’을 누르면 그 자리부터 새 조가 됩니다.</p>';
    if (dup) h += '<p class="banner warn">같은 이름의 사규 「' + esc(dup.title) + '」가 이미 있습니다 (판 ' + dup.versions + '개). 저장할 때 개정 버전으로 넣을지 묻습니다.</p>';
    h += '<div class="draft">';
    var lastCh = null;
    DRAFT.forEach(function (a, i) {
      if ((a.chapter || "") !== lastCh) { lastCh = a.chapter || ""; if (lastCh) h += '<h3 class="month-head">' + esc(lastCh) + '</h3>'; }
      var rows = Math.min(12, Math.max(2, String(a.content || "").split("\n").length));
      h += '<div class="dr' + (warnOf(a) ? ' warn' : '') + '" data-i="' + i + '"><div class="drh">'
        + '<input class="drn" value="' + esc(a.number) + '" aria-label="조 번호" />'
        + '<input class="drt" value="' + esc(a.title) + '" placeholder="제목" aria-label="제목" />'
        + '<span class="small muted">' + esc(counts(a.content)) + (a.supp ? ' · 부칙' : '') + (a.attach ? ' · 별첨' : '') + '</span>'
        + '<span class="dbtn">' + (i ? '<button type="button" class="btn btn-quiet" data-d="merge" title="위 조에 합칩니다">위와 병합</button>' : '')
        + '<button type="button" class="btn btn-quiet" data-d="split" title="내용 칸의 커서 자리에서 나눕니다">분할</button>'
        + (i ? '<button type="button" class="btn btn-quiet" data-d="up" title="위로">↑</button>' : '')
        + '<button type="button" class="btn btn-quiet" data-d="del">삭제</button></span></div>'
        + '<textarea class="drc mono" rows="' + rows + '">' + esc(a.content) + '</textarea></div>';
    });
    h += '</div></div>';
    prev.innerHTML = h;
  }

  prev.addEventListener("click", function (e) {
    var b = e.target.closest("[data-d]");
    if (b) {
      sync();
      var d = b.closest(".dr"), i = +d.getAttribute("data-i"), a = DRAFT[i];
      var act = b.getAttribute("data-d");
      if (act === "merge") { var pv = DRAFT[i - 1]; pv.content = (pv.content + "\n" + a.number + (a.title ? "(" + a.title + ")" : "") + " " + a.content).trim(); DRAFT.splice(i, 1); }
      else if (act === "split") {
        var ta = d.querySelector(".drc"), pos = ta.selectionStart || Math.floor(ta.value.length / 2);
        var rest = a.content.slice(pos).trim(); a.content = a.content.slice(0, pos).trim();
        var m = /^\s*(제\s*\d+\s*조(?:\s*의\s*\d+)?)\s*(?:\(([^)]{0,40})\))?\s*([\s\S]*)$/.exec(rest);
        DRAFT.splice(i + 1, 0, { id: "", number: m ? m[1].replace(/\s+/g, "") : "제?조", title: m && m[2] ? m[2] : "", content: m ? m[3] : rest, chapter: a.chapter });
      }
      else if (act === "up" && i) { var t = DRAFT[i - 1]; DRAFT[i - 1] = DRAFT[i]; DRAFT[i] = t; }
      else if (act === "del") DRAFT.splice(i, 1);
      draw(null);
      return;
    }
    if (e.target.id === "imOK") save("");
    var m2 = e.target.closest("[data-mode]");
    if (m2) { var md = m2.getAttribute("data-mode"); if (md === "cancel") { var bx = document.getElementById("imDup"); if (bx) bx.remove(); } else save(md); }
  });

  function save(mode) {
    sync();
    var body = { title: $("imT").value.trim(), categoryId: $("imC").value, label: $("imV").value, effectiveDate: $("imD").value, note: $("imR").value,
      reviseOf: revise, mode: mode, articles: DRAFT };
    if (!revise && !body.title) { toast("사규명을 적으세요."); $("imT").focus(); return; }
    post("/rules/save", body).then(function (r) {
      if (r.ok) { toast(r.message); location.href = r.go; return; }
      if (r.dupId) {
        var box = document.getElementById("imDup") || document.createElement("div");
        box.id = "imDup"; box.className = "card banner warn";
        box.innerHTML = "<p><strong>" + esc(r.message) + "</strong></p><p class=\"hint\">개정 버전으로 넣으면 사규는 하나로 유지되고, 읽기 화면에서 개정 전·후를 견줄 수 있습니다.</p>"
          + '<p class="row-actions"><button type="button" class="btn btn-primary" data-mode="revise">개정 버전으로 추가</button>'
          + '<button type="button" class="btn" data-mode="separate">별도 사규로 등록</button>'
          + '<button type="button" class="btn btn-quiet" data-mode="cancel">취소</button></p>';
        prev.insertBefore(box, prev.firstChild);
        box.scrollIntoView({ behavior: "smooth", block: "center" });
        return;
      }
      toast(r.message || "저장하지 못했습니다.");
    });
  }
})();
