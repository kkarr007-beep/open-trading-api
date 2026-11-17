// 경영 현황판을 Portal 안에서 쓸 때: 브라우저 저장소 대신 Portal의 데이터 파일에 저장한다.
// 현황판 화면이 열리기 전에 Portal에 저장된 값을 불러와 넣고, 바뀔 때마다 Portal로 보낸다. 백업에도 함께 들어간다.
(function () {
  "use strict";
  var KEYS = /^exec-/;
  var ls;
  try { ls = window.localStorage; } catch (e) { return; }
  var data = {};
  try {
    var x = new XMLHttpRequest();
    x.open("GET", "/board/state", false);
    x.send();
    data = JSON.parse(x.responseText || "{}") || {};
  } catch (e) { data = {}; }
  var fromServer = Object.keys(data).length > 0;
  if (fromServer) {
    for (var i = ls.length - 1; i >= 0; i--) { var k = ls.key(i); if (KEYS.test(k) && !(k in data)) ls.removeItem(k); }
    Object.keys(data).forEach(function (k) { try { ls.setItem(k, data[k]); } catch (e) { } });
  }
  var set = Storage.prototype.setItem, rem = Storage.prototype.removeItem, timer = null;
  function snapshot() {
    var o = {};
    for (var i = 0; i < ls.length; i++) { var k = ls.key(i); if (KEYS.test(k)) o[k] = ls.getItem(k); }
    return JSON.stringify(o);
  }
  function send(sync) {
    clearTimeout(timer); timer = null;
    try {
      var x = new XMLHttpRequest();
      x.open("POST", "/board/state", !sync);
      x.setRequestHeader("Content-Type", "application/json");
      x.send(snapshot());
    } catch (e) { }
  }
  function later() { clearTimeout(timer); timer = setTimeout(function () { send(false); }, 500); }
  Storage.prototype.setItem = function (k, v) { set.call(this, k, v); if (this === ls && KEYS.test(k)) later(); };
  Storage.prototype.removeItem = function (k) { rem.call(this, k); if (this === ls && KEYS.test(k)) later(); };
  window.addEventListener("pagehide", function () { if (timer) send(true); });
  if (!fromServer) later(); // 처음: 이 창에 있던 값이 있으면 Portal로 옮긴다

  // 파일 내보내기(백업·CSV·보고 문구)는 Portal을 거쳐 내려받는다. 프로그램 창에서도 저장 창이 뜬다.
  window.addEventListener("DOMContentLoaded", function () {
    if (typeof window.download !== "function") return;
    window.download = function (name, text, mime) {
      var isCsv = /csv/.test(mime || "");
      var x = new XMLHttpRequest();
      x.open("POST", "/board/file", false);
      x.setRequestHeader("Content-Type", "application/json");
      x.send(JSON.stringify({ name: name, text: (isCsv ? "﻿" : "") + text, mime: mime || "application/json" }));
      var id = (JSON.parse(x.responseText || "{}") || {}).id;
      if (!id) return;
      var url = "/board/file/" + id + "?dl=1";
      var top = null;
      try { top = window.top && window.top.auditApp; } catch (e) { top = null; }
      if (top && top.download) top.download(new URL(url, location.href).href);
      else { var a = document.createElement("a"); a.href = url; a.download = name; document.body.appendChild(a); a.click(); a.remove(); }
    };
  });
})();
