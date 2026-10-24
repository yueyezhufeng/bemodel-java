import request from './request'

// ---------- 业务域 ----------
export const listDomains = () => request.get('/domain/list')

export const createDomain = (data) => request.post('/domain', data)

// ---------- 概念 ----------
export const listConcepts = (domainCode) =>
  request.get('/concept/list', {
    params: domainCode ? { domainCode } : {}
  })

export const conceptDetail = (code) => request.get(`/concept/detail/${code}`)

export const createConcept = (data) => request.post('/concept', data)

export const updateConcept = (data) => request.put('/concept', data)

// 仅 DRAFT 可删；有引用时后端报错 msg 携带引用计数
export const deleteConcept = (code) => request.delete(`/concept/${code}`)

// ---------- 多父继承 ----------
export const listParents = (code) => request.get(`/concept/${code}/parents`)

// body: { parentCode, isPrimary(0/1) }；成环后端 500，msg 由拦截器展示
export const addParent = (code, data) => request.post(`/concept/${code}/parents`, data)

export const removeParent = (code, parentCode) =>
  request.delete(`/concept/${code}/parents/${parentCode}`)

export const setPrimaryParent = (code, parentCode) =>
  request.put(`/concept/${code}/parents/${parentCode}/primary`)

export const transitionConcept = (code, target) =>
  request.post(`/concept/transition/${code}`, null, { params: { target } })

// ---------- 概念属性 ----------
export const createAttribute = (data) => request.post('/concept/attribute', data)

export const deleteAttribute = (id) => request.delete(`/concept/attribute/${id}`)

// ---------- 概念关系 ----------
export const createRelation = (data) => request.post('/concept/relation', data)

export const updateRelation = (data) => request.put('/concept/relation', data)

export const deleteRelation = (id) => request.delete(`/concept/relation/${id}`)

// ---------- 规则 ----------
export const listRules = (conceptCode) =>
  request.get('/rule/list', { params: conceptCode ? { conceptCode } : {} })

export const createRule = (data) => request.post('/rule', data)

export const updateRule = (data) => request.put('/rule', data)

export const transitionRule = (ruleCode, target) =>
  request.post(`/rule/transition/${ruleCode}`, null, { params: { target } })

export const deleteRule = (id) => request.delete(`/rule/${id}`)

// ---------- 动作 ----------
export const listActions = (conceptCode) =>
  request.get('/action/list', { params: conceptCode ? { conceptCode } : {} })

export const createAction = (data) => request.post('/action', data)

export const updateAction = (data) => request.put('/action', data)

export const transitionAction = (actionCode, target) =>
  request.post(`/action/transition/${actionCode}`, null, { params: { target } })

export const deleteAction = (id) => request.delete(`/action/${id}`)

// ---------- 实例浏览 ----------
// 不传 tableName 时返回全部来源表（每表首页）；传 tableName 时该表按页返回
export const listInstances = (conceptCode, { tableName, pageNum = 1, pageSize = 20 } = {}) =>
  request.get(`/instance/${conceptCode}`, {
    params: { ...(tableName ? { tableName } : {}), pageNum, pageSize }
  })

// ---------- 公理 ----------
export const listAxioms = () => request.get('/axiom/list')

export const createAxiom = (data) => request.post('/axiom', data)

export const deleteAxiom = (id) => request.delete(`/axiom/${id}`)

// ---------- 概念互斥 ----------
export const listDisjoint = () => request.get('/ontology/disjoint')

export const createDisjoint = (data) => request.post('/ontology/disjoint', data)

export const deleteDisjoint = (id) => request.delete(`/ontology/disjoint/${id}`)

// ---------- 本体自检 ----------
export const checkOntology = () => request.post('/ontology/check')

// ---------- 传递闭包 ----------
export const relationClosure = (relation, concept) =>
  request.get('/ontology/relation/closure', { params: { relation, concept } })

// ---------- OWL 导入 ----------
export const previewOwlImport = (file) => {
  const form = new FormData()
  form.append('file', file)
  return request.post('/ontology/import/preview', form)
}

export const executeOwlImport = (file) => {
  const form = new FormData()
  form.append('file', file)
  return request.post('/ontology/import/execute', form)
}

// ---------- 扩展提案（本体增长回路） ----------
export const listOntologyMisses = () => request.get('/ontology/misses')

export const dismissMiss = (id, reason) =>
  request.post(`/ontology/misses/${id}/dismiss`, { reason })

export const undismissMiss = (id) => request.post(`/ontology/misses/${id}/undismiss`)

export const adoptMiss = (id, data) => request.post(`/ontology/misses/${id}/adopt`, data)

// 把 miss 的说法挂为现有概念的方言术语（bm_term）
export const adoptMissAsTerm = (id, conceptCode) =>
  request.post(`/ontology/misses/${id}/adopt-as-term`, { conceptCode })

export const revokeMiss = (id) => request.post(`/ontology/misses/${id}/revoke`)

export const classifyMiss = (id) => request.post(`/ontology/misses/${id}/classify`)

// ---------- AI 提案队列（P1a：AI 提议人裁决） ----------
export const fetchProposals = (params) => request.get('/proposal/list', { params })

export const runProposalGeneration = () => request.post('/proposal/run')

export const adoptProposal = (id) => request.post(`/proposal/${id}/adopt`)

export const rejectProposal = (id, reason) => request.post(`/proposal/${id}/reject`, { reason })

// Working model only; no release, physical SQL or architecture engines.
export const ontologyGraph = () => request.get('/ontology/graph')
export const previewRelation = (relation, concept, mode, direction) =>
  request.get('/ontology/relation/preview', { params: { relation, concept, mode, direction } })
