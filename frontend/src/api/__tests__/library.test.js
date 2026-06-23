import { describe, it, expect, vi, beforeEach } from 'vitest'
import http from '../axios.js'

vi.mock('../axios.js', () => ({
  default: { get: vi.fn() }
}))

import { getMovies, getMovie, getShows, getShow,
         getSeasons, getSeason, getEpisodes, getEpisode } from '../library.js'

describe('library API', () => {
  beforeEach(() => vi.clearAllMocks())

  it('getMovies calls /api/movies with params', async () => {
    http.get.mockResolvedValue({ data: { content: [], totalPages: 0 } })
    await getMovies({ search: 'matrix', year: 1999, page: 0 })
    expect(http.get).toHaveBeenCalledWith('/api/movies', {
      params: { search: 'matrix', year: 1999, page: 0, size: 50 }
    })
  })

  it('getMovie calls /api/movies/:id', async () => {
    http.get.mockResolvedValue({ data: { id: 1, title: 'Test' } })
    const result = await getMovie(1)
    expect(http.get).toHaveBeenCalledWith('/api/movies/1')
    expect(result.title).toBe('Test')
  })

  it('getShows calls /api/tv with params', async () => {
    http.get.mockResolvedValue({ data: { content: [], totalPages: 0 } })
    await getShows({ page: 1 })
    expect(http.get).toHaveBeenCalledWith('/api/tv', {
      params: { page: 1, size: 50 }
    })
  })

  it('getSeasons calls /api/tv/:showId/seasons', async () => {
    http.get.mockResolvedValue({ data: [] })
    await getSeasons(5)
    expect(http.get).toHaveBeenCalledWith('/api/tv/5/seasons')
  })

  it('getEpisodes calls /api/tv/:showId/seasons/:seasonId/episodes', async () => {
    http.get.mockResolvedValue({ data: [] })
    await getEpisodes(5, 3)
    expect(http.get).toHaveBeenCalledWith('/api/tv/5/seasons/3/episodes', { params: {} })
  })
})
