const $ = selector => document.querySelector(selector);
const esc = value => String(value ?? "—").replace(/[&<>"']/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
const state = {model:null,snapshot:null,domain:"all",view:"map",selected:"Demand",search:"",session:null,busy:false,error:""};
const statusLabels = {observed_demo:"已观测演示实例",adapter_no_records:"已有适配 · 当前无记录",not_integrated:"待接入",demo_adapter:"已有演示适配",proposed:"待接入"};
const headings = {
  map:["01 / CONNECTED SUPPLY CHAIN","从业务环节，到可追溯的对象"],
  objects:["02 / BUSINESS OBJECT DICTIONARY","稳定身份、清晰粒度、明确责任"],
  mapping:["03 / SOURCE FIELD ALIGNMENT","逐字段核对来源、类型与单位"],
  rules:["04 / GUARDED BUSINESS DECISIONS","规则有依据，动作有边界"],
  gaps:["05 / FOUNDATION READINESS","定义完整度与业务接入分别验收"],
};
const svgNS="http://www.w3.org/2000/svg";
const platformMode=new URLSearchParams(location.search).get('platform')==='bemodel';
if(platformMode){document.body.classList.add('platform-embed');$('#connect').textContent='查看业务实例 ↗';}
const fmt=value=>Number.isSafeInteger(value)?value.toLocaleString("zh-CN"):"—";
function coverage(e){return state.snapshot?.coverage.find(c=>c.entity===e.code);}
function badge(e){const s=coverage(e)?.status||e.availability;return `<span class="badge"><i class="dot ${s==="observed_demo"?"observed":s==="demo_adapter"||s==="adapter_no_records"?"adapter":"proposed"}"></i>${esc(statusLabels[s]||s)}</span>`;}
function matches(e){return (state.domain==="all"||e.domain===state.domain)&&(!state.search||`${e.name} ${e.code} ${e.definition} ${e.attributes.map(a=>a.name+" "+a.code).join(" ")}`.toLowerCase().includes(state.search));}
function filtered(){return state.model.entities.filter(matches);}
function byCode(code){return state.model.entities.find(e=>e.code===code);}
function choose(code){state.selected=code;state.domain="all";state.search="";$("#search").value="";state.view="map";render();}
function download(name,data){const url=URL.createObjectURL(new Blob([JSON.stringify(data,null,2)],{type:"application/json"}));const a=document.createElement("a");a.href=url;a.download=name;a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);}
function renderNav(){
  $("#domains").innerHTML=[{id:"all",name:"供应链全景"},...state.model.domains].map((d,i)=>`<button data-domain="${esc(d.id)}" aria-current="${state.domain===d.id}"><span>${String(i).padStart(2,"0")}</span>${esc(d.name)}<em>${d.id==="all"?state.model.entities.length:state.model.entities.filter(e=>e.domain===d.id).length}</em></button>`).join("");
  $("#domains").querySelectorAll("button").forEach(b=>b.onclick=()=>{state.domain=b.dataset.domain;render();});
}
function renderStats(){const m=state.model;$("#model-stats").innerHTML=[[m.domains.length,"供应链业务域","DOMAINS"],[m.entities.length,"业务对象","OBJECT TYPES"],[m.entities.reduce((n,e)=>n+e.attributes.length,0),"属性定义","ATTRIBUTES"],[m.relations.length,"关系定义","RELATIONS"],[m.rules.length,"管理规则","CONSTRAINTS"]].map(([v,k,en])=>`<div class="stat"><span class="stat-label">${k}</span><span class="stat-value">${v}</span><small>${en}</small></div>`).join("");
  const snap=state.snapshot;
  $("#business-metrics").innerHTML=[["current_usable_kg","合格可用 kg"],["shortfall_kg","实际缺口 kg"],["protected_kg","其他订单保护 kg"]].map(([k,n])=>`<div><strong>${fmt(snap?.summary[k])}</strong><span>${n}</span></div>`).join("");
  $("#connection-state").textContent=snap?`${snap.scope.id} · ${snap.actor.role} · 业务修订 ${snap.revision}`:"未连接业务数据";
  $("#snapshot-scope").textContent=snap?`虚构演示 · ${snap.scope.clock} · run ${snap.scope.run_id.slice(0,8)}`:state.error||"模型定义不产生库存、订单或财务事实";
  $("#connect").textContent=platformMode?"查看业务实例 ↗":snap?"切换业务身份 ↗":"连接业务快照 ↗";
  $("#refresh").disabled=!state.session||state.busy;
  $("#export-snapshot").disabled=!snap;
}
function renderGraph(){
  const m=state.model,selected=byCode(state.selected),positions=new Map(),parts=[];
  m.domains.forEach((d,index)=>{
    const x=16+(index%5)*313,y=18+Math.floor(index/5)*383,items=m.entities.filter(e=>e.domain===d.id);
    parts.push(`<g opacity="${state.domain==="all"||state.domain===d.id?1:.22}"><rect class="graph-domain" x="${x}" y="${y}" width="295" height="361"/><text class="graph-caption" x="${x+16}" y="${y+23}">${String(index+1).padStart(2,"0")} / ${esc(d.systems)}</text><text class="graph-title" x="${x+16}" y="${y+47}">${esc(d.name)}</text><line x1="${x+16}" y1="${y+62}" x2="${x+279}" y2="${y+62}" stroke="#cecec5"/></g>`);
    items.forEach((e,i)=>{
      const node={x:x+16,y:y+77+i*39};positions.set(e.code,node);
    });
  });
  const active=m.relations.filter(r=>r.from_entity===selected.code||r.to_entity===selected.code);
  m.relations.forEach(r=>{
    const a=positions.get(r.from_entity),b=positions.get(r.to_entity),highlight=active.includes(r);
    if(!a||!b||state.search&&!matches(byCode(r.from_entity))&&!matches(byCode(r.to_entity)))return;
    const same=Math.abs(a.x-b.x)<5;
    const x1=same?a.x+264:a.x+(b.x>a.x?264:0),x2=same?b.x+264:b.x+(b.x>a.x?0:264),y1=a.y+16,y2=b.y+16;
    const bend=same?x1+15:(x1+x2)/2;
    parts.push(`<path class="graph-edge${highlight?" active":""}" d="M${x1},${y1} C${bend},${y1} ${bend},${y2} ${x2},${y2}"><title>${esc(r.from_entity)} → ${esc(r.to_entity)} · ${esc(r.name)} (${r.from_cardinality} → ${r.to_cardinality})</title></path>`);
  });
  m.entities.forEach(e=>{
    const p=positions.get(e.code),s=coverage(e)?.status,color=s==="observed_demo"?"#41745b":e.source_collection?"#d65b35":"#a7a89c";
    parts.push(`<g class="graph-node${state.selected===e.code?" selected":""}" data-code="${esc(e.code)}" role="button" tabindex="0" aria-label="${esc(e.name)}，${esc(statusLabels[s||e.availability])}" opacity="${matches(e)?1:.18}"><rect x="${p.x}" y="${p.y}" width="264" height="32" rx="2"/><circle cx="${p.x+12}" cy="${p.y+16}" r="3" fill="${color}"/><text x="${p.x+23}" y="${p.y+14}">${esc(e.name)}</text><text class="code" x="${p.x+23}" y="${p.y+25}">${esc(e.code)}</text><text class="code" text-anchor="end" x="${p.x+252}" y="${p.y+20}">${coverage(e)?.instance_count??"—"}</text></g>`);
  });
  $("#graph").innerHTML=parts.join("");
  $("#graph").querySelectorAll("[data-code]").forEach(node=>{const select=()=>{state.selected=node.dataset.code;renderGraph();renderInspector();};node.onclick=select;node.onkeydown=e=>{if(e.key==="Enter"||e.key===" "){e.preventDefault();select();}};});
}
function renderInspector(){
  const e=byCode(state.selected),c=coverage(e),instances=state.snapshot?.instances.filter(i=>i.entity===e.code)||[];
  const rel=state.model.relations.filter(r=>r.from_entity===e.code||r.to_entity===e.code);
  $("#inspector").innerHTML=`<span class="eyebrow">OBJECT INSPECTOR</span><h3>${esc(e.name)}</h3><span class="object-code">${esc(e.code)}</span><p>${esc(e.definition)}</p>${badge(e)}<div class="mini-meta"><div><span>业务责任</span>${esc(e.owner)}</div><div><span>属性 / 关联</span>${e.attributes.length} / ${rel.length}</div><div><span>当前实例</span>${state.snapshot?instances.length:"未连接"}</div><div><span>来源</span>${esc(e.source_collection||"待源系统接入")}</div></div><p><b>稳定身份</b><br>${esc(e.identity)}</p><h4>属性定义 / ${e.attributes.length}</h4>${e.attributes.map(a=>`<div class="field-chip">${esc(a.name)} <code>${esc(a.code)}</code><span>${esc(a.type)}${a.unit?" · "+esc(a.unit):""} · ${esc(a.definition)}</span></div>`).join("")}<h4>关系 / ${rel.length}</h4>${rel.map(r=>{const other=r.from_entity===e.code?r.to_entity:r.from_entity;return `<button class="relation-link" data-object="${esc(other)}">${r.from_entity===e.code?"→":"←"} ${esc(byCode(other).name)}<small>${esc(r.name)} · ${esc(r.from_cardinality)} → ${esc(r.to_cardinality)}</small></button>`;}).join("")}<h4>当前实例 / ${instances.length}</h4>${instances.length?instances.slice(0,5).map(i=>`<details><summary>${esc(i.business_id)}</summary><pre class="instance-data">${esc(JSON.stringify(i.fields,null,2))}</pre></details>`).join(""):"<p>当前没有可展示的源记录。未观测不等于不存在。</p>"}`;
  bindObjectButtons($("#inspector"));
}
function bindObjectButtons(root){root.querySelectorAll("[data-object]").forEach(b=>b.onclick=()=>choose(b.dataset.object));}
function renderObjects(){
  $("#objects-body").innerHTML=filtered().map(e=>`<tr><td><button class="object-button" data-object="${esc(e.code)}">${esc(e.name)} ↗</button><small>${esc(e.code)}</small></td><td>${esc(e.definition)}<small>${esc(e.identity)}</small></td><td>${esc(e.owner)}</td><td>${e.attributes.length}</td><td>${badge(e)}<small>${esc(e.source_collection||e.source_system)}</small></td></tr>`).join("")||'<tr><td colspan="5">未找到匹配对象</td></tr>';
  bindObjectButtons($("#objects-body"));
}
function renderMappings(){
  $("#mapping-body").innerHTML=filtered().flatMap(e=>e.attributes.map(a=>{
    const instances=state.snapshot?.instances.filter(i=>i.entity===e.code)||[],present=instances.filter(i=>i.field_states[a.code]==='known').length;
    return `<tr><td><button class="object-button" data-object="${esc(e.code)}">${esc(e.name)}</button> · ${esc(a.name)}<small>${esc(e.code)}.${esc(a.code)}</small></td><td>${esc(a.type)}<small>${esc(a.unit||"不适用")}</small></td><td>${esc(e.source_collection||e.source_system)}<small>${esc(a.source_field||"待取得源字段字典")}</small></td><td>${a.source_field?"演示适配器已定义":"目标字段待接入"}<small>业务owner签认待完成</small></td><td>${state.snapshot?(instances.length?`${present} / ${instances.length} 条有值`:e.source_collection?"无当前记录":"尚未接入"):"未连接"}</td></tr>`;
  })).join("")||'<tr><td colspan="5">未找到匹配字段</td></tr>';
  bindObjectButtons($("#mapping-body"));
}
function renderRules(){
  const match=r=>(state.domain==="all"||r.domain===state.domain)&&(!state.search||JSON.stringify(r).toLowerCase().includes(state.search));
  $("#rules-list").innerHTML=state.model.rules.filter(match).map(r=>`<article class="rule-card"><span class="eyebrow">${esc(r.id)} / ${r.source_code?"现有演示业务守卫":"目标约束 · 待实现"}</span><h3>${esc(r.name)}</h3><p>${esc(r.expression)}</p><span class="eyebrow">${esc(r.entities.join(" · "))}</span></article>`).join("")||'<p>当前筛选无规则</p>';
  $("#actions-list").innerHTML=state.model.actions.filter(a=>(state.domain==="all"||a.entities.some(c=>byCode(c).domain===state.domain))&&(!state.search||JSON.stringify(a).toLowerCase().includes(state.search))).map(a=>`<article class="action-card"><strong>${esc(a.id)} · ${esc(a.name)}</strong><p>${esc(a.effect)}</p><small>授权角色：${esc(a.roles.join(" / "))}<br>前置条件：${esc(a.precondition)}<br>失败处理：${esc(a.failure)}</small><p>${a.endpoint?"原业务工作台受控执行":"尚无执行器"} · 本页执行关闭</p></article>`).join("");
  $("#actions-list").insertAdjacentHTML('beforeend','<h3>指标口径 / '+state.model.metrics.length+'</h3>'+state.model.metrics.filter(metric=>matches(byCode(metric.entity))).map(metric=>`<article class="action-card"><strong>${esc(metric.id)} · ${esc(metric.name)}</strong><p>${esc(metric.formula)}</p><small>单位：${esc(metric.unit)}<br>${esc(metric.data_status)}<br>分母为零：${esc(metric.zero_denominator)}</small></article>`).join(''));
}
function renderGaps(){
  const m=state.model,s=state.snapshot,notIntegrated=m.entities.filter(e=>!e.source_collection),adapters=m.entities.length-notIntegrated.length;
  $("#validation-summary").textContent=`模型定义：${m.entities.length} 类对象，${m.relations.length} 类关系。已有 ${adapters} 类演示适配定义，${notIntegrated.length} 类待源接入。${s?`当前观测到 ${s.coverage.filter(c=>c.instance_count>0).length} 类、${s.instances.length} 条实例。`:"尚未连接业务快照，实例质量检查未运行。"} 生产数据、业务语义和正式发布审批仍待验收。`;
  $("#questions-list").innerHTML=m.questions.filter(q=>(state.domain==="all"||q.domain===state.domain)&&(!state.search||JSON.stringify(q).toLowerCase().includes(state.search))).map(q=>`<article class="question-card"><span class="eyebrow">${esc(q.id)} / 业务验收待完成</span><h3>${esc(q.question)}</h3><p>${esc(q.data_status)}</p><div class="entities">对象：${esc(q.entities.join(" → "))}<br>约束：${esc(q.rules.join(" / "))}</div></article>`).join("");
  const checks=[{id:"MODEL",name:"类型、关系、问题与规则引用",status:s?(s.validation.structure.length?"检查失败":"结构检查通过"):"模型定义已生成；实时检查未运行"},{id:"FIELDS",name:"源字段类型与单位",status:s?(s.validation.field_issues.length?`${s.validation.field_issues.length} 项类型差异`:"当前已观测字段类型检查通过"):"未运行"},...(s?.validation.checks||[]).map(c=>({...c,status:c.status==="pass"?"通过":"检查失败"})),{id:"SCOPE",name:"真实源与权限验收",status:"待真实ERP/WMS/QMS/MES等源字典、样本及授权"},{id:"RELEASE",name:"业务owner与发布审批",status:"待确认 · 模型仍为草稿"}];
  $("#checks-list").innerHTML=checks.map(c=>`<div class="check"><span class="check-code">${esc(c.id)}</span><div>${esc(c.name)}<small>${esc(c.status)}</small></div></div>`).join("");
  $("#gaps-list").innerHTML=`<div class="gap-chips">${notIntegrated.filter(matches).map(e=>`<button data-object="${esc(e.code)}">${esc(e.name)} ↗</button>`).join("")}</div>`;
  bindObjectButtons($("#gaps-list"));
}
function render(){
  if(!state.model)return;renderNav();renderStats();
  document.querySelectorAll("[data-view]").forEach(b=>b.setAttribute("aria-selected",String(b.dataset.view===state.view)));
  document.querySelectorAll("section.view").forEach(s=>s.hidden=s.id!==`${state.view}-view`);
  $("#section-kicker").textContent=headings[state.view][0];$("#section-title").textContent=headings[state.view][1];
  ({map:()=>{renderGraph();renderInspector();},objects:renderObjects,mapping:renderMappings,rules:renderRules,gaps:renderGaps})[state.view]();
  $("#model-release").textContent=`${state.model.release_id} / DRAFT`;
}
async function fetchSnapshot(){
  state.busy=true;state.error="";renderStats();
  try{
    const response=await fetch("/api/demo/ontology/snapshot",{headers:{Authorization:`Bearer ${state.session.token}`},cache:"no-store"});
    if(!response.ok){const error=await response.json().catch(()=>({}));throw new Error(error.detail||`快照读取失败 (${response.status})`);}
    state.snapshot=await response.json();render();$("#login-dialog").close();
  }catch(error){state.snapshot=null;state.error=error.message;render();throw error;}
  finally{state.busy=false;renderStats();}
}
$("#connect").onclick=()=>{if(platformMode){window.open('http://127.0.0.1:8000/ontology/index.html','_blank','noopener');return;}$("#login-error").textContent="";$("#login-dialog").showModal();};
$("#cancel-login").onclick=()=>$("#login-dialog").close();
$("#login-form").onsubmit=async event=>{
  event.preventDefault();const submit=event.target.querySelector('[type="submit"]');submit.disabled=true;$("#login-error").textContent="";
  try{
    state.snapshot=null;state.session=null;state.error="正在连接业务快照…";render();const form=new FormData(event.target);
    const response=await fetch("/api/demo/login",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({username:form.get("username"),password:form.get("password")})});
    if(!response.ok)throw new Error("用户名或密码错误，或业务服务未连接");
    state.session=await response.json();sessionStorage.setItem("forgepilot-ontology-session",JSON.stringify(state.session));await fetchSnapshot();
  }catch(error){state.error=error.message;$("#login-error").textContent=error.message;render();}finally{submit.disabled=false;}
};
$("#refresh").onclick=()=>fetchSnapshot().catch(error=>{$("#snapshot-scope").textContent=error.message;});
$("#export").onclick=()=>download(`${state.model.release_id}.json`,state.model);
$("#export-snapshot").onclick=()=>state.snapshot&&download(`forgepilot-snapshot-${state.snapshot.revision}.json`,state.snapshot);
$("#search").oninput=event=>{state.search=event.target.value.trim().toLowerCase();render();};
document.querySelectorAll("[data-view]").forEach(b=>b.onclick=()=>{state.view=b.dataset.view;render();});
try{
  const response=await fetch("./catalog.json");if(!response.ok)throw new Error(`模型加载失败 (${response.status})`);
  state.model=await response.json();render();
  try{state.session=JSON.parse(sessionStorage.getItem("forgepilot-ontology-session")||sessionStorage.getItem("forgepilot-demo-session")||"null");}catch{state.session=null;}
  if(state.session?.token&&!platformMode)await fetchSnapshot().catch(()=>{});
  document.body.dataset.ontologyReady="true";
}catch(error){$("#load-error").hidden=false;$("#load-error").textContent=error.message;}
