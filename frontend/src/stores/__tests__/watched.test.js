import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useWatchedStore } from '../watched.js'

vi.mock('../../api/watched.js', () => ({
  getWatched:              vi.fn(),
  getSubscriptions:        vi.fn(),
  getSeasonSubscriptions:  vi.fn(),
  subscribe:               vi.fn(),
  unsubscribe:             vi.fn(),
  subscribeSeason:         vi.fn(),
  unsubscribeSeasonSub:    vi.fn(),
  syncNow:                 vi.fn(),
}))

import {
  getWatched, getSubscriptions, getSeasonSubscriptions,
  subscribe as apiSubscribe, unsubscribe as apiUnsubscribe,
  subscribeSeason as apiSubscribeSeason,
  unsubscribeSeasonSub as apiUnsubscribeSeasonSub,
  syncNow as apiSyncNow
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

  it('fetchSubscriptions populates show and season subscription maps', async () => {
    getSubscriptions.mockResolvedValue([
      { showId: 10, targetCount: 5, id: 1, updatedAt: '2026-01-01' }
    ])
    getSeasonSubscriptions.mockResolvedValue([
      { seasonId: 100, showId: 10, targetCount: 10, id: 2, updatedAt: '2026-01-01' }
    ])
    const store = useWatchedStore()
    await store.fetchSubscriptions()
    expect(store.getSubscription(10)).toBe(5)
    expect(store.getSubscription(99)).toBeNull()
    expect(store.getSeasonSubscription(100)).toBe(10)
    expect(store.getSeasonSubscription(999)).toBeNull()
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
    store.subscriptions.set(10, 5)
    await store.unsubscribe(10)
    expect(apiUnsubscribe).toHaveBeenCalledWith(10)
    expect(store.getSubscription(10)).toBeNull()
  })

  it('subscribeSeason calls API and updates seasonSubscriptions map', async () => {
    apiSubscribeSeason.mockResolvedValue({ seasonId: 100, showId: 10, targetCount: 5, id: 2, updatedAt: '2026-01-01' })
    const store = useWatchedStore()
    await store.subscribeSeason(100, 5)
    expect(apiSubscribeSeason).toHaveBeenCalledWith(100, 5)
    expect(store.getSeasonSubscription(100)).toBe(5)
  })

  it('unsubscribeSeason calls API and removes from map', async () => {
    apiUnsubscribeSeasonSub.mockResolvedValue({})
    const store = useWatchedStore()
    store.seasonSubscriptions.set(100, 5)
    await store.unsubscribeSeason(100)
    expect(apiUnsubscribeSeasonSub).toHaveBeenCalledWith(100)
    expect(store.getSeasonSubscription(100)).toBeNull()
  })

  it('syncNow updates watchedByShow', async () => {
    apiSyncNow.mockResolvedValue({ watchedEpisodeIds: [7, 8] })
    const store = useWatchedStore()
    await store.syncNow(10)
    expect(store.isWatched(10, 7)).toBe(true)
  })
})
