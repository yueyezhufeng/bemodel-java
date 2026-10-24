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
