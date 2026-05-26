import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'
import { useWatchedStore } from '../../stores/watched.js'
import TvShowDetailView from '../TvShowDetailView.vue'

vi.mock('../../api/library.js', () => ({
  getShow:    vi.fn(),
  getSeasons: vi.fn()
}))
vi.mock('vue-router', () => ({
  useRoute:  () => ({ params: { showId: '10' } }),
  useRouter: () => ({ back: vi.fn(), push: vi.fn() })
}))
import { getShow, getSeasons } from '../../api/library.js'

describe('TvShowDetailView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    getShow.mockResolvedValue({
      id: 10, plexId: 'show-1', title: 'Breaking Bad',
      year: 2008, totalSeasons: 2, summary: 'Chemistry teacher.', rating: 9.5
    })
    getSeasons.mockResolvedValue([
      { id: 100, plexId: 's1', seasonNumber: 1, title: 'Season 1', episodeCount: 7 }
    ])
  })

  it('renders show title', async () => {
    const pinia = createTestingPinia({ createSpy: vi.fn })
    const store = useWatchedStore(pinia)
    store.fetchWatched = vi.fn()
    store.fetchSubscriptions = vi.fn()
    const w = mount(TvShowDetailView, {
      global: {
        plugins: [pinia],
        stubs: { SubscribeButton: { template: '<div class="sb" />', props: ['showId', 'small', 'seasonId'] },
                 PosterCard: { template: '<div class="pc"><slot name="badge" /></div>', props: ['plexId','title','subtitle','watched'] } }
      }
    })
    await flushPromises()
    expect(w.text()).toContain('Breaking Bad')
    expect(store.fetchWatched).toHaveBeenCalledWith(10)
    expect(store.fetchSubscriptions).toHaveBeenCalled()
  })

  it('season cards have no subscribe badge', async () => {
    const pinia = createTestingPinia({ createSpy: vi.fn })
    const store = useWatchedStore(pinia)
    store.fetchWatched = vi.fn()
    store.fetchSubscriptions = vi.fn()
    const w = mount(TvShowDetailView, {
      global: {
        plugins: [pinia],
        stubs: {
          SubscribeButton: { template: '<div class="sb" />', props: ['showId', 'small', 'seasonId'] },
          PosterCard: { template: '<div class="pc"><slot name="badge" /></div>', props: ['plexId','title','subtitle','watched'] }
        }
      }
    })
    await flushPromises()
    const cards = w.findAll('.pc')
    expect(cards.length).toBeGreaterThan(0)
    cards.forEach(card => {
      expect(card.find('.sb').exists()).toBe(false)
    })
  })
})
