import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'
import PlaylistDetailView from '../PlaylistDetailView.vue'

vi.mock('../../api/playlists.js', () => ({
  getPlaylist:            vi.fn(),
  subscribe:              vi.fn(),
  unsubscribe:            vi.fn(),
  getPlaylistQueueCount:  vi.fn()
}))
vi.mock('vue-router', () => ({
  useRoute:  vi.fn(() => ({ params: { id: '1' } })),
  useRouter: vi.fn(() => ({ back: vi.fn(), push: vi.fn() }))
}))

import { getPlaylist, subscribe, unsubscribe, getPlaylistQueueCount } from '../../api/playlists.js'
import { useRouter } from 'vue-router'

const fakePlaylist = {
  id: 1, plexId: 'pl1', title: 'Action Movies', playlistType: 'video',
  leafCount: 3, subscribed: false, posterPlexIds: [],
  items: [
    { id: 10, plexId: 'm1', mediaType: 'MOVIE', ordinal: 0,
      title: 'The Dark Knight', year: 2008, queueStatus: 'DONE', tdarrStatus: 'TRANSCODED',
      mediaId: 42, showId: null, seasonId: null },
    { id: 11, plexId: 'm2', mediaType: 'MOVIE', ordinal: 1,
      title: 'Inception', year: 2010, queueStatus: null, tdarrStatus: null,
      mediaId: null, showId: null, seasonId: null },
    { id: 12, plexId: 'ep1', mediaType: 'EPISODE', ordinal: 2,
      title: 'Pilot', year: 2020, queueStatus: null, tdarrStatus: null,
      mediaId: 99, showId: 5, seasonId: 12 }
  ]
}

describe('PlaylistDetailView', () => {
  afterEach(() => {
    document.body.innerHTML = ''
  })

  beforeEach(() => { vi.clearAllMocks() })

  it('renders title and items', async () => {
    getPlaylist.mockResolvedValue(fakePlaylist)
    const w = mount(PlaylistDetailView, {
      global: { plugins: [createTestingPinia()] },
      attachTo: document.body
    })
    await flushPromises()
    expect(w.text()).toContain('Action Movies')
    expect(w.text()).toContain('The Dark Knight')
    expect(w.text()).toContain('Inception')
  })

  it('shows transcoded badge for first item', async () => {
    getPlaylist.mockResolvedValue(fakePlaylist)
    const w = mount(PlaylistDetailView, {
      global: { plugins: [createTestingPinia()] },
      attachTo: document.body
    })
    await flushPromises()
    expect(w.find('.status-done').exists()).toBe(true)
  })

  it('shows "not queued" for item with null queueStatus', async () => {
    getPlaylist.mockResolvedValue(fakePlaylist)
    const w = mount(PlaylistDetailView, {
      global: { plugins: [createTestingPinia()] },
      attachTo: document.body
    })
    await flushPromises()
    expect(w.text()).toContain('not queued')
  })

  it('subscribe button calls subscribe API and updates state', async () => {
    getPlaylist.mockResolvedValue({ ...fakePlaylist, subscribed: false })
    subscribe.mockResolvedValue(undefined)
    const w = mount(PlaylistDetailView, {
      global: { plugins: [createTestingPinia()] },
      attachTo: document.body
    })
    await flushPromises()
    await w.find('[data-testid="subscribe-btn"]').trigger('click')
    await flushPromises()
    expect(subscribe).toHaveBeenCalledWith(1)
    expect(w.find('[data-testid="subscribe-btn"]').text()).toContain('Unsubscribe')
  })

  it('shows confirm modal before unsubscribing when subscribed', async () => {
    getPlaylist.mockResolvedValue({ ...fakePlaylist, subscribed: true })
    getPlaylistQueueCount.mockResolvedValue(2)
    const w = mount(PlaylistDetailView, {
      global: { plugins: [createTestingPinia()] },
      attachTo: document.body
    })
    await flushPromises()
    await w.find('[data-testid="subscribe-btn"]').trigger('click')
    await vi.waitFor(() => !!document.body.querySelector('.modal-backdrop'))
    const message = document.body.querySelector('.modal-message').textContent
    expect(message).toContain('2')
    expect(message).toContain('queued download')
    expect(unsubscribe).not.toHaveBeenCalled()
  })

  it('shows modal with simple message when queue count is 0', async () => {
    getPlaylist.mockResolvedValue({ ...fakePlaylist, subscribed: true })
    getPlaylistQueueCount.mockResolvedValue(0)
    const w = mount(PlaylistDetailView, {
      global: { plugins: [createTestingPinia()] },
      attachTo: document.body
    })
    await flushPromises()
    await w.find('[data-testid="subscribe-btn"]').trigger('click')
    await vi.waitFor(() => !!document.body.querySelector('.modal-backdrop'))
    const message = document.body.querySelector('.modal-message').textContent
    expect(message).toBe('Remove subscription for this playlist?')
  })

  it('uses singular "download" when queue count is 1', async () => {
    getPlaylist.mockResolvedValue({ ...fakePlaylist, subscribed: true })
    getPlaylistQueueCount.mockResolvedValue(1)
    const w = mount(PlaylistDetailView, {
      global: { plugins: [createTestingPinia()] },
      attachTo: document.body
    })
    await flushPromises()
    await w.find('[data-testid="subscribe-btn"]').trigger('click')
    await vi.waitFor(() => !!document.body.querySelector('.modal-backdrop'))
    const message = document.body.querySelector('.modal-message').textContent
    expect(message).toContain('1 queued download')
    expect(message).not.toContain('downloads')
  })

  it('calls unsubscribe API after confirming in modal', async () => {
    getPlaylist.mockResolvedValue({ ...fakePlaylist, subscribed: true })
    getPlaylistQueueCount.mockResolvedValue(2)
    unsubscribe.mockResolvedValue(undefined)
    const w = mount(PlaylistDetailView, {
      global: { plugins: [createTestingPinia()] },
      attachTo: document.body
    })
    await flushPromises()
    await w.find('[data-testid="subscribe-btn"]').trigger('click')
    await vi.waitFor(() => !!document.body.querySelector('[data-testid="confirm-btn"]'))
    document.body.querySelector('[data-testid="confirm-btn"]').click()
    await flushPromises()
    expect(unsubscribe).toHaveBeenCalledWith(1)
  })

  it('does not call unsubscribe when cancel is clicked in modal', async () => {
    getPlaylist.mockResolvedValue({ ...fakePlaylist, subscribed: true })
    getPlaylistQueueCount.mockResolvedValue(3)
    const w = mount(PlaylistDetailView, {
      global: { plugins: [createTestingPinia()] },
      attachTo: document.body
    })
    await flushPromises()
    await w.find('[data-testid="subscribe-btn"]').trigger('click')
    await vi.waitFor(() => !!document.body.querySelector('.btn-cancel'))
    document.body.querySelector('.btn-cancel').click()
    await w.vm.$nextTick()
    expect(unsubscribe).not.toHaveBeenCalled()
  })

  it('closes modal after cancel without unsubscribing', async () => {
    getPlaylist.mockResolvedValue({ ...fakePlaylist, subscribed: true })
    getPlaylistQueueCount.mockResolvedValue(2)
    const w = mount(PlaylistDetailView, {
      global: { plugins: [createTestingPinia()] },
      attachTo: document.body
    })
    await flushPromises()
    await w.find('[data-testid="subscribe-btn"]').trigger('click')
    await vi.waitFor(() => !!document.body.querySelector('.modal-backdrop'))
    document.body.querySelector('.btn-cancel').click()
    await w.vm.$nextTick()
    expect(document.body.querySelector('.modal-backdrop')).toBeNull()
  })

  it('shows count badge with item count', async () => {
    getPlaylist.mockResolvedValue(fakePlaylist)
    const w = mount(PlaylistDetailView, {
      global: { plugins: [createTestingPinia()] },
      attachTo: document.body
    })
    await flushPromises()
    const badge = w.find('[data-testid="count-badge"]')
    expect(badge.exists()).toBe(true)
    expect(badge.text()).toBe('3')
  })

  it('item row with mediaId has clickable class', async () => {
    getPlaylist.mockResolvedValue(fakePlaylist)
    const w = mount(PlaylistDetailView, {
      global: { plugins: [createTestingPinia()] },
      attachTo: document.body
    })
    await flushPromises()
    const rows = w.findAll('.item-row')
    expect(rows[0].classes()).toContain('clickable')    // mediaId=42
    expect(rows[1].classes()).not.toContain('clickable') // mediaId=null
  })

  it('clicking a movie row navigates to /movies/:mediaId', async () => {
    getPlaylist.mockResolvedValue(fakePlaylist)
    const mockPush = vi.fn()
    useRouter.mockReturnValue({ back: vi.fn(), push: mockPush })
    const w = mount(PlaylistDetailView, {
      global: { plugins: [createTestingPinia()] },
      attachTo: document.body
    })
    await flushPromises()
    await w.findAll('.item-row')[0].trigger('click')
    expect(mockPush).toHaveBeenCalledWith('/movies/42')
  })

  it('clicking an episode row navigates to /tv/:showId/seasons/:seasonId/episodes/:mediaId', async () => {
    getPlaylist.mockResolvedValue(fakePlaylist)
    const mockPush = vi.fn()
    useRouter.mockReturnValue({ back: vi.fn(), push: mockPush })
    const w = mount(PlaylistDetailView, {
      global: { plugins: [createTestingPinia()] },
      attachTo: document.body
    })
    await flushPromises()
    await w.findAll('.item-row')[2].trigger('click')
    expect(mockPush).toHaveBeenCalledWith('/tv/5/seasons/12/episodes/99')
  })
})
