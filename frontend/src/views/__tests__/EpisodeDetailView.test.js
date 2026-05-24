import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'
import EpisodeDetailView from '../EpisodeDetailView.vue'

vi.mock('../../api/library.js', () => ({
  getEpisode: vi.fn(),
  getShow:    vi.fn(),
  getSeason:  vi.fn()
}))
vi.mock('vue-router', () => ({
  useRoute:  () => ({ params: { showId: '10', seasonId: '100', episodeId: '200' } }),
  useRouter: () => ({ back: vi.fn() })
}))
import { getEpisode, getShow, getSeason } from '../../api/library.js'

const fakeEpisode = {
  id: 200, plexId: 'ep-200', title: 'Pilot',
  episodeNumber: 1, airDate: '2008-01-20',
  durationMs: 2880000,
  director: 'Vince Gilligan',
  videoResolution: '1080p',
  summary: 'Walter White begins his transformation.',
  thumbnailUrl: 'http://plex/thumb.jpg'
}
const fakeShow   = { id: 10, title: 'Breaking Bad' }
const fakeSeason = { id: 100, seasonNumber: 1 }

const stubs = { DownloadButton: true }

describe('EpisodeDetailView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    getEpisode.mockResolvedValue(fakeEpisode)
    getShow.mockResolvedValue(fakeShow)
    getSeason.mockResolvedValue(fakeSeason)
  })

  function mount_() {
    return mount(EpisodeDetailView, { global: { plugins: [createTestingPinia()], stubs } })
  }

  it('shows loading state initially', () => {
    getEpisode.mockImplementation(() => new Promise(() => {}))
    const w = mount_()
    expect(w.text()).toContain('Loading')
  })

  it('renders episode title', async () => {
    const w = mount_()
    await flushPromises()
    expect(w.text()).toContain('Pilot')
  })

  it('renders S1E1 meta', async () => {
    const w = mount_()
    await flushPromises()
    expect(w.text()).toContain('S1E1')
  })

  it('renders show title', async () => {
    const w = mount_()
    await flushPromises()
    expect(w.text()).toContain('Breaking Bad')
  })

  it('renders air date', async () => {
    const w = mount_()
    await flushPromises()
    expect(w.text()).toContain('2008-01-20')
  })

  it('formats duration as minutes', async () => {
    const w = mount_()
    await flushPromises()
    expect(w.text()).toContain('48m')
  })

  it('renders director', async () => {
    const w = mount_()
    await flushPromises()
    expect(w.text()).toContain('Vince Gilligan')
  })

  it('renders video resolution', async () => {
    const w = mount_()
    await flushPromises()
    expect(w.text()).toContain('1080p')
  })

  it('renders summary', async () => {
    const w = mount_()
    await flushPromises()
    expect(w.text()).toContain('Walter White begins his transformation.')
  })

  it('shows placeholder when no thumbnailUrl', async () => {
    getEpisode.mockResolvedValue({ ...fakeEpisode, thumbnailUrl: null })
    const w = mount_()
    await flushPromises()
    expect(w.find('.thumb-placeholder').exists()).toBe(true)
    expect(w.find('img.thumb').exists()).toBe(false)
  })

  it('calls API with correct route params', async () => {
    mount_()
    await flushPromises()
    expect(getEpisode).toHaveBeenCalledWith('10', '100', '200')
    expect(getShow).toHaveBeenCalledWith('10')
    expect(getSeason).toHaveBeenCalledWith('10', '100')
  })
})
