import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useDownloadStore } from '../download.js'

vi.mock('../../api/download.js', () => ({
  getQueue: vi.fn(),
  enqueue:  vi.fn()
}))
import { getQueue, enqueue } from '../../api/download.js'

describe('download store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('fetchQueue populates queueItems', async () => {
    getQueue.mockResolvedValue([
      { id: 1, mediaType: 'MOVIE', mediaId: 10, status: 'PENDING' }
    ])
    const store = useDownloadStore()
    await store.fetchQueue()
    expect(store.queueItems).toHaveLength(1)
    expect(store.queueItems[0].mediaId).toBe(10)
  })

  it('statusFor returns PENDING for queued item', async () => {
    getQueue.mockResolvedValue([
      { id: 1, mediaType: 'MOVIE', mediaId: 10, status: 'PENDING' }
    ])
    const store = useDownloadStore()
    await store.fetchQueue()
    expect(store.statusFor('MOVIE', 10)).toBe('PENDING')
  })

  it('statusFor returns null for unqueued item', async () => {
    getQueue.mockResolvedValue([])
    const store = useDownloadStore()
    await store.fetchQueue()
    expect(store.statusFor('MOVIE', 999)).toBeNull()
  })

  it('enqueue calls API and refreshes queue', async () => {
    enqueue.mockResolvedValue({ jobIds: [5], status: 'QUEUED' })
    getQueue.mockResolvedValue([{ id: 5, mediaType: 'MOVIE', mediaId: 10, status: 'PENDING' }])
    const store = useDownloadStore()
    await store.enqueue('MOVIE', 10)
    expect(enqueue).toHaveBeenCalledWith('MOVIE', 10)
    expect(getQueue).toHaveBeenCalled()
  })
})
