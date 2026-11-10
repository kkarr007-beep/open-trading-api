// 현황판 그래프. 데이터는 서버가 data-* 특성으로 넣은 JSON만 사용하고, 외부 요청은 하지 않는다.
(function () {
  var holder = document.getElementById("dashboard-data");
  if (!holder || typeof Chart === "undefined") return;

  var data;
  try {
    data = JSON.parse(holder.getAttribute("data-charts") || "{}");
  } catch (e) {
    return;
  }

  var registry = [];

  function cssVar(name, fallback) {
    var value = getComputedStyle(document.documentElement).getPropertyValue(name).trim();
    return value || fallback;
  }

  function palette() {
    return [
      cssVar("--chart-1", "#2563eb"),
      cssVar("--chart-2", "#0ea5a3"),
      cssVar("--chart-3", "#f59e0b"),
      cssVar("--chart-4", "#8b5cf6"),
      cssVar("--chart-5", "#64748b"),
      cssVar("--chart-6", "#dc2626")
    ];
  }

  function baseOptions() {
    var ink = cssVar("--muted", "#5b6572");
    var grid = cssVar("--line", "#e2e8f0");
    Chart.defaults.font.family = getComputedStyle(document.body).fontFamily;
    Chart.defaults.color = ink;
    return { ink: ink, grid: grid };
  }

  function makeBar(id, rows, label) {
    var canvas = document.getElementById(id);
    if (!canvas || !rows || !rows.length) return;
    var theme = baseOptions();
    var colors = palette();
    registry.push(new Chart(canvas, {
      type: "bar",
      data: {
        labels: rows.map(function (r) { return r.label; }),
        datasets: [{
          label: label,
          data: rows.map(function (r) { return r.count; }),
          backgroundColor: colors[0],
          borderRadius: 6,
          maxBarThickness: 44
        }]
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        plugins: { legend: { display: false } },
        scales: {
          x: { grid: { display: false }, ticks: { color: theme.ink } },
          y: { beginAtZero: true, ticks: { precision: 0, color: theme.ink }, grid: { color: theme.grid } }
        }
      }
    }));
  }

  function makeDoughnut(id, rows) {
    var canvas = document.getElementById(id);
    if (!canvas || !rows || !rows.length) return;
    baseOptions();
    var colors = palette();
    registry.push(new Chart(canvas, {
      type: "doughnut",
      data: {
        labels: rows.map(function (r) { return r.label; }),
        datasets: [{
          data: rows.map(function (r) { return r.count; }),
          backgroundColor: rows.map(function (_, i) { return colors[i % colors.length]; }),
          borderWidth: 0
        }]
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        cutout: "62%",
        plugins: {
          legend: { position: "bottom", labels: { boxWidth: 10, boxHeight: 10, usePointStyle: true } }
        }
      }
    }));
  }

  function render() {
    registry.forEach(function (chart) { chart.destroy(); });
    registry = [];
    makeBar("chart-years", data.byYear, "등록 건수");
    makeDoughnut("chart-phase", (data.byPhase || []).filter(function (r) { return r.count > 0; }));
    makeDoughnut("chart-category", (data.byCategory || []).filter(function (r) { return r.count > 0; }));
    makeBar("chart-actions", data.byActionState, "조치요구");
  }

  render();
  document.addEventListener("audit:theme", function () {
    // 토큰 색이 바뀐 다음 프레임에 다시 그린다.
    requestAnimationFrame(render);
  });
})();
