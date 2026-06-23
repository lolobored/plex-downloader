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

const fakeShows = {
  content: [
    { id: 1, plexId: 'tv1', title: 'Breaking Bad',        year: 2008, totalSeasons: 5, hasEpisodesMissingSubtitles: true },
    { id: 2, plexId: 'tv2', title: 'The Wire',            year: 2002, totalSeasons: 5, hasEpisodesMissingSubtitles: false },
    { id: 3, plexId: 'tv3', title: 'Arrested Development', year: 2003, totalSeasons: 5, hasEpisodesMissingSubtitles: false }
  ],
  totalPages: 1, totalElements: 3, number: 0
}

// Stub that surfaces both title text and badge slot
const PcStub = {
  template: '<div class="pc">{{ title }}<slot name="badge" /></div>',
  props: ['title','plexId','subtitle','watched']
}

const SubBadgeStub = {
  template: '<span class="sub-badge" :data-langs="langs" :data-scanned="scanned ? \'true\' : \'false\'" />',
  props: ['langs','scanned']
}

describe('TvView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    getShows.mockResolvedValue(fakeShows)
  })

  it('fetches shows on mount and renders poster cards', async () => {
    const w = mount(TvView, { global: { plugins: [createTestingPinia()],
      stubs: { PosterCard: PcStub, SearchFilter: true } } })
    await flushPromises()
    expect(getShows).toHaveBeenCalledOnce()
    expect(w.findAll('.pc')).toHaveLength(3)
  })

  it('renders show titles', async () => {
    const w = mount(TvView, { global: { plugins: [createTestingPinia()],
      stubs: { PosterCard: PcStub, SearchFilter: true } } })
    await flushPromises()
    expect(w.text()).toContain('Breaking Bad')
  })

  it('shows empty state when no results', async () => {
    getShows.mockResolvedValue({ content: [], totalPages: 0, totalElements: 0, number: 0 })
    const w = mount(TvView, { global: { plugins: [createTestingPinia()],
      stubs: { PosterCard: PcStub, SearchFilter: true } } })
    await flushPromises()
    expect(w.text()).toContain('No TV shows found')
  })

  it('has no pagination buttons', async () => {
    const w = mount(TvView, { global: { plugins: [createTestingPinia()],
      stubs: { PosterCard: PcStub, SearchFilter: true } } })
    await flushPromises()
    expect(w.find('.pagination').exists()).toBe(false)
  })

  it('has alphabet sidebar', async () => {
    const w = mount(TvView, { global: { plugins: [createTestingPinia()],
      stubs: { PosterCard: PcStub, SearchFilter: true } } })
    await flushPromises()
    expect(w.find('.alpha-sidebar').exists()).toBe(true)
    expect(w.findAll('.alpha-btn').length).toBe(26)
  })

  it('shows count badge with totalElements', async () => {
    const w = mount(TvView, { global: { plugins: [createTestingPinia()],
      stubs: { PosterCard: PcStub, SearchFilter: true } } })
    await flushPromises()
    const badge = w.find('[data-testid="count-badge"]')
    expect(badge.exists()).toBe(true)
    expect(badge.text()).toBe('3')
  })

  it('count badge hidden when no results', async () => {
    getShows.mockResolvedValue({ content: [], totalPages: 0, totalElements: 0, number: 0 })
    const w = mount(TvView, { global: { plugins: [createTestingPinia()],
      stubs: { PosterCard: PcStub, SearchFilter: true } } })
    await flushPromises()
    expect(w.find('[data-testid="count-badge"]').exists()).toBe(false)
  })

  it('assigns letter id to first card of each letter group only', async () => {
    // Breaking Bad(B), The Wire→Wire(W), Arrested Development(A)
    // sorted: A, B, W → Arrested(A), Breaking(B), Wire(W)
    const w = mount(TvView, { global: { plugins: [createTestingPinia()],
      stubs: { PosterCard: PcStub, SearchFilter: true } } })
    await flushPromises()
    expect(w.find('#letter-A').exists()).toBe(true)  // Arrested Development
    expect(w.find('#letter-B').exists()).toBe(true)  // Breaking Bad
    expect(w.find('#letter-W').exists()).toBe(true)  // The Wire → Wire
    expect(w.findAll('.pc[id]')).toHaveLength(3)
  })

  it('has name TvView for keep-alive', () => {
    expect(TvView.name ?? TvView.__name).toBe('TvView')
  })

  // ── Aggregate subtitle badge ──────────────────────────────────────────────────

  it('shows missing-subs aggregate badge for show with hasEpisodesMissingSubtitles=true', async () => {
    const w = mount(TvView, { global: { plugins: [createTestingPinia()],
      stubs: { PosterCard: PcStub, SearchFilter: true, SubtitleBadge: SubBadgeStub } } })
    await flushPromises()
    // Breaking Bad has hasEpisodesMissingSubtitles: true → should have an aggregate badge
    expect(w.find('[data-testid="missing-subs-badge"]').exists()).toBe(true)
  })

  it('only shows missing-subs aggregate badge when flag is true, not for all shows', async () => {
    const w = mount(TvView, { global: { plugins: [createTestingPinia()],
      stubs: { PosterCard: PcStub, SearchFilter: true, SubtitleBadge: SubBadgeStub } } })
    await flushPromises()
    // Only 1 of 3 shows has the flag
    expect(w.findAll('[data-testid="missing-subs-badge"]')).toHaveLength(1)
  })
})
