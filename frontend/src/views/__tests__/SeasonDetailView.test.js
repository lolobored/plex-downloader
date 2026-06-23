import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'
import { useWatchedStore } from '../../stores/watched.js'
import SeasonDetailView from '../SeasonDetailView.vue'

vi.mock('../../api/library.js', () => ({
  getShow:     vi.fn(),
  getSeason:   vi.fn(),
  getEpisodes: vi.fn()
}))
vi.mock('vue-router', () => ({
  useRoute:  () => ({ params: { showId: '10', seasonId: '100' } }),
  useRouter: () => ({ back: vi.fn(), push: vi.fn() })
}))
import { getShow, getSeason, getEpisodes } from '../../api/library.js'

const fakeShow    = { id: 10, plexId: 'show-1', title: 'Breaking Bad' }
const fakeSeason  = { id: 100, plexId: 's1', seasonNumber: 1, title: 'Season 1', episodeCount: 2 }
const fakeEpisodes = [
  { id: 200, plexId: 'ep-200', episodeNumber: 1, title: 'Pilot',              airDate: '2008-01-20', thumbnailUrl: null, subtitleLangs: ',eng,', subtitlesScanned: true },
  { id: 201, plexId: 'ep-201', episodeNumber: 2, title: "Cat's in the Bag",   airDate: '2008-01-27', thumbnailUrl: null, subtitleLangs: null,    subtitlesScanned: false }
]

const SubBadgeStub = {
  template: '<span class="sub-badge" :data-langs="langs" :data-scanned="scanned ? \'true\' : \'false\'" />',
  props: ['langs','scanned']
}

function factory(overrides = {}) {
  const pinia = createTestingPinia({ createSpy: vi.fn })
  const store = useWatchedStore(pinia)
  store.fetchWatched       = vi.fn()
  store.fetchSubscriptions = vi.fn()
  store.isWatched          = vi.fn().mockReturnValue(false)
  Object.assign(store, overrides)
  const w = mount(SeasonDetailView, {
    global: {
      plugins: [pinia],
      stubs: {
        SubscribeButton: { template: '<div class="sb" :data-season-id="seasonId" />', props: ['showId', 'small', 'seasonId'] },
        DownloadButton:  { template: '<button class="dl-btn" />', props: ['type', 'mediaId', 'small'] },
        SubtitleBadge:   SubBadgeStub
      }
    }
  })
  return { w, store }
}

describe('SeasonDetailView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    getShow.mockResolvedValue(fakeShow)
    getSeason.mockResolvedValue(fakeSeason)
    getEpisodes.mockResolvedValue(fakeEpisodes)
  })

  it('shows loading state initially', () => {
    getShow.mockImplementation(() => new Promise(() => {}))
    const { w } = factory()
    expect(w.text()).toContain('Loading')
  })

  it('renders show title and season title', async () => {
    const { w } = factory()
    await flushPromises()
    expect(w.text()).toContain('Breaking Bad')
    expect(w.text()).toContain('Season 1')
  })

  it('renders episode count', async () => {
    const { w } = factory()
    await flushPromises()
    expect(w.text()).toContain('2 episodes')
  })

  it('renders all episode titles', async () => {
    const { w } = factory()
    await flushPromises()
    expect(w.text()).toContain('Pilot')
    expect(w.text()).toContain("Cat's in the Bag")
  })

  it('shows download button for unwatched episodes', async () => {
    const { w } = factory()
    await flushPromises()
    expect(w.findAll('.dl-btn').length).toBe(2)
  })

  it('shows watched badge instead of download for watched episode', async () => {
    const { w } = factory({ isWatched: vi.fn((showId, epId) => epId === 200) })
    await flushPromises()
    expect(w.find('.watched-badge').exists()).toBe(true)
    expect(w.findAll('.dl-btn').length).toBe(1)
  })

  it('calls fetchWatched and fetchSubscriptions on mount', async () => {
    const { store } = factory()
    await flushPromises()
    expect(store.fetchWatched).toHaveBeenCalledWith(10)
    expect(store.fetchSubscriptions).toHaveBeenCalled()
  })

  it('calls API with correct route params', async () => {
    factory()
    await flushPromises()
    expect(getShow).toHaveBeenCalledWith('10')
    expect(getSeason).toHaveBeenCalledWith('10', '100')
    expect(getEpisodes).toHaveBeenCalledWith('10', '100')
  })

  it('passes seasonId to SubscribeButton', async () => {
    const { w } = factory()
    await flushPromises()
    const sb = w.find('.sb')
    expect(sb.exists()).toBe(true)
    expect(sb.attributes('data-season-id')).toBe('100')
  })

  // ── Subtitle filter + badge tests ────────────────────────────────────────────

  it('toggling "No subtitles" filter re-calls getEpisodes with subtitles: none', async () => {
    const { w } = factory()
    await flushPromises()
    getEpisodes.mockClear()
    await w.find('[data-testid="sub-filter-none"]').trigger('click')
    await flushPromises()
    expect(getEpisodes).toHaveBeenCalledWith('10', '100', expect.objectContaining({ subtitles: 'none' }))
  })

  it('entering a lang code with has-mode passes hasLang to getEpisodes', async () => {
    const { w } = factory()
    await flushPromises()
    getEpisodes.mockClear()
    await w.find('[data-testid="sub-lang-mode"]').setValue('has')
    await w.find('[data-testid="sub-lang-input"]').setValue('eng')
    await flushPromises()
    expect(getEpisodes).toHaveBeenCalledWith('10', '100', expect.objectContaining({ hasLang: 'eng' }))
  })

  it('entering a lang code with missing-mode passes missingLang to getEpisodes', async () => {
    const { w } = factory()
    await flushPromises()
    getEpisodes.mockClear()
    await w.find('[data-testid="sub-lang-mode"]').setValue('missing')
    await w.find('[data-testid="sub-lang-input"]').setValue('fra')
    await flushPromises()
    expect(getEpisodes).toHaveBeenCalledWith('10', '100', expect.objectContaining({ missingLang: 'fra' }))
  })

  it('renders a SubtitleBadge per episode', async () => {
    const { w } = factory()
    await flushPromises()
    const badges = w.findAll('.sub-badge')
    expect(badges).toHaveLength(2)
    expect(badges[0].attributes('data-langs')).toBe(',eng,')
    expect(badges[0].attributes('data-scanned')).toBe('true')
    expect(badges[1].attributes('data-scanned')).toBe('false')
  })
})
