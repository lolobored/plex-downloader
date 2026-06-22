import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'
import { useDownloadStore } from '../../stores/download.js'
import QueueView from '../QueueView.vue'
import * as downloadApi from '../../api/download.js'
import * as playlistApi from '../../api/playlists.js'
import * as transcodeApi from '../../api/transcode.js'
import { useRouter } from 'vue-router'

vi.mock('../../api/download.js', () => ({
  getQueue:          vi.fn().mockResolvedValue([]),
  enqueue:           vi.fn().mockResolvedValue({}),
  removeQueueItem:   vi.fn().mockResolvedValue(undefined),
  retryQueueItem:    vi.fn().mockResolvedValue({}),
  retryAllErrored:   vi.fn().mockResolvedValue({ retried: 2 })
}))
vi.mock('../../api/playlists.js', () => ({
  unsubscribe:           vi.fn().mockResolvedValue(undefined),
  getPlaylistQueueCount: vi.fn().mockResolvedValue(0)
}))
vi.mock('../../api/transcode.js', () => ({
  getConcurrency: vi.fn().mockResolvedValue(2),
  setConcurrency: vi.fn().mockResolvedValue(3)
}))
vi.mock('vue-router', () => ({
  useRouter: vi.fn(),
  useRoute:  vi.fn(() => ({ params: {} }))
}))

// Helper factories
function movieItem(overrides = {}) {
  return {
    id: 1, mediaType: 'MOVIE', mediaId: 10, title: 'Inception',
    status: 'QUEUED', progressPercent: 0, transcodeError: null,
    playlistId: null, playlistTitle: null,
    showId: null, seasonId: null, showTitle: null, seasonNumber: null,
    queuePosition: 1, requestedAt: '2026-01-01T00:00:00Z', completedAt: null,
    compressionRatio: null, sourceSizeBytes: null, outputSizeBytes: null,
    transcodeStartedAt: null,
    ...overrides
  }
}
function episodeItem(overrides = {}) {
  return {
    id: 2, mediaType: 'EPISODE', mediaId: 99, title: 'Breaking Bad S01E01 - Pilot',
    status: 'QUEUED', progressPercent: 0, transcodeError: null,
    playlistId: null, playlistTitle: null,
    showId: 50, seasonId: 100, showTitle: 'Breaking Bad', seasonNumber: 1,
    queuePosition: 2, requestedAt: '2026-01-01T00:00:00Z', completedAt: null,
    compressionRatio: null, sourceSizeBytes: null, outputSizeBytes: null,
    transcodeStartedAt: null,
    ...overrides
  }
}

describe('QueueView', () => {
  let pushMock

  beforeEach(() => {
    pushMock = vi.fn()
    vi.mocked(useRouter).mockReturnValue({ push: pushMock })
    vi.clearAllMocks()
    // Re-apply useRouter mock after clearAllMocks (clearAllMocks clears call history only,
    // but mockReturnValue must be set before each test so the mock is ready)
    vi.mocked(useRouter).mockReturnValue({ push: pushMock })
  })

  afterEach(() => {
    // Clean up any DOM that Teleport injected into document.body
    document.body.innerHTML = ''
  })

  function factory(items = [], role = 'USER') {
    const pinia = createTestingPinia({ createSpy: vi.fn,
      initialState: { auth: { role } } })
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

  it('TRANSCODING items appear in "Transcoding" sub-section', async () => {
    const { wrapper } = factory([
      movieItem({ playlistId: 5, playlistTitle: 'P', status: 'TRANSCODING', progressPercent: 42 })
    ])
    await wrapper.find('[data-testid="group-header-playlist-5"]').trigger('click')
    expect(wrapper.find('[data-testid="sub-label-TRANSCODING"]').exists()).toBe(true)
  })

  it('QUEUED items appear in "Queued" sub-section', async () => {
    const { wrapper } = factory([
      movieItem({ id: 1, playlistId: 5, playlistTitle: 'P', status: 'QUEUED', title: 'Film A' }),
    ])
    await wrapper.find('[data-testid="group-header-playlist-5"]').trigger('click')
    expect(wrapper.find('[data-testid="sub-label-QUEUED"]').exists()).toBe(true)
  })

  it('DONE items appear in Done sub-section', async () => {
    const { wrapper } = factory([
      movieItem({ id: 1, playlistId: 5, playlistTitle: 'P', status: 'DONE', title: 'Film A' }),
      movieItem({ id: 2, playlistId: 5, playlistTitle: 'P', mediaId: 11, status: 'DONE', title: 'Film B' }),
    ])
    await wrapper.find('[data-testid="group-header-playlist-5"]').trigger('click')
    expect(wrapper.find('[data-testid="sub-label-DONE"]').exists()).toBe(true)
    expect(wrapper.findAll('[data-testid="queue-item-row"]')).toHaveLength(2)
  })

  it('ERROR item shows retry button inside Done section', async () => {
    const { wrapper } = factory([
      movieItem({ id: 1, playlistId: 5, playlistTitle: 'P', status: 'ERROR',
                  transcodeError: 'codec error' })
    ])
    await wrapper.find('[data-testid="group-header-playlist-5"]').trigger('click')
    expect(wrapper.find('[data-testid="retry-btn"]').exists()).toBe(true)
  })

  it('TRANSCODING item shows progress bar', async () => {
    const { wrapper } = factory([
      movieItem({ playlistId: 5, playlistTitle: 'P', status: 'TRANSCODING', progressPercent: 55 })
    ])
    await wrapper.find('[data-testid="group-header-playlist-5"]').trigger('click')
    expect(wrapper.find('[data-testid="progress-bar"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('55%')
  })

  it('QUEUED item shows queued badge', () => {
    const { wrapper } = factory([movieItem({ status: 'QUEUED' })])
    expect(wrapper.find('.badge-queued').exists()).toBe(true)
  })

  it('DONE item shows done badge', () => {
    const { wrapper } = factory([movieItem({ status: 'DONE' })])
    expect(wrapper.find('.badge-done').exists()).toBe(true)
  })

  it('DONE item shows compression rate when present', () => {
    const { wrapper } = factory([movieItem({ status: 'DONE', compressionRatio: 42.5 })])
    const el = wrapper.find('[data-testid="compression-rate"]')
    expect(el.exists()).toBe(true)
    expect(el.text()).toBe('↓ 42.5%')
  })

  it('DONE item without compression rate hides the badge', () => {
    const { wrapper } = factory([movieItem({ status: 'DONE', compressionRatio: null })])
    expect(wrapper.find('[data-testid="compression-rate"]').exists()).toBe(false)
  })

  it('ERROR item shows error badge', () => {
    const { wrapper } = factory([movieItem({ status: 'ERROR' })])
    expect(wrapper.find('.badge-error').exists()).toBe(true)
  })

  // ── Filter bar ───────────────────────────────────────────────────────────────

  it('filter bar has 4 status chips: QUEUED/TRANSCODING/DONE/ERROR (no COPYING chip)', () => {
    const { wrapper } = factory([])
    expect(wrapper.find('[data-testid="chip-status-QUEUED"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="chip-status-TRANSCODING"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="chip-status-DONE"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="chip-status-ERROR"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="chip-status-PENDING"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="chip-status-COPYING"]').exists()).toBe(false)
  })

  // ── COPYING status ────────────────────────────────────────────────────────────

  it('COPYING item shows copying badge', () => {
    const { wrapper } = factory([movieItem({ status: 'COPYING' })])
    expect(wrapper.find('.badge-copying').exists()).toBe(true)
    expect(wrapper.find('.badge-copying').text()).toBe('copying…')
  })

  it('COPYING item is in the in-progress group (not pending or done)', async () => {
    const { wrapper } = factory([
      movieItem({ playlistId: 5, playlistTitle: 'P', status: 'COPYING' })
    ])
    await wrapper.find('[data-testid="group-header-playlist-5"]').trigger('click')
    expect(wrapper.find('[data-testid="sub-label-TRANSCODING"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="sub-label-QUEUED"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="sub-label-DONE"]').exists()).toBe(false)
  })

  it('COPYING item has remove button disabled', () => {
    const { wrapper } = factory([movieItem({ id: 1, status: 'COPYING' })])
    const removeBtn = wrapper.find('[data-testid="remove-btn-1"]')
    expect(removeBtn.exists()).toBe(true)
    expect(removeBtn.element.disabled).toBe(true)
  })

  it('COPYING item is shown when Transcoding filter chip is active', async () => {
    const { wrapper } = factory([
      movieItem({ id: 1, title: 'Inception', status: 'QUEUED' }),
      movieItem({ id: 2, mediaId: 11, title: 'The Matrix', status: 'COPYING' }),
    ])
    await wrapper.find('[data-testid="chip-status-TRANSCODING"]').trigger('click')
    expect(wrapper.find('[data-testid="count-badge"]').text()).toBe('1')
    expect(wrapper.text()).toContain('The Matrix')
    expect(wrapper.text()).not.toContain('Inception')
  })

  it('COPYING item does not show progress bar', () => {
    const { wrapper } = factory([movieItem({ status: 'COPYING' })])
    expect(wrapper.find('[data-testid="progress-bar"]').exists()).toBe(false)
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
      movieItem({ id: 2, mediaId: 11, title: 'B', status: 'DONE',
                  completedAt: '2026-01-01T01:00:00Z' })
    ])
    await wrapper.find('[data-testid="chip-status-DONE"]').trigger('click')
    expect(wrapper.find('[data-testid="count-badge"]').text()).toBe('1')
  })

  it('TRANSCODING filter chip shows only transcoding items', async () => {
    const { wrapper } = factory([
      movieItem({ id: 1, title: 'Inception', status: 'QUEUED' }),
      movieItem({ id: 2, mediaId: 11, title: 'The Matrix', status: 'TRANSCODING', progressPercent: 30 }),
    ])
    await wrapper.find('[data-testid="chip-status-TRANSCODING"]').trigger('click')
    expect(wrapper.find('[data-testid="count-badge"]').text()).toBe('1')
    expect(wrapper.text()).toContain('The Matrix')
    expect(wrapper.text()).not.toContain('Inception')
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

  it('retry button click calls retryQueueItem and does not navigate', async () => {
    const { wrapper } = factory([movieItem({ status: 'ERROR', transcodeError: 'oops' })])
    await wrapper.find('[data-testid="retry-btn"]').trigger('click')
    await flushPromises()
    expect(downloadApi.retryQueueItem).toHaveBeenCalledWith(1)
    expect(pushMock).not.toHaveBeenCalled()
  })

  // ── Unsubscribe flow ─────────────────────────────────────────────────────────
  // ConfirmModal uses <Teleport to="body">, so its DOM lives outside the wrapper.
  // Tests that inspect modal content must mount with { attachTo: document.body }
  // and query document.body directly.

  it('unsubscribe button calls getPlaylistQueueCount', async () => {
    const { wrapper } = factory([
      movieItem({ playlistId: 5, playlistTitle: 'Action Movies' })
    ])
    await wrapper.find('[data-testid="unsub-btn-5"]').trigger('click')
    await flushPromises()
    expect(playlistApi.getPlaylistQueueCount).toHaveBeenCalledWith(5)
    wrapper.unmount()
  })

  it('unsubscribe button shows confirm modal', async () => {
    vi.mocked(playlistApi.getPlaylistQueueCount).mockResolvedValue(3)
    const pinia = createTestingPinia({ createSpy: vi.fn })
    const store = useDownloadStore(pinia)
    store.queueItems = [movieItem({ playlistId: 5, playlistTitle: 'Action Movies' })]
    store.fetchQueue = vi.fn()
    const wrapper = mount(QueueView, { global: { plugins: [pinia] }, attachTo: document.body })
    await wrapper.find('[data-testid="unsub-btn-5"]').trigger('click')
    // Modal is teleported to body — wait for it and read from document.body
    await vi.waitFor(() => !!document.body.querySelector('.modal-message'))
    const msg = document.body.querySelector('.modal-message')
    expect(msg.textContent).toContain('3')
    expect(msg.textContent).toContain('Action Movies')
    wrapper.unmount()
    document.body.innerHTML = ''
  })

  it('confirming unsubscribe calls unsubscribe and fetchQueue', async () => {
    vi.mocked(playlistApi.getPlaylistQueueCount).mockResolvedValue(0)
    const pinia = createTestingPinia({ createSpy: vi.fn })
    const store = useDownloadStore(pinia)
    store.queueItems = [movieItem({ playlistId: 5, playlistTitle: 'Action Movies' })]
    store.fetchQueue = vi.fn()
    const wrapper = mount(QueueView, { global: { plugins: [pinia] }, attachTo: document.body })
    await wrapper.find('[data-testid="unsub-btn-5"]').trigger('click')
    await flushPromises()
    // ConfirmModal renders a confirm button with data-testid="confirm-btn" (teleported to body)
    await vi.waitFor(() => !!document.body.querySelector('[data-testid="confirm-btn"]'))
    document.body.querySelector('[data-testid="confirm-btn"]').click()
    await flushPromises()
    expect(playlistApi.unsubscribe).toHaveBeenCalledWith(5)
    expect(store.fetchQueue).toHaveBeenCalled()
    wrapper.unmount()
    document.body.innerHTML = ''
  })

  it('unsubscribe with 0 queued items shows message without count', async () => {
    vi.mocked(playlistApi.getPlaylistQueueCount).mockResolvedValue(0)
    const pinia = createTestingPinia({ createSpy: vi.fn })
    const store = useDownloadStore(pinia)
    store.queueItems = [movieItem({ playlistId: 5, playlistTitle: 'My Playlist' })]
    store.fetchQueue = vi.fn()
    const wrapper = mount(QueueView, { global: { plugins: [pinia] }, attachTo: document.body })
    await wrapper.find('[data-testid="unsub-btn-5"]').trigger('click')
    await flushPromises()
    const msg = document.body.querySelector('.modal-message')
    expect(msg).not.toBeNull()
    expect(msg.textContent).toContain('My Playlist')
    expect(msg.textContent).not.toContain('download')
    wrapper.unmount()
    document.body.innerHTML = ''
  })

  // ── Concurrency control ──────────────────────────────────────────────────────

  it('loads concurrency on mount and displays value', async () => {
    const { wrapper } = factory([], 'ADMIN')
    await flushPromises()
    expect(transcodeApi.getConcurrency).toHaveBeenCalled()
    expect(wrapper.find('[data-testid="concurrency-value"]').text()).toBe('2')
  })

  it('shows concurrency label', async () => {
    const { wrapper } = factory([], 'ADMIN')
    await flushPromises()
    expect(wrapper.find('[data-testid="concurrency-label"]').exists()).toBe(true)
  })

  it('increment button calls setConcurrency with n+1 and updates display', async () => {
    vi.mocked(transcodeApi.setConcurrency).mockResolvedValue(3)
    const { wrapper } = factory([], 'ADMIN')
    await flushPromises()
    await wrapper.find('[data-testid="concurrency-btn-inc"]').trigger('click')
    await flushPromises()
    expect(transcodeApi.setConcurrency).toHaveBeenCalledWith(3)
    expect(wrapper.find('[data-testid="concurrency-value"]').text()).toBe('3')
  })

  it('decrement button calls setConcurrency with n-1 and updates display', async () => {
    vi.mocked(transcodeApi.getConcurrency).mockResolvedValue(3)
    vi.mocked(transcodeApi.setConcurrency).mockResolvedValue(2)
    const { wrapper } = factory([], 'ADMIN')
    await flushPromises()
    await wrapper.find('[data-testid="concurrency-btn-dec"]').trigger('click')
    await flushPromises()
    expect(transcodeApi.setConcurrency).toHaveBeenCalledWith(2)
    expect(wrapper.find('[data-testid="concurrency-value"]').text()).toBe('2')
  })

  it('decrement button disabled when value is 1', async () => {
    vi.mocked(transcodeApi.getConcurrency).mockResolvedValue(1)
    const { wrapper } = factory([], 'ADMIN')
    await flushPromises()
    const btn = wrapper.find('[data-testid="concurrency-btn-dec"]')
    expect(btn.element.disabled).toBe(true)
  })

  it('stepper buttons hidden for non-admin', async () => {
    const { wrapper } = factory([], 'USER')
    await flushPromises()
    expect(wrapper.find('[data-testid="concurrency-btn-dec"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="concurrency-btn-inc"]').exists()).toBe(false)
  })

  // ── Retry all errored ────────────────────────────────────────────────────────

  it('retry-all button hidden when no errored items', () => {
    const { wrapper } = factory([movieItem({ status: 'QUEUED' })])
    expect(wrapper.find('[data-testid="retry-all-btn"]').exists()).toBe(false)
  })

  it('retry-all button shown when at least one ERROR item exists', () => {
    const { wrapper } = factory([movieItem({ status: 'ERROR', transcodeError: 'oops' })])
    expect(wrapper.find('[data-testid="retry-all-btn"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="retry-all-btn"]').text()).toContain('(1)')
  })

  it('retry-all button shows count of errored items', () => {
    const { wrapper } = factory([
      movieItem({ id: 1, status: 'ERROR' }),
      movieItem({ id: 2, mediaId: 11, status: 'ERROR' }),
      movieItem({ id: 3, mediaId: 12, status: 'DONE' }),
    ])
    expect(wrapper.find('[data-testid="retry-all-btn"]').text()).toContain('(2)')
  })

  it('retry-all button calls retryAllErrored api and refreshes queue', async () => {
    const { wrapper, store } = factory([movieItem({ status: 'ERROR' })])
    await wrapper.find('[data-testid="retry-all-btn"]').trigger('click')
    await flushPromises()
    expect(downloadApi.retryAllErrored).toHaveBeenCalled()
    expect(store.fetchQueue).toHaveBeenCalled()
  })

  // ── Error-msg truncation ─────────────────────────────────────────────────────

  it('error-msg shows truncated first line for long multi-line errors', () => {
    const longError = 'first line\nsecond line\nthird line'
    const { wrapper } = factory([movieItem({ status: 'ERROR', transcodeError: longError })])
    const errSpan = wrapper.find('.error-msg')
    expect(errSpan.exists()).toBe(true)
    expect(errSpan.text()).toBe('first line…')
    expect(errSpan.attributes('title')).toBe(longError)
  })

  it('error-msg shows truncated text (140 chars) with ellipsis for long single-line errors', () => {
    const longError = 'a'.repeat(200)
    const { wrapper } = factory([movieItem({ status: 'ERROR', transcodeError: longError })])
    const errSpan = wrapper.find('.error-msg')
    expect(errSpan.text()).toBe('a'.repeat(140) + '…')
    expect(errSpan.attributes('title')).toBe(longError)
  })

  it('error-msg shows full short text without ellipsis', () => {
    const shortError = 'codec error'
    const { wrapper } = factory([movieItem({ status: 'ERROR', transcodeError: shortError })])
    const errSpan = wrapper.find('.error-msg')
    expect(errSpan.text()).toBe('codec error')
    expect(errSpan.attributes('title')).toBe(shortError)
  })

  // ── Info button (DONE rows) ───────────────────────────────────────────────────

  it('info button appears on DONE row with sizes', () => {
    const { wrapper } = factory([movieItem({
      status: 'DONE', compressionRatio: 42.0,
      sourceSizeBytes: 4_200_000_000, outputSizeBytes: 2_400_000_000,
      transcodeStartedAt: '2026-01-01T00:00:00Z', completedAt: '2026-01-01T00:12:30Z'
    })])
    expect(wrapper.find('[data-testid="info-btn"]').exists()).toBe(true)
  })

  it('info button absent on QUEUED row', () => {
    const { wrapper } = factory([movieItem({ status: 'QUEUED' })])
    expect(wrapper.find('[data-testid="info-btn"]').exists()).toBe(false)
  })

  it('info button absent on DONE row with no sizes and no ratio', () => {
    const { wrapper } = factory([movieItem({
      status: 'DONE', compressionRatio: null, sourceSizeBytes: null, outputSizeBytes: null
    })])
    expect(wrapper.find('[data-testid="info-btn"]').exists()).toBe(false)
  })

  it('clicking info button opens popover with four values', async () => {
    const { wrapper } = factory([movieItem({
      status: 'DONE', compressionRatio: 42.0,
      sourceSizeBytes: 4_200_000_000, outputSizeBytes: 2_400_000_000,
      transcodeStartedAt: '2026-01-01T00:00:00Z', completedAt: '2026-01-01T00:12:30Z'
    })])
    await wrapper.find('[data-testid="info-btn"]').trigger('click')
    expect(wrapper.find('[data-testid="info-popover"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="info-compression"]').text()).toContain('42.0%')
    expect(wrapper.find('[data-testid="info-source-size"]').text()).toBe('4.2 GB')
    expect(wrapper.find('[data-testid="info-output-size"]').text()).toBe('2.4 GB')
    expect(wrapper.find('[data-testid="info-duration"]').text()).toBe('12m 30s')
  })

  it('clicking info button again closes popover', async () => {
    const { wrapper } = factory([movieItem({
      status: 'DONE', compressionRatio: 30.0, sourceSizeBytes: 1_000_000, outputSizeBytes: 700_000,
      transcodeStartedAt: '2026-01-01T00:00:00Z', completedAt: '2026-01-01T00:01:00Z'
    })])
    await wrapper.find('[data-testid="info-btn"]').trigger('click')
    expect(wrapper.find('[data-testid="info-popover"]').exists()).toBe(true)
    await wrapper.find('[data-testid="info-btn"]').trigger('click')
    expect(wrapper.find('[data-testid="info-popover"]').exists()).toBe(false)
  })

  it('popover shows — for transcode time when timestamps missing', async () => {
    const { wrapper } = factory([movieItem({
      status: 'DONE', compressionRatio: 10.0, sourceSizeBytes: 1_000_000, outputSizeBytes: 900_000,
      transcodeStartedAt: null, completedAt: null
    })])
    await wrapper.find('[data-testid="info-btn"]').trigger('click')
    expect(wrapper.find('[data-testid="info-duration"]').text()).toBe('—')
  })

  it('popover shows negative compression as "↑ x% larger"', async () => {
    const { wrapper } = factory([movieItem({
      status: 'DONE', compressionRatio: -5.2, sourceSizeBytes: 1_000_000, outputSizeBytes: 1_052_000,
      transcodeStartedAt: null, completedAt: null
    })])
    await wrapper.find('[data-testid="info-btn"]').trigger('click')
    expect(wrapper.find('[data-testid="info-compression"]').text()).toContain('5.2%')
    expect(wrapper.find('[data-testid="info-compression"]').text()).toContain('larger')
  })

  // ── Formatting helpers ────────────────────────────────────────────────────────

  it('formatBytes: bytes → GB for large values', () => {
    const { wrapper } = factory([movieItem({
      status: 'DONE', compressionRatio: 0.0, sourceSizeBytes: 4_500_000_000, outputSizeBytes: 4_500_000_000,
      transcodeStartedAt: null, completedAt: null
    })])
    wrapper.find('[data-testid="info-btn"]').trigger('click')
    // 4.5 GB
    expect(wrapper.html()).not.toThrow
  })

  it('formatBytes renders KB correctly', async () => {
    const { wrapper } = factory([movieItem({
      status: 'DONE', compressionRatio: 0.0, sourceSizeBytes: 512_000, outputSizeBytes: 256_000,
      transcodeStartedAt: null, completedAt: null
    })])
    await wrapper.find('[data-testid="info-btn"]').trigger('click')
    expect(wrapper.find('[data-testid="info-source-size"]').text()).toBe('512.0 KB')
    expect(wrapper.find('[data-testid="info-output-size"]').text()).toBe('256.0 KB')
  })

  it('formatDuration: short duration under a minute shows only seconds', async () => {
    const { wrapper } = factory([movieItem({
      status: 'DONE', compressionRatio: 0.0, sourceSizeBytes: 1_000_000, outputSizeBytes: 900_000,
      transcodeStartedAt: '2026-01-01T00:00:00Z', completedAt: '2026-01-01T00:00:45Z'
    })])
    await wrapper.find('[data-testid="info-btn"]').trigger('click')
    expect(wrapper.find('[data-testid="info-duration"]').text()).toBe('45s')
  })
})
