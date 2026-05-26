import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'
import { useDownloadStore } from '../../stores/download.js'
import QueueView from '../QueueView.vue'
import * as downloadApi from '../../api/download.js'
import { useRouter } from 'vue-router'

vi.mock('../../api/download.js', () => ({
  getQueue: vi.fn().mockResolvedValue([]),
  enqueue: vi.fn().mockResolvedValue({}),
  removeQueueItem: vi.fn().mockResolvedValue(undefined),
  refreshTdarrStatus: vi.fn().mockResolvedValue({}),
  retryQueueItem: vi.fn().mockResolvedValue({})
}))

vi.mock('vue-router', () => ({
  useRouter: vi.fn(),
  useRoute: vi.fn(() => ({ params: {} }))
}))

describe('QueueView', () => {
  let pushMock

  beforeEach(() => {
    pushMock = vi.fn()
    vi.mocked(useRouter).mockReturnValue({ push: pushMock })
  })

  function factory(items = []) {
    const pinia = createTestingPinia({ createSpy: vi.fn })
    const store = useDownloadStore(pinia)
    store.queueItems = items
    store.fetchQueue = vi.fn()
    return { wrapper: mount(QueueView, { global: { plugins: [pinia] } }), store }
  }

  it('shows empty state when queue is empty', () => {
    const { wrapper } = factory([])
    expect(wrapper.text()).toContain('Queue is empty')
  })

  it('shows IN_PROGRESS item in active section', () => {
    const { wrapper } = factory([
      { id: 1, mediaType: 'MOVIE', mediaId: 5, status: 'IN_PROGRESS',
        queuePosition: 1, requestedAt: '2026-05-24T10:00:00Z', completedAt: null }
    ])
    expect(wrapper.text()).toContain('In Progress')
    expect(wrapper.text()).toContain('MOVIE')
  })

  it('shows PENDING items in pending section', () => {
    const { wrapper } = factory([
      { id: 2, mediaType: 'EPISODE', mediaId: 7, status: 'PENDING',
        queuePosition: 2, requestedAt: '2026-05-24T10:01:00Z', completedAt: null }
    ])
    expect(wrapper.text()).toContain('Pending')
    expect(wrapper.text()).toContain('EPISODE')
  })

  it('fetchQueue called on mount', () => {
    const { store } = factory()
    expect(store.fetchQueue).toHaveBeenCalled()
  })

  it('shows NONE tdarr badge on DONE item', () => {
    const { wrapper } = factory([
      { id: 10, mediaType: 'MOVIE', mediaId: 5, status: 'DONE',
        tdarrStatus: 'NONE', tdarrError: null,
        queuePosition: 1, requestedAt: '2026-05-24T10:00:00Z', completedAt: '2026-05-24T10:05:00Z' }
    ])
    expect(wrapper.text()).toContain('Queued in Tdarr')
  })

  it('shows PROCESSING tdarr badge on DONE item', () => {
    const { wrapper } = factory([
      { id: 11, mediaType: 'MOVIE', mediaId: 5, status: 'DONE',
        tdarrStatus: 'PROCESSING', tdarrError: null,
        queuePosition: 1, requestedAt: '2026-05-24T10:00:00Z', completedAt: '2026-05-24T10:05:00Z' }
    ])
    expect(wrapper.text()).toContain('Transcoding…')
  })

  it('shows TRANSCODED tdarr badge on DONE item', () => {
    const { wrapper } = factory([
      { id: 12, mediaType: 'MOVIE', mediaId: 5, status: 'DONE',
        tdarrStatus: 'TRANSCODED', tdarrError: null,
        queuePosition: 1, requestedAt: '2026-05-24T10:00:00Z', completedAt: '2026-05-24T10:05:00Z' }
    ])
    expect(wrapper.text()).toContain('Transcoded ✓')
  })

  it('shows TDARR_ERROR badge with error message', () => {
    const { wrapper } = factory([
      { id: 13, mediaType: 'MOVIE', mediaId: 5, status: 'ERROR',
        tdarrStatus: 'TDARR_ERROR', tdarrError: 'codec not supported',
        queuePosition: 1, requestedAt: '2026-05-24T10:00:00Z', completedAt: '2026-05-24T10:05:00Z' }
    ])
    expect(wrapper.text()).toContain('Tdarr error')
    expect(wrapper.text()).toContain('codec not supported')
  })

  it('shows retry button for ERROR+TDARR_ERROR items', () => {
    const { wrapper } = factory([
      { id: 20, mediaType: 'MOVIE', mediaId: 5, status: 'ERROR',
        tdarrStatus: 'TDARR_ERROR', tdarrError: 'codec not supported',
        queuePosition: 1, requestedAt: '2026-05-24T10:00:00Z', completedAt: '2026-05-24T10:05:00Z' }
    ])
    expect(wrapper.find('[data-testid="retry-btn"]').exists()).toBe(true)
  })

  it('does not show retry button for DONE items', () => {
    const { wrapper } = factory([
      { id: 21, mediaType: 'MOVIE', mediaId: 5, status: 'DONE',
        tdarrStatus: 'TRANSCODED', tdarrError: null,
        queuePosition: 1, requestedAt: '2026-05-24T10:00:00Z', completedAt: '2026-05-24T10:05:00Z' }
    ])
    expect(wrapper.find('[data-testid="retry-btn"]').exists()).toBe(false)
  })

  it('clicking retry calls retryQueueItem and refreshes queue', async () => {
    downloadApi.retryQueueItem.mockResolvedValue({ id: 22, status: 'DONE', tdarrStatus: 'NONE' })

    const { wrapper, store } = factory([
      { id: 22, mediaType: 'MOVIE', mediaId: 5, status: 'ERROR',
        tdarrStatus: 'TDARR_ERROR', tdarrError: 'fail',
        queuePosition: 1, requestedAt: '2026-05-24T10:00:00Z', completedAt: '2026-05-24T10:05:00Z' }
    ])
    await wrapper.find('[data-testid="retry-btn"]').trigger('click')
    await flushPromises()
    expect(downloadApi.retryQueueItem).toHaveBeenCalledWith(22)
    expect(store.fetchQueue).toHaveBeenCalled()
  })

  it('shows remove button on each item', () => {
    const { wrapper } = factory([
      { id: 1, mediaType: 'MOVIE', mediaId: 5, status: 'DONE',
        tdarrStatus: 'NONE', tdarrError: null,
        queuePosition: 1, requestedAt: '2026-05-24T10:00:00Z', completedAt: '2026-05-24T10:05:00Z' }
    ])
    expect(wrapper.find('[data-testid="remove-btn-1"]').exists()).toBe(true)
  })

  it('remove button is disabled when IN_PROGRESS', () => {
    const { wrapper } = factory([
      { id: 2, mediaType: 'MOVIE', mediaId: 5, status: 'IN_PROGRESS',
        queuePosition: 1, requestedAt: '2026-05-24T10:00:00Z', completedAt: null }
    ])
    const btn = wrapper.find('[data-testid="remove-btn-2"]')
    expect(btn.element.disabled).toBe(true)
  })

  it('calls removeQueueItem and refreshes queue on click', async () => {
    const { wrapper, store } = factory([
      { id: 3, mediaType: 'MOVIE', mediaId: 5, status: 'DONE',
        tdarrStatus: 'TRANSCODED', tdarrError: null,
        queuePosition: 1, requestedAt: '2026-05-24T10:00:00Z', completedAt: '2026-05-24T10:05:00Z' }
    ])
    await wrapper.find('[data-testid="remove-btn-3"]').trigger('click')
    await flushPromises()
    expect(downloadApi.removeQueueItem).toHaveBeenCalledWith(3)
    expect(store.fetchQueue).toHaveBeenCalled()
  })

  // ── Filter bar ──────────────────────────────────────────────────────────────

  it('filter bar renders type chips, status chips, and search input', () => {
    const { wrapper } = factory([])
    expect(wrapper.find('[data-testid="chip-type-ALL"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="chip-type-MOVIE"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="chip-type-TV"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="chip-status-ERROR"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="chip-status-TRANSCODED"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="filter-search"]').exists()).toBe(true)
  })

  it('Movie chip filters out TV items', async () => {
    const { wrapper } = factory([
      { id: 1, mediaType: 'MOVIE', mediaId: 1, title: 'Inception', status: 'DONE',
        tdarrStatus: 'NONE', queuePosition: 1,
        requestedAt: '2026-01-01T00:00:00Z', completedAt: '2026-01-01T01:00:00Z' },
      { id: 2, mediaType: 'EPISODE', mediaId: 2, title: 'Pilot', status: 'DONE',
        tdarrStatus: 'NONE', queuePosition: 2,
        requestedAt: '2026-01-01T00:00:00Z', completedAt: '2026-01-01T01:00:00Z' }
    ])
    await wrapper.find('[data-testid="chip-type-MOVIE"]').trigger('click')
    expect(wrapper.text()).toContain('Inception')
    expect(wrapper.text()).not.toContain('Pilot')
  })

  it('TV chip filters out movie items', async () => {
    const { wrapper } = factory([
      { id: 1, mediaType: 'MOVIE', mediaId: 1, title: 'Inception', status: 'DONE',
        tdarrStatus: 'NONE', queuePosition: 1,
        requestedAt: '2026-01-01T00:00:00Z', completedAt: '2026-01-01T01:00:00Z' },
      { id: 2, mediaType: 'EPISODE', mediaId: 2, title: 'Pilot', status: 'DONE',
        tdarrStatus: 'NONE', queuePosition: 2,
        requestedAt: '2026-01-01T00:00:00Z', completedAt: '2026-01-01T01:00:00Z' }
    ])
    await wrapper.find('[data-testid="chip-type-TV"]').trigger('click')
    expect(wrapper.text()).not.toContain('Inception')
    expect(wrapper.text()).toContain('Pilot')
  })

  it('clicking active type chip resets filter and shows all', async () => {
    const { wrapper } = factory([
      { id: 1, mediaType: 'MOVIE', mediaId: 1, title: 'Inception', status: 'DONE',
        tdarrStatus: 'NONE', queuePosition: 1,
        requestedAt: '2026-01-01T00:00:00Z', completedAt: '2026-01-01T01:00:00Z' },
      { id: 2, mediaType: 'EPISODE', mediaId: 2, title: 'Pilot', status: 'DONE',
        tdarrStatus: 'NONE', queuePosition: 2,
        requestedAt: '2026-01-01T00:00:00Z', completedAt: '2026-01-01T01:00:00Z' }
    ])
    await wrapper.find('[data-testid="chip-type-MOVIE"]').trigger('click')
    await wrapper.find('[data-testid="chip-type-MOVIE"]').trigger('click') // toggle off
    expect(wrapper.text()).toContain('Inception')
    expect(wrapper.text()).toContain('Pilot')
  })

  it('active type chip has active class', async () => {
    const { wrapper } = factory([])
    const chip = wrapper.find('[data-testid="chip-type-MOVIE"]')
    await chip.trigger('click')
    expect(chip.classes()).toContain('active')
  })

  it('status chip ERROR hides non-error items', async () => {
    const { wrapper } = factory([
      { id: 1, mediaType: 'MOVIE', mediaId: 1, title: 'Good', status: 'DONE',
        tdarrStatus: 'TRANSCODED', queuePosition: 1,
        requestedAt: '2026-01-01T00:00:00Z', completedAt: '2026-01-01T01:00:00Z' },
      { id: 2, mediaType: 'MOVIE', mediaId: 2, title: 'Bad', status: 'ERROR',
        tdarrStatus: 'TDARR_ERROR', queuePosition: 2,
        requestedAt: '2026-01-01T00:00:00Z', completedAt: '2026-01-01T01:00:00Z' }
    ])
    await wrapper.find('[data-testid="chip-status-ERROR"]').trigger('click')
    expect(wrapper.text()).not.toContain('Good')
    expect(wrapper.text()).toContain('Bad')
  })

  it('status chips ERROR + TRANSCODED show both', async () => {
    const { wrapper } = factory([
      { id: 1, mediaType: 'MOVIE', mediaId: 1, title: 'AllDone', status: 'DONE',
        tdarrStatus: 'TRANSCODED', queuePosition: 1,
        requestedAt: '2026-01-01T00:00:00Z', completedAt: '2026-01-01T01:00:00Z' },
      { id: 2, mediaType: 'MOVIE', mediaId: 2, title: 'BadItem', status: 'ERROR',
        tdarrStatus: 'TDARR_ERROR', queuePosition: 2,
        requestedAt: '2026-01-01T00:00:00Z', completedAt: '2026-01-01T01:00:00Z' },
      { id: 3, mediaType: 'MOVIE', mediaId: 3, title: 'StillWaiting', status: 'PENDING',
        tdarrStatus: null, queuePosition: 3,
        requestedAt: '2026-01-01T00:00:00Z', completedAt: null }
    ])
    await wrapper.find('[data-testid="chip-status-ERROR"]').trigger('click')
    await wrapper.find('[data-testid="chip-status-TRANSCODED"]').trigger('click')
    expect(wrapper.text()).toContain('AllDone')
    expect(wrapper.text()).toContain('BadItem')
    expect(wrapper.text()).not.toContain('StillWaiting')
  })

  it('active status chip has active class', async () => {
    const { wrapper } = factory([])
    const chip = wrapper.find('[data-testid="chip-status-ERROR"]')
    await chip.trigger('click')
    expect(chip.classes()).toContain('active')
  })

  it('text filter matches title', async () => {
    const { wrapper } = factory([
      { id: 1, mediaType: 'MOVIE', mediaId: 1, title: 'Inception', status: 'DONE',
        tdarrStatus: 'NONE', queuePosition: 1,
        requestedAt: '2026-01-01T00:00:00Z', completedAt: '2026-01-01T01:00:00Z' },
      { id: 2, mediaType: 'MOVIE', mediaId: 2, title: 'Matrix', status: 'DONE',
        tdarrStatus: 'NONE', queuePosition: 2,
        requestedAt: '2026-01-01T00:00:00Z', completedAt: '2026-01-01T01:00:00Z' }
    ])
    await wrapper.find('[data-testid="filter-search"]').setValue('incep')
    expect(wrapper.text()).toContain('Inception')
    expect(wrapper.text()).not.toContain('Matrix')
  })

  it('text filter matches mediaType', async () => {
    const { wrapper } = factory([
      { id: 1, mediaType: 'MOVIE', mediaId: 1, title: 'Inception', status: 'DONE',
        tdarrStatus: 'NONE', queuePosition: 1,
        requestedAt: '2026-01-01T00:00:00Z', completedAt: '2026-01-01T01:00:00Z' },
      { id: 2, mediaType: 'EPISODE', mediaId: 2, title: 'Pilot', status: 'DONE',
        tdarrStatus: 'NONE', queuePosition: 2,
        requestedAt: '2026-01-01T00:00:00Z', completedAt: '2026-01-01T01:00:00Z' }
    ])
    await wrapper.find('[data-testid="filter-search"]').setValue('episode')
    expect(wrapper.text()).not.toContain('Inception')
    expect(wrapper.text()).toContain('Pilot')
  })

  it('text filter matches errorMessage', async () => {
    const { wrapper } = factory([
      { id: 1, mediaType: 'MOVIE', mediaId: 1, title: 'Bad', status: 'ERROR',
        errorMessage: 'disk full', tdarrStatus: 'NONE', queuePosition: 1,
        requestedAt: '2026-01-01T00:00:00Z', completedAt: '2026-01-01T01:00:00Z' },
      { id: 2, mediaType: 'MOVIE', mediaId: 2, title: 'Other', status: 'ERROR',
        errorMessage: 'network timeout', tdarrStatus: 'NONE', queuePosition: 2,
        requestedAt: '2026-01-01T00:00:00Z', completedAt: '2026-01-01T01:00:00Z' }
    ])
    await wrapper.find('[data-testid="filter-search"]').setValue('disk')
    expect(wrapper.text()).toContain('Bad')
    expect(wrapper.text()).not.toContain('Other')
  })

  it('text filter matches rendered fallback when title is absent', async () => {
    const { wrapper } = factory([
      { id: 1, mediaType: 'MOVIE', mediaId: 42, title: null, status: 'DONE',
        tdarrStatus: 'NONE', queuePosition: 1,
        requestedAt: '2026-01-01T00:00:00Z', completedAt: '2026-01-01T01:00:00Z' }
    ])
    await wrapper.find('[data-testid="filter-search"]').setValue('movie #42')
    expect(wrapper.text()).toContain('MOVIE #42')
  })

  it('text filter matches tdarrError field', async () => {
    const { wrapper } = factory([
      { id: 1, mediaType: 'MOVIE', mediaId: 1, title: 'Film', status: 'ERROR',
        tdarrStatus: 'TDARR_ERROR', tdarrError: 'codec unsupported', queuePosition: 1,
        requestedAt: '2026-01-01T00:00:00Z', completedAt: '2026-01-01T01:00:00Z' },
      { id: 2, mediaType: 'MOVIE', mediaId: 2, title: 'Other', status: 'ERROR',
        tdarrStatus: 'TDARR_ERROR', tdarrError: 'network error', queuePosition: 2,
        requestedAt: '2026-01-01T00:00:00Z', completedAt: '2026-01-01T01:00:00Z' }
    ])
    await wrapper.find('[data-testid="filter-search"]').setValue('codec')
    expect(wrapper.text()).toContain('Film')
    expect(wrapper.text()).not.toContain('Other')
  })

  it('toggling active status chip off restores items', async () => {
    const { wrapper } = factory([
      { id: 1, mediaType: 'MOVIE', mediaId: 1, title: 'Good', status: 'DONE',
        tdarrStatus: 'TRANSCODED', queuePosition: 1,
        requestedAt: '2026-01-01T00:00:00Z', completedAt: '2026-01-01T01:00:00Z' },
      { id: 2, mediaType: 'MOVIE', mediaId: 2, title: 'Bad', status: 'ERROR',
        tdarrStatus: 'TDARR_ERROR', queuePosition: 2,
        requestedAt: '2026-01-01T00:00:00Z', completedAt: '2026-01-01T01:00:00Z' }
    ])
    await wrapper.find('[data-testid="chip-status-ERROR"]').trigger('click')
    expect(wrapper.text()).not.toContain('Good')
    await wrapper.find('[data-testid="chip-status-ERROR"]').trigger('click') // toggle off
    expect(wrapper.text()).toContain('Good')
    expect(wrapper.text()).toContain('Bad')
  })

  it('DONE chip matches DONE item with null tdarrStatus', async () => {
    const { wrapper } = factory([
      { id: 1, mediaType: 'MOVIE', mediaId: 1, title: 'Legacy', status: 'DONE',
        tdarrStatus: null, queuePosition: 1,
        requestedAt: '2026-01-01T00:00:00Z', completedAt: '2026-01-01T01:00:00Z' },
      { id: 2, mediaType: 'MOVIE', mediaId: 2, title: 'Waiting', status: 'PENDING',
        tdarrStatus: null, queuePosition: 2,
        requestedAt: '2026-01-01T00:00:00Z', completedAt: null }
    ])
    await wrapper.find('[data-testid="chip-status-DONE"]').trigger('click')
    expect(wrapper.text()).toContain('Legacy')
    expect(wrapper.text()).not.toContain('Waiting')
  })

  // ── Navigation ───────────────────────────────────────────────────────────────

  it('clicking MOVIE item navigates to movie detail', async () => {
    const { wrapper } = factory([
      { id: 1, mediaType: 'MOVIE', mediaId: 42, title: 'Inception', status: 'PENDING',
        tdarrStatus: null, showId: null, seasonId: null,
        queuePosition: 1, requestedAt: '2026-01-01T00:00:00Z', completedAt: null }
    ])
    await flushPromises()

    await wrapper.find('[data-testid="queue-item-row"]').trigger('click')

    expect(pushMock).toHaveBeenCalledWith('/movies/42')
  })

  it('clicking EPISODE item navigates to episode detail', async () => {
    const { wrapper } = factory([
      { id: 1, mediaType: 'EPISODE', mediaId: 99, title: 'Pilot', status: 'PENDING',
        tdarrStatus: null, showId: 10, seasonId: 20,
        queuePosition: 1, requestedAt: '2026-01-01T00:00:00Z', completedAt: null }
    ])
    await flushPromises()

    await wrapper.find('[data-testid="queue-item-row"]').trigger('click')

    expect(pushMock).toHaveBeenCalledWith('/tv/10/seasons/20/episodes/99')
  })

  it('clicking remove button does not navigate', async () => {
    const { wrapper } = factory([
      { id: 1, mediaType: 'MOVIE', mediaId: 42, title: 'Inception', status: 'PENDING',
        tdarrStatus: null, showId: null, seasonId: null,
        queuePosition: 1, requestedAt: '2026-01-01T00:00:00Z', completedAt: null }
    ])
    await flushPromises()

    await wrapper.find('[data-testid="remove-btn-1"]').trigger('click')

    expect(pushMock).not.toHaveBeenCalled()
  })
})
