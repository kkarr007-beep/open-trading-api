// 워드처럼 쓰는 편집기. <textarea data-rich="필드이름Html"> 를 편집기로 바꾼다.
// 글자 칸(textarea)에는 글자만, data-rich 이름의 숨은 칸에는 꾸민 모양(HTML)을 담아 보낸다. 서버가 한 번 더 걸러 저장한다.
// 표 안에 표: 칸 안에 커서를 두고 '표'를 누르면 그 칸 안에 표가 들어간다. 워드·한글에서 붙여 넣은 표 안의 표도 그대로 둔다.
(function () {
  "use strict";
  if (window.__richEditor) return;
  window.__richEditor = true;

  var SIZES = ["10px", "12px", "13px", "14px", "15px", "16px", "18px", "20px", "24px", "28px", "32px", "40px"];
  var FONTS = [["", "글꼴"], ["'맑은 고딕','Malgun Gothic',sans-serif", "맑은 고딕"], ["'바탕','Batang',serif", "바탕"],
    ["'굴림','Gulim',sans-serif", "굴림"], ["'돋움','Dotum',sans-serif", "돋움"], ["'궁서','Gungsuh',serif", "궁서"],
    ["Arial,sans-serif", "Arial"], ["'Times New Roman',serif", "Times"], ["Consolas,monospace", "고정폭"]];

  function esc(s) { return String(s == null ? "" : s).replace(/[&<>"]/g, function (c) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]; }); }

  function textToHtml(t) {
    t = String(t || "").replace(/\r\n?/g, "\n");
    if (!t.trim()) return "";
    var lines = t.split("\n"), out = "", i = 0;
    while (i < lines.length) {
      if (lines[i].indexOf("\t") >= 0) {
        var j = i, rows = [];
        while (j < lines.length && lines[j].indexOf("\t") >= 0) { rows.push(lines[j].split("\t")); j++; }
        if (rows.length >= 2 || rows[0].length >= 3) {
          out += "<table>" + rows.map(function (r) { return "<tr>" + r.map(function (c) { return "<td>" + esc(c.trim()) + "</td>"; }).join("") + "</tr>"; }).join("") + "</table>";
          i = j; continue;
        }
      }
      out += lines[i].trim() ? "<p>" + esc(lines[i]) + "</p>" : "<p><br></p>";
      i++;
    }
    return out;
  }

  // 글자만: 칸은 탭, 줄은 줄바꿈
  function htmlToText(root) {
    var out = [];
    function walk(n) {
      if (n.nodeType === 3) { out.push(n.nodeValue.replace(/ /g, " ")); return; }
      if (n.nodeType !== 1) return;
      var t = n.tagName;
      if (t === "BR") { out.push("\n"); return; }
      if (t === "IMG") { out.push("[그림]"); return; }
      if (t === "LI") out.push("- ");
      for (var c = n.firstChild; c; c = c.nextSibling) walk(c);
      if (t === "TD" || t === "TH") out.push("\t");
      else if (/^(P|DIV|LI|TR|H[1-6]|BLOCKQUOTE|PRE|TABLE|FIGURE)$/.test(t)) out.push("\n");
    }
    walk(root);
    return out.join("").replace(/\t+\n/g, "\n").replace(/[ ]+\n/g, "\n").replace(/\n{3,}/g, "\n\n").trim();
  }

  // 붙여 넣은 HTML의 문서 프로그램 찌꺼기를 걷는다. 표·표 안의 표·서식은 남긴다. 최종 거르기는 서버가 한다.
  function cleanPaste(html) {
    var doc = new DOMParser().parseFromString(html, "text/html");
    doc.querySelectorAll("script,style,meta,link,title,xml,o\\:p,head").forEach(function (n) { n.remove(); });
    var walker = doc.createTreeWalker(doc.body, NodeFilter.SHOW_COMMENT);
    var dead = [];
    while (walker.nextNode()) dead.push(walker.currentNode);
    dead.forEach(function (n) { n.remove(); });
    doc.body.querySelectorAll("*").forEach(function (el) {
      el.removeAttribute("class"); el.removeAttribute("id"); el.removeAttribute("lang");
      Array.prototype.slice.call(el.attributes).forEach(function (a) { if (/^on/i.test(a.name)) el.removeAttribute(a.name); });
      var st = el.getAttribute("style");
      if (st) {
        var keep = st.split(";").filter(function (d) {
          var k = d.split(":")[0].trim().toLowerCase();
          return /^(color|background(-color)?|font-(size|weight|style|family)|text-(decoration|align|indent)|vertical-align|padding-left|margin-left|border(-[a-z]+)*|width|line-height)$/.test(k) && !/mso-/i.test(d);
        });
        if (keep.length) el.setAttribute("style", keep.join(";")); else el.removeAttribute("style");
      }
      if (el.tagName === "IMG" && !/^data:image\//.test(el.getAttribute("src") || "")) el.remove();
    });
    return doc.body.innerHTML;
  }

  function readImage(file) {
    return new Promise(function (resolve, reject) {
      var r = new FileReader();
      r.onload = function () {
        var img = new Image();
        img.onload = function () {
          var max = 1400, w = img.width, h = img.height;
          if (w > max) { h = Math.round(h * max / w); w = max; }
          var c = document.createElement("canvas"); c.width = w; c.height = h;
          c.getContext("2d").drawImage(img, 0, 0, w, h);
          resolve(c.toDataURL(file.type === "image/png" ? "image/png" : "image/jpeg", 0.88));
        };
        img.onerror = reject;
        img.src = r.result;
      };
      r.onerror = reject;
      r.readAsDataURL(file);
    });
  }

  function button(label, title, cmd) {
    var b = document.createElement("button");
    b.type = "button"; b.className = "rte-b"; b.innerHTML = label; b.title = title; b.setAttribute("aria-label", title);
    if (cmd) b.dataset.cmd = cmd;
    return b;
  }

  function closestCell(node, root) {
    while (node && node !== root) { if (node.nodeType === 1 && (node.tagName === "TD" || node.tagName === "TH")) return node; node = node.parentNode; }
    return null;
  }

  function build(ta) {
    var form = ta.form;
    var hiddenName = ta.getAttribute("data-rich");
    var hidden = form ? form.querySelector('input[type=hidden][name="' + hiddenName + '"]') : null;
    if (!hidden && form) { hidden = document.createElement("input"); hidden.type = "hidden"; hidden.name = hiddenName; form.appendChild(hidden); }
    // 편집기가 이름표(label) 안에 있으면, 본문을 누를 때 이름표가 첫 입력칸(도구줄의 '문단')으로 커서를 보낸다.
    // 이름표를 같은 모양의 div로 바꿔 그 일을 막는다.
    var lab = ta.closest("label");
    var labText = lab && lab.firstChild ? (lab.firstChild.textContent || "").trim() : "";
    if (lab) {
      var dv = document.createElement("div");
      Array.prototype.forEach.call(lab.attributes, function (a) { if (a.name !== "for") dv.setAttribute(a.name, a.value); });
      while (lab.firstChild) dv.appendChild(lab.firstChild);
      lab.parentNode.replaceChild(dv, lab);
    }
    var wrap = document.createElement("div"); wrap.className = "rte";
    var bar = document.createElement("div"); bar.className = "rte-bar"; bar.setAttribute("role", "toolbar"); bar.setAttribute("aria-label", "서식");
    var body = document.createElement("div"); body.className = "rte-body rich"; body.contentEditable = "true";
    body.setAttribute("role", "textbox"); body.setAttribute("aria-multiline", "true");
    body.setAttribute("aria-label", ta.getAttribute("aria-label") || labText || "내용");
    var rows = parseInt(ta.getAttribute("rows") || "8", 10);
    body.style.minHeight = Math.max(120, rows * 24) + "px";
    var initial = ta.getAttribute("data-html");
    body.innerHTML = initial != null && initial !== "" ? initial : textToHtml(ta.value);
    if (ta.disabled || ta.readOnly) body.contentEditable = "false";

    // 글꼴·크기
    var font = document.createElement("select"); font.className = "rte-sel"; font.title = "글꼴"; font.setAttribute("aria-label", "글꼴");
    FONTS.forEach(function (f) { var o = document.createElement("option"); o.value = f[0]; o.textContent = f[1]; font.appendChild(o); });
    var size = document.createElement("select"); size.className = "rte-sel"; size.title = "글자 크기"; size.setAttribute("aria-label", "글자 크기");
    var o0 = document.createElement("option"); o0.value = ""; o0.textContent = "크기"; size.appendChild(o0);
    SIZES.forEach(function (s) { var o = document.createElement("option"); o.value = s; o.textContent = parseInt(s, 10); size.appendChild(o); });
    var block = document.createElement("select"); block.className = "rte-sel"; block.title = "문단 꼴"; block.setAttribute("aria-label", "문단 꼴");
    [["", "문단"], ["p", "본문"], ["h2", "제목 1"], ["h3", "제목 2"], ["h4", "제목 3"], ["blockquote", "인용"]].forEach(function (f) {
      var o = document.createElement("option"); o.value = f[0]; o.textContent = f[1]; block.appendChild(o);
    });
    var color = document.createElement("input"); color.type = "color"; color.value = "#d1304a"; color.title = "글자색"; color.className = "rte-color";
    var bg = document.createElement("input"); bg.type = "color"; bg.value = "#fff3a3"; bg.title = "형광(배경색)"; bg.className = "rte-color";
    var colorB = button('<b style="border-bottom:3px solid currentColor">가</b>', "글자색 칠하기", null);
    var bgB = button('<span style="background:#fff3a3;color:#000;padding:0 3px">가</span>', "형광 칠하기", null);

    var parts = [
      [block, font, size],
      [button("<b>가</b>", "굵게 (Ctrl+B)", "bold"), button("<i>가</i>", "기울임 (Ctrl+I)", "italic"), button("<u>가</u>", "밑줄 (Ctrl+U)", "underline"),
        button("<s>가</s>", "취소선", "strikeThrough"), button("x²", "위첨자", "superscript"), button("x₂", "아래첨자", "subscript")],
      [colorB, color, bgB, bg],
      [button("⇤", "왼쪽 정렬", "justifyLeft"), button("⇔", "가운데 정렬", "justifyCenter"), button("⇥", "오른쪽 정렬", "justifyRight"), button("☰", "양쪽 정렬", "justifyFull")],
      [button("•", "글머리 기호", "insertUnorderedList"), button("1.", "번호 매기기", "insertOrderedList"), button("→", "들여쓰기", "indent"), button("←", "내어쓰기", "outdent")],
      [button("표", "표 넣기 (칸 안에서 누르면 표 안의 표)", null), button("행+", "아래에 행 넣기", null), button("열+", "오른쪽에 열 넣기", null),
        button("행−", "행 지우기", null), button("열−", "열 지우기", null), button("―", "가로줄", "insertHorizontalRule"), button("그림", "그림 넣기", null),
        button("병합", "셀 병합: 표에서 칸을 마우스로 끌어 고른 뒤 누릅니다", null), button("나누기", "셀 나누기: 합친 칸 안에서 누릅니다", null)],
      [button("서식복사", "서식 복사: 글을 고르고 누른 뒤, 바꿀 글을 끌어 고르면 같은 서식이 입혀집니다 (두 번 누르면 계속, Esc로 끝)", null),
        button("⌫서식", "서식 지우기", "removeFormat"), button("↶", "되돌리기 (Ctrl+Z)", "undo"), button("↷", "다시 하기 (Ctrl+Y)", "redo")]
    ];
    parts.forEach(function (g) {
      var grp = document.createElement("span"); grp.className = "rte-g";
      g.forEach(function (el) { grp.appendChild(el); });
      bar.appendChild(grp);
    });
    var tableB = parts[5][0], rowAdd = parts[5][1], colAdd = parts[5][2], rowDel = parts[5][3], colDel = parts[5][4], imgB = parts[5][6];
    var mergeB = parts[5][7], splitB = parts[5][8], painterB = parts[6][0];
    mergeB.classList.add("rte-merge"); splitB.classList.add("rte-split"); painterB.classList.add("rte-painter");
    var picker = document.createElement("input"); picker.type = "file"; picker.accept = "image/*"; picker.hidden = true;
    wrap.appendChild(bar); wrap.appendChild(body); wrap.appendChild(picker);
    ta.parentNode.insertBefore(wrap, ta);
    ta.hidden = true;
    ta.removeAttribute("required");
    ta.dataset.richReady = "1";

    var saved = null;
    function keep() {
      var s = window.getSelection();
      if (s.rangeCount && body.contains(s.getRangeAt(0).commonAncestorContainer)) saved = s.getRangeAt(0).cloneRange();
    }
    function restore() {
      body.focus();
      if (saved) { var s = window.getSelection(); s.removeAllRanges(); s.addRange(saved); }
    }
    body.addEventListener("keyup", keep);
    body.addEventListener("mouseup", keep);
    body.addEventListener("input", function () { keep(); sync(); });

    function exec(cmd, val) {
      // 칸을 여러 개 골라 두었으면 그 칸들의 글 전체에 서식을 입힌다
      if (cellSel && !/^(undo|redo|insertHTML|insertHorizontalRule)$/.test(cmd)) {
        var cells = Array.prototype.slice.call(body.querySelectorAll(".rte-cellsel"));
        if (cells.length) {
          body.focus();
          try { document.execCommand("styleWithCSS", false, true); } catch (e) { }
          cells.forEach(function (c) { var r = document.createRange(); r.selectNodeContents(c); var s = window.getSelection(); s.removeAllRanges(); s.addRange(r); document.execCommand(cmd, false, val == null ? null : val); });
          sync(); return;
        }
      }
      restore();
      try { document.execCommand("styleWithCSS", false, true); } catch (e) { }
      document.execCommand(cmd, false, val == null ? null : val);
      keep(); sync();
    }
    bar.addEventListener("mousedown", function (e) { if (e.target.closest("button")) e.preventDefault(); });
    bar.addEventListener("click", function (e) {
      var b = e.target.closest("button[data-cmd]");
      if (!b) return;
      if (b.dataset.cmd === "indent" || b.dataset.cmd === "outdent") { shiftLevel(b.dataset.cmd === "indent" ? 1 : -1); return; }
      exec(b.dataset.cmd);
    });
    font.addEventListener("change", function () { if (font.value) exec("fontName", font.value); font.value = ""; });
    block.addEventListener("change", function () { if (block.value) exec("formatBlock", block.value); block.value = ""; });
    size.addEventListener("change", function () {
      var v = size.value; size.value = "";
      if (!v) return;
      restore();
      try { document.execCommand("styleWithCSS", false, false); } catch (x) { }
      document.execCommand("fontSize", false, "7");
      body.querySelectorAll('font[size="7"]').forEach(function (f) {
        var sp = document.createElement("span"); sp.style.fontSize = v;
        while (f.firstChild) sp.appendChild(f.firstChild);
        f.parentNode.replaceChild(sp, f);
      });
      body.querySelectorAll("span").forEach(function (sp) { if (sp.style.fontSize === "xxx-large" || sp.style.fontSize === "-webkit-xxx-large") sp.style.fontSize = v; });
      keep(); sync();
    });
    colorB.addEventListener("click", function () { exec("foreColor", color.value); });
    color.addEventListener("change", function () { exec("foreColor", color.value); });
    bgB.addEventListener("click", function () { exec("hiliteColor", bg.value); });
    bg.addEventListener("change", function () { exec("hiliteColor", bg.value); });

    // 표 넣기: 화면 안의 칸판에서 행·열을 고른다(프로그램 창은 입력 창이 뜨지 않아서)
    var grid = null;
    function insertTable(r, c) {
      var h = "<table>";
      for (var i = 0; i < r; i++) { h += "<tr>"; for (var j = 0; j < c; j++) h += (i === 0 ? "<th>" : "<td>") + "<br>" + (i === 0 ? "</th>" : "</td>"); h += "</tr>"; }
      h += "</table><p><br></p>";
      exec("insertHTML", h);
    }
    function closeGrid() { if (grid) { grid.remove(); grid = null; } document.removeEventListener("mousedown", outside, true); }
    function outside(e) { if (grid && !grid.contains(e.target) && e.target !== tableB) closeGrid(); }
    tableB.addEventListener("click", function () {
      if (grid) { closeGrid(); return; }
      keep();
      grid = document.createElement("div"); grid.className = "rte-grid"; grid.setAttribute("role", "dialog"); grid.setAttribute("aria-label", "표 크기 고르기");
      var R = 10, C = 8, cells = [];
      var label = document.createElement("div"); label.className = "rte-grid-l"; label.textContent = "표 크기를 고르세요";
      var board = document.createElement("div"); board.className = "rte-grid-b"; board.style.gridTemplateColumns = "repeat(" + C + ", 18px)";
      for (var i = 1; i <= R; i++) for (var j = 1; j <= C; j++) {
        var d = document.createElement("button"); d.type = "button"; d.dataset.r = i; d.dataset.c = j; d.setAttribute("aria-label", i + "행 " + j + "열");
        board.appendChild(d); cells.push(d);
      }
      function mark(r, c) { cells.forEach(function (x) { x.classList.toggle("on", +x.dataset.r <= r && +x.dataset.c <= c); }); label.textContent = r + "행 × " + c + "열"; }
      board.addEventListener("mouseover", function (e) { var t = e.target.closest("button"); if (t) mark(+t.dataset.r, +t.dataset.c); });
      board.addEventListener("focusin", function (e) { var t = e.target.closest("button"); if (t) mark(+t.dataset.r, +t.dataset.c); });
      board.addEventListener("mousedown", function (e) { e.preventDefault(); });
      board.addEventListener("click", function (e) { var t = e.target.closest("button"); if (!t) return; var r = +t.dataset.r, c = +t.dataset.c; closeGrid(); insertTable(r, c); });
      var more = document.createElement("div"); more.className = "rte-grid-m";
      more.innerHTML = '<input type="number" min="1" max="60" value="3" aria-label="행"> × <input type="number" min="1" max="20" value="3" aria-label="열"> <button type="button" class="btn btn-sm">넣기</button>';
      more.querySelector("button").addEventListener("click", function () {
        var ins = more.querySelectorAll("input"), r = Math.max(1, Math.min(60, +ins[0].value || 1)), c = Math.max(1, Math.min(20, +ins[1].value || 1));
        closeGrid(); insertTable(r, c);
      });
      grid.appendChild(label); grid.appendChild(board); grid.appendChild(more);
      wrap.appendChild(grid);
      var br = tableB.getBoundingClientRect(), wr = wrap.getBoundingClientRect();
      grid.style.left = Math.max(0, br.left - wr.left) + "px"; grid.style.top = (br.bottom - wr.top + 4) + "px";
      setTimeout(function () { document.addEventListener("mousedown", outside, true); }, 0);
    });
    // 고른 줄들을 한 단계 들이거나 내어 쓴다. 사규 글(para)은 본문·항 → 호 → 목, 목록은 목록 단계, 그 밖은 왼쪽 여백 2글자.
    function blocksInSelection() {
      restore();
      var s = window.getSelection(); if (!s.rangeCount) return [];
      var rg = s.getRangeAt(0), out = [];
      function blockOf(n) {
        while (n && n !== body) { if (n.nodeType === 1 && /^(P|DIV|LI|H[1-6]|BLOCKQUOTE)$/.test(n.tagName) && !n.closest("td,th")) return n; n = n.parentNode; }
        return null;
      }
      var start = blockOf(rg.startContainer), end = blockOf(rg.endContainer);
      if (!start) return [];
      var all = Array.prototype.slice.call(body.querySelectorAll("p,div,li,h1,h2,h3,h4,h5,h6,blockquote"));
      var a = all.indexOf(start), z = end ? all.indexOf(end) : a;
      for (var i = a; i <= Math.max(a, z); i++) if (all[i] && !all[i].closest("td,th") && !all[i].querySelector("p,div,li")) out.push(all[i]);
      return out.length ? out : [start];
    }
    function shiftLevel(dir) {
      var blocks = blocksInSelection();
      if (!blocks.length) { exec(dir > 0 ? "indent" : "outdent"); return; }
      if (blocks[0].tagName === "LI") { exec(dir > 0 ? "indent" : "outdent"); return; }
      blocks.forEach(function (bk) {
        if (bk.classList.contains("para")) {
          var lv = bk.classList.contains("m") ? 3 : bk.classList.contains("h") ? 2 : 1;
          var circled = /^\s*[\u2460-\u2473]/.test(bk.textContent);
          var next = Math.max(1, Math.min(3, lv + dir));
          ["b", "p", "h", "m"].forEach(function (c) { bk.classList.remove(c); });
          bk.classList.add(next === 3 ? "m" : next === 2 ? "h" : circled ? "p" : "b");
        } else {
          var cur = parseFloat(bk.style.marginLeft) || 0, nv = Math.max(0, cur + dir * 2);
          bk.style.marginLeft = nv ? nv + "em" : "";
        }
      });
      keep(); sync();
    }
    function cellNow() { restore(); var s = window.getSelection(); return s.rangeCount ? closestCell(s.anchorNode, body) : null; }
    rowAdd.addEventListener("click", function () {
      var td = cellNow(); if (!td) return;
      var tr = td.parentNode, n = tr.cloneNode(true);
      n.querySelectorAll("td,th").forEach(function (c) { var d = document.createElement("td"); d.colSpan = c.colSpan; d.innerHTML = "<br>"; c.parentNode.replaceChild(d, c); });
      tr.parentNode.insertBefore(n, tr.nextSibling); sync();
    });
    colAdd.addEventListener("click", function () {
      var td = cellNow(); if (!td) return;
      var idx = Array.prototype.indexOf.call(td.parentNode.children, td), table = td.closest("table");
      Array.prototype.forEach.call(table.rows, function (tr) {
        if (tr.closest("table") !== table) return;
        var ref = tr.children[idx], d = document.createElement(ref && ref.tagName === "TH" ? "th" : "td"); d.innerHTML = "<br>";
        if (ref) ref.parentNode.insertBefore(d, ref.nextSibling); else tr.appendChild(d);
      });
      sync();
    });
    rowDel.addEventListener("click", function () {
      var td = cellNow(); if (!td) return;
      var tr = td.parentNode, table = tr.closest("table");
      tr.remove(); if (!table.rows.length) table.remove(); sync();
    });
    colDel.addEventListener("click", function () {
      var td = cellNow(); if (!td) return;
      var idx = Array.prototype.indexOf.call(td.parentNode.children, td), table = td.closest("table");
      Array.prototype.forEach.call(Array.prototype.slice.call(table.rows), function (tr) { if (tr.closest("table") === table && tr.children[idx]) tr.children[idx].remove(); });
      if (!table.querySelector("td,th")) table.remove();
      sync();
    });
    // ───── 셀 병합·나누기: 표 칸을 끌어 고른다 ─────
    function tableMap(table) {
      var map = [], pos = new Map();
      Array.prototype.forEach.call(table.rows, function (tr, r) {
        if (tr.closest("table") !== table) return;
        map[r] = map[r] || [];
        var c = 0;
        for (var cell = tr.firstElementChild; cell; cell = cell.nextElementSibling) {
          if (!/^T[DH]$/.test(cell.tagName)) continue;
          while (map[r][c]) c++;
          var rs = cell.rowSpan || 1, cs = cell.colSpan || 1;
          for (var rr = r; rr < r + rs; rr++) { map[rr] = map[rr] || []; for (var cc = c; cc < c + cs; cc++) map[rr][cc] = cell; }
          pos.set(cell, { r: r, c: c, rs: rs, cs: cs });
          c += cs;
        }
      });
      return { map: map, pos: pos };
    }
    var cellSel = null;   // { table, r0, c0, r1, c1 }
    function cellRect(table, a, b) {
      var tm = tableMap(table), pa = tm.pos.get(a), pb = tm.pos.get(b);
      if (!pa || !pb) return null;
      var r0 = Math.min(pa.r, pb.r), c0 = Math.min(pa.c, pb.c), r1 = Math.max(pa.r + pa.rs, pb.r + pb.rs) - 1, c1 = Math.max(pa.c + pa.cs, pb.c + pb.cs) - 1;
      // 걸친 합친 칸이 모두 들어가도록 넓힌다
      for (var grew = true; grew;) {
        grew = false;
        for (var r = r0; r <= r1; r++) for (var c = c0; c <= c1; c++) {
          var x = tm.map[r] && tm.map[r][c]; if (!x) continue;
          var p = tm.pos.get(x);
          if (p.r < r0) { r0 = p.r; grew = true; } if (p.c < c0) { c0 = p.c; grew = true; }
          if (p.r + p.rs - 1 > r1) { r1 = p.r + p.rs - 1; grew = true; } if (p.c + p.cs - 1 > c1) { c1 = p.c + p.cs - 1; grew = true; }
        }
      }
      return { table: table, r0: r0, c0: c0, r1: r1, c1: c1, tm: tm };
    }
    function paintCells() {
      body.querySelectorAll(".rte-cellsel").forEach(function (c) { c.classList.remove("rte-cellsel"); });
      if (!cellSel) return;
      for (var r = cellSel.r0; r <= cellSel.r1; r++) for (var c = cellSel.c0; c <= cellSel.c1; c++) { var x = cellSel.tm.map[r] && cellSel.tm.map[r][c]; if (x) x.classList.add("rte-cellsel"); }
    }
    function clearCells() { cellSel = null; paintCells(); }
    var cellDown = null;
    body.addEventListener("mousedown", function (e) {
      if (e.button !== 0 || edgeOf(e)) return;
      var c = e.target.closest && e.target.closest("td,th");
      clearCells();
      cellDown = c && body.contains(c) ? c : null;
    });
    body.addEventListener("mousemove", function (e) {
      if (!cellDown || !(e.buttons & 1)) return;
      var c = e.target.closest && e.target.closest("td,th");
      if (!c || c === cellDown || c.closest("table") !== cellDown.closest("table")) { if (cellSel && c === cellDown) clearCells(); return; }
      cellSel = cellRect(cellDown.closest("table"), cellDown, c);
      var s = window.getSelection(); if (s.rangeCount) s.removeAllRanges();   // 칸을 고르는 동안 글 선택은 없앤다
      paintCells();
    });
    document.addEventListener("mouseup", function () { cellDown = null; });
    mergeB.addEventListener("click", function () {
      if (!cellSel || (cellSel.r0 === cellSel.r1 && cellSel.c0 === cellSel.c1)) {
        if (window.auditToast) window.auditToast("합칠 칸을 마우스로 끌어 두 칸 이상 고르세요.");
        return;
      }
      var tm = tableMap(cellSel.table), first = tm.map[cellSel.r0][cellSel.c0], seen = new Set([first]);
      for (var r = cellSel.r0; r <= cellSel.r1; r++) for (var c = cellSel.c0; c <= cellSel.c1; c++) {
        var x = tm.map[r] && tm.map[r][c];
        if (!x || seen.has(x)) continue;
        seen.add(x);
        var txt = (x.textContent || "").trim();
        if (txt || x.querySelector("img,table")) { if ((first.textContent || "").trim() || first.querySelector("img,table")) first.appendChild(document.createElement("br")); while (x.firstChild) first.appendChild(x.firstChild); }
        x.remove();
      }
      first.colSpan = cellSel.c1 - cellSel.c0 + 1;
      first.rowSpan = cellSel.r1 - cellSel.r0 + 1;
      clearCells(); sync();
    });
    splitB.addEventListener("click", function () {
      var td = cellSel ? cellSel.tm.map[cellSel.r0][cellSel.c0] : cellNow();
      if (!td || ((td.colSpan || 1) === 1 && (td.rowSpan || 1) === 1)) {
        if (window.auditToast) window.auditToast("합친 칸 안을 누른 뒤 '나누기'를 누르세요.");
        return;
      }
      var table = td.closest("table"), tm = tableMap(table), p = tm.pos.get(td);
      td.colSpan = 1; td.rowSpan = 1;
      for (var r = p.r; r < p.r + p.rs; r++) {
        var tr = table.rows[r];
        for (var c = p.c; c < p.c + p.cs; c++) {
          if (r === p.r && c === p.c) continue;
          var nc = document.createElement(td.tagName === "TH" ? "th" : "td"); nc.innerHTML = "<br>";
          // 그 줄에서 c보다 뒤에서 시작하는 첫 칸 앞에 넣는다
          var before = null;
          for (var k = tr.firstElementChild; k; k = k.nextElementSibling) { var q = tm.pos.get(k); if (q && q.c > c) { before = k; break; } }
          tr.insertBefore(nc, before);
          tm = tableMap(table);
        }
      }
      clearCells(); sync();
    });

    // ───── 서식 복사 ─────
    var painter = null;   // { style, sticky }
    function styleAt(node) {
      var el = node && (node.nodeType === 1 ? node : node.parentElement);
      if (!el || !body.contains(el)) return null;
      var cs = getComputedStyle(el), base = getComputedStyle(body);
      var st = {};
      if (cs.fontWeight !== base.fontWeight && parseInt(cs.fontWeight, 10) >= 600) st.fontWeight = "700";
      if (cs.fontStyle === "italic") st.fontStyle = "italic";
      var deco = []; for (var e = el; e && e !== body; e = e.parentElement) { var d = getComputedStyle(e).textDecorationLine; if (d && d !== "none") d.split(" ").forEach(function (x) { if (deco.indexOf(x) < 0) deco.push(x); }); }
      if (deco.length) st.textDecoration = deco.join(" ");
      if (cs.color !== base.color) st.color = cs.color;
      for (var b2 = el; b2 && b2 !== body; b2 = b2.parentElement) { var bgc = getComputedStyle(b2).backgroundColor; if (bgc && bgc !== "rgba(0, 0, 0, 0)" && bgc !== "transparent" && !/^(TD|TH|TABLE|DIV|P)$/.test(b2.tagName)) { st.backgroundColor = bgc; break; } }
      if (cs.fontSize !== base.fontSize) st.fontSize = cs.fontSize;
      if (cs.fontFamily !== base.fontFamily) st.fontFamily = cs.fontFamily;
      if (cs.verticalAlign === "super" || cs.verticalAlign === "sub") st.verticalAlign = cs.verticalAlign;
      return st;
    }
    function applyStyle(range, st) {
      // 고른 글자에 입혀진 서식을 지우고 복사한 서식을 입힌다
      var s = window.getSelection(); s.removeAllRanges(); s.addRange(range);
      try { document.execCommand("removeFormat", false, null); } catch (x) { }
      range = s.rangeCount ? s.getRangeAt(0) : range;
      var texts = [], w = document.createTreeWalker(range.commonAncestorContainer.nodeType === 1 ? range.commonAncestorContainer : range.commonAncestorContainer.parentNode, NodeFilter.SHOW_TEXT);
      for (var n = w.nextNode(); n; n = w.nextNode()) if (range.intersectsNode(n) && n.nodeValue.length) texts.push(n);
      var first = null, last = null;
      texts.forEach(function (t) {
        var a = t === range.startContainer ? range.startOffset : 0, z = t === range.endContainer ? range.endOffset : t.nodeValue.length;
        if (z <= a) return;
        if (z < t.nodeValue.length) t.splitText(z);
        if (a > 0) t = t.splitText(a);
        var sp = document.createElement("span");
        Object.keys(st).forEach(function (k) { sp.style[k] = st[k]; });
        t.parentNode.insertBefore(sp, t); sp.appendChild(t);
        first = first || sp; last = sp;
      });
      if (first) { var r2 = document.createRange(); r2.setStartBefore(first); r2.setEndAfter(last); s.removeAllRanges(); s.addRange(r2); }
      keep(); sync();
    }
    function painterOff() { painter = null; painterB.classList.remove("on"); painterB.removeAttribute("aria-pressed"); body.classList.remove("rte-painting"); }
    var painterClick = 0;
    painterB.addEventListener("click", function () {
      var now = Date.now(), dbl = now - painterClick < 400; painterClick = now;
      if (painter && !dbl) { painterOff(); return; }
      restore();
      var s = window.getSelection();
      var st = s.rangeCount ? styleAt(s.getRangeAt(0).startContainer.nodeType === 3 && s.getRangeAt(0).startOffset === s.getRangeAt(0).startContainer.nodeValue.length && !s.isCollapsed ? s.getRangeAt(0).endContainer : s.getRangeAt(0).startContainer) : null;
      if (!st) { if (window.auditToast) window.auditToast("서식을 가져올 글을 먼저 누르거나 고르세요."); return; }
      painter = { style: st, sticky: dbl };
      painterB.classList.add("on"); painterB.setAttribute("aria-pressed", "true"); body.classList.add("rte-painting");
      if (window.auditToast) window.auditToast(dbl ? "서식 복사 계속: 바꿀 글을 끌어 고르세요. 끝낼 때는 Esc 또는 단추를 다시 누르세요." : "바꿀 글을 끌어 고르면 같은 서식이 입혀집니다.");
    });
    body.addEventListener("mouseup", function () {
      if (!painter) return;
      setTimeout(function () {
        var s = window.getSelection();
        if (!s.rangeCount || s.isCollapsed || !body.contains(s.getRangeAt(0).commonAncestorContainer)) return;
        applyStyle(s.getRangeAt(0).cloneRange(), painter.style);
        if (!painter.sticky) painterOff();
      }, 0);
    });
    document.addEventListener("keydown", function (e) { if (e.key === "Escape") { if (painter) painterOff(); if (cellSel) clearCells(); } });

    // ───── 고른 글의 서식을 도구 줄에 보여 준다(바로 바꿀 수 있게) ─────
    var stateBtns = Array.prototype.slice.call(bar.querySelectorAll("button[data-cmd]")).filter(function (b) {
      return /^(bold|italic|underline|strikeThrough|superscript|subscript|justifyLeft|justifyCenter|justifyRight|justifyFull|insertUnorderedList|insertOrderedList)$/.test(b.dataset.cmd);
    });
    function showState() {
      var s = window.getSelection();
      if (!s.rangeCount || !body.contains(s.getRangeAt(0).commonAncestorContainer)) return;
      stateBtns.forEach(function (b) { var on = false; try { on = document.queryCommandState(b.dataset.cmd); } catch (x) { } b.classList.toggle("on", !!on); b.setAttribute("aria-pressed", on ? "true" : "false"); });
      var n = s.getRangeAt(0).startContainer, el = n.nodeType === 1 ? n : n.parentElement;
      if (!el) return;
      var cs = getComputedStyle(el), pt = Math.round(parseFloat(cs.fontSize) * 0.75 * 2) / 2;
      size.options[0].textContent = (pt % 1 ? pt : Math.round(pt)) + "";
      font.options[0].textContent = (cs.fontFamily.split(",")[0] || "글꼴").replace(/["']/g, "").slice(0, 10);
      var bk = el.closest(".para, p, h2, h3, h4, blockquote, li, td, th");
      var bn = !bk ? "문단" : bk.classList.contains("p") ? "항" : bk.classList.contains("h") ? "호" : bk.classList.contains("m") ? "목"
        : ({ H2: "제목 1", H3: "제목 2", H4: "제목 3", BLOCKQUOTE: "인용", LI: "목록", TD: "표 칸", TH: "표 칸" }[bk.tagName] || "본문");
      block.options[0].textContent = bn;
    }
    // 끄는 도중 편집 칸 밖에서 손을 떼도 고른 범위를 기억한다
    document.addEventListener("selectionchange", function () {
      var s = window.getSelection();
      if (s.rangeCount && body.contains(s.getRangeAt(0).commonAncestorContainer)) { keep(); showState(); }
    });

    imgB.addEventListener("click", function () { keep(); picker.click(); });
    picker.addEventListener("change", function () {
      var f = picker.files && picker.files[0]; picker.value = "";
      if (!f) return;
      readImage(f).then(function (d) { exec("insertHTML", '<img src="' + d + '" alt="' + esc(f.name) + '">'); });
    });

    body.addEventListener("paste", function (e) {
      var dt = e.clipboardData; if (!dt) return;
      var imgs = [];
      Array.prototype.forEach.call(dt.files || [], function (f) { if (/^image\//.test(f.type)) imgs.push(f); });
      var html = dt.getData("text/html"), text = dt.getData("text/plain");
      if (imgs.length && !html && !text.trim()) {
        e.preventDefault();
        imgs.forEach(function (f) { readImage(f).then(function (d) { exec("insertHTML", '<img src="' + d + '" alt="붙여넣은 그림">'); }); });
        return;
      }
      if (html) { e.preventDefault(); exec("insertHTML", cleanPaste(html)); return; }
      if (text && text.indexOf("\t") >= 0) { e.preventDefault(); exec("insertHTML", textToHtml(text)); }
    });
    body.addEventListener("keydown", function (e) {
      if (e.key === "Tab") {
        e.preventDefault();
        if (closestCell(window.getSelection().anchorNode, body)) return; // 표 칸 안에서는 아무것도 하지 않는다
        shiftLevel(e.shiftKey ? -1 : 1);
      }
      e.stopPropagation(); // 화면 단축키(Ctrl+Z 되돌리기 등)와 섞이지 않게
    });

    // 표 칸의 오른쪽 경계를 끌면 그 열 너비가, 아래 경계를 끌면 그 행 높이가 바뀐다
    var EDGE = 5, drag = null;
    function edgeOf(e) {
      var cell = e.target && e.target.closest ? e.target.closest("td,th") : null;
      if (!cell || !body.contains(cell)) return null;
      var r = cell.getBoundingClientRect();
      if (r.right - e.clientX <= EDGE) return { kind: "col", cell: cell };
      if (r.bottom - e.clientY <= EDGE) return { kind: "row", cell: cell };
      return null;
    }
    body.addEventListener("mousemove", function (e) {
      if (drag) return;
      var ed = edgeOf(e);
      body.style.cursor = ed ? (ed.kind === "col" ? "col-resize" : "row-resize") : "";
    });
    body.addEventListener("mousedown", function (e) {
      var ed = edgeOf(e);
      if (!ed) return;
      e.preventDefault();
      var table = ed.cell.closest("table"), r0 = ed.cell.getBoundingClientRect();
      if (ed.kind === "col") {
        var idx = 0, x = ed.cell.previousElementSibling;
        while (x) { idx += x.colSpan || 1; x = x.previousElementSibling; }
        idx += (ed.cell.colSpan || 1) - 1;
        // 표의 칸마다 지금 너비를 고정해 두고(다른 열이 따라 움직이지 않게), 경계 양쪽 열만 바꾼다(워드처럼)
        function colCells(i) {
          var out = [];
          Array.prototype.forEach.call(table.rows, function (tr) {
            if (tr.closest("table") !== table) return;
            var k = 0;
            for (var c = tr.firstElementChild; c; c = c.nextElementSibling) { var span = c.colSpan || 1, end = k + span - 1; if (span === 1 && end === i) { out.push(c); break; } k = end + 1; }
          });
          return out;
        }
        var cols = 0;
        Array.prototype.forEach.call(table.rows, function (tr) { var n = 0; for (var c = tr.firstElementChild; c; c = c.nextElementSibling) n += c.colSpan || 1; cols = Math.max(cols, n); });
        var widths = [];
        for (var i = 0; i < cols; i++) { var cc = colCells(i); widths.push(cc.length ? cc[0].getBoundingClientRect().width : table.getBoundingClientRect().width / cols); }
        // 열 너비는 표의 <colgroup>에 둔다(칸 너비는 화면 규칙이 자동으로 되돌리므로)
        var cg = table.querySelector(":scope > colgroup");
        if (!cg) { cg = document.createElement("colgroup"); table.insertBefore(cg, table.firstChild); }
        while (cg.children.length < cols) cg.appendChild(document.createElement("col"));
        while (cg.children.length > cols) cg.lastChild.remove();
        for (var k2 = 0; k2 < cols; k2++) cg.children[k2].style.width = Math.round(widths[k2]) + "px";
        Array.prototype.forEach.call(table.querySelectorAll("td,th"), function (c) { if (c.closest("table") === table) { c.style.width = ""; c.style.minWidth = ""; } });
        table.style.tableLayout = "fixed";
        drag = { kind: "col", x0: e.clientX, left: [cg.children[idx]], right: idx + 1 < cols ? [cg.children[idx + 1]] : [], lw: widths[idx] || r0.width, rw: widths[idx + 1] || 0 };
      } else {
        drag = { kind: "row", y0: e.clientY, h0: r0.height, cells: Array.prototype.slice.call(ed.cell.parentNode.children) };
      }
      document.body.classList.add("rte-resizing");
    });
    document.addEventListener("mousemove", function (e) {
      if (!drag) return;
      if (drag.kind === "col") {
        var dx = e.clientX - drag.x0;
        if (drag.right.length) dx = Math.max(24 - drag.lw, Math.min(drag.rw - 24, dx));
        else dx = Math.max(24 - drag.lw, dx);
        var lw = Math.round(drag.lw + dx), rw = Math.round(drag.rw - dx);
        drag.left.forEach(function (c) { c.style.width = lw + "px"; });
        drag.right.forEach(function (c) { c.style.width = rw + "px"; });
      }
      else { var h = Math.max(20, Math.round(drag.h0 + e.clientY - drag.y0)); drag.cells.forEach(function (c) { c.style.height = h + "px"; }); }
    });
    document.addEventListener("mouseup", function () {
      if (!drag) return;
      drag = null; document.body.classList.remove("rte-resizing"); body.style.cursor = ""; sync();
    });

    function sync() {
      ta.value = htmlToText(body);
      if (hidden) hidden.value = body.innerHTML;
    }
    sync();
    if (form && !form.dataset.richHook) {
      form.dataset.richHook = "1";
      form.addEventListener("submit", function () {
        form.querySelectorAll("textarea[data-rich-ready]").forEach(function (t) { if (t.__richSync) t.__richSync(); });
      }, true);
    }
    ta.__richSync = sync;
  }

  function init(root) {
    (root || document).querySelectorAll("textarea[data-rich]:not([data-rich-ready])").forEach(build);
  }
  window.richEditorInit = init;
  document.addEventListener("DOMContentLoaded", function () { init(document); });
  if (document.readyState !== "loading") init(document);
  // 저장 뒤 화면 일부가 새로 그려지면 다시 붙인다
  new MutationObserver(function () { init(document); }).observe(document.documentElement, { childList: true, subtree: true });

  // 사규 등록: 붙여 넣은 표(표 안의 표 포함)를 사규 글 꼴로
  document.addEventListener("paste", function (e) {
    var ta = e.target;
    if (!ta || ta.tagName !== "TEXTAREA" || !ta.hasAttribute("data-rule-paste") || !window.sagyuPaste) return;
    var dt = e.clipboardData; if (!dt) return;
    var t = dt.getData("text/html");
    if (t && /<t(able|body|r)\b/i.test(t)) t = window.sagyuPaste.htmlToText(t);
    else {
      t = dt.getData("text/plain") || "";
      if (t.indexOf("\t") < 0) return;
      t = window.sagyuPaste.tsvToTbl(window.sagyuPaste.dropStyleJunk(t));
    }
    if (!t) return;
    e.preventDefault();
    var a = ta.selectionStart, b = ta.selectionEnd;
    ta.value = ta.value.slice(0, a) + t + ta.value.slice(b);
    ta.selectionStart = ta.selectionEnd = a + t.length;
    if (window.auditToast) window.auditToast("표를 칸 구분해서 붙였습니다. 표 안의 표도 칸 안에 담았습니다.");
  }, true);
})();
