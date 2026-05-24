import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useWatchedStore } from '../watched.js'

vi.mock('../../api/watched.js', () => ({
  getWatched:       vi.fn(),
  getSubscriptions: vi.fn(),
  subscribe:        vi.fn(),
  unsubscribe:      vi.fn(),
  syncNow:          vi.fn(),
  enqueueUnwatched: vi.fn()
}))

import {
  getWatched, getSubscriptions, subscribe as apiSubscribe,
  unsubscribe as apiUnsubscribe, syncNow as apiSyncNow
} from '../../api/watched.js'

describe('watched store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('fetchWatched populates watchedByShow', async () => {
    getWatched.mockResolvedValue({ watchedEpisodeIds: [1, 2, 3] })
    const store = useWatchedStore()
    await store.fetchWatched(10)
    expect(store.isWatched(10, 1)).toBe(true)
    expect(store.isWatched(10, 99)).toBe(false)
  })

  it('fetchSubscriptions populates subscriptions map', async () => {
    getSubscriptions.mockResolvedValue([
      { showId: 10, targetCount: 5, id: 1, updatedAt: '2026-01-01' }
    ])
    const store = useWatchedStore()
    await store.fetchSubscriptions()
    expect(store.getSubscription(10)).toBe(5)
    expect(store.getSubscription(99)).toBeNull()
  })

  it('subscribe calls API and updates map', async () => {
    apiSubscribe.mockResolvedValue({ showId: 10, targetCount: 10, id: 1, updatedAt: '2026-01-01' })
    getWatched.mockResolvedValue({ watchedEpisodeIds: [] })
    const store = useWatchedStore()
    await store.subscribe(10, 10)
    expect(apiSubscribe).toHaveBeenCalledWith(10, 10)
    expect(store.getSubscription(10)).toBe(10)
  })

  it('unsubscribe calls API and removes from map', async () => {
    apiUnsubscribe.mockResolvedValue({})
    const store = useWatchedStore()
    store.subscriptions.set(10, 5) // seed
    await store.unsubscribe(10)
    expect(apiUnsubscribe).toHaveBeenCalledWith(10)
    expect(store.getSubscription(10)).toBeNull()
  })

  it('syncNow updates watchedByShow', async () => {
    apiSyncNow.mockResolvedValue({ watchedEpisodeIds: [7, 8] })
    const store = useWatchedStore()
    await store.syncNow(10)
    expect(store.isWatched(10, 7)).toBe(true)
  })
})
