(function () {
  try {
    var choice = localStorage.getItem("audit-theme") || "system";
    var dark = choice === "dark" || (choice !== "light" && window.matchMedia("(prefers-color-scheme: dark)").matches);
    document.documentElement.setAttribute("data-theme", dark ? "dark" : "light");
    document.documentElement.setAttribute("data-theme-choice", choice);
  } catch (e) {
    document.documentElement.setAttribute("data-theme", "light");
  }
})();
