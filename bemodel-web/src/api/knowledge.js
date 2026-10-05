import request from './request'

// ---------- 制度文档 ----------
export const listDocuments = () => request.get('/knowledge/documents')

export const uploadDocument = (file, owner) => {
  const form = new FormData()
  form.append('file', file)
  if (owner) form.append('owner', owner)
  return request.post('/knowledge/documents', form)
}

export const listChunks = (id) => request.get(`/knowledge/documents/${id}/chunks`)

export const transitionDocument = (id, target) =>
  request.post(`/knowledge/documents/${id}/transition`, { target })

export const deleteDocument = (id) => request.delete(`/knowledge/documents/${id}`)

// ---------- 知识条目 ----------
export const listEntries = () => request.get('/knowledge/entries')

export const createEntry = (data) => request.post('/knowledge/entries', data)

export const updateEntry = (id, data) => request.put(`/knowledge/entries/${id}`, data)

export const transitionEntry = (id, target) =>
  request.post(`/knowledge/entries/${id}/transition`, { target })
