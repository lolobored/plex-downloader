import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'
import SettingsView from '../SettingsView.vue'

vi.mock('../../api/admin.js', () => ({
  getSettings:      vi.fn(),
  putSettings:      vi.fn(),
  getSyncStatus:    vi.fn(),
  triggerSync:      vi.fn(),
  getPlexLibraries: vi.fn()
}))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))

import { getSettings, getSyncStatus, triggerSync, putSettings, getPlexLibraries } from '../../api/admin.js'

beforeEach(() => {
  vi.clearAllMocks()
})

function fullSettings(overrides = {}) {
  return {
    'plex.server.url':     'http://localhost:32400',
    'plex.sync.cron':      '0 0 */6 * * *',
    'plex.sync.libraries': '1',
    'tdarr.server.url':    '',
    'tdarr.sync.cron':     '',
    ...overrides
  }
}

describe('SettingsView', () => {
  function factory(role = 'ADMIN') {
    const pinia = createTestingPinia({ createSpy: vi.fn,
      initialState: { auth: { role } } })
    getSettings.mockResolvedValue(fullSettings())
    getSyncStatus.mockResolvedValue({ state: 'IDLE', lastSyncAt: null, itemsSynced: 0, error: null })
    getPlexLibraries.mockResolvedValue([
      { key: '1', title: 'Movies', type: 'movie' },
      { key: '2', title: 'TV Shows', type: 'show' }
    ])
    putSettings.mockResolvedValue(undefined)
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

  it('renders tdarr URL field', async () => {
    getSettings.mockResolvedValue(fullSettings({ 'tdarr.server.url': 'http://tdarr:8265', 'tdarr.sync.cron': '0 */30 * * * *' }))
    getSyncStatus.mockResolvedValue({ state: 'IDLE', lastSyncAt: null, itemsSynced: 0, error: null })
    getPlexLibraries.mockResolvedValue([])
    const pinia = createTestingPinia({ createSpy: vi.fn, initialState: { auth: { role: 'ADMIN' } } })
    const w = mount(SettingsView, { global: { plugins: [pinia] } })
    await flushPromises()
    const input = w.find('input[name="tdarrUrl"]')
    expect(input.exists()).toBe(true)
    expect(input.element.value).toBe('http://tdarr:8265')
  })

  it('has no plex token field', async () => {
    const w = factory()
    await flushPromises()
    expect(w.find('input[name="plexToken"]').exists()).toBe(false)
    // Tdarr API key field is password type; no Plex token field should exist
    expect(w.find('input[name="plexToken"]').exists()).toBe(false)
  })

  it('shows load libraries button', async () => {
    const w = factory()
    await flushPromises()
    expect(w.find('[data-testid="load-libraries-btn"]').exists()).toBe(true)
  })

  it('fetches libraries and shows checkboxes on button click', async () => {
    const w = factory()
    await flushPromises()
    await w.find('[data-testid="load-libraries-btn"]').trigger('click')
    await flushPromises()
    expect(getPlexLibraries).toHaveBeenCalled()
    const checkboxes = w.findAll('input[type="checkbox"]')
    expect(checkboxes.length).toBe(2)
  })

  it('pre-checks library keys from plex.sync.libraries setting', async () => {
    // plex.sync.libraries = "1" → only Movies pre-checked
    const w = factory()
    await flushPromises()
    await w.find('[data-testid="load-libraries-btn"]').trigger('click')
    await flushPromises()
    const checkboxes = w.findAll('input[type="checkbox"]')
    expect(checkboxes[0].element.checked).toBe(true)   // Movies (key=1) checked
    expect(checkboxes[1].element.checked).toBe(false)  // TV Shows (key=2) unchecked
  })

  it('save does not include plex.server.token', async () => {
    const w = factory()
    await flushPromises()
    await w.find('.btn-save').trigger('click')
    await flushPromises()
    const payload = putSettings.mock.calls[0][0]
    expect(payload).not.toHaveProperty('plex.server.token')
  })

  it('save includes plex.sync.libraries as comma-joined keys', async () => {
    const w = factory()
    await flushPromises()
    await w.find('[data-testid="load-libraries-btn"]').trigger('click')
    await flushPromises()
    // Check both checkboxes
    const checkboxes = w.findAll('input[type="checkbox"]')
    await checkboxes[1].setValue(true)
    await flushPromises()
    await w.find('.btn-save').trigger('click')
    await flushPromises()
    const payload = putSettings.mock.calls[0][0]
    expect(payload['plex.sync.libraries']).toBe('1,2')
  })
})
