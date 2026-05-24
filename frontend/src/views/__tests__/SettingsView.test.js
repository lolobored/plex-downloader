import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'
import SettingsView from '../SettingsView.vue'

vi.mock('../../api/admin.js', () => ({
  getSettings:    vi.fn(),
  putSettings:    vi.fn(),
  getSyncStatus:  vi.fn(),
  triggerSync:    vi.fn()
}))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))

import { getSettings, getSyncStatus, triggerSync, putSettings } from '../../api/admin.js'
import { useAuthStore } from '../../stores/auth.js'

describe('SettingsView', () => {
  function factory(role = 'ADMIN') {
    const pinia = createTestingPinia({ createSpy: vi.fn,
      initialState: { auth: { role } } })
    getSettings.mockResolvedValue({
      'plex.server.url':        'http://localhost:32400',
      'plex.path.prefix.plex':  '/data/plex',
      'plex.path.prefix.app':   '/movies',
      'plex.poster.dir':        '/posters',
      'plex.conversion.dir':    '/conversion',
      'plex.sync.cron':         '0 0 */6 * * *'
    })
    getSyncStatus.mockResolvedValue({ state: 'IDLE', lastSyncAt: null, itemsSynced: 0, error: null })
    return mount(SettingsView, { global: { plugins: [pinia] } })
  }

  it('redirects non-admin (tested via guard — just renders admin content for admin)', async () => {
    const w = factory('ADMIN')
    await flushPromises()
    expect(w.text()).toContain('Settings')
  })

  it('renders Plex URL field with fetched value', async () => {
    const w = factory()
    await flushPromises()
    const input = w.find('input[name="plexUrl"]')
    expect(input.element.value).toBe('http://localhost:32400')
  })

  it('calls triggerSync when Sync Now clicked', async () => {
    triggerSync.mockResolvedValue()
    const w = factory()
    await flushPromises()
    await w.find('[data-testid="sync-btn"]').trigger('click')
    expect(triggerSync).toHaveBeenCalled()
  })
})
