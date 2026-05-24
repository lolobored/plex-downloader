import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'
import MovieDetailView from '../MovieDetailView.vue'

vi.mock('../../api/library.js', () => ({ getMovie: vi.fn() }))
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { id: '42' } }),
  useRouter: () => ({ back: vi.fn() })
}))
import { getMovie } from '../../api/library.js'

const fakeMovie = {
  id: 42, plexId: 'pk42', title: 'Inception', year: 2010,
  summary: 'A thief who steals corporate secrets.', rating: 8.8,
  studio: 'Warner', durationMs: 8880000,
  genres: ['Action', 'Sci-Fi'], directors: ['Christopher Nolan'],
  actors: [{ id: 1, name: 'Leonardo DiCaprio' }]
}

describe('MovieDetailView', () => {
  beforeEach(() => { vi.clearAllMocks(); getMovie.mockResolvedValue(fakeMovie) })

  it('fetches movie by route id and renders title', async () => {
    const w = mount(MovieDetailView, { global: { plugins: [createTestingPinia()],
      stubs: { DownloadButton: true } } })
    await flushPromises()
    expect(getMovie).toHaveBeenCalledWith('42')
    expect(w.text()).toContain('Inception')
  })

  it('renders summary', async () => {
    const w = mount(MovieDetailView, { global: { plugins: [createTestingPinia()],
      stubs: { DownloadButton: true } } })
    await flushPromises()
    expect(w.text()).toContain('A thief who steals corporate secrets.')
  })

  it('renders cast name', async () => {
    const w = mount(MovieDetailView, { global: { plugins: [createTestingPinia()],
      stubs: { DownloadButton: true } } })
    await flushPromises()
    expect(w.text()).toContain('Leonardo DiCaprio')
  })
})
