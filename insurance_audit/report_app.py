"""애플 스타일 대화형 편중 대시보드 (단일 HTML).

내부망에서 열어야 하므로 외부 라이브러리를 부르지 않는다. 데이터를 JSON으로
파일 안에 심고, 순수 자바스크립트로 탭·드롭다운·차트를 그린다. 차트는 인라인
SVG/막대라 별도 그래프 패키지가 필요 없다.

핵심 목적을 한 화면에서 통제한다.
- 담당자·차상위자가 특정 손사법인·조사자에 편중 배당하는지 (HHI·Top-N)
- 부서·팀 단위 편중
- 팀·부서 이동 후에도 편중이 유지되는지 (이동 전후 Top1·HHI 비교)
"""

from __future__ import annotations

import json
from datetime import datetime
from pathlib import Path

import pandas as pd

from . import analytics, config


def write(
    path: str | Path,
    data: dict[str, pd.DataFrame],
    raw: pd.DataFrame | None = None,
) -> Path:
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = _payload(data, raw)
    blob = json.dumps(payload, ensure_ascii=False).replace("<", "\\u003c")
    html = _TEMPLATE.replace("__DATA__", blob).replace(
        "__STAMP__", datetime.now().strftime("%Y-%m-%d %H:%M")
    )
    path.write_text(html, encoding="utf-8")
    return path


def _payload(data: dict[str, pd.DataFrame], raw: pd.DataFrame | None = None) -> dict:
    cases = data["cases"]
    tables = analytics.build(data)
    quality = analytics.data_quality(
        raw if raw is not None else data["payments"], data
    )

    handlers = tables["담당자"]
    approvers = tables["결재자"]

    def suspects(frame: pd.DataFrame) -> int:
        if frame.empty:
            return 0
        return int(frame["법인편중"].isin(("고편중", "심각")).sum())

    amount = "지급보험금"
    delegated = cases[cases["법인_id"].notna()] if "법인_id" in cases.columns else cases
    kpi = {
        "처리건수": quality["처리건수"],
        "위임건수": quality["위임건수"],
        "위임율": quality["위임율"],
        "총건수": int(len(delegated)),
        "총금액": int(delegated[amount].sum()) if amount in delegated.columns else 0,
        "법인수": int(cases["법인_id"].nunique()) if "법인_id" in cases.columns else 0,
        "조사자수": int(cases["조사자_id"].nunique()) if "조사자_id" in cases.columns else 0,
        "담당자수": int(cases["담당자_id"].nunique()) if "담당자_id" in cases.columns else 0,
        "차상위자수": int(cases["결재자_id"].nunique()) if "결재자_id" in cases.columns else 0,
        "편중담당자수": suspects(handlers),
        "편중차상위자수": suspects(approvers),
    }

    # 팀 히트맵은 편중이 있는 팀만 미리 계산한다(전 팀을 담으면 파일이 커진다).
    team_matrices = {}
    team_tbl = tables["팀"]
    if not team_tbl.empty:
        focus = team_tbl[team_tbl["법인HHI"] >= config.HHI_HIGH]["조직"].tolist()
        if not focus:
            focus = team_tbl.head(10)["조직"].tolist()
        for org in focus[:20]:
            m = analytics.org_member_matrix(cases, "소속3", org)
            if m:
                team_matrices[str(org)] = m

    return {
        "stamp": datetime.now().strftime("%Y-%m-%d %H:%M"),
        "kpi": kpi,
        "quality": quality,
        "thresholds": {
            "hhi_high": config.HHI_HIGH,
            "hhi_severe": config.HHI_SEVERE,
            "vendor_ratio": config.VENDOR_RATIO_FLAG,
            "investigator": int(config.INVESTIGATOR_SHARE_FLAG * 100),
        },
        "vendor_shares": analytics.to_records(tables["vendor_shares"]),
        "handlers": analytics.to_records(handlers),
        "approvers": analytics.to_records(approvers),
        "dept": analytics.to_records(tables["부서"]),
        "team": analytics.to_records(team_tbl),
        "team_matrices": team_matrices,
        "mobility": {
            "담당자_팀": analytics.to_records(tables["이동_담당자_팀"]),
            "담당자_부서": analytics.to_records(tables["이동_담당자_부서"]),
            "차상위자_팀": analytics.to_records(tables["이동_결재자_팀"]),
            "차상위자_부서": analytics.to_records(tables["이동_결재자_부서"]),
        },
    }


_TEMPLATE = r"""<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>외부조사 편중 대시보드</title>
<style>
:root{
  --bg:#f5f5f7; --panel:#ffffff; --panel2:#fbfbfd; --line:#e5e5ea;
  --ink:#1d1d1f; --sub:#6e6e73; --accent:#0071e3; --accent2:#e8f0fe;
  --ok:#0071e3; --warn:#ff9f0a; --bad:#ff3b30;
  --warnbg:#fff4e0; --badbg:#ffe5e3; --okbg:#e8f0fe;
  --shadow:0 1px 3px rgba(0,0,0,.06),0 6px 20px rgba(0,0,0,.05);
  --radius:18px;
}
@media (prefers-color-scheme:dark){:root{
  --bg:#000000; --panel:#1c1c1e; --panel2:#161618; --line:#2c2c2e;
  --ink:#f5f5f7; --sub:#98989d; --accent:#0a84ff; --accent2:#0a2540;
  --ok:#0a84ff; --warn:#ff9f0a; --bad:#ff453a;
  --warnbg:#3a2a10; --badbg:#3a1512; --okbg:#0a2540;
}}
*{box-sizing:border-box}
html{-webkit-text-size-adjust:100%}
body{margin:0;background:var(--bg);color:var(--ink);
  font-family:-apple-system,BlinkMacSystemFont,"SF Pro Text","Malgun Gothic","Apple SD Gothic Neo",sans-serif;
  font-size:14px;line-height:1.5;letter-spacing:-.01em;word-break:keep-all}
.wrap{max-width:1080px;margin:0 auto;padding:0 20px 80px}
header.top{position:sticky;top:0;z-index:20;background:color-mix(in srgb,var(--bg) 82%,transparent);
  backdrop-filter:saturate(180%) blur(20px);-webkit-backdrop-filter:saturate(180%) blur(20px);
  border-bottom:1px solid var(--line);margin-bottom:24px}
.top .wrap{padding-top:16px;padding-bottom:0}
.eyebrow{color:var(--sub);font-size:12px;margin:0}
h1{font-size:26px;font-weight:700;margin:2px 0 14px;letter-spacing:-.02em}
h2{font-size:20px;font-weight:650;margin:0 0 4px;letter-spacing:-.02em}
h3{font-size:15px;font-weight:650;margin:22px 0 10px}
.muted{color:var(--sub)}
.tiny{font-size:12px}
/* 세그먼트 탭 */
.tabbar{display:flex;gap:10px;align-items:center;padding-bottom:12px}
.tabs{display:flex;gap:4px;overflow-x:auto;scrollbar-width:none;flex:1 1 auto}
.tabs::-webkit-scrollbar{display:none}
.printbtn{flex:0 0 auto;border:1px solid var(--line);background:var(--panel);color:var(--ink);
  font:inherit;font-weight:600;padding:7px 14px;border-radius:980px;cursor:pointer;box-shadow:var(--shadow)}
.printbtn:hover{background:var(--panel2)}
.printhead{display:none}
.tab{flex:0 0 auto;border:none;background:transparent;color:var(--sub);
  font:inherit;font-weight:600;padding:8px 14px;border-radius:980px;cursor:pointer;white-space:nowrap}
.tab.on{background:var(--panel);color:var(--ink);box-shadow:var(--shadow)}
section.page{display:none;animation:fade .3s ease}
section.page.on{display:block}
@keyframes fade{from{opacity:0;transform:translateY(4px)}to{opacity:1;transform:none}}
/* 카드 */
.card{background:var(--panel);border:1px solid var(--line);border-radius:var(--radius);
  padding:20px;box-shadow:var(--shadow);margin-bottom:16px}
.grid{display:grid;gap:12px}
.kpis{grid-template-columns:repeat(auto-fit,minmax(150px,1fr))}
.kpi{background:var(--panel);border:1px solid var(--line);border-radius:14px;padding:16px}
.kpi .n{font-size:26px;font-weight:700;letter-spacing:-.02em;font-variant-numeric:tabular-nums}
.kpi .l{color:var(--sub);font-size:12px;margin-top:2px}
.kpi.flag .n{color:var(--bad)}
/* 배지 */
.badge{display:inline-block;padding:2px 9px;border-radius:980px;font-size:12px;font-weight:650}
.b-보통{background:var(--okbg);color:var(--ok)}
.b-고편중{background:var(--warnbg);color:var(--warn)}
.b-심각{background:var(--badbg);color:var(--bad)}
.b-yes{background:var(--badbg);color:var(--bad)}
.b-no{background:var(--okbg);color:var(--ok)}
/* 표 */
.tbl{width:100%;border-collapse:collapse;font-size:13px}
.tbl th,.tbl td{padding:9px 10px;border-bottom:1px solid var(--line);text-align:left;vertical-align:middle}
.tbl th{color:var(--sub);font-weight:600;font-size:12px;position:sticky;top:0;background:var(--panel);cursor:pointer;user-select:none}
.tbl td.n,.tbl th.n{text-align:right;font-variant-numeric:tabular-nums}
.tbl tbody tr{cursor:pointer}
.tbl tbody tr:hover{background:var(--panel2)}
.scroll{max-height:520px;overflow:auto;border-radius:12px;border:1px solid var(--line)}
/* 막대 */
.bar{position:relative;height:22px;background:var(--panel2);border-radius:6px;overflow:hidden;min-width:120px}
.bar>span{position:absolute;left:0;top:0;bottom:0;background:var(--accent);border-radius:6px}
.bar.warn>span{background:var(--warn)} .bar.bad>span{background:var(--bad)}
.bar em{position:absolute;right:8px;top:0;line-height:22px;font-style:normal;font-size:12px;font-weight:600;font-variant-numeric:tabular-nums}
.barrow{display:grid;grid-template-columns:130px 1fr;gap:10px;align-items:center;margin:6px 0}
.barrow .k{font-size:13px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
/* 선택기 */
.pick{display:flex;gap:10px;flex-wrap:wrap;align-items:center;margin-bottom:14px}
select,.seg{font:inherit;color:var(--ink);background:var(--panel);border:1px solid var(--line);
  border-radius:10px;padding:8px 12px;max-width:100%}
.seg{display:inline-flex;gap:2px;padding:3px}
.seg button{border:none;background:transparent;color:var(--sub);font:inherit;font-weight:600;
  padding:6px 12px;border-radius:8px;cursor:pointer}
.seg button.on{background:var(--accent);color:#fff}
label.chk{display:inline-flex;gap:6px;align-items:center;cursor:pointer;color:var(--sub)}
/* 히트맵 */
.heat{border-collapse:separate;border-spacing:2px;font-size:12px}
.heat th{color:var(--sub);font-weight:600;padding:4px;text-align:center;font-size:11px}
.heat td.name{text-align:left;padding:4px 8px;white-space:nowrap}
.heat td.cell{width:38px;height:26px;text-align:center;border-radius:5px;color:#fff;font-variant-numeric:tabular-nums}
/* 이동 비교 */
.cmp{display:grid;grid-template-columns:1fr 1fr;gap:16px}
@media (max-width:680px){.cmp{grid-template-columns:1fr}}
.arrow{color:var(--sub);font-weight:600}
.legend{display:flex;gap:16px;flex-wrap:wrap;color:var(--sub);font-size:12px;margin:10px 0}
.dot{display:inline-block;width:10px;height:10px;border-radius:3px;margin-right:5px;vertical-align:middle}
footer{color:var(--sub);font-size:12px;border-top:1px solid var(--line);padding-top:16px;margin-top:24px}

/* 인쇄 — 보고서 형태. 지금 보고 있는 탭만 한 부로 낸다. */
@media print{
  @page{size:A4;margin:14mm}
  :root{--bg:#fff;--panel:#fff;--panel2:#fafafa;--line:#c7c7cc;--ink:#000;--sub:#4a4a4f;
    --accent:#0057c8;--ok:#0057c8;--warn:#a85b00;--bad:#c1271c;
    --okbg:#e8f0fe;--warnbg:#fdf0dc;--badbg:#fbe3e1;--shadow:none}
  body{background:#fff;color:#000;font-size:10pt}
  .wrap{max-width:none;padding:0}
  header.top{position:static;background:none;border:none;margin:0;backdrop-filter:none;-webkit-backdrop-filter:none}
  header.top .wrap{padding:0}
  .eyebrow,h1,.tabbar,.pick,.no-print,.printbtn,select,.seg,label.chk{display:none !important}
  /* 인쇄용 표제. 화면에서는 감춰 둔다. */
  .printhead{display:block;border-bottom:2px solid #000;padding-bottom:10px;margin-bottom:16px}
  .printhead .t{font-size:17pt;font-weight:700;margin:0;letter-spacing:-.02em}
  .printhead .s{font-size:10pt;color:#4a4a4f;margin:4px 0 0}
  section.page{display:none !important}
  section.page.on{display:block !important}
  .card{border:1px solid #c7c7cc;border-radius:10px;padding:12px;margin-bottom:12px;
    box-shadow:none;break-inside:avoid}
  .kpi{border:1px solid #c7c7cc;border-radius:10px;padding:10px;break-inside:avoid}
  .kpi .n{font-size:15pt}
  .kpis{grid-template-columns:repeat(4,1fr)}
  /* 화면에서는 표를 상자 안에서 굴리지만 인쇄면에서는 전부 펼쳐야 한다. */
  .scroll{max-height:none;overflow:visible;border:1px solid #c7c7cc}
  .tbl{font-size:9pt}
  .tbl th{position:static;background:#f2f2f7;color:#000}
  .tbl tbody tr{break-inside:avoid}
  thead{display:table-header-group}
  h2{font-size:13pt;break-after:avoid}
  h3{font-size:10.5pt;break-after:avoid}
  details{break-inside:avoid}
  details>summary{list-style:none}
  .bar,.bar>span,.badge,.heat td.cell,.kpi{-webkit-print-color-adjust:exact;print-color-adjust:exact}
  .cmp{grid-template-columns:1fr 1fr}
  footer{border-top:1px solid #c7c7cc;margin-top:14px;font-size:9pt}
}
</style>
</head>
<body>
<header class="top">
  <div class="wrap">
    <p class="eyebrow">보험금 심사 · 외부조사 배당 편중 점검</p>
    <h1>편중 대시보드</h1>
    <div class="tabbar">
      <div class="tabs" id="tabs"></div>
      <button type="button" class="printbtn" id="printbtn" title="지금 보고 있는 탭을 보고서로 인쇄합니다">
        인쇄 / PDF
      </button>
    </div>
  </div>
</header>
<div class="wrap">
  <div class="printhead">
    <p class="t">외부조사 배당 편중 분석 — <span id="ph-tab">요약</span></p>
    <p class="s">보험금 심사 · 산출 __STAMP__ · <span id="ph-kpi"></span></p>
  </div>
  <section class="page on" data-page="summary"></section>
  <section class="page" data-page="org"></section>
  <section class="page" data-page="person"></section>
  <section class="page" data-page="approver"></section>
  <section class="page" data-page="move"></section>
  <footer>
    산출 __STAMP__ · HHI 0.25 이상 고편중, 0.50 이상 심각.
    편중은 소명 우선순위를 정하는 신호이며, 그 자체로 비위를 뜻하지 않습니다.
  </footer>
</div>
<script id="data" type="application/json">__DATA__</script>
<script>
const D = JSON.parse(document.getElementById('data').textContent);
const T = D.thresholds;
const won = n => (n>=1e8? (n/1e8).toFixed(1)+'억' : n>=1e4? Math.round(n/1e4).toLocaleString()+'만' : Math.round(n).toLocaleString());
const num = n => (n==null?'-':Number(n).toLocaleString());
const esc = s => String(s==null?'':s).replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
const grade = g => `<span class="badge b-${g}">${g}</span>`;
function barColor(hhi){return hhi>=T.hhi_severe?'bad':hhi>=T.hhi_high?'warn':'';}
function bar(pct,cls,label){const w=Math.max(0,Math.min(100,pct));
  return `<div class="bar ${cls||''}"><span style="width:${w}%"></span><em>${label!=null?label:w.toFixed(1)+'%'}</em></div>`;}
function breakdownBars(rows,flagShare){
  return (rows||[]).map(r=>{
    const cls = flagShare&&r.비율>=flagShare?'bad':(r.비율>=40?'warn':'');
    return `<div class="barrow"><div class="k" title="${esc(r.명)}">${esc(r.명)}</div>${bar(r.비율,cls,r.비율.toFixed(1)+'% · '+num(r.건수)+'건')}</div>`;
  }).join('');
}

/* ---- 정렬 가능한 표 ---- */
function table(cols, rows, opts){
  opts=opts||{};
  const id='t'+Math.random().toString(36).slice(2,7);
  let sortIdx=opts.sort!=null?opts.sort:null, dir=-1;
  function render(){
    let rr=rows.slice();
    if(sortIdx!=null){const c=cols[sortIdx];
      rr.sort((a,b)=>{let x=c.val(a),y=c.val(b);
        if(typeof x==='number'&&typeof y==='number')return (x-y)*dir;
        return String(x).localeCompare(String(y),'ko')*dir;});}
    const head=cols.map((c,i)=>`<th class="${c.n?'n':''}" data-i="${i}">${esc(c.label)}${sortIdx===i?(dir<0?' ▾':' ▴'):''}</th>`).join('');
    const body=rr.map(r=>`<tr ${opts.click?`data-k="${esc(opts.key(r))}"`:''}>`+cols.map(c=>`<td class="${c.n?'n':''}">${c.cell(r)}</td>`).join('')+'</tr>').join('');
    const el=document.getElementById(id);
    el.innerHTML=`<table class="tbl"><thead><tr>${head}</tr></thead><tbody>${body}</tbody></table>`;
    el.querySelectorAll('th').forEach(th=>th.onclick=()=>{const i=+th.dataset.i;
      if(sortIdx===i)dir=-dir;else{sortIdx=i;dir=-1;}render();});
    if(opts.click)el.querySelectorAll('tbody tr').forEach(tr=>tr.onclick=()=>opts.click(tr.dataset.k));
  }
  setTimeout(render,0);
  return `<div class="scroll" id="${id}"></div>`;
}

/* ---- 화면 1: 요약 ---- */
function pageSummary(){
  const k=D.kpi, q=D.quality;
  const cards=[
    ['처리 건수 (전체)',num(k.처리건수)],
    ['위임 건수 (손사법인 배정)',num(k.위임건수)],
    ['위임율',k.위임율.toFixed(1)+'%'],
    ['위임 지급액',won(k.총금액)+'원'],
    ['손사법인',num(k.법인수)+'곳'],['조사자',num(k.조사자수)+'명'],
    ['담당자',num(k.담당자수)+'명'],['차상위자',num(k.차상위자수)+'명'],
    ['편중 담당자',num(k.편중담당자수)+'명',true],['편중 차상위자',num(k.편중차상위자수)+'명',true],
  ];
  const kpis=cards.map(c=>`<div class="kpi ${c[2]?'flag':''}"><div class="n">${c[1]}</div><div class="l">${c[0]}</div></div>`).join('');

  // 숫자가 예상과 다를 때 먼저 보는 표. 채움률이 낮은 컬럼이 범인이다.
  const qrows=(q.컬럼||[]).map(f=>{
    const low=f.채움률<90;
    return `<tr><td>${esc(f.항목)}</td><td class="n">${f.채움률.toFixed(1)}%${low?' <span class="badge b-고편중">낮음</span>':''}</td><td class="n">${num(f.고유값)}</td></tr>`;
  }).join('');
  const qcard=`<details class="card"><summary style="cursor:pointer;font-weight:650">데이터 읽기 점검 — 숫자가 예상과 다르면 먼저 보세요</summary>
    <p class="muted tiny" style="margin-top:10px">원본 ${num(q.원본행수)}행 · 지급 ${num(q.지급행수)}행 → 처리 ${num(q.처리건수)}건.
      그중 손사법인이 붙어 외부로 나간 <b>위임 ${num(q.위임청구건수)}건</b>, 자체 처리 ${num(q.자체처리건수)}건
      (위임율 ${q.위임율.toFixed(1)}%). 위임 배정은 청구건×손사법인 기준 ${num(q.위임건수)}건입니다.
      편중은 위임 건 안에서만 따집니다.</p>
    <table class="tbl"><thead><tr><th>식별 항목</th><th class="n">채움률</th><th class="n">고유값</th></tr></thead><tbody>${qrows}</tbody></table>
    <p class="muted tiny" style="margin-top:8px">채움률이 낮은 항목은 그 항목으로 묶는 집계가 적게 잡힙니다.
      사번이 비어 있으면 이름으로 대신 묶습니다.</p></details>`;

  const vs=D.vendor_shares.slice(0,15);
  const vbars=vs.map(v=>{
    const cls=v.편중의심?'bad':(v.평균대비배수>=1.5?'warn':'');
    return `<div class="barrow"><div class="k" title="${esc(v.법인_명)}">${esc(v.법인_명)}${v.편중의심?' ⚠':''}</div>${bar(v.건수비율,cls,v.건수비율.toFixed(1)+'% · '+num(v.건수)+'건 · 금액 '+v.금액비율.toFixed(1)+'%')}</div>`;
  }).join('');

  const susp=D.handlers.filter(h=>h.법인편중!=='보통').slice(0,10);
  const cols=[
    {label:'담당자',cell:r=>esc(r.명),val:r=>r.명},
    {label:'부서·팀',cell:r=>esc((r.부서||'')+' · '+(r.팀||'')),val:r=>r.팀||''},
    {label:'처리',n:1,cell:r=>num(r.처리건수),val:r=>r.처리건수},
    {label:'위임',n:1,cell:r=>num(r.총건수)+` <span class="muted tiny">${(r.위임율||0).toFixed(0)}%</span>`,val:r=>r.총건수},
    {label:'법인HHI',n:1,cell:r=>r.법인HHI.toFixed(3)+' '+grade(r.법인편중),val:r=>r.법인HHI},
    {label:'최다 법인',cell:r=>esc(r.최다법인),val:r=>r.최다법인},
    {label:'비율',n:1,cell:r=>r.최다법인비율.toFixed(1)+'%',val:r=>r.최다법인비율},
  ];
  return `<h2>요약</h2><p class="muted tiny">핵심 지표와 편중 상위 담당자입니다.<span class="no-print"> 열 제목을 누르면 정렬됩니다.</span></p>
    <div class="grid kpis" style="margin:16px 0">${kpis}</div>
    ${qcard}
    <div class="card"><h3 style="margin-top:0">손사법인별 배당 비율 (위임 건수 기준 · ⚠ 평균의 ${T.vendor_ratio}배 이상)</h3>${vbars}</div>
    <div class="card"><h3 style="margin-top:0">편중 담당자 Top 10</h3>${table(cols,susp)}</div>`;
}

/* ---- 화면 2: 조직별 ---- */
let orgLevel='팀';
function pageOrg(){
  const rowsFor=lv=>lv==='팀'?D.team:D.dept;
  function draw(){
    const rows=rowsFor(orgLevel);
    const cols=[
      {label:orgLevel,cell:r=>esc(r.조직),val:r=>r.조직},
      {label:'처리',n:1,cell:r=>num(r.처리건수),val:r=>r.처리건수},
      {label:'위임',n:1,cell:r=>num(r.총건수)+` <span class="muted tiny">${(r.위임율||0).toFixed(0)}%</span>`,val:r=>r.총건수},
      {label:'인원',n:1,cell:r=>num(r.인원수),val:r=>r.인원수},
      {label:'법인HHI',n:1,cell:r=>r.법인HHI.toFixed(3)+' '+grade(r.법인편중),val:r=>r.법인HHI},
      {label:'최다 법인',cell:r=>esc(r.최다법인),val:r=>r.최다법인},
      {label:'비율',n:1,cell:r=>bar(r.최다법인비율,barColor(r.법인HHI),r.최다법인비율.toFixed(1)+'%'),val:r=>r.최다법인비율},
    ];
    const canHeat=orgLevel==='팀';
    const tbl=table(cols,rows,canHeat?{key:r=>r.조직,click:showHeat}:{});
    document.getElementById('org-body').innerHTML=tbl+`<div id="heat" class="card" style="display:none"></div>`;
  }
  function showHeat(org){
    const m=D.team_matrices[org];const box=document.getElementById('heat');
    if(!m){box.style.display='none';return;}
    let max=1;m.members.forEach(mm=>mm.건수.forEach(v=>{if(v>max)max=v;}));
    const head='<th></th>'+m.vendors.map(v=>`<th title="${esc(v)}">${esc(v).replace('손해사정','')}</th>`).join('');
    const body=m.members.map(mm=>{
      const cells=mm.건수.map(v=>{const a=v/max;const bg=v===0?'var(--panel2)':`rgba(0,113,227,${(0.15+a*0.85).toFixed(2)})`;
        return `<td class="cell" style="background:${bg};color:${a>0.5?'#fff':'var(--ink)'}">${v||''}</td>`;}).join('');
      return `<tr><td class="name">${esc(mm.명)} <span class="muted tiny">${mm.총건수}건</span></td>${cells}</tr>`;
    }).join('');
    box.style.display='block';
    box.innerHTML=`<h3 style="margin-top:0">${esc(org)} — 팀 내 담당자 × 법인 건수</h3>
      <p class="muted tiny">색이 진할수록 그 담당자가 그 법인에 많이 보냈습니다. 같은 팀인데 한 명만 진하면 개인 편중입니다.</p>
      <div style="overflow:auto"><table class="heat"><thead><tr>${head}</tr></thead><tbody>${body}</tbody></table></div>`;
    box.scrollIntoView({behavior:'smooth',block:'nearest'});
  }
  const seg=`<div class="pick"><div class="seg" id="org-seg">
    <button data-lv="팀" class="${orgLevel==='팀'?'on':''}">팀</button>
    <button data-lv="부서" class="${orgLevel==='부서'?'on':''}">부서</button></div>
    <span class="muted tiny">팀 행을 누르면 팀 내 개인별 히트맵이 열립니다.</span></div>`;
  document.querySelector('section[data-page="org"]').innerHTML=
    `<h2>조직별 편중</h2><p class="muted tiny">부서·팀 단위로 어느 법인에 쏠렸는지 봅니다.</p>${seg}<div id="org-body"></div>`;
  draw();
  document.querySelectorAll('#org-seg button').forEach(b=>b.onclick=()=>{orgLevel=b.dataset.lv;pageOrg();});
}

/* ---- 화면 3·4: 개인 / 차상위자 ---- */
function personCard(r,roleName){
  if(!r)return '<div class="card muted">대상을 선택하세요.</div>';
  const invBlock = r.top조사자? `<h3>조사자 배당 — 최다 ${r.최다조사자비율.toFixed(1)}% ${r.조사자편중의심?'<span class="badge b-yes">조사자 편중 ('+T.investigator+'%↑)</span>':''}</h3>
    <p class="muted tiny">조사자 HHI ${r.조사자HHI.toFixed(3)} ${grade(r.조사자편중)}</p>${breakdownBars(r.top조사자,T.investigator)}`:'';
  return `<div class="card">
    <div style="display:flex;justify-content:space-between;align-items:flex-start;flex-wrap:wrap;gap:10px">
      <div><h2 style="margin:0">${esc(r.명)}</h2>
        <p class="muted" style="margin:2px 0 0">${roleName} · ${esc(r.부서||'')} ${esc(r.팀||'')}</p>
        <p class="muted" style="margin:2px 0 0">처리 ${num(r.처리건수)}건 중 <b>위임 ${num(r.총건수)}건</b>
          (위임율 ${(r.위임율||0).toFixed(1)}%) · 거래법인 ${num(r.거래법인수)}곳</p></div>
      <div style="text-align:right"><div style="font-size:30px;font-weight:700;font-variant-numeric:tabular-nums">${r.법인HHI.toFixed(3)}</div>
        <div>${grade(r.법인편중)} <span class="muted tiny">법인 HHI</span></div></div>
    </div>
    <h3>손사법인 배당 — 최다 ${esc(r.최다법인)} ${r.최다법인비율.toFixed(1)}%</h3>
    ${breakdownBars(r.top법인)}
    ${invBlock}</div>`;
}
function pagerPerson(which){
  const list = which==='approver'?D.approvers:D.handlers;
  const roleName = which==='approver'?'차상위자(팀장·부서장)':'담당자(실무자)';
  const pageEl = document.querySelector(`section[data-page="${which==='approver'?'approver':'person'}"]`);
  if(!list.length){pageEl.innerHTML=`<h2>${which==='approver'?'차상위자별':'개인별'} 상세</h2><p class="muted">표본을 넘는 대상이 없습니다.</p>`;return;}
  const opts=list.map((r,i)=>`<option value="${i}">${esc(r.명)} · HHI ${r.법인HHI.toFixed(2)} · ${r.법인편중} · ${num(r.총건수)}건</option>`).join('');
  pageEl.innerHTML=`<h2>${which==='approver'?'차상위자별':'개인별'} 상세</h2>
    <p class="muted tiny">HHI 높은 순으로 정렬돼 있습니다.<span class="no-print"> 한 명을 고르면 법인·조사자 배당 비율이 나옵니다.</span></p>
    <div class="pick"><select id="sel-${which}">${opts}</select></div>
    <div id="card-${which}"></div>`;
  const sel=document.getElementById('sel-'+which);
  const draw=()=>{document.getElementById('card-'+which).innerHTML=personCard(list[+sel.value],roleName);};
  sel.onchange=draw;draw();
}

/* ---- 화면 5: 이동 추적 ---- */
let moveRole='담당자', moveLevel='팀', moveKeepOnly=false;
function pageMove(){
  function draw(){
    const rows=D.mobility[moveRole+'_'+moveLevel]||[];
    const shown=moveKeepOnly?rows.filter(r=>r.편중유지):rows;
    const kept=rows.filter(r=>r.편중유지).length;
    const cols=[
      {label:'대상',cell:r=>esc(r.명),val:r=>r.명},
      {label:'이동',cell:r=>`${esc(r.이전소속)} <span class="arrow">→</span> ${esc(r.이후소속)}`,val:r=>r.이전소속},
      {label:'이전 Top1',cell:r=>esc(r.이전Top1법인)+` <span class="muted tiny">${r.이전Top1비율.toFixed(0)}%</span>`,val:r=>r.이전Top1법인},
      {label:'이후 Top1',cell:r=>esc(r.이후Top1법인)+` <span class="muted tiny">${r.이후Top1비율.toFixed(0)}%</span>`,val:r=>r.이후Top1법인},
      {label:'HHI 변화',n:1,cell:r=>`${r.이전HHI.toFixed(2)}→${r.이후HHI.toFixed(2)}`,val:r=>r.HHI변화},
      {label:'편중유지',n:1,cell:r=>r.편중유지?'<span class="badge b-yes">유지</span>':'<span class="badge b-no">변경</span>',val:r=>r.편중유지?1:0},
    ];
    document.getElementById('move-body').innerHTML=
      `<p class="muted tiny">전체 ${rows.length}건 중 편중 유지 <b style="color:var(--bad)">${kept}건</b>.<span class="no-print"> 행을 누르면 이동 전후 법인 분포를 비교합니다.</span></p>`
      +table(cols,shown,{key:r=>r.id,click:showCmp})
      +`<div id="cmp" class="card" style="display:none"></div>`;
  }
  function showCmp(id){
    const rows=D.mobility[moveRole+'_'+moveLevel]||[];
    const r=rows.find(x=>x.id===id);const box=document.getElementById('cmp');
    if(!r){box.style.display='none';return;}
    box.style.display='block';
    box.innerHTML=`<h3 style="margin-top:0">${esc(r.명)} · ${esc(r.이전소속)} → ${esc(r.이후소속)} ${r.편중유지?'<span class="badge b-yes">편중 유지</span>':'<span class="badge b-no">편중 변경</span>'}</h3>
      <div class="cmp">
        <div><p class="muted tiny">이동 전 (${esc(r.이전기간)}) · ${num(r.이전건수)}건 · HHI ${r.이전HHI.toFixed(3)}</p>${breakdownBars(r.이전top법인)}</div>
        <div><p class="muted tiny">이동 후 (${esc(r.이후기간)}) · ${num(r.이후건수)}건 · HHI ${r.이후HHI.toFixed(3)}</p>${breakdownBars(r.이후top법인)}</div>
      </div>`;
    box.scrollIntoView({behavior:'smooth',block:'nearest'});
  }
  const seg=`<div class="pick">
    <div class="seg" id="mv-role"><button data-v="담당자" class="${moveRole==='담당자'?'on':''}">담당자</button><button data-v="차상위자" class="${moveRole==='차상위자'?'on':''}">차상위자</button></div>
    <div class="seg" id="mv-lv"><button data-v="팀" class="${moveLevel==='팀'?'on':''}">팀 이동</button><button data-v="부서" class="${moveLevel==='부서'?'on':''}">부서 이동</button></div>
    <label class="chk"><input type="checkbox" id="mv-keep" ${moveKeepOnly?'checked':''}> 편중 유지만</label></div>`;
  document.querySelector('section[data-page="move"]').innerHTML=
    `<h2>소속 이동 후 편중 유지</h2>
     <p class="muted tiny">가장 중요한 분석입니다. 팀·부서를 옮긴 뒤에도 같은 법인에 계속 쏠리면 조직 관행이 아니라 사람을 따라간 편중입니다.</p>
     ${seg}<div id="move-body"></div>`;
  draw();
  document.querySelectorAll('#mv-role button').forEach(b=>b.onclick=()=>{moveRole=b.dataset.v;pageMove();});
  document.querySelectorAll('#mv-lv button').forEach(b=>b.onclick=()=>{moveLevel=b.dataset.v;pageMove();});
  document.getElementById('mv-keep').onchange=e=>{moveKeepOnly=e.target.checked;pageMove();};
}

/* ---- 탭 ---- */
const PAGES=[['summary','요약',pageSummary],['org','조직별',pageOrg],['person','개인별',()=>pagerPerson('person')],
  ['approver','차상위자',()=>pagerPerson('approver')],['move','이동 추적',pageMove]];
const tabsEl=document.getElementById('tabs');
PAGES.forEach(([id,label],i)=>{
  const b=document.createElement('button');b.className='tab'+(i===0?' on':'');b.textContent=label;
  b.onclick=()=>{document.querySelectorAll('.tab').forEach(t=>t.classList.remove('on'));b.classList.add('on');
    document.querySelectorAll('.page').forEach(p=>p.classList.remove('on'));
    const pg=document.querySelector(`section[data-page="${id}"]`);pg.classList.add('on');
    // 인쇄 표제에도 지금 탭 이름이 찍혀야 한 부로 봤을 때 무슨 장인지 안다.
    document.getElementById('ph-tab').textContent=label;};
  tabsEl.appendChild(b);
});
(function(){
  const q=D.quality;
  document.getElementById('ph-kpi').textContent=
    `처리 ${num(q.처리건수)}건 · 위임 ${num(q.위임청구건수)}건 (위임율 ${q.위임율.toFixed(1)}%)`;
  // 인쇄는 지금 보고 있는 탭만 나간다. 접힌 점검 패널은 펼쳐서 함께 낸다.
  const btn=document.getElementById('printbtn');
  btn.onclick=()=>{
    const det=document.querySelector('section.page.on details');
    const wasOpen=det?det.open:null;
    if(det)det.open=true;
    window.print();
    if(det&&wasOpen!==null)setTimeout(()=>{det.open=wasOpen;},0);
  };
})();
// 요약 외 페이지는 selectors가 innerHTML을 통째로 바꾸므로, 탭 전환 시가 아니라 최초에 한 번 그려둔다.
document.querySelector('section[data-page="summary"]').innerHTML=pageSummary();
pageOrg();pagerPerson('person');pagerPerson('approver');pageMove();
</script>
</body>
</html>"""
