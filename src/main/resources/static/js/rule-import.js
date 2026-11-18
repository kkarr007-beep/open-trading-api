// 사규 등록: 파일 읽기 → 분석 미리보기(조 고치기·병합·분할·순서·삭제) → 확정 저장. 예전 '사규 관리 시스템'의 등록 흐름을 따른다.
(function () {
  "use strict";
  var root = document.getElementById("rule-import");
  if (!root) return;
  var revise = root.getAttribute("data-revise") || "";
  var editOf = root.getAttribute("data-edit") || "";
  var prev = document.getElementById("imPrev");
  var DRAFT = null;
  var DOC_HTML = "";   // 고른 문서를 읽은 원본 서식(HTML)

  // ── 원본 서식을 조마다 잘라 붙이기 ──
  // 조 나누기는 글로 하고, 화면에는 원본 문서의 서식(굵게·글꼴·정렬·띄어쓰기·줄바꿈·표)을 그대로 쓰기 위해서다.
  var RE_ART = /^\s*(?:\*\*)?\s*(제\s*\d+\s*조(?:\s*의\s*\d+)?)\s*(?:\([^)]{0,40}\)|（[^）]{0,40}）)?/;
  var RE_HEAD = /^(제\s*\d+\s*(장|절|편|관)(\s|$)|부\s*칙(\s*[<〈(][^>〉)]*[>〉)])?\s*$)/;
  var RE_ATT = /^[\[<(（〈【]?\s*(별\s*첨|별\s*표|별\s*지|붙\s*임|서\s*식|부\s*표)/;
  function core(num) { var m = /(제\s*\d+\s*조(?:\s*의\s*\d+)?)/.exec(num || ""); return m ? m[1].replace(/\s+/g, "") : ""; }
  function plain(h) { return window.sagyuPaste ? window.sagyuPaste.htmlToText(h) : String(h).replace(/<[^>]*>/g, ""); }
  function same(t) { return String(t || "").replace(/[\s|*>^<\u23ce·]/g, ""); }
  function stripPrefix(el, n) {
    var w = document.createTreeWalker(el, NodeFilter.SHOW_TEXT), node;
    while (n > 0 && (node = w.nextNode())) {
      var len = node.nodeValue.length;
      if (len <= n) { n -= len; node.nodeValue = ""; } else { node.nodeValue = node.nodeValue.slice(n); n = 0; }
    }
    // 머리 다음 맨 앞 빈칸
    w = document.createTreeWalker(el, NodeFilter.SHOW_TEXT);
    while ((node = w.nextNode())) { if (!node.nodeValue) continue; node.nodeValue = node.nodeValue.replace(/^\s+/, ""); if (node.nodeValue) break; }
  }
  function attachHtml(arts, html) {
    if (!html || !arts || !arts.length) return 0;
    var box = document.createElement("div");
    box.innerHTML = html;
    var info = [].slice.call(box.children).map(function (b) { return { el: b, t: plain(b.outerHTML).replace(/^>+\s*/, "").trim() }; });
    var starts = [], j = 0, i, k;
    for (i = 0; i < arts.length; i++) {
      var a = arts[i], c = core(a.number), found = -1;
      if (i === 0 && /^전문/.test(a.number || "")) { starts.push(0); continue; }   // 첫 조 앞 글(제목 등)
      for (k = j; k < info.length; k++) {
        var t = info[k].t.replace(/^\*\*/, "");
        if (c) { var m = RE_ART.exec(t); if (m && m[1].replace(/\s+/g, "") === c) { found = k; break; } }
        else if (a.supp && /^부\s*칙/.test(t)) { found = k; break; }
        else if (a.attach && RE_ATT.test(t)) { found = k; break; }
      }
      starts.push(found);
      if (found >= 0) j = found + 1;
    }
    var n = 0;
    for (i = 0; i < arts.length; i++) {
      if (starts[i] < 0) continue;
      var end = info.length;
      for (var q = i + 1; q < arts.length; q++) if (starts[q] >= 0) { end = starts[q]; break; }
      var parts = [], ok = true;
      for (k = starts[i]; k < end; k++) {
        if (k > starts[i] && RE_HEAD.test(info[k].t)) continue;     // 장·절·부칙 머리는 따로 나온다
        var el = info[k].el.cloneNode(true);
        var pre = i === 0 && /^전문/.test(arts[i].number || "");
        if (pre && k > starts[i] && RE_HEAD.test(info[k].t)) continue;
        if (k === starts[i] && !pre) {
          if (!core(arts[i].number)) continue;                         // 부칙·별표 머리 줄은 조 머리로 나온다
          var m2 = RE_ART.exec(el.textContent);
          if (!m2) { ok = false; break; }
          stripPrefix(el, m2[0].length);
          if (!el.textContent.trim() && !el.querySelector("img,table")) continue;
        }
        parts.push(el.outerHTML);
      }
      if (!ok || !parts.length) continue;
      var h = parts.join("");
      // 글이 나눈 조와 같을 때만 붙인다(다르면 지금처럼 글로 보여 준다)
      if (same(plain(h)) === same(arts[i].content)) { arts[i].html = h; arts[i]._orig = arts[i].content; n++; }
    }
    return n;
  }
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
      DRAFT = r.articles;
      var kept = attachHtml(DRAFT, DOC_HTML);
      draw(r.dup);
      toast(r.message + (DOC_HTML ? " · 원본 서식 " + kept + "/" + DRAFT.length + "개 조 유지" : ""));
      prev.scrollIntoView({ behavior: "smooth", block: "start" });
    });
  }

  $("imF").addEventListener("change", function () { if (this.files && this.files[0]) $("imP").click(); });
  $("imP").addEventListener("click", function () {
    var f = $("imF").files && $("imF").files[0];
    if (!f) { DOC_HTML = ""; analyzeText($("imX").value); return; }
    var fd = new FormData(); fd.append("upload", f);
    $("imP").disabled = true; $("imP").textContent = "읽는 중…";
    fetch("/rules/analyze", { method: "POST", body: fd, headers: { "X-Audit-Fetch": "1" } }).then(function (r) { return r.json(); }).then(function (r) {
      $("imP").disabled = false; $("imP").textContent = "분석 미리보기";
      if (!r.ok) { toast(r.message); return; }
      if (!revise && !$("imT").value.trim()) $("imT").value = r.name || "";
      // 문서 모양(HTML)이 있으면 표 안의 표까지 칸을 살려 사규 글 꼴로 바꾼다
      var t = r.text;
      DOC_HTML = r.html || "";
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
        + (a.html && a.content === a._orig ? '<span class="pill ok src-fmt" title="원본 문서의 서식으로 보여 줍니다">원본 서식</span>' : '')
        + '<span class="dbtn">' + (i ? '<button type="button" class="btn btn-quiet" data-d="merge" title="위 조에 합칩니다">위와 병합</button>' : '')
        + '<button type="button" class="btn btn-quiet" data-d="split" title="내용 칸의 커서 자리에서 나눕니다">분할</button>'
        + (i ? '<button type="button" class="btn btn-quiet" data-d="up" title="위로">↑</button>' : '')
        + '<button type="button" class="btn btn-quiet" data-d="del">삭제</button></span></div>'
        + '<textarea class="drc mono" aria-label="' + esc(a.number) + ' 내용" rows="' + rows + '">' + esc(a.content) + '</textarea></div>';
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
    DRAFT.forEach(function (a) { if (a.html && a.content !== a._orig) delete a.html; });
    var body = { title: $("imT").value.trim(), categoryId: $("imC").value, label: $("imV").value, effectiveDate: $("imD").value, note: $("imR").value,
      reviseOf: revise, mode: mode, articles: DRAFT, editOf: editOf };
    if (!revise && !editOf && !body.title) { toast("사규명을 적으세요."); $("imT").focus(); return; }
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
  // 조문 전체 고치기: 지금 판의 조문을 불러와 바로 편집
  if (editOf) {
    try { DRAFT = JSON.parse(root.getAttribute("data-arts") || "[]"); } catch (e) { DRAFT = []; }
    draw(null);
  }
})();
