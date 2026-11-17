// 업무 일정·사규 화면에서 쓰는 작은 동작. 이 파일이 돌지 않아도 단추와 양식만으로 모두 쓸 수 있다.
(function () {
  // 지우기처럼 되돌릴 수 없는 단추는 한 번 더 묻는다
  document.addEventListener("submit", function (event) {
    var form = event.target;
    if (!(form instanceof HTMLFormElement) || !form.hasAttribute("data-confirm")) return;
    if (!window.confirm(form.getAttribute("data-confirm"))) {
      event.preventDefault();
      event.stopImmediatePropagation();
    }
  }, true);

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
