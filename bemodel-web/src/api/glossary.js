import request from './request'

// ---------- 术语 ----------
export const listTerms = (conceptCode) =>
  request.get('/term/list', {
    params: conceptCode ? { conceptCode } : {}
  })

export const createTerm = (data) => request.post('/term', data)

export const deleteTerm = (id) => request.delete(`/term/${id}`)

// ---------- 指标 ----------
export const listMetrics = () => request.get('/metric/list')

// ---------- 指标监控 ----------
export const evaluateMetric = (metricCode) =>
  request.post(`/metric/evaluate/${metricCode}`)

export const evaluateAllMetrics = () => request.post('/metric/evaluate-all')

export const updateMetric = (data) => request.put('/metric', data)

// ---------- 语义搜索 ----------
export const searchGlossary = (q) => request.get('/search', { params: { q } })

// ---------- 对账组(P0-1 对账分歧工单化) ----------
export const listReconcile = () => request.get('/reconcile/list')

export const runReconcile = (groupCode) =>
  request.post(`/reconcile/run/${groupCode}`)

export const createReconcile = (data) => request.post('/reconcile', data)

export const updateReconcile = (data) => request.put('/reconcile', data)

export const claimReconcile = (groupCode) =>
  request.post(`/reconcile/claim/${groupCode}`)

export const resolveReconcile = (groupCode, verdict, note) =>
  request.post(`/reconcile/resolve/${groupCode}`, { verdict, note })

// 直连数据库 vs 本体平台对照(两边实时跑)
export const compareReconcile = (groupCode) =>
  request.get(`/reconcile/compare/${groupCode}`)
