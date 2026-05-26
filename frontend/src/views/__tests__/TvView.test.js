import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'
import TvView from '../TvView.vue'

vi.mock('../../api/library.js', () => ({ getShows: vi.fn() }))
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn() }),
  RouterLink: { template: '<a><slot /></a>' }
}))
import { getShows } from '../../api/library.js'

describe('TvView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    getShows.mockResolvedValue({
      content: [
        { id: 1, plexId: 'tv1', title: 'Breaking Bad', year: 2008, totalSeasons: 5 },
        { id: 2, plexId: 'tv2', title: 'The Wire',     year: 2002, totalSeasons: 5 }
      ],
      totalPages: 1, totalElements: 2, number: 0
    })
  })

  it('renders show titles', async () => {
    const w = mount(TvView, { global: { plugins: [createTestingPinia()],
      stubs: { PosterCard: { template: '<div class="pc">{{ title }}</div>', props: ['title','plexId','subtitle'] },
               SearchFilter: true, DownloadButton: true } } })
    await flushPromises()
    expect(w.findAll('.pc')).toHaveLength(2)
    expect(w.text()).toContain('Breaking Bad')
  })

  it('has name TvView for keep-alive', () => {
    expect(TvView.name ?? TvView.__name).toBe('TvView')
  })

  it('shows count badge with totalElements', async () => {
    const w = mount(TvView, { global: { plugins: [createTestingPinia()],
      stubs: { PosterCard: { template: '<div class="pc">{{ title }}</div>', props: ['title','plexId','subtitle'] },
               SearchFilter: true } } })
    await flushPromises()
    const badge = w.find('[data-testid="count-badge"]')
    expect(badge.exists()).toBe(true)
    expect(badge.text()).toBe('2')
  })

  it('count badge hidden when no results', async () => {
    getShows.mockResolvedValue({ content: [], totalPages: 0, totalElements: 0, number: 0 })
    const w = mount(TvView, { global: { plugins: [createTestingPinia()],
      stubs: { PosterCard: { template: '<div class="pc">{{ title }}</div>', props: ['title','plexId','subtitle'] },
               SearchFilter: true } } })
    await flushPromises()
    expect(w.find('[data-testid="count-badge"]').exists()).toBe(false)
  })
})
