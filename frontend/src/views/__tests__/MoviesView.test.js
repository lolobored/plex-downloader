import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'
import MoviesView from '../MoviesView.vue'

vi.mock('../../api/library.js', () => ({
  getMovies: vi.fn()
}))
import { getMovies } from '../../api/library.js'

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn() }),
  RouterLink: { template: '<a><slot /></a>' }
}))

const fakeMovies = {
  content: [
    { id: 1, plexId: 'pk1', title: 'Inception', year: 2010, genres: [] },
    { id: 2, plexId: 'pk2', title: 'The Matrix', year: 1999, genres: [] },
  ],
  totalPages: 1, number: 0
}

describe('MoviesView', () => {
  beforeEach(() => { vi.clearAllMocks(); getMovies.mockResolvedValue(fakeMovies) })

  it('fetches movies on mount and renders poster cards', async () => {
    const w = mount(MoviesView, { global: { plugins: [createTestingPinia()],
      stubs: { PosterCard: { template: '<div class="pc">{{ title }}</div>', props: ['title','plexId'] },
               SearchFilter: true, DownloadButton: true } } })
    await flushPromises()
    expect(getMovies).toHaveBeenCalledOnce()
    expect(w.findAll('.pc')).toHaveLength(2)
  })

  it('shows empty state when no results', async () => {
    getMovies.mockResolvedValue({ content: [], totalPages: 0, number: 0 })
    const w = mount(MoviesView, { global: { plugins: [createTestingPinia()],
      stubs: { PosterCard: true, SearchFilter: true, DownloadButton: true } } })
    await flushPromises()
    expect(w.text()).toContain('No movies found')
  })
})
