import http from './axios.js'

export async function getPlaylists() {
  const { data } = await http.get('/api/playlists')
  return data  // PlaylistResponse[]
}

export async function getPlaylist(id) {
  const { data } = await http.get(`/api/playlists/${id}`)
  return data  // PlaylistDetailResponse
}

export async function subscribe(id) {
  await http.post(`/api/playlists/${id}/subscribe`)
}

export async function unsubscribe(id) {
  await http.delete(`/api/playlists/${id}/subscribe`)
}

export async function getPlaylistQueueCount(playlistId) {
  const { data } = await http.get(`/api/playlists/${playlistId}/queue-count`)
  return data.count  // number
}
