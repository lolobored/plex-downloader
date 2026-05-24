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

export async function getPlexLibraries() {
  const { data } = await http.get('/api/admin/plex/libraries')
  return data  // [{ key, title, type, agent }, ...]
}
