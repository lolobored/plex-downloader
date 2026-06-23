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
    { id: 1, plexId: 'pk1', title: 'Inception',     year: 2010, watched: false, subtitleLangs: ',eng,', subtitlesScanned: true },
    { id: 2, plexId: 'pk2', title: 'The Matrix',     year: 1999, watched: false, subtitleLangs: null,    subtitlesScanned: false },
    { id: 3, plexId: 'pk3', title: 'Interstellar',   year: 2014, watched: false, subtitleLangs: null,    subtitlesScanned: true },
  ],
  totalPages: 1, totalElements: 3, number: 0
}

// Stub passes through fallthrough attrs (including `id`) to root element
const PcStub = {
  template: '<div class="pc"><slot name="badge" /></div>',
  props: ['title','plexId','watched','subtitle']
}

// SubtitleBadge stub that surfaces langs/scanned as data-attrs for assertions
const SubBadgeStub = {
  template: '<span class="sub-badge" :data-langs="langs" :data-scanned="scanned ? \'true\' : \'false\'" />',
  props: ['langs','scanned']
}

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

  // ── Subtitle filter + badge tests ────────────────────────────────────────────

  it('toggling "No subtitles" calls getMovies with subtitles: none', async () => {
    const w = mount(MoviesView, { global: { plugins: [createTestingPinia()],
      stubs: { PosterCard: PcStub, SearchFilter: true, DownloadButton: true, SubtitleBadge: SubBadgeStub } } })
    await flushPromises()
    getMovies.mockClear()
    await w.find('[data-testid="sub-filter-none"]').trigger('click')
    await flushPromises()
    expect(getMovies).toHaveBeenCalledWith(expect.objectContaining({ subtitles: 'none' }))
  })

  it('toggling "No subtitles" again clears the filter', async () => {
    const w = mount(MoviesView, { global: { plugins: [createTestingPinia()],
      stubs: { PosterCard: PcStub, SearchFilter: true, DownloadButton: true, SubtitleBadge: SubBadgeStub } } })
    await flushPromises()
    await w.find('[data-testid="sub-filter-none"]').trigger('click')
    await flushPromises()
    getMovies.mockClear()
    await w.find('[data-testid="sub-filter-none"]').trigger('click')
    await flushPromises()
    const call = getMovies.mock.calls[0][0]
    expect(call.subtitles).toBeUndefined()
  })

  it('entering a lang code with has-mode passes hasLang to getMovies', async () => {
    const w = mount(MoviesView, { global: { plugins: [createTestingPinia()],
      stubs: { PosterCard: PcStub, SearchFilter: true, DownloadButton: true, SubtitleBadge: SubBadgeStub } } })
    await flushPromises()
    getMovies.mockClear()
    // ensure mode is 'has' (default)
    const modeSelect = w.find('[data-testid="sub-lang-mode"]')
    await modeSelect.setValue('has')
    const langInput = w.find('[data-testid="sub-lang-input"]')
    await langInput.setValue('eng')
    await flushPromises()
    expect(getMovies).toHaveBeenCalledWith(expect.objectContaining({ hasLang: 'eng' }))
  })

  it('entering a lang code with missing-mode passes missingLang to getMovies', async () => {
    const w = mount(MoviesView, { global: { plugins: [createTestingPinia()],
      stubs: { PosterCard: PcStub, SearchFilter: true, DownloadButton: true, SubtitleBadge: SubBadgeStub } } })
    await flushPromises()
    getMovies.mockClear()
    const modeSelect = w.find('[data-testid="sub-lang-mode"]')
    await modeSelect.setValue('missing')
    const langInput = w.find('[data-testid="sub-lang-input"]')
    await langInput.setValue('fra')
    await flushPromises()
    expect(getMovies).toHaveBeenCalledWith(expect.objectContaining({ missingLang: 'fra' }))
  })

  it('renders a SubtitleBadge per movie card', async () => {
    const w = mount(MoviesView, { global: { plugins: [createTestingPinia()],
      stubs: { PosterCard: PcStub, SearchFilter: true, DownloadButton: true, SubtitleBadge: SubBadgeStub } } })
    await flushPromises()
    const badges = w.findAll('.sub-badge')
    expect(badges).toHaveLength(3)
    // sortedMovies order: Inception(I), Interstellar(I), Matrix(M)
    // badges[0] = Inception: scanned, has eng
    expect(badges[0].attributes('data-langs')).toBe(',eng,')
    expect(badges[0].attributes('data-scanned')).toBe('true')
    // badges[1] = Interstellar: scanned, no langs
    expect(badges[1].attributes('data-scanned')).toBe('true')
    // badges[2] = Matrix: not scanned
    expect(badges[2].attributes('data-scanned')).toBe('false')
  })
})
