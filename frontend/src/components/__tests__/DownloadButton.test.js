import { describe, it, expect, vi, beforeEach } from 'vitest'
import { nextTick } from 'vue'
import { mount } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'
import { useDownloadStore } from '../../stores/download.js'
import DownloadButton from '../DownloadButton.vue'

const PROFILE_DEFAULT = { id: 1, name: 'High', isDefault: true }
const PROFILE_OTHER   = { id: 2, name: 'Low',  isDefault: false }

describe('DownloadButton', () => {
  function factory(storeState = {}) {
    const pinia = createTestingPinia({ createSpy: vi.fn })
    const store = useDownloadStore(pinia)
    store.statusFor      = vi.fn().mockReturnValue(storeState.status ?? null)
    store.enqueue        = vi.fn()
    store.fetchProfiles  = vi.fn()
    store.profiles       = storeState.profiles ?? []
    return mount(DownloadButton, {
      props: { type: 'MOVIE', mediaId: 1, ...( storeState.small ? { small: true } : {} ) },
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

  it('no picker when single profile — enqueue called with null profile id', async () => {
    const pinia = createTestingPinia({ createSpy: vi.fn })
    const store = useDownloadStore(pinia)
    store.statusFor     = vi.fn().mockReturnValue(null)
    store.enqueue       = vi.fn()
    store.fetchProfiles = vi.fn()
    store.profiles      = [PROFILE_DEFAULT]
    const w = mount(DownloadButton, {
      props: { type: 'MOVIE', mediaId: 5 },
      global: { plugins: [pinia] }
    })
    expect(w.find('select').exists()).toBe(false)
    await w.find('button').trigger('click')
    expect(store.enqueue).toHaveBeenCalledWith('MOVIE', 5, null)
  })

  it('no picker when no profiles — enqueue called with null profile id', async () => {
    const pinia = createTestingPinia({ createSpy: vi.fn })
    const store = useDownloadStore(pinia)
    store.statusFor     = vi.fn().mockReturnValue(null)
    store.enqueue       = vi.fn()
    store.fetchProfiles = vi.fn()
    store.profiles      = []
    const w = mount(DownloadButton, {
      props: { type: 'MOVIE', mediaId: 5 },
      global: { plugins: [pinia] }
    })
    expect(w.find('select').exists()).toBe(false)
    await w.find('button').trigger('click')
    expect(store.enqueue).toHaveBeenCalledWith('MOVIE', 5, null)
  })

  it('small button with multi-profile shows no picker and passes null', async () => {
    const pinia = createTestingPinia({ createSpy: vi.fn })
    const store = useDownloadStore(pinia)
    store.statusFor     = vi.fn().mockReturnValue(null)
    store.enqueue       = vi.fn()
    store.fetchProfiles = vi.fn()
    store.profiles      = [PROFILE_DEFAULT, PROFILE_OTHER]
    const w = mount(DownloadButton, {
      props: { type: 'MOVIE', mediaId: 7, small: true },
      global: { plugins: [pinia] }
    })
    expect(w.find('select').exists()).toBe(false)
    await w.find('button').trigger('click')
    expect(store.enqueue).toHaveBeenCalledWith('MOVIE', 7, null)
  })

  it('full-size with multi-profile renders picker and passes selected id', async () => {
    const pinia = createTestingPinia({ createSpy: vi.fn })
    const store = useDownloadStore(pinia)
    store.statusFor     = vi.fn().mockReturnValue(null)
    store.enqueue       = vi.fn()
    store.fetchProfiles = vi.fn()
    store.profiles      = [PROFILE_DEFAULT, PROFILE_OTHER]
    const w = mount(DownloadButton, {
      props: { type: 'MOVIE', mediaId: 9 },
      global: { plugins: [pinia] }
    })
    // Allow onMounted / watch to run and initialize selectedProfileId
    await nextTick()
    // Picker select should be rendered
    expect(w.find('select.profile-select').exists()).toBe(true)
    // Options should match profiles
    const opts = w.findAll('option')
    expect(opts).toHaveLength(2)
    // Set selectedProfileId via the exposed ref (after onMounted has run)
    w.vm.selectedProfileId = PROFILE_OTHER.id
    await w.vm.$nextTick()
    await w.find('button.dl-btn').trigger('click')
    // selectedProfileId is now 2 (Number) — enqueue must pass it as a Number
    expect(store.enqueue).toHaveBeenCalledWith('MOVIE', 9, 2)
  })

  it('full-size with multi-profile defaults to isDefault profile id', async () => {
    const pinia = createTestingPinia({ createSpy: vi.fn })
    const store = useDownloadStore(pinia)
    store.statusFor     = vi.fn().mockReturnValue(null)
    store.enqueue       = vi.fn()
    // Profiles are pre-set before mount so initSelectedProfile() picks up the default
    store.fetchProfiles = vi.fn()
    store.profiles      = [PROFILE_DEFAULT, PROFILE_OTHER]
    const w = mount(DownloadButton, {
      props: { type: 'MOVIE', mediaId: 11 },
      global: { plugins: [pinia] }
    })
    // Allow onMounted / watch to run and initialize selectedProfileId
    await nextTick()
    await w.find('button.dl-btn').trigger('click')
    // selectedProfileId should be initialized to PROFILE_DEFAULT.id (1)
    expect(store.enqueue).toHaveBeenCalledWith('MOVIE', 11, 1)
  })
})
