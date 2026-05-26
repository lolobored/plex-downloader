import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'
import { useDownloadStore } from '../../stores/download.js'
import QueueView from '../QueueView.vue'
import * as downloadApi from '../../api/download.js'
import * as playlistApi from '../../api/playlists.js'
import { useRouter } from 'vue-router'

vi.mock('../../api/download.js', () => ({
  getQueue:          vi.fn().mockResolvedValue([]),
  enqueue:           vi.fn().mockResolvedValue({}),
  removeQueueItem:   vi.fn().mockResolvedValue(undefined),
  refreshTdarrStatus:vi.fn().mockResolvedValue({}),
  retryQueueItem:    vi.fn().mockResolvedValue({})
}))
vi.mock('../../api/playlists.js', () => ({
  unsubscribe:           vi.fn().mockResolvedValue(undefined),
  getPlaylistQueueCount: vi.fn().mockResolvedValue(0)
}))
vi.mock('vue-router', () => ({
  useRouter: vi.fn(),
  useRoute:  vi.fn(() => ({ params: {} }))
}))

// Helper factories
function movieItem(overrides = {}) {
  return {
    id: 1, mediaType: 'MOVIE', mediaId: 10, title: 'Inception',
    status: 'PENDING', tdarrStatus: 'NONE', tdarrError: null,
    playlistId: null, playlistTitle: null,
    showId: null, seasonId: null, showTitle: null, seasonNumber: null,
    queuePosition: 1, requestedAt: '2026-01-01T00:00:00Z', completedAt: null,
    ...overrides
  }
}
function episodeItem(overrides = {}) {
  return {
    id: 2, mediaType: 'EPISODE', mediaId: 99, title: 'Breaking Bad S01E01 - Pilot',
    status: 'PENDING', tdarrStatus: 'NONE', tdarrError: null,
    playlistId: null, playlistTitle: null,
    showId: 50, seasonId: 100, showTitle: 'Breaking Bad', seasonNumber: 1,
    queuePosition: 2, requestedAt: '2026-01-01T00:00:00Z', completedAt: null,
    ...overrides
  }
}

describe('QueueView', () => {
  let pushMock

  beforeEach(() => {
    pushMock = vi.fn()
    vi.mocked(useRouter).mockReturnValue({ push: pushMock })
    vi.clearAllMocks()
  })

  function factory(items = []) {
    const pinia = createTestingPinia({ createSpy: vi.fn })
    const store = useDownloadStore(pinia)
    store.queueItems = items
    store.fetchQueue = vi.fn()
    return { wrapper: mount(QueueView, { global: { plugins: [pinia] } }), store }
  }

  // ── Empty / basic ────────────────────────────────────────────────────────────

  it('shows empty state when queue is empty', () => {
    const { wrapper } = factory([])
    expect(wrapper.text()).toContain('Queue is empty')
  })

  it('fetchQueue called on mount', () => {
    const { store } = factory()
    expect(store.fetchQueue).toHaveBeenCalled()
  })

  // ── Count badge ──────────────────────────────────────────────────────────────

  it('count badge hidden when queue is empty', () => {
    const { wrapper } = factory([])
    expect(wrapper.find('[data-testid="count-badge"]').exists()).toBe(false)
  })

  it('count badge shows total visible items', () => {
    const { wrapper } = factory([movieItem(), episodeItem()])
    expect(wrapper.find('[data-testid="count-badge"]').text()).toBe('2')
  })

  // ── Subscribed Playlists section ─────────────────────────────────────────────

  it('playlist section hidden when no playlist items', () => {
    const { wrapper } = factory([movieItem()])
    expect(wrapper.find('[data-testid="section-playlists"]').exists()).toBe(false)
  })

  it('playlist group renders with title', () => {
    const { wrapper } = factory([
      movieItem({ playlistId: 5, playlistTitle: 'Action Movies' })
    ])
    expect(wrapper.find('[data-testid="section-playlists"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('Action Movies')
  })

  it('playlist group starts collapsed — no queue-item-rows visible', () => {
    const { wrapper } = factory([
      movieItem({ playlistId: 5, playlistTitle: 'Action Movies' })
    ])
    expect(wrapper.findAll('[data-testid="queue-item-row"]')).toHaveLength(0)
  })

  it('click playlist group header expands items', async () => {
    const { wrapper } = factory([
      movieItem({ playlistId: 5, playlistTitle: 'Action Movies' })
    ])
    await wrapper.find('[data-testid="group-header-playlist-5"]').trigger('click')
    expect(wrapper.findAll('[data-testid="queue-item-row"]')).toHaveLength(1)
  })

  it('unsubscribe button present on playlist group header', () => {
    const { wrapper } = factory([
      movieItem({ playlistId: 5, playlistTitle: 'Action Movies' })
    ])
    expect(wrapper.find('[data-testid="unsub-btn-5"]').exists()).toBe(true)
  })

  it('unsubscribe button click does not expand group', async () => {
    const { wrapper } = factory([
      movieItem({ playlistId: 5, playlistTitle: 'Action Movies' })
    ])
    await wrapper.find('[data-testid="unsub-btn-5"]').trigger('click')
    await flushPromises()
    // group should remain collapsed
    expect(wrapper.findAll('[data-testid="queue-item-row"]')).toHaveLength(0)
  })

  // ── Individual Downloads section ─────────────────────────────────────────────

  it('individual downloads section hidden when all items have playlistId', () => {
    const { wrapper } = factory([
      movieItem({ playlistId: 5, playlistTitle: 'Action' })
    ])
    expect(wrapper.find('[data-testid="section-individual"]').exists()).toBe(false)
  })

  it('show group renders for episodes without playlistId', () => {
    const { wrapper } = factory([episodeItem()])
    expect(wrapper.find('[data-testid="section-individual"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('Breaking Bad')
  })

  it('show group starts collapsed', () => {
    const { wrapper } = factory([episodeItem()])
    expect(wrapper.findAll('[data-testid="queue-item-row"]')).toHaveLength(0)
  })

  it('click show group header expands seasons', async () => {
    const { wrapper } = factory([episodeItem()])
    await wrapper.find('[data-testid="group-header-show-50"]').trigger('click')
    // Season sub-group should now be visible (but still collapsed itself)
    expect(wrapper.find('[data-testid="group-header-season-100"]').exists()).toBe(true)
  })

  it('click season header expands episodes', async () => {
    const { wrapper } = factory([episodeItem()])
    await wrapper.find('[data-testid="group-header-show-50"]').trigger('click')
    await wrapper.find('[data-testid="group-header-season-100"]').trigger('click')
    expect(wrapper.findAll('[data-testid="queue-item-row"]')).toHaveLength(1)
  })

  it('solo movie renders flat in individual downloads', () => {
    const { wrapper } = factory([movieItem()])
    expect(wrapper.find('[data-testid="section-individual"]').exists()).toBe(true)
    // Movie renders directly (always visible, no group header)
    expect(wrapper.findAll('[data-testid="queue-item-row"]')).toHaveLength(1)
  })

  // ── Status sections within expanded group ────────────────────────────────────

  it('IN_PROGRESS items appear in "In Progress" sub-section', async () => {
    const { wrapper } = factory([
      movieItem({ playlistId: 5, playlistTitle: 'P', status: 'IN_PROGRESS' })
    ])
    await wrapper.find('[data-testid="group-header-playlist-5"]').trigger('click')
    expect(wrapper.find('[data-testid="sub-label-IN_PROGRESS"]').exists()).toBe(true)
  })

  it('DONE and TRANSCODED items both appear in Done sub-section', async () => {
    const { wrapper } = factory([
      movieItem({ id: 1, playlistId: 5, playlistTitle: 'P', status: 'DONE', tdarrStatus: 'NONE', title: 'Film A' }),
      movieItem({ id: 2, playlistId: 5, playlistTitle: 'P', mediaId: 11, status: 'DONE', tdarrStatus: 'TRANSCODED', title: 'Film B' }),
    ])
    await wrapper.find('[data-testid="group-header-playlist-5"]').trigger('click')
    expect(wrapper.find('[data-testid="sub-label-DONE"]').exists()).toBe(true)
    expect(wrapper.findAll('[data-testid="queue-item-row"]')).toHaveLength(2)
    // No TRANSCODED sub-section (both are in Done)
    expect(wrapper.find('[data-testid="sub-label-TRANSCODED"]').exists()).toBe(false)
  })

  it('TDARR_ERROR item shows retry button inside Done section', async () => {
    const { wrapper } = factory([
      movieItem({ id: 1, playlistId: 5, playlistTitle: 'P', status: 'ERROR',
                  tdarrStatus: 'TDARR_ERROR', tdarrError: 'codec error' })
    ])
    await wrapper.find('[data-testid="group-header-playlist-5"]').trigger('click')
    expect(wrapper.find('[data-testid="retry-btn"]').exists()).toBe(true)
  })

  // ── Filter bar ───────────────────────────────────────────────────────────────

  it('filter bar has 4 status chips (no TRANSCODING/TRANSCODED)', () => {
    const { wrapper } = factory([])
    expect(wrapper.find('[data-testid="chip-status-PENDING"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="chip-status-COPYING"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="chip-status-DONE"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="chip-status-ERROR"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="chip-status-TRANSCODING"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="chip-status-TRANSCODED"]').exists()).toBe(false)
  })

  it('MOVIE type chip hides show groups', async () => {
    const { wrapper } = factory([
      movieItem(),
      episodeItem({ id: 3, mediaId: 100 })
    ])
    await wrapper.find('[data-testid="chip-type-MOVIE"]').trigger('click')
    // show group gone, movie still there
    expect(wrapper.find('[data-testid="section-individual"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="group-header-show-50"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="queue-item-row"]').exists()).toBe(true)
  })

  it('text filter hides non-matching items and empty groups', async () => {
    const { wrapper } = factory([
      movieItem({ title: 'Inception' }),
      movieItem({ id: 3, mediaId: 11, title: 'The Matrix' }),
    ])
    await wrapper.find('[data-testid="filter-search"]').setValue('incep')
    expect(wrapper.text()).toContain('Inception')
    expect(wrapper.text()).not.toContain('The Matrix')
    expect(wrapper.find('[data-testid="count-badge"]').text()).toBe('1')
  })

  it('count badge reflects filtered count', async () => {
    const { wrapper } = factory([
      movieItem({ id: 1, title: 'A' }),
      movieItem({ id: 2, mediaId: 11, title: 'B', status: 'DONE', tdarrStatus: 'NONE',
                  completedAt: '2026-01-01T01:00:00Z' })
    ])
    await wrapper.find('[data-testid="chip-status-DONE"]').trigger('click')
    expect(wrapper.find('[data-testid="count-badge"]').text()).toBe('1')
  })

  // ── Item actions ─────────────────────────────────────────────────────────────

  it('remove button on solo movie calls removeQueueItem', async () => {
    const { wrapper, store } = factory([movieItem()])
    await wrapper.find('[data-testid="remove-btn-1"]').trigger('click')
    await flushPromises()
    expect(downloadApi.removeQueueItem).toHaveBeenCalledWith(1)
    expect(store.fetchQueue).toHaveBeenCalled()
  })

  it('clicking movie row navigates to movie detail', async () => {
    const { wrapper } = factory([movieItem()])
    await wrapper.find('[data-testid="queue-item-row"]').trigger('click')
    expect(pushMock).toHaveBeenCalledWith('/movies/10')
  })

  it('clicking episode row navigates to episode detail', async () => {
    const { wrapper } = factory([episodeItem()])
    // expand show → season to reveal row
    await wrapper.find('[data-testid="group-header-show-50"]').trigger('click')
    await wrapper.find('[data-testid="group-header-season-100"]').trigger('click')
    await wrapper.find('[data-testid="queue-item-row"]').trigger('click')
    expect(pushMock).toHaveBeenCalledWith('/tv/50/seasons/100/episodes/99')
  })

  it('remove button click does not navigate', async () => {
    const { wrapper } = factory([movieItem()])
    await wrapper.find('[data-testid="remove-btn-1"]').trigger('click')
    expect(pushMock).not.toHaveBeenCalled()
  })
})
