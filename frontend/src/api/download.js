import http from './axios.js'

// type: 'MOVIE' | 'EPISODE' | 'SEASON' | 'SHOW'
export async function enqueue(type, id) {
  const { data } = await http.post('/api/download', { type, id })
  return data  // { jobIds: number[], status: 'QUEUED' }
}

export async function getQueue() {
  const { data } = await http.get('/api/download/queue')
  return data  // DownloadQueueItem[]
}
