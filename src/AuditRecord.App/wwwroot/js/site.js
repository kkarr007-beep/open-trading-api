(function () {
  document.addEventListener("click", function (event) {
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

  document.addEventListener("submit", function (event) {
    var form = event.target;
    if (!(form instanceof HTMLFormElement)) return;
    if (form.dataset.submitted === "1") {
      event.preventDefault();
      return;
    }
    form.dataset.submitted = "1";
    form.setAttribute("aria-busy", "true");
  });
})();
