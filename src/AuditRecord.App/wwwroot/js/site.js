(function () {
  // 대화상자 열기·닫기, 위치 복사
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
        var original = copy.getAttribute("data-copy-label") || copy.textContent;
        copy.setAttribute("data-copy-label", original);
        copy.textContent = "복사됨";
        setTimeout(function () {
          copy.textContent = copy.getAttribute("data-copy-label");
          copy.removeAttribute("data-copied");
        }, 1600);
      }).catch(function () { });
    }
    var print = event.target.closest("[data-print]");
    if (print) window.print();
  });

  // 저장하지 않은 입력이 있을 때 이동 확인
  var dirty = false;
  document.addEventListener("input", function (event) {
    if (event.target.closest("form[data-track]")) dirty = true;
  });
  document.addEventListener("click", function (event) {
    var link = event.target.closest("a[href]");
    if (!link || !dirty) return;
    if (!window.confirm("저장하지 않은 입력이 있습니다. 이동할까요?")) event.preventDefault();
  });

  // 이중 제출 방지
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

  // 화면 모드 전환. 저장은 이 브라우저의 localStorage에만 한다.
  function currentTheme() {
    var set = document.documentElement.getAttribute("data-theme");
    if (set === "dark" || set === "light") return set;
    return window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
  }
  function syncThemeButtons() {
    var mode = currentTheme();
    document.querySelectorAll("[data-theme-toggle]").forEach(function (button) {
      button.setAttribute("aria-pressed", mode === "dark" ? "true" : "false");
      var label = button.querySelector(".theme-label");
      if (label) label.textContent = mode === "dark" ? "밝게" : "어둡게";
    });
  }
  document.addEventListener("click", function (event) {
    var toggle = event.target.closest("[data-theme-toggle]");
    if (!toggle) return;
    var next = currentTheme() === "dark" ? "light" : "dark";
    document.documentElement.setAttribute("data-theme", next);
    try { localStorage.setItem("audit-theme", next); } catch (e) { }
    syncThemeButtons();
    document.dispatchEvent(new CustomEvent("audit:theme", { detail: { theme: next } }));
  });
  syncThemeButtons();

  // 검색 단축키. 입력 중에는 가로채지 않는다(WCAG 2.1.4).
  document.addEventListener("keydown", function (event) {
    if (event.key !== "/" || event.ctrlKey || event.metaKey || event.altKey) return;
    var active = document.activeElement;
    if (active && (active.tagName === "INPUT" || active.tagName === "TEXTAREA" || active.tagName === "SELECT" || active.isContentEditable)) return;
    var search = document.querySelector("[data-search-focus]");
    if (!search) return;
    event.preventDefault();
    search.focus();
    if (typeof search.select === "function") search.select();
  });

  // 알림 띠 자동 닫기(저장 완료 같은 확인성 알림만)
  document.querySelectorAll(".banner.info[role='status']").forEach(function (banner) {
    banner.classList.add("toast-like");
    setTimeout(function () { banner.classList.add("fading"); }, 6000);
    setTimeout(function () { banner.classList.add("hidden-out"); }, 6800);
  });

  // 보존연한 빠른 선택
  document.addEventListener("click", function (event) {
    var preset = event.target.closest("[data-retention-preset]");
    if (!preset) return;
    var scope = preset.closest("form") || document;
    var input = scope.querySelector("[data-retention-input]");
    if (!input) return;
    input.value = preset.getAttribute("data-retention-preset");
    input.dispatchEvent(new Event("input", { bubbles: true }));
    input.focus();
  });
})();
