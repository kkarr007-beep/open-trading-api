// 원본 보기 창: 쪽 그림 + 글자 층, 찾기·배율·인쇄
(function () {
  var KEY = document.body.getAttribute("data-key");
  var box = document.getElementById("box"), pagesEl = document.getElementById("pages");
  var S = null, TEXT = [], zoom = 0, hits = [], cur = -1, built = false;
  function el(t, c, h) { var e = document.createElement(t); if (c) e.className = c; if (h != null) e.innerHTML = h; return e; }
  function esc(s) { return String(s == null ? "" : s).replace(/[&<>"]/g, function (c) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]; }); }

  function poll() {
    fetch("/original/" + KEY + "/state", { cache: "no-store" }).then(function (r) { return r.json(); }).then(function (s) {
      S = s;
      if (s.state === "없음") return;
      document.getElementById("dsrc").hidden = false;
      if (s.state === "만드는 중") { showMaking(s); setTimeout(poll, 1200); return; }
      if (s.state === "실패") { showFail(s); return; }
      document.getElementById("dpdf").hidden = !s.pdf;
      document.getElementById("via").textContent = "등록 당시 원본 모양 · " + (s.via ? s.via + "로 만듦 · " : "") + "고칠 수 없음 · 글자 찾기 됨" + (s.note ? " · " + s.note : "");
      box.innerHTML = "";
      fetch("/original/" + KEY + "/text.json", { cache: "no-store" }).then(function (r) { return r.json(); }).then(function (t) { TEXT = t || []; build(); });
    }).catch(function () { box.innerHTML = '<div class="msg"><h2>상태를 읽지 못했습니다</h2><p>프로그램이 켜져 있는지 확인하고 창을 새로 고치세요.</p></div>'; });
  }
  function showMaking(s) {
    var pct = s.pages ? Math.round(s.done / s.pages * 100) : 5;
    box.innerHTML = '<div class="msg"><h2>원본 모양을 만드는 중입니다</h2>'
      + '<p>' + (s.pages ? "쪽 그림 " + s.done + " / " + s.pages : "이 컴퓨터의 한글·오피스 프로그램으로 PDF를 만드는 중입니다. 문서가 크면 1~2분 걸립니다.") + '</p>'
      + '<div class="prog"><i></i></div><p>창을 닫아도 계속 만듭니다. 다음에 열면 바로 보입니다.</p></div>';
    box.querySelector(".prog i").style.width = pct + "%";
  }
  function showFail(s) {
    box.innerHTML = '<div class="msg"><h2>원본 모양을 만들지 못했습니다</h2><p>' + esc(s.note) + '</p>'
      + '<p>한글·MS 오피스·LibreOffice 중 하나가 이 컴퓨터에 있어야 합니다. 설치했다면 다시 만드세요.</p>'
      + '<p class="acts"><button class="b pri" id="remake" type="button">다시 만들기</button> <a class="b" href="/original/' + KEY + '/source">원본 내려받기</a></p></div>';
    document.getElementById("remake").onclick = function () {
      this.disabled = true;
      fetch("/original/" + KEY + "/make", { method: "POST", headers: { "X-Audit-Fetch": "1" } }).then(function (r) { return r.json(); }).then(function (r) {
        if (!r.ok) { alert(r.message); } poll();
      });
    };
  }

  function fitWidth() { return Math.min(window.innerWidth - 48, 1000); }
  function build() {
    pagesEl.innerHTML = "";
    var meta = S.meta || [];
    for (var i = 0; i < meta.length; i++) {
      var pg = el("div", "pg"); pg.dataset.i = i;
      var img = el("img"); img.loading = "lazy"; img.alt = (i + 1) + "쪽"; img.src = "/original/" + KEY + "/p/" + (i + 1) + ".png?v=" + encodeURIComponent(S.made || "");
      pg.appendChild(img);
      var tl = el("div", "tl");
      (TEXT[i] || []).forEach(function (l) {
        var sp = el("span"); sp.textContent = l.t; l._sp = sp;
        sp.style.left = l.x + "%"; sp.style.top = l.y + "%"; sp.dataset.w = l.w; sp.dataset.h = l.h;
        tl.appendChild(sp);
      });
      pg.appendChild(tl);
      pg.appendChild(el("span", "pn", (i + 1) + " / " + meta.length));
      pagesEl.appendChild(pg);
    }
    built = true;
    layout();
    var q = document.getElementById("q").value.trim(); if (q) find(q);
  }
  // 쪽 크기와 글자 층 크기를 화면 배율에 맞춘다
  function layout() {
    if (!built) return;
    var meta = S.meta || [], base = fitWidth();
    Array.prototype.forEach.call(pagesEl.children, function (pg, i) {
      var m = meta[i] || [595, 842], w = zoom ? m[0] * zoom * 96 / 72 : base, h = w * m[1] / m[0];
      pg.style.width = w + "px"; pg.style.height = h + "px";
      Array.prototype.forEach.call(pg.querySelectorAll(".tl span"), function (sp) {
        var hh = parseFloat(sp.dataset.h) / 100 * h / 1.15, ww = parseFloat(sp.dataset.w) / 100 * w;
        sp.style.fontSize = Math.max(4, hh) + "px";
        sp.style.transform = "";
        var nat = sp.getBoundingClientRect().width || 1;
        sp.style.transform = "scaleX(" + (ww / nat) + ")";
      });
    });
    drawHits();
  }

  function find(q) {
    hits = []; cur = -1;
    if (q) {
      var ql = q.toLowerCase();
      TEXT.forEach(function (lines, pi) {
        (lines || []).forEach(function (l) {
          var t = String(l.t).toLowerCase(), at = t.indexOf(ql);
          while (at >= 0) { hits.push({ p: pi, l: l, a: at, n: ql.length, len: t.length || 1 }); at = t.indexOf(ql, at + ql.length); }
        });
      });
    }
    cur = hits.length ? 0 : -1;
    drawHits(); go();
  }
  function drawHits() {
    Array.prototype.forEach.call(pagesEl.querySelectorAll(".hl"), function (h) { h.remove(); });
    hits.forEach(function (h, k) {
      var pg = pagesEl.children[h.p]; if (!pg) return;
      var d = el("div", "hl" + (k === cur ? " cur" : ""));
      var x = h.l.x + h.l.w * h.a / h.len, w = Math.max(0.6, h.l.w * h.n / h.len);
      // 글자 층의 실제 글자 위치로 맞춘다(글자 너비가 제각각이라)
      var tn = h.l._sp && h.l._sp.firstChild;
      if (tn && tn.length >= h.a + h.n) {
        try {
          var rg = document.createRange(); rg.setStart(tn, h.a); rg.setEnd(tn, h.a + h.n);
          var rr = rg.getBoundingClientRect(), pr = pg.getBoundingClientRect();
          if (rr.width > 0 && pr.width > 0) { x = (rr.left - pr.left) / pr.width * 100; w = rr.width / pr.width * 100; }
        } catch (e) { }
      }
      d.style.left = x + "%"; d.style.top = h.l.y + "%";
      d.style.width = w + "%"; d.style.height = h.l.h + "%";
      h.el = d; pg.appendChild(d);
    });
    var c = document.getElementById("cnt"), q = document.getElementById("q").value.trim();
    c.textContent = !q ? "" : hits.length ? (cur + 1) + " / " + hits.length : "없음";
  }
  function go() {
    hits.forEach(function (h, k) { if (h.el) h.el.classList.toggle("cur", k === cur); });
    var h = hits[cur]; if (!h || !h.el) return;
    h.el.scrollIntoView({ block: "center" });
    document.getElementById("cnt").textContent = (cur + 1) + " / " + hits.length;
  }
  function step(d) { if (!hits.length) return; cur = (cur + d + hits.length) % hits.length; go(); }

  var qi = document.getElementById("q"), last = "";
  qi.addEventListener("keydown", function (e) {
    if (e.key !== "Enter") return;
    e.preventDefault();
    var q = qi.value.trim();
    if (q !== last) { last = q; find(q); } else step(e.shiftKey ? -1 : 1);
  });
  qi.addEventListener("input", function () { if (!qi.value.trim()) { last = ""; find(""); } });
  document.getElementById("next").onclick = function () { var q = qi.value.trim(); if (q !== last) { last = q; find(q); } else step(1); };
  document.getElementById("prev").onclick = function () { step(-1); };
  function curZoom() { var m = (S && S.meta && S.meta[0]) || [595, 842]; return zoom || fitWidth() / (m[0] * 96 / 72); }
  document.getElementById("zi").onclick = function () { zoom = Math.min(4, curZoom() * 1.2); layout(); };
  document.getElementById("zo").onclick = function () { zoom = Math.max(0.3, curZoom() / 1.2); layout(); };
  document.getElementById("zf").onclick = function () { zoom = 0; layout(); };
  document.getElementById("pr").onclick = function () {
    var imgs = Array.prototype.slice.call(pagesEl.querySelectorAll("img"));
    Promise.all(imgs.map(function (im) { im.loading = "eager"; return im.complete ? 0 : new Promise(function (ok) { im.onload = im.onerror = ok; }); })).then(function () { window.print(); });
  };
  document.addEventListener("keydown", function (e) {
    if ((e.ctrlKey || e.metaKey) && (e.key === "f" || e.key === "F")) { e.preventDefault(); qi.focus(); qi.select(); }
  });
  var rt; window.addEventListener("resize", function () { if (zoom) return; clearTimeout(rt); rt = setTimeout(layout, 150); });
  if (!document.querySelector(".msg")) poll();
})();
