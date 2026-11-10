// 화면 모드(밝게·어둡게)를 그리기 전에 적용한다. localStorage에만 저장하며 서버로 보내지 않는다.
(function () {
  var saved = null;
  try { saved = localStorage.getItem("audit-theme"); } catch (e) { }
  if (saved === "dark" || saved === "light") {
    document.documentElement.setAttribute("data-theme", saved);
  }
})();
