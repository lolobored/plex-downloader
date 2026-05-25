import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'
import { useDownloadStore } from '../../stores/download.js'
import QueueView from '../QueueView.vue'
import * as downloadApi from '../../api/download.js'

vi.mock('../../api/download.js', () => ({
  getQueue: vi.fn().mockResolvedValue([]),
  enqueue: vi.fn().mockResolvedValue({}),
  removeQueueItem: vi.fn().mockResolvedValue(undefined),
  refreshTdarrStatus: vi.fn().mockResolvedValue({}),
  retryQueueItem: vi.fn().mockResolvedValue({})
}))

describe('QueueView', () => {
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
})
