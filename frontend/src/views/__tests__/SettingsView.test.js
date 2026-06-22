import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'
import SettingsView from '../SettingsView.vue'

vi.mock('../../api/admin.js', () => ({
  getSettings:              vi.fn(),
  putSettings:              vi.fn(),
  getSyncStatus:            vi.fn(),
  triggerSync:              vi.fn(),
  getPlexLibraries:         vi.fn(),
  createQualityProfile:     vi.fn(),
  updateQualityProfile:     vi.fn(),
  deleteQualityProfile:     vi.fn(),
  setDefaultQualityProfile: vi.fn()
}))
vi.mock('../../api/download.js', () => ({
  getQualityProfiles: vi.fn()
}))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))

import { getSettings, getSyncStatus, triggerSync, putSettings, getPlexLibraries,
         createQualityProfile, updateQualityProfile, deleteQualityProfile, setDefaultQualityProfile } from '../../api/admin.js'
import { getQualityProfiles } from '../../api/download.js'

beforeEach(() => {
  vi.clearAllMocks()
})

function fullSettings(overrides = {}) {
  return {
    'plex.server.url':           'http://localhost:32400',
    'plex.sync.cron':            '0 0 */6 * * *',
    'plex.sync.libraries':       '1',
    ...overrides
  }
}

const sampleProfiles = [
  { id: 1, name: 'HD Default', codec: 'HEVC_QSV', container: 'MKV', qualityLevel: 23, resolutionCap: 'P1080', audioMode: 'COPY', isDefault: true },
  { id: 2, name: 'SD Encode',  codec: 'H264_QSV', container: 'MP4', qualityLevel: 28, resolutionCap: 'P720',  audioMode: 'AAC',  isDefault: false }
]

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
    getQualityProfiles.mockResolvedValue(sampleProfiles)
    createQualityProfile.mockResolvedValue({ id: 3, name: 'New', codec: 'HEVC_QSV', container: 'MKV', qualityLevel: 23, resolutionCap: 'KEEP', audioMode: 'COPY', isDefault: false })
    updateQualityProfile.mockResolvedValue({})
    deleteQualityProfile.mockResolvedValue(undefined)
    setDefaultQualityProfile.mockResolvedValue({})
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

  it('has no plex token field', async () => {
    const w = factory()
    await flushPromises()
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

  // ── Transcoding section ──────────────────────────────────────────────────────

  it('renders Transcoding section heading', async () => {
    const w = factory()
    await flushPromises()
    expect(w.text()).toContain('Transcoding')
  })

  it('renders quality profile list from getQualityProfiles', async () => {
    const w = factory()
    await flushPromises()
    expect(getQualityProfiles).toHaveBeenCalled()
    expect(w.text()).toContain('HD Default')
    expect(w.text()).toContain('SD Encode')
  })

  it('marks the default profile with a default badge', async () => {
    const w = factory()
    await flushPromises()
    const badges = w.findAll('.default-badge')
    expect(badges.length).toBe(1)
    expect(badges[0].text()).toBe('default')
  })

  it('create profile calls createQualityProfile with exact enum strings', async () => {
    getQualityProfiles
      .mockResolvedValueOnce(sampleProfiles)  // initial load
      .mockResolvedValueOnce([...sampleProfiles, { id: 3, name: 'My Profile', codec: 'HEVC_QSV', container: 'MKV', qualityLevel: 23, resolutionCap: 'KEEP', audioMode: 'COPY', isDefault: false }])
    const w = factory()
    await flushPromises()

    await w.find('input[name="profileName"]').setValue('My Profile')
    // codec, container, resolutionCap, audioMode already default to correct enum values
    await w.find('[data-testid="save-profile-btn"]').trigger('click')
    await flushPromises()

    expect(createQualityProfile).toHaveBeenCalledWith(expect.objectContaining({
      name: 'My Profile',
      codec: 'HEVC_QSV',
      container: 'MKV',
      resolutionCap: 'KEEP',
      audioMode: 'COPY'
    }))
  })

  it('edit profile calls updateQualityProfile with correct id', async () => {
    getQualityProfiles
      .mockResolvedValueOnce(sampleProfiles)
      .mockResolvedValueOnce(sampleProfiles)
    const w = factory()
    await flushPromises()

    // Click the first Edit button (profile id=1)
    await w.findAll('.btn-sm')[0].trigger('click')
    await flushPromises()

    // Change name
    await w.find('input[name="profileName"]').setValue('Renamed Profile')
    await w.find('[data-testid="save-profile-btn"]').trigger('click')
    await flushPromises()

    expect(updateQualityProfile).toHaveBeenCalledWith(1, expect.objectContaining({ name: 'Renamed Profile' }))
  })

  it('set default calls setDefaultQualityProfile', async () => {
    getQualityProfiles
      .mockResolvedValueOnce(sampleProfiles)
      .mockResolvedValueOnce(sampleProfiles)
    const w = factory()
    await flushPromises()

    // "Set default" buttons: first profile (id=1) is already default so its button is disabled;
    // the second profile (id=2) has an enabled Set default button
    const setDefaultBtns = w.findAll('.btn-sm').filter(b => b.text() === 'Set default')
    const enabledBtn = setDefaultBtns.find(b => !b.element.disabled)
    await enabledBtn.trigger('click')
    await flushPromises()

    expect(setDefaultQualityProfile).toHaveBeenCalledWith(2)
  })

  it('delete profile calls deleteQualityProfile', async () => {
    getQualityProfiles
      .mockResolvedValueOnce(sampleProfiles)
      .mockResolvedValueOnce([sampleProfiles[0]])
    const w = factory()
    await flushPromises()

    const deleteBtns = w.findAll('.btn-danger')
    await deleteBtns[0].trigger('click')
    await flushPromises()

    expect(deleteQualityProfile).toHaveBeenCalled()
  })
})
