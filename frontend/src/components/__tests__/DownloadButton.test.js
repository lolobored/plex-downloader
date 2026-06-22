import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'
import { useDownloadStore } from '../../stores/download.js'
import DownloadButton from '../DownloadButton.vue'

describe('DownloadButton', () => {
  function factory(storeState = {}) {
    const pinia = createTestingPinia({ createSpy: vi.fn })
    const store = useDownloadStore(pinia)
    store.statusFor = vi.fn().mockReturnValue(storeState.status ?? null)
    store.enqueue   = vi.fn()
    return mount(DownloadButton, {
      props: { type: 'MOVIE', mediaId: 1 },
      global: { plugins: [pinia] }
    })
  }

  it('shows download button when not queued', () => {
    const w = factory({ status: null })
    expect(w.text()).toContain('⬇')
  })

  it('shows in-queue state when QUEUED', () => {
    const w = factory({ status: 'QUEUED' })
    expect(w.text()).toContain('⏳')
  })

  it('shows done state when DONE', () => {
    const w = factory({ status: 'DONE' })
    expect(w.text()).toContain('✓')
  })

  it('shows transcoding state when TRANSCODING', () => {
    const w = factory({ status: 'TRANSCODING' })
    expect(w.text()).toContain('⏳')
  })

  it('calls store.enqueue when clicked in idle state', async () => {
    const pinia = createTestingPinia({ createSpy: vi.fn })
    const store = useDownloadStore(pinia)
    store.statusFor = vi.fn().mockReturnValue(null)
    store.enqueue   = vi.fn()
    const w = mount(DownloadButton, {
      props: { type: 'MOVIE', mediaId: 5 },
      global: { plugins: [pinia] }
    })
    await w.find('button').trigger('click')
    expect(store.enqueue).toHaveBeenCalledWith('MOVIE', 5)
  })
})
