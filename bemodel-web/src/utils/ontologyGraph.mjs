import { foldRelations, axiomStyleOf, axiomSuffixOf } from './axiomEdge.js'
export const escapeGraphText = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]))
const colors=['#d65b35','#527f70','#587da0','#9a7a50','#8873a2','#718b4b','#ad6262','#4d8b92','#9b7d89','#687687']
export function buildOntologyGraph(model={}, {domain='',center='',disjoint=[]}={}) {
  const concepts=model.concepts||[],valid=new Set(concepts.map(c=>c.code)),byCode=new Map(concepts.map(c=>[c.code,c]))
  const relations=(model.relations||[]).filter(r=>valid.has(r.fromConcept)&&valid.has(r.toConcept))
  const parents=(model.parents||[]).filter(p=>valid.has(p.childCode)&&valid.has(p.parentCode))
  let ids=new Set(concepts.filter(c=>!domain||c.domainCode===domain).map(c=>c.code))
  if(center){ids=new Set(valid.has(center)?[center]:[]);for(const r of relations){if(r.fromConcept===center||r.toConcept===center){ids.add(r.fromConcept);ids.add(r.toConcept)}}for(const p of parents){if(p.childCode===center||p.parentCode===center){ids.add(p.childCode);ids.add(p.parentCode)}}for(const d of disjoint){if(d.conceptACode===center&&valid.has(d.conceptBCode))ids.add(d.conceptBCode);if(d.conceptBCode===center&&valid.has(d.conceptACode))ids.add(d.conceptACode)}}
  else if(domain){for(const r of relations){if(byCode.get(r.fromConcept).domainCode===domain||byCode.get(r.toConcept).domainCode===domain){ids.add(r.fromConcept);ids.add(r.toConcept)}}}
  const domains=[...new Set(concepts.map(c=>c.domainCode))].sort(),categories=domains.map((name,i)=>({name,itemStyle:{color:colors[i%colors.length]}}))
  const nodes=[...ids].map(code=>{const c=byCode.get(code);return {id:`C:${code}`,name:c.name,category:domains.indexOf(c.domainCode),symbolSize:code===center?38:24,_code:code,itemStyle:code===center?{color:'#d65b35'}:undefined,label:{fontSize:12},tooltip:{formatter:()=>`${escapeGraphText(c.name)} (${escapeGraphText(c.code)})`}}})
  const visible=relations.filter(r=>ids.has(r.fromConcept)&&ids.has(r.toConcept)&&(!center||r.fromConcept===center||r.toConcept===center))
  const edges=foldRelations(visible).map(e=>({source:e.source,target:e.target,...axiomStyleOf(e,'#729c80'),label:{show:!!center,formatter:e.label+axiomSuffixOf(e),fontSize:11},_raw:e}))
  for(const p of parents){if(ids.has(p.childCode)&&ids.has(p.parentCode)&&(!center||p.childCode===center||p.parentCode===center))edges.push({source:`C:${p.childCode}`,target:`C:${p.parentCode}`,label:{show:true,formatter:'继承'},lineStyle:{color:'#587da0',type:'dashed'},_raw:{label:'继承',axioms:[]}})}
  if(center)for(const d of disjoint){if((d.conceptACode===center||d.conceptBCode===center)&&ids.has(d.conceptACode)&&ids.has(d.conceptBCode))edges.push({source:`C:${d.conceptACode}`,target:`C:${d.conceptBCode}`,label:{show:true,formatter:'互斥'},lineStyle:{color:'#c75050',type:'dashed'},symbol:['none','none']})}
  return {nodes,edges,categories}
}
