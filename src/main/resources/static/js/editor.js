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
    var wrap = document.createElement("div"); wrap.className = "rte";
    var bar = document.createElement("div"); bar.className = "rte-bar"; bar.setAttribute("role", "toolbar"); bar.setAttribute("aria-label", "서식");
    var body = document.createElement("div"); body.className = "rte-body rich"; body.contentEditable = "true";
    body.setAttribute("role", "textbox"); body.setAttribute("aria-multiline", "true");
    body.setAttribute("aria-label", ta.getAttribute("aria-label") || (ta.closest("label") ? ta.closest("label").firstChild.textContent.trim() : "내용"));
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
        button("행−", "행 지우기", null), button("열−", "열 지우기", null), button("―", "가로줄", "insertHorizontalRule"), button("그림", "그림 넣기", null)],
      [button("⌫서식", "서식 지우기", "removeFormat"), button("↶", "되돌리기 (Ctrl+Z)", "undo"), button("↷", "다시 하기 (Ctrl+Y)", "redo")]
    ];
    parts.forEach(function (g) {
      var grp = document.createElement("span"); grp.className = "rte-g";
      g.forEach(function (el) { grp.appendChild(el); });
      bar.appendChild(grp);
    });
    var tableB = parts[5][0], rowAdd = parts[5][1], colAdd = parts[5][2], rowDel = parts[5][3], colDel = parts[5][4], imgB = parts[5][6];
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
      restore();
      try { document.execCommand("styleWithCSS", false, true); } catch (e) { }
      document.execCommand(cmd, false, val == null ? null : val);
      keep(); sync();
    }
    bar.addEventListener("mousedown", function (e) { if (e.target.closest("button")) e.preventDefault(); });
    bar.addEventListener("click", function (e) {
      var b = e.target.closest("button[data-cmd]");
      if (b) exec(b.dataset.cmd);
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

    tableB.addEventListener("click", function () {
      var spec = window.prompt("표 크기를 '행x열'로 적으세요. (예: 3x4)", "3x3");
      if (!spec) return;
      var m = /^(\d{1,2})\s*[x×*,]\s*(\d{1,2})$/.exec(spec.trim());
      if (!m) return;
      var r = Math.min(60, +m[1]), c = Math.min(20, +m[2]), h = "<table>";
      for (var i = 0; i < r; i++) { h += "<tr>"; for (var j = 0; j < c; j++) h += (i === 0 ? "<th>" : "<td>") + "<br>" + (i === 0 ? "</th>" : "</td>"); h += "</tr>"; }
      h += "</table><p><br></p>";
      exec("insertHTML", h);
    });
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
      if (e.key === "Tab") { e.preventDefault(); exec(e.shiftKey ? "outdent" : "indent"); }
      e.stopPropagation(); // 화면 단축키(Ctrl+Z 되돌리기 등)와 섞이지 않게
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
