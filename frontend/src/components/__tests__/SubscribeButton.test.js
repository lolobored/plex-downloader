import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'
import { useWatchedStore } from '../../stores/watched.js'
import SubscribeButton from '../SubscribeButton.vue'

describe('SubscribeButton', () => {
  function factory(subscription = null) {
    const pinia = createTestingPinia({ createSpy: vi.fn })
    const store = useWatchedStore(pinia)
    store.getSubscription = vi.fn().mockReturnValue(subscription)
    store.subscribe       = vi.fn()
    store.unsubscribe     = vi.fn()
    store.enqueueUnwatched = vi.fn()
    return { wrapper: mount(SubscribeButton, {
      props: { showId: 10 },
      global: { plugins: [pinia] }
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
})
