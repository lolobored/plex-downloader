import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'
import { useWatchedStore } from '../../stores/watched.js'
import { useDownloadStore } from '../../stores/download.js'
import { getShowQueueCount, getSeasonQueueCount } from '../../api/watched.js'
import * as downloadApi from '../../api/download.js'
import SubscribeButton from '../SubscribeButton.vue'

vi.mock('../../api/watched.js', () => ({
  getShowQueueCount:   vi.fn(),
  getSeasonQueueCount: vi.fn()
}))
vi.mock('../../api/download.js', () => ({
  enqueue:            vi.fn().mockResolvedValue({ jobIds: [1], status: 'QUEUED' }),
  getQualityProfiles: vi.fn().mockResolvedValue([])
}))

describe('SubscribeButton — show context (no seasonId)', () => {
  afterEach(() => { document.body.innerHTML = '' })
  beforeEach(() => { vi.clearAllMocks() })

  function factory(subscription = null, profiles = []) {
    const pinia = createTestingPinia({ createSpy: vi.fn })
    const store = useWatchedStore(pinia)
    store.getSubscription       = vi.fn().mockReturnValue(subscription)
    store.getSeasonSubscription = vi.fn().mockReturnValue(null)
    store.subscribe             = vi.fn()
    store.unsubscribe           = vi.fn()
    store.subscribeSeason       = vi.fn()
    store.unsubscribeSeason     = vi.fn()
    const dlStore = useDownloadStore(pinia)
    dlStore.fetchProfiles = vi.fn()
    dlStore.profiles      = profiles
    dlStore.outputConfigured = true
    return { wrapper: mount(SubscribeButton, {
      props: { showId: 10 },
      global: { plugins: [pinia] },
      attachTo: document.body
    }), store }
  }

  it('shows Download when no subscription', () => {
    const { wrapper } = factory(null)
    expect(wrapper.text()).toContain('Download')
    expect(wrapper.text()).not.toContain('Next')
  })

  it('shows active subscription target', () => {
    const { wrapper } = factory(10)
    expect(wrapper.text()).toContain('Next 10')
  })

  it('opens picker on click', async () => {
    const { wrapper } = factory(null)
    await wrapper.find('button.sub-btn').trigger('click')
    expect(wrapper.find('.picker').exists()).toBe(true)
  })

  it('calls subscribe with chosen target', async () => {
    const { wrapper, store } = factory(null)
    await wrapper.find('button.sub-btn').trigger('click')
    await wrapper.findAll('button.picker-opt')[0].trigger('click')
    expect(store.subscribe).toHaveBeenCalledWith(10, 5)
  })

  it('download-all button calls enqueue with type SHOW', async () => {
    const { wrapper } = factory(null)
    await wrapper.find('button.sub-btn').trigger('click')
    const dlBtn = wrapper.find('[data-testid="download-all-btn"]')
    expect(dlBtn.exists()).toBe(true)
    await dlBtn.trigger('click')
    expect(downloadApi.enqueue).toHaveBeenCalledWith('SHOW', 10, null)
  })

  it('picker has exactly 4 subscribe opts + 1 download-all (no N-count one-time)', async () => {
    const { wrapper } = factory(null)
    await wrapper.find('button.sub-btn').trigger('click')
    const opts = wrapper.findAll('button.picker-opt')
    // 4 subscribe options + 1 download-all button = 5
    expect(opts.length).toBe(5)
  })

  it('shows confirm with show queue count on unsubscribe', async () => {
    getShowQueueCount.mockResolvedValue(3)
    const { wrapper } = factory(5)
    await wrapper.find('button.sub-btn').trigger('click')
    await wrapper.find('button.cancel-opt').trigger('click')
    await vi.waitFor(() => !!document.body.querySelector('.modal-backdrop'))
    const msg = document.body.querySelector('.modal-message').textContent
    expect(msg).toContain('3')
    expect(getShowQueueCount).toHaveBeenCalledWith(10)
  })

  it('calls unsubscribe(showId) after confirm', async () => {
    getShowQueueCount.mockResolvedValue(0)
    const { wrapper, store } = factory(5)
    await wrapper.find('button.sub-btn').trigger('click')
    await wrapper.find('button.cancel-opt').trigger('click')
    await vi.waitFor(() => !!document.body.querySelector('[data-testid="confirm-btn"]'))
    document.body.querySelector('[data-testid="confirm-btn"]').click()
    await wrapper.vm.$nextTick()
    expect(store.unsubscribe).toHaveBeenCalledWith(10)
  })

  it('does not call unsubscribe before modal confirm', async () => {
    getShowQueueCount.mockResolvedValue(3)
    const { wrapper, store } = factory(10)
    await wrapper.find('button.sub-btn').trigger('click')
    await wrapper.find('button.cancel-opt').trigger('click')
    await vi.waitFor(() => !!document.body.querySelector('.modal-backdrop'))
    expect(store.unsubscribe).not.toHaveBeenCalled()
  })

  it('does not unsubscribe when cancel clicked in modal', async () => {
    getShowQueueCount.mockResolvedValue(3)
    const { wrapper, store } = factory(10)
    await wrapper.find('button.sub-btn').trigger('click')
    await wrapper.find('button.cancel-opt').trigger('click')
    await vi.waitFor(() => !!document.body.querySelector('.btn-cancel'))
    document.body.querySelector('.btn-cancel').click()
    await wrapper.vm.$nextTick()
    expect(store.unsubscribe).not.toHaveBeenCalled()
  })

  it('sub-btn disabled with tooltip when outputConfigured false', () => {
    const pinia = createTestingPinia({ createSpy: vi.fn })
    const store = useWatchedStore(pinia)
    store.getSubscription = vi.fn().mockReturnValue(null)
    store.getSeasonSubscription = vi.fn().mockReturnValue(null)
    const dlStore = useDownloadStore(pinia)
    dlStore.fetchProfiles = vi.fn()
    dlStore.profiles = []
    dlStore.outputConfigured = false
    const wrapper = mount(SubscribeButton, {
      props: { showId: 10 },
      global: { plugins: [pinia] },
      attachTo: document.body
    })
    const btn = wrapper.find('button.sub-btn')
    expect(btn.attributes('disabled')).toBeDefined()
    expect(btn.attributes('title')).toContain('Settings')
  })

  it('sub-btn enabled when outputConfigured true', () => {
    const pinia = createTestingPinia({ createSpy: vi.fn })
    const store = useWatchedStore(pinia)
    store.getSubscription = vi.fn().mockReturnValue(null)
    store.getSeasonSubscription = vi.fn().mockReturnValue(null)
    const dlStore = useDownloadStore(pinia)
    dlStore.fetchProfiles = vi.fn()
    dlStore.profiles = []
    dlStore.outputConfigured = true
    const wrapper = mount(SubscribeButton, {
      props: { showId: 10 },
      global: { plugins: [pinia] },
      attachTo: document.body
    })
    const btn = wrapper.find('button.sub-btn')
    // Not disabled by outputConfigured (loading is false, outputConfigured is true)
    expect(btn.attributes('disabled')).toBeUndefined()
  })
})

describe('SubscribeButton — season context (with seasonId)', () => {
  afterEach(() => { document.body.innerHTML = '' })
  beforeEach(() => { vi.clearAllMocks() })

  function factory(seasonSubscription = null, profiles = []) {
    const pinia = createTestingPinia({ createSpy: vi.fn })
    const store = useWatchedStore(pinia)
    store.getSubscription       = vi.fn().mockReturnValue(null)
    store.getSeasonSubscription = vi.fn().mockReturnValue(seasonSubscription)
    store.subscribe             = vi.fn()
    store.unsubscribe           = vi.fn()
    store.subscribeSeason       = vi.fn()
    store.unsubscribeSeason     = vi.fn()
    const dlStore = useDownloadStore(pinia)
    dlStore.fetchProfiles = vi.fn()
    dlStore.profiles      = profiles
    dlStore.outputConfigured = true
    return { wrapper: mount(SubscribeButton, {
      props: { showId: 10, seasonId: 100 },
      global: { plugins: [pinia] },
      attachTo: document.body
    }), store }
  }

  it('shows Download when no season subscription', () => {
    const { wrapper } = factory(null)
    expect(wrapper.text()).toContain('Download')
  })

  it('shows active season subscription target', () => {
    const { wrapper } = factory(5)
    expect(wrapper.text()).toContain('Next 5')
  })

  it('calls subscribeSeason(seasonId, n) on subscribe option click', async () => {
    const { wrapper, store } = factory(null)
    await wrapper.find('button.sub-btn').trigger('click')
    await wrapper.findAll('button.picker-opt')[0].trigger('click')
    expect(store.subscribeSeason).toHaveBeenCalledWith(100, 5)
  })

  it('download-all calls enqueue with type SEASON and seasonId', async () => {
    const { wrapper } = factory(null)
    await wrapper.find('button.sub-btn').trigger('click')
    await wrapper.find('[data-testid="download-all-btn"]').trigger('click')
    expect(downloadApi.enqueue).toHaveBeenCalledWith('SEASON', 100, null)
  })

  it('shows confirm with season queue count on unsubscribe', async () => {
    getSeasonQueueCount.mockResolvedValue(2)
    const { wrapper } = factory(5)
    await wrapper.find('button.sub-btn').trigger('click')
    await wrapper.find('button.cancel-opt').trigger('click')
    await vi.waitFor(() => !!document.body.querySelector('.modal-backdrop'))
    expect(getSeasonQueueCount).toHaveBeenCalledWith(100)
  })

  it('calls unsubscribeSeason(seasonId) after confirm', async () => {
    getSeasonQueueCount.mockResolvedValue(0)
    const { wrapper, store } = factory(5)
    await wrapper.find('button.sub-btn').trigger('click')
    await wrapper.find('button.cancel-opt').trigger('click')
    await vi.waitFor(() => !!document.body.querySelector('[data-testid="confirm-btn"]'))
    document.body.querySelector('[data-testid="confirm-btn"]').click()
    await wrapper.vm.$nextTick()
    expect(store.unsubscribeSeason).toHaveBeenCalledWith(100)
  })
})
