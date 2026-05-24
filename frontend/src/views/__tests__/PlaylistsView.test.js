import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'
import PlaylistsView from '../PlaylistsView.vue'

vi.mock('../../api/playlists.js', () => ({
  getPlaylists: vi.fn(),
  subscribe: vi.fn(),
  unsubscribe: vi.fn()
}))
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn() }),
  RouterLink: { template: '<a><slot /></a>' }
}))

import { getPlaylists } from '../../api/playlists.js'

const fakePlaylists = [
  { id: 1, plexId: 'pl1', title: 'Action Movies', leafCount: 5, subscribed: true,  posterPlexIds: ['m1','m2','m3','m4'] },
  { id: 2, plexId: 'pl2', title: 'Sci-Fi Picks',  leafCount: 8, subscribed: false, posterPlexIds: ['m5'] }
]

describe('PlaylistsView', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('fetches playlists on mount and renders titles', async () => {
    getPlaylists.mockResolvedValue(fakePlaylists)
    const w = mount(PlaylistsView, { global: { plugins: [createTestingPinia()] } })
    await flushPromises()
    expect(getPlaylists).toHaveBeenCalledOnce()
    expect(w.text()).toContain('Action Movies')
    expect(w.text()).toContain('Sci-Fi Picks')
  })

  it('shows loading state before data arrives', () => {
    getPlaylists.mockReturnValue(new Promise(() => {}))  // never resolves
    const w = mount(PlaylistsView, { global: { plugins: [createTestingPinia()] } })
    expect(w.text()).toContain('Loading')
  })

  it('shows empty state when no playlists', async () => {
    getPlaylists.mockResolvedValue([])
    const w = mount(PlaylistsView, { global: { plugins: [createTestingPinia()] } })
    await flushPromises()
    expect(w.text()).toContain('No playlists')
  })

  it('renders 4 composite tiles for subscribed playlist', async () => {
    getPlaylists.mockResolvedValue(fakePlaylists)
    const w = mount(PlaylistsView, { global: { plugins: [createTestingPinia()] } })
    await flushPromises()
    const tiles = w.findAll('.composite-tile')
    expect(tiles.length).toBeGreaterThanOrEqual(4)
  })
})
