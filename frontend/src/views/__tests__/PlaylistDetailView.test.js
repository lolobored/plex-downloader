import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'
import PlaylistDetailView from '../PlaylistDetailView.vue'

vi.mock('../../api/playlists.js', () => ({
  getPlaylist:  vi.fn(),
  subscribe:    vi.fn(),
  unsubscribe:  vi.fn()
}))
vi.mock('vue-router', () => ({
  useRoute:  () => ({ params: { id: '1' } }),
  useRouter: () => ({ back: vi.fn() })
}))

import { getPlaylist, subscribe, unsubscribe } from '../../api/playlists.js'

const fakePlaylist = {
  id: 1, plexId: 'pl1', title: 'Action Movies', playlistType: 'video',
  leafCount: 2, subscribed: false, posterPlexIds: [],
  items: [
    { id: 10, plexId: 'm1', mediaType: 'MOVIE', ordinal: 0,
      title: 'The Dark Knight', year: 2008, queueStatus: 'DONE', tdarrStatus: 'TRANSCODED' },
    { id: 11, plexId: 'm2', mediaType: 'MOVIE', ordinal: 1,
      title: 'Inception', year: 2010, queueStatus: null, tdarrStatus: null }
  ]
}

describe('PlaylistDetailView', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('renders title and items', async () => {
    getPlaylist.mockResolvedValue(fakePlaylist)
    const w = mount(PlaylistDetailView, { global: { plugins: [createTestingPinia()] } })
    await flushPromises()
    expect(w.text()).toContain('Action Movies')
    expect(w.text()).toContain('The Dark Knight')
    expect(w.text()).toContain('Inception')
  })

  it('shows transcoded badge for first item', async () => {
    getPlaylist.mockResolvedValue(fakePlaylist)
    const w = mount(PlaylistDetailView, { global: { plugins: [createTestingPinia()] } })
    await flushPromises()
    expect(w.find('.status-done').exists()).toBe(true)
  })

  it('shows "not queued" for item with null queueStatus', async () => {
    getPlaylist.mockResolvedValue(fakePlaylist)
    const w = mount(PlaylistDetailView, { global: { plugins: [createTestingPinia()] } })
    await flushPromises()
    expect(w.text()).toContain('not queued')
  })

  it('subscribe button calls subscribe API and updates state', async () => {
    getPlaylist.mockResolvedValue({ ...fakePlaylist, subscribed: false })
    subscribe.mockResolvedValue(undefined)
    const w = mount(PlaylistDetailView, { global: { plugins: [createTestingPinia()] } })
    await flushPromises()
    await w.find('[data-testid="subscribe-btn"]').trigger('click')
    await flushPromises()
    expect(subscribe).toHaveBeenCalledWith(1)
    expect(w.find('[data-testid="subscribe-btn"]').text()).toContain('Unsubscribe')
  })

  it('unsubscribe button calls unsubscribe API', async () => {
    getPlaylist.mockResolvedValue({ ...fakePlaylist, subscribed: true })
    unsubscribe.mockResolvedValue(undefined)
    const w = mount(PlaylistDetailView, { global: { plugins: [createTestingPinia()] } })
    await flushPromises()
    await w.find('[data-testid="subscribe-btn"]').trigger('click')
    await flushPromises()
    expect(unsubscribe).toHaveBeenCalledWith(1)
  })
})
