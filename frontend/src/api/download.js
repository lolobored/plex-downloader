import http from './axios.js'

// type: 'MOVIE' | 'EPISODE' | 'SEASON' | 'SHOW'
export async function enqueue(type, id, qualityProfileId = null) {
  const { data } = await http.post('/api/download', { type, id, qualityProfileId })
  return data  // { jobIds: number[], status: 'QUEUED' }
}

export async function getQualityProfiles() {
  const { data } = await http.get('/api/quality-profiles')
  return data  // QualityProfile[]
}

export async function getQueue({ sourceSubtitles, outputSubtitles, hasLang, missingLang, outputHasLang, outputMissingLang } = {}) {
  const params = {}
  if (sourceSubtitles  != null) params.sourceSubtitles  = sourceSubtitles
  if (outputSubtitles  != null) params.outputSubtitles  = outputSubtitles
  if (hasLang          != null) params.hasLang          = hasLang
  if (missingLang      != null) params.missingLang      = missingLang
  if (outputHasLang    != null) params.outputHasLang    = outputHasLang
  if (outputMissingLang != null) params.outputMissingLang = outputMissingLang
  const { data } = await http.get('/api/download/queue', { params })
  return data  // DownloadQueueItem[]
}

export async function removeQueueItem(id) {
  await http.delete(`/api/download/${id}`)
}

export async function retryQueueItem(id) {
  const { data } = await http.post(`/api/download/${id}/retry`)
  return data
}

export async function retryAllErrored() {
  const { data } = await http.post('/api/download/retry-all-errored')
  return data  // { retried: N }
}

export async function getOutputStatus() {
  const { data } = await http.get('/api/output-status')
  return data  // { configured: boolean }
}

export async function transcodeAgain(id) {
  const { data } = await http.post(`/api/download/${id}/transcode-again`)
  return data
}
