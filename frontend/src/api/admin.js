import http from './axios.js'

export async function getSettings() {
  const { data } = await http.get('/api/admin/settings')
  return data  // { 'plex.server.url': '', 'plex.sync.cron': '', ... }
}

export async function putSettings(settings) {
  await http.put('/api/admin/settings', settings)
}

export async function getSyncStatus() {
  const { data } = await http.get('/api/admin/sync/status')
  return data  // { state, lastSyncAt, itemsSynced, error }
}

export async function triggerSync() {
  await http.post('/api/admin/sync')
}

export async function getPlexLibraries(url) {
  const params = url ? { url } : {}
  const { data } = await http.get('/api/admin/plex/libraries', { params })
  return data  // [{ key, title, type, agent }, ...]
}

export async function testTdarr(url, apiKey) {
  const params = {}
  if (url)    params.url    = url
  if (apiKey) params.apiKey = apiKey
  const { data } = await http.get('/api/admin/tdarr/test', { params })
  return data  // { ok: true } or { ok: false, error: '...' }
}

export async function createQualityProfile(profile) {
  const { data } = await http.post('/api/admin/quality-profiles', profile)
  return data  // created QualityProfile
}

export async function updateQualityProfile(id, profile) {
  const { data } = await http.put(`/api/admin/quality-profiles/${id}`, profile)
  return data  // updated QualityProfile
}

export async function deleteQualityProfile(id) {
  await http.delete(`/api/admin/quality-profiles/${id}`)
}

export async function setDefaultQualityProfile(id) {
  const { data } = await http.put(`/api/admin/quality-profiles/${id}/default`)
  return data  // updated QualityProfile
}
