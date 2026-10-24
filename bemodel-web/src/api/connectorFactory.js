import request from './request'
const base='/datasource/factory'
export const connectorTemplates=()=>request.get(`${base}/templates`)
export const connectorList=()=>request.get(`${base}/connectors`)
export const initializeConnectors=()=>request.post(`${base}/initialize`)
export const createConnector=(body)=>request.post(`${base}/connectors`,body)
export const saveConnector=(code,body)=>request.put(`${base}/connectors/${code}`,body)
export const validateConnector=(code,version)=>request.post(`${base}/connectors/${code}/validate`,{version})
export const sampleConnector=(code,body)=>request.post(`${base}/connectors/${code}/sample`,body)
export const connectorLogs=(code)=>request.get(`${base}/connectors/${code}/logs`)
