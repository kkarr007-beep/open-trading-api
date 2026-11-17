// 사규 등록 칸에 붙여 넣은 HTML(워드·한글·엑셀)을 사규 글 꼴로 바꾼다. 표 안의 표, 합친 칸, 들여쓰기, 굵게·밑줄을 살린다.
// 예전 '사규 관리 시스템 v4'의 변환 규칙을 그대로 옮겼다.
(function () {
'use strict';
function uid(p){return (p||'id')+'_'+Date.now().toString(36)+Math.random().toString(36).slice(2,7)}
function tblRow(cells){return '| '+cells.map(c=>String(c==null?'':c).replace(/\s+/g,' ').replace(/\|/g,'/').trim()).join(' | ')+' |'}
function isTblRow(t){return /^\|.*\|$/.test(String(t||'').trim())&&String(t).split('|').length>=4}
/* 칸 기호: < 왼쪽과 합침, ^ 위와 합침, ⏎ 칸 안 줄바꿈 */
const TBL_L='<', TBL_U='^', TBL_BR='\u23ce';
/* 한 칸의 글 — 칸 안의 표는 ' / ' 와 ' ; ' 로 접어 넣는다 */
function tblCellText(td){
 const cl=td.cloneNode(true), subs=[];
 Array.prototype.forEach.call(cl.querySelectorAll('table'),t=>{
  if(!cl.contains(t))return;
  const rs=[];
  for(let r=0;r<t.rows.length;r++){const cs=[];
   for(let k=0;k<t.rows[r].cells.length;k++)cs.push(mdInline(t.rows[r].cells[k]).replace(/\s+/g,' ').trim());
   const ln=cs.filter(Boolean).join(' / ');if(ln)rs.push(ln)}
  if(rs.length)subs.push(rs.join(' ; '));
  t.remove()});
 let t=mdInline(cl).replace(/\u00a0/g,' ').replace(/[ \t]+/g,' ');
 t=t.split('\n').map(x=>x.trim()).filter(Boolean).join(TBL_BR);
 if(subs.length)t=(t?t+TBL_BR:'')+subs.join(TBL_BR);
 return t.replace(/\|/g,'/').trim()}
/* 표를 칸 자리까지 맞춘 격자로 편다 */
function tblGrid(tb){
 const g=[],set=(r,c,v)=>{(g[r]=g[r]||[])[c]=v},has=(r,c)=>g[r]&&g[r][c]!==undefined;
 const R=tb.rows.length;
 for(let r=0;r<R;r++){let c=0;const cells=tb.rows[r].cells;
  for(let k=0;k<cells.length;k++){
   while(has(r,c))c++;
   const cell=cells[k];
   const cs=Math.max(1,Math.min(30,cell.colSpan||1)), rs=Math.max(1,Math.min(60,cell.rowSpan||1));
   set(r,c,tblCellText(cell));
   for(let j=1;j<cs;j++)set(r,c+j,TBL_L);
   for(let i=1;i<rs&&r+i<R;i++){set(r+i,c,TBL_U);for(let j=1;j<cs;j++)set(r+i,c+j,TBL_L)}
   c+=cs}}
 const w=g.reduce((m,x)=>Math.max(m,x?x.length:0),0);
 return g.slice(0,R).map(row=>{const o=[];for(let i=0;i<w;i++)o.push((row&&row[i]!==undefined)?row[i]:'');return o})}
function tblCells(t){return String(t||'').trim().replace(/^\|/,'').replace(/\|$/,'').split('|').map(x=>x.trim())}
/* 탭으로 나뉜 줄(엑셀·한글에서 복사)도 표로 본다 */
function tsvToTbl(t){return String(t||'').split('\n').map(l=>{
 if(l.indexOf('\t')<0)return l;const cs=l.split('\t').map(x=>x.trim());
 return cs.filter(x=>x!=='').length>=2? tblRow(cs) : cs.join(' ')}).join('\n')}
/* ===== Word·한글 서식 → 글자 규칙 변환 =====
   들여쓰기는 앞 공백(한 단계 2칸), 가운데=>>, 오른쪽=>>>,
   굵게=**..**, 밑줄=__..__, 취소선=~~..~~ 로 옮겨 담는다 */
const MDB=/^(p|div|li|h[1-6]|blockquote|dt|dd|pre|tr|section|article|address|center)$/;
const MDSKIP=/^(style|script|xml|head|title|meta|link|base|select|option|noscript|o:p)$/;
function cssPx(v){
 const m=/^(-?[\d.]+)\s*(pt|px|cm|mm|in|em|rem|ex|ch)?$/.exec(String(v||'').trim());
 if(!m)return 0;
 const n=parseFloat(m[1])||0;
 const k={pt:1.3333,px:1,cm:37.8,mm:3.78,in:96,em:16,rem:16,ex:8,ch:8}[m[2]||'px'];
 return k?n*k:0}
function mdIndent(el){
 let px=0;
 for(let p=el;p&&p.nodeType===1;p=p.parentElement){
  const st=p.style||{};
  px+=cssPx(st.marginLeft)+cssPx(st.paddingLeft);
  if(p===el)px+=Math.max(0,cssPx(st.textIndent));
  const t=(p.tagName||'').toLowerCase();
  if(t==='blockquote'||t==='ul'||t==='ol'||t==='dd')px+=26;
  if(t==='body'||t==='html')break}
 return Math.max(0,Math.min(8,Math.round(px/26)))}
function mdAlign(el){
 for(let p=el;p&&p.nodeType===1;p=p.parentElement){
  const a=String((p.style&&p.style.textAlign)||'').toLowerCase();
  if(a==='center')return '>> ';
  if(a==='right')return '>>> ';
  if(a)return '';
  if((p.tagName||'').toLowerCase()==='center')return '>> ';
  if(p.parentElement===null)break}
 return ''}
function mdInline(el){
 let out='';
 Array.prototype.forEach.call(el.childNodes,n=>{
  if(n.nodeType===3){out+=n.nodeValue;return}
  if(n.nodeType!==1)return;
  const t=n.tagName.toLowerCase();
  if(MDSKIP.test(t))return;
  if(t==='br'){out+='\n';return}
  if(t==='img'){const a=n.getAttribute('alt')||'';out+=a?'[그림 '+a+']':'[그림]';return}
  const st=n.style||{};
  const fw=String(st.fontWeight||'');
  const td=String(st.textDecoration||st.textDecorationLine||'');
  const bold=/^(b|strong|th)$/.test(t)||/bold/i.test(fw)||(parseInt(fw,10)>=600);
  const und=t==='u'||/underline/i.test(td);
  const del=/^(s|strike|del)$/.test(t)||/line-through/i.test(td);
  let inner=mdInline(n);
  const m=/^(\s*)([\s\S]*?)(\s*)$/.exec(inner);
  if(m&&m[2]){let c=m[2];
   if(del&&c.indexOf('~~')<0)c='~~'+c+'~~';
   if(und&&c.indexOf('__')<0)c='__'+c+'__';
   if(bold&&c.indexOf('**')<0)c='**'+c+'**';
   inner=m[1]+c+m[3]}
  out+=inner});
 return out}
function mdHasBlock(el){
 return Array.prototype.some.call(el.children||[],c=>MDB.test(c.tagName.toLowerCase())||mdHasBlock(c))}
function mdLines(root){
 const out=[];
 const emit=(el,mark)=>{
  let txt=mdInline(el).replace(/\u00a0/g,' ');
  txt=txt.split('\n').map(l=>l.replace(/[ \t]+/g,' ').trim()).join('\n');
  if(!txt.trim()){out.push('');return}
  /* 문단 전체가 굵으면 표시를 떼고(제목 줄이 대부분이다), */
  const whole=/^\*\*([^*][\s\S]*?)\*\*$/.exec(txt.trim());
  if(whole&&whole[1].indexOf('**')<0)txt=whole[1];
  /* 조·장·부칙·별첨 머리글에 걸린 표시도 뗀다 — 구조 분석을 막는다 */
  txt=txt.replace(/^([ \t]*)(?:\*\*|__)+[ \t]*((?:제[ \t]*\d+[ \t]*(?:조|장|절|편|관)|부[ \t]*칙|별[ \t]*[첨표지]|붙[ \t]*임|서[ \t]*식)[^*_\n]{0,70}?)(?:\*\*|__)+/,'$1$2');
  const pad='  '.repeat(mdIndent(el)), al=mdAlign(el);
  txt.split('\n').forEach((l,i)=>out.push(l.trim()?pad+(i?'':al)+(i?'':(mark||''))+l.trim():''))};
 const walk=el=>{
  if(!mdHasBlock(el)){emit(el);return}
  let oi=0;
  Array.prototype.forEach.call(Array.prototype.slice.call(el.childNodes),n=>{
   if(n.nodeType===3){const z=n.nodeValue.replace(/[ \t]+/g,' ');
    if(z.trim())z.split('\n').forEach(x=>{const q=x.trim();if(q)out.push(q)});
    return}
   if(n.nodeType!==1)return;
   const t=n.tagName.toLowerCase();
   if(MDSKIP.test(t))return;
   if(t==='br'){out.push('');return}
   if(t==='li'){
    const p=(el.tagName||'').toLowerCase();
    let mk='';
    if(p==='ol'){oi++;mk=oi+'. '}
    else if(p==='ul')mk='· ';
    if(mdHasBlock(n))walk(n);else emit(n,mk);
    return}
   if(MDB.test(t)||t==='ul'||t==='ol'||t==='table'){
    if(t==='ul'||t==='ol'){walk(n);out.push('');return}
    if(mdHasBlock(n))walk(n);else emit(n);
    return}
   emit(n)})};
 walk(root);
 return out.join('\n')}
/* Word·한글에서 붙여넣으면 딸려오는 스타일·주석 덩어리를 걷어낸다 */
const CSSLINE=/^\s*(?:[.#@*a-zA-Z][\w\-.#:,>\[\]="'()\s]*\{[^{}]*\}|[\w-]+\s*:[^;{}]*;|\}|\{|<!\[endif\]|<!\[if[^\]]*\]|\/\*.*\*\/)\s*$/;
const CSSHINT=/(mso-|font-family\s*:|text-indent\s*:|margin-(top|bottom|left|right)\s*:|line-height\s*:|page:\s*WordSection|@(page|font-face|media)|\bcharset\b)/i;
function dropStyleJunk(t){
 const out=[];let open=0;
 String(t||'').split('\n').forEach(l=>{
  const z=l.trim();
  open+=(z.match(/\{/g)||[]).length-(z.match(/\}/g)||[]).length;
  if(open>0&&CSSHINT.test(z)){return}
  if(open<0)open=0;
  if(!z){out.push('');return}
  if(CSSLINE.test(z)&&CSSHINT.test(z))return;
  if(/^<!--/.test(z)||/^-->$/.test(z))return;
  if(CSSHINT.test(z)&&z.indexOf('{')>=0&&z.indexOf('}')>=0)return;
  out.push(l)});
 return out.join('\n')}
function htmlToText(h){if(h==null)return'';h=String(h);if(!/[<&]/.test(h))return h;
 h=h.replace(/<!--[\s\S]*?-->/g,'')
    .replace(/<\?[\s\S]*?\?>/g,'')
    .replace(/<(style|script|xml|head|title|select|option)\b[\s\S]*?<\/\1\s*>/gi,'')
    .replace(/<(meta|link|base)\b[^>]*>/gi,'');
 const isHtml=/<[a-z!\/]/i.test(h);
 const d=document.createElement('div');
 d.innerHTML=isHtml? h : h.replace(/<br\s*\/?>/gi,'\n').replace(/<\/(p|div|li|h\d)>/gi,'\n');
 try{Array.prototype.forEach.call(d.querySelectorAll('style,script,xml,head,title,meta,link,base,select,option,noscript'),x=>x.remove())}catch(e){}
 Array.prototype.forEach.call(d.querySelectorAll('table'),tb=>{
  if(!d.contains(tb))return;                                   /* 바깥 표와 함께 이미 빠진 것 */
  if(tb.parentElement&&tb.parentElement.closest('table'))return; /* 칸 안의 표는 칸에서 처리 */
  let rows=[];
  try{rows=tblGrid(tb).map(r=>r.length>=2?tblRow(r):(r[0]||''))}catch(e){rows=[]}
  rows=rows.filter(x=>String(x).replace(/[|<^\s]/g,'')!==''||/\|/.test(x));
  tb.replaceWith(document.createTextNode('\n'+rows.join('\n')+'\n'))});
 let t=isHtml? mdLines(d) : (d.textContent||'');
 t=t.replace(/[\u200b-\u200f\u2028\u2029\ufeff]/g,'').replace(/\u00a0/g,' ');
 t=dropStyleJunk(t);
 return t.replace(/[ \t]+\n/g,'\n').replace(/\n{3,}/g,'\n\n').replace(/\s+$/,'')}

window.sagyuPaste = { htmlToText: htmlToText, tsvToTbl: tsvToTbl, dropStyleJunk: dropStyleJunk, CSSHINT: CSSHINT };
})();
