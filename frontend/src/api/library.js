import http from './axios.js'

export async function getMovies({ search, year, page = 0, size = 50 } = {}) {
  const params = { page, size }
  if (search) params.search = search
  if (year)   params.year   = year
  const { data } = await http.get('/api/movies', { params })
  return data  // Spring Page: { content, totalPages, totalElements, number }
}

export async function getMovie(id) {
  const { data } = await http.get(`/api/movies/${id}`)
  return data
}

export async function getShows({ search, year, page = 0, size = 50 } = {}) {
  const params = { page, size }
  if (search) params.search = search
  if (year)   params.year   = year
  const { data } = await http.get('/api/tv', { params })
  return data
}

export async function getShow(showId) {
  const { data } = await http.get(`/api/tv/${showId}`)
  return data
}

export async function getSeasons(showId) {
  const { data } = await http.get(`/api/tv/${showId}/seasons`)
  return data  // array
}

export async function getSeason(showId, seasonId) {
  const { data } = await http.get(`/api/tv/${showId}/seasons/${seasonId}`)
  return data
}

export async function getEpisodes(showId, seasonId) {
  const { data } = await http.get(`/api/tv/${showId}/seasons/${seasonId}/episodes`)
  return data  // array
}

export async function getEpisode(showId, seasonId, episodeId) {
  const { data } = await http.get(`/api/tv/${showId}/seasons/${seasonId}/episodes/${episodeId}`)
  return data
}
