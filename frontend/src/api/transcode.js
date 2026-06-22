import http from './axios.js'

/**
 * GET /api/transcode/concurrency
 * Returns the current max concurrent transcode count.
 */
export async function getConcurrency() {
  const { data } = await http.get('/api/transcode/concurrency')
  return data.maxConcurrent
}

/**
 * PUT /api/transcode/concurrency
 * Sets the max concurrent transcode count.
 * Returns the confirmed value from the server.
 */
export async function setConcurrency(n) {
  const { data } = await http.put('/api/transcode/concurrency', { maxConcurrent: n })
  return data.maxConcurrent
}
