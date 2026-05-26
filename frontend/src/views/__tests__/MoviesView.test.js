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
    { id: 1, plexId: 'pk1', title: 'Inception',     year: 2010, watched: false },
    { id: 2, plexId: 'pk2', title: 'The Matrix',     year: 1999, watched: false },
    { id: 3, plexId: 'pk3', title: 'Interstellar',   year: 2014, watched: false },
  ],
  totalPages: 1, totalElements: 3, number: 0
}

// Stub passes through fallthrough attrs (including `id`) to root element
const PcStub = { template: '<div class="pc">{{ title }}</div>', props: ['title','plexId','watched','subtitle'] }

describe('MoviesView', () => {
  beforeEach(() => { vi.clearAllMocks(); getMovies.mockResolvedValue(fakeMovies) })

  it('fetches movies on mount and renders poster cards', async () => {
    const w = mount(MoviesView, { global: { plugins: [createTestingPinia()],
      stubs: { PosterCard: PcStub, SearchFilter: true, DownloadButton: true } } })
    await flushPromises()
    expect(getMovies).toHaveBeenCalledOnce()
    expect(w.findAll('.pc')).toHaveLength(3)
  })

  it('shows empty state when no results', async () => {
    getMovies.mockResolvedValue({ content: [], totalPages: 0, number: 0 })
    const w = mount(MoviesView, { global: { plugins: [createTestingPinia()],
      stubs: { PosterCard: PcStub, SearchFilter: true, DownloadButton: true } } })
    await flushPromises()
    expect(w.text()).toContain('No movies found')
  })

  it('renders no letter-anchor divs in grid', async () => {
    const w = mount(MoviesView, { global: { plugins: [createTestingPinia()],
      stubs: { PosterCard: PcStub, SearchFilter: true, DownloadButton: true } } })
    await flushPromises()
    expect(w.find('.letter-anchor').exists()).toBe(false)
  })

  it('assigns letter id to first card of each letter group only', async () => {
    // Inception(I), Interstellar(I), Matrix(M) — only Inception and Matrix get ids
    const w = mount(MoviesView, { global: { plugins: [createTestingPinia()],
      stubs: { PosterCard: PcStub, SearchFilter: true, DownloadButton: true } } })
    await flushPromises()
    expect(w.find('#letter-I').exists()).toBe(true)   // Inception is first 'I'
    expect(w.find('#letter-M').exists()).toBe(true)   // The Matrix is first 'M'
    expect(w.findAll('.pc[id]')).toHaveLength(2)      // only 2 of 3 cards have id
  })

  it('has name MoviesView for keep-alive', () => {
    expect(MoviesView.name ?? MoviesView.__name).toBe('MoviesView')
  })

  it('shows count badge with totalElements', async () => {
    const w = mount(MoviesView, { global: { plugins: [createTestingPinia()],
      stubs: { PosterCard: PcStub, SearchFilter: true, DownloadButton: true } } })
    await flushPromises()
    const badge = w.find('[data-testid="count-badge"]')
    expect(badge.exists()).toBe(true)
    expect(badge.text()).toBe('3')
  })

  it('count badge hidden when no results', async () => {
    getMovies.mockResolvedValue({ content: [], totalPages: 0, totalElements: 0, number: 0 })
    const w = mount(MoviesView, { global: { plugins: [createTestingPinia()],
      stubs: { PosterCard: PcStub, SearchFilter: true, DownloadButton: true } } })
    await flushPromises()
    expect(w.find('[data-testid="count-badge"]').exists()).toBe(false)
  })
})
