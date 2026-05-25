import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'
import { useWatchedStore } from '../../stores/watched.js'
import { getShowQueueCount } from '../../api/watched.js'
import SubscribeButton from '../SubscribeButton.vue'

vi.mock('../../api/watched.js', () => ({
  getShowQueueCount: vi.fn()
}))

describe('SubscribeButton', () => {
  afterEach(() => {
    document.body.innerHTML = ''
  })

  beforeEach(() => {
    vi.clearAllMocks()
  })

  function factory(subscription = null) {
    const pinia = createTestingPinia({ createSpy: vi.fn })
    const store = useWatchedStore(pinia)
    store.getSubscription = vi.fn().mockReturnValue(subscription)
    store.subscribe       = vi.fn()
    store.unsubscribe     = vi.fn()
    store.enqueueUnwatched = vi.fn()
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

  it('calls subscribe with chosen target on subscribe option click', async () => {
    const { wrapper, store } = factory(null)
    await wrapper.find('button.sub-btn').trigger('click')
    await wrapper.findAll('button.picker-opt')[0].trigger('click') // first subscribe option = 5
    expect(store.subscribe).toHaveBeenCalledWith(10, 5)
  })

  it('calls enqueueUnwatched on one-time option click', async () => {
    const { wrapper, store } = factory(null)
    await wrapper.find('button.sub-btn').trigger('click')
    // one-time buttons come after subscribe buttons (4 subscribe + 4 one-time)
    const opts = wrapper.findAll('button.picker-opt')
    await opts[4].trigger('click') // first one-time option = 5
    expect(store.enqueueUnwatched).toHaveBeenCalledWith(10, 5)
  })

  it('shows confirm modal with count message when queue count > 0', async () => {
    getShowQueueCount.mockResolvedValue(3)
    const { wrapper } = factory(10)
    await wrapper.find('button.sub-btn').trigger('click')
    await wrapper.find('button.cancel-opt').trigger('click')
    // wait for async getShowQueueCount to resolve and modal to render via Teleport
    await vi.waitFor(() => !!document.body.querySelector('.modal-backdrop'))
    const message = document.body.querySelector('.modal-message').textContent
    expect(message).toContain('3')
    expect(message).toContain('queued download')
  })

  it('shows confirm modal with simple message when queue count is 0', async () => {
    getShowQueueCount.mockResolvedValue(0)
    const { wrapper } = factory(10)
    await wrapper.find('button.sub-btn').trigger('click')
    await wrapper.find('button.cancel-opt').trigger('click')
    await vi.waitFor(() => !!document.body.querySelector('.modal-backdrop'))
    const message = document.body.querySelector('.modal-message').textContent
    expect(message).toBe('Remove subscription for this show?')
  })

  it('uses singular "download" when count is 1', async () => {
    getShowQueueCount.mockResolvedValue(1)
    const { wrapper } = factory(10)
    await wrapper.find('button.sub-btn').trigger('click')
    await wrapper.find('button.cancel-opt').trigger('click')
    await vi.waitFor(() => !!document.body.querySelector('.modal-backdrop'))
    const message = document.body.querySelector('.modal-message').textContent
    expect(message).toContain('1 queued download')
    expect(message).not.toContain('downloads')
  })

  it('does not call unsubscribe API before modal is confirmed', async () => {
    getShowQueueCount.mockResolvedValue(3)
    const { wrapper, store } = factory(10)
    await wrapper.find('button.sub-btn').trigger('click')
    await wrapper.find('button.cancel-opt').trigger('click')
    await vi.waitFor(() => !!document.body.querySelector('.modal-backdrop'))
    expect(store.unsubscribe).not.toHaveBeenCalled()
  })

  it('calls unsubscribe after confirming in modal', async () => {
    getShowQueueCount.mockResolvedValue(3)
    const { wrapper, store } = factory(10)
    await wrapper.find('button.sub-btn').trigger('click')
    await wrapper.find('button.cancel-opt').trigger('click')
    await vi.waitFor(() => !!document.body.querySelector('[data-testid="confirm-btn"]'))
    document.body.querySelector('[data-testid="confirm-btn"]').click()
    await wrapper.vm.$nextTick()
    expect(store.unsubscribe).toHaveBeenCalledWith(10)
  })

  it('does not call unsubscribe when cancel is clicked in modal', async () => {
    getShowQueueCount.mockResolvedValue(3)
    const { wrapper, store } = factory(10)
    await wrapper.find('button.sub-btn').trigger('click')
    await wrapper.find('button.cancel-opt').trigger('click')
    await vi.waitFor(() => !!document.body.querySelector('.btn-cancel'))
    document.body.querySelector('.btn-cancel').click()
    await wrapper.vm.$nextTick()
    expect(store.unsubscribe).not.toHaveBeenCalled()
  })

  it('closes modal after cancel without unsubscribing', async () => {
    getShowQueueCount.mockResolvedValue(2)
    const { wrapper } = factory(10)
    await wrapper.find('button.sub-btn').trigger('click')
    await wrapper.find('button.cancel-opt').trigger('click')
    await vi.waitFor(() => !!document.body.querySelector('.modal-backdrop'))
    document.body.querySelector('.btn-cancel').click()
    await wrapper.vm.$nextTick()
    expect(document.body.querySelector('.modal-backdrop')).toBeNull()
  })
})
