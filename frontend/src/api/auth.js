import http from './axios.js'

export async function initPin() {
  const { data } = await http.post('/api/auth/plex/pin')
  // returns { pinId, code }
  return data
}

export async function checkPin(pinId) {
  const { data, status } = await http.get(`/api/auth/plex/pin/${pinId}`, {
    validateStatus: s => s === 200 || s === 202
  })
  // 200 → { token, username, role }
  // 202 → { status: 'pending' }
  if (status === 200) return data
  return null
}
