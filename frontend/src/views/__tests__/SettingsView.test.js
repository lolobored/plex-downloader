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
  setDefaultQualityProfile: vi.fn(),
  fsList:                   vi.fn(),
  fsMkdir:                  vi.fn(),
  relocateOutput:           vi.fn(),
  runSubtitleScan:          vi.fn(),
  getSubtitleScanStatus:    vi.fn()
}))
vi.mock('../../api/download.js', () => ({
  getQualityProfiles: vi.fn()
}))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))

import { getSettings, getSyncStatus, triggerSync, putSettings, getPlexLibraries,
         createQualityProfile, updateQualityProfile, deleteQualityProfile, setDefaultQualityProfile,
         fsList, fsMkdir, relocateOutput, runSubtitleScan, getSubtitleScanStatus } from '../../api/admin.js'
import { getQualityProfiles } from '../../api/download.js'

beforeEach(() => {
  vi.clearAllMocks()
})

function fullSettings(overrides = {}) {
  return {
    'plex.server.url':           'http://localhost:32400',
    'plex.sync.cron':            '0 0 */6 * * *',
    'plex.sync.libraries':       '1',
    'output.movies.dir':         '/plex-conversion/libraries/movies',
    'output.tvshows.dir':        '/plex-conversion/libraries/tvshows',
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
    fsList.mockResolvedValue({ path: '/plex-conversion', parent: null, entries: [] })
    fsMkdir.mockResolvedValue({})
    getQualityProfiles.mockResolvedValue(sampleProfiles)
    createQualityProfile.mockResolvedValue({ id: 3, name: 'New', codec: 'HEVC_QSV', container: 'MKV', qualityLevel: 23, resolutionCap: 'KEEP', audioMode: 'COPY', isDefault: false })
    updateQualityProfile.mockResolvedValue({})
    deleteQualityProfile.mockResolvedValue(undefined)
    setDefaultQualityProfile.mockResolvedValue({})
    getSubtitleScanStatus.mockResolvedValue({ running: false, lastRunAt: null, scanned: 0, failed: 0, remainingUnknown: 0 })
    runSubtitleScan.mockResolvedValue(undefined)
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

  // ── Output Folders section ───────────────────────────────────────────────────

  it('renders Output Folders section', async () => {
    const w = factory()
    await flushPromises()
    expect(w.text()).toContain('Output Folders')
  })

  it('renders movies output dir input with loaded value', async () => {
    const w = factory()
    await flushPromises()
    const input = w.find('input[name="moviesDir"]')
    expect(input.element.value).toBe('/plex-conversion/libraries/movies')
  })

  it('renders tvshows output dir input with loaded value', async () => {
    const w = factory()
    await flushPromises()
    const input = w.find('input[name="tvshowsDir"]')
    expect(input.element.value).toBe('/plex-conversion/libraries/tvshows')
  })

  it('browse movies button opens folder picker', async () => {
    fsList.mockResolvedValue({ path: '/plex-conversion', parent: null, entries: [] })
    const w = factory()
    await flushPromises()
    await w.find('[data-testid="browse-movies-btn"]').trigger('click')
    await flushPromises()
    // FolderPicker modal should appear
    expect(w.find('.folder-picker-overlay').exists()).toBe(true)
  })

  it('browse tvshows button opens folder picker', async () => {
    fsList.mockResolvedValue({ path: '/plex-conversion', parent: null, entries: [] })
    const w = factory()
    await flushPromises()
    await w.find('[data-testid="browse-tvshows-btn"]').trigger('click')
    await flushPromises()
    expect(w.find('.folder-picker-overlay').exists()).toBe(true)
  })

  it('save output dirs calls putSettings with output dir keys', async () => {
    const w = factory()
    await flushPromises()
    await w.find('[data-testid="save-output-dirs-btn"]').trigger('click')
    await flushPromises()
    expect(putSettings).toHaveBeenCalledWith(expect.objectContaining({
      'output.movies.dir':  '/plex-conversion/libraries/movies',
      'output.tvshows.dir': '/plex-conversion/libraries/tvshows'
    }))
  })
})

describe('Output relocation modal', () => {
  function factoryWithChangedDir(overrides = {}) {
    // Loads settings with one dir, then we simulate user changing it
    const pinia = createTestingPinia({ createSpy: vi.fn, initialState: { auth: { role: 'ADMIN' } } })
    getSettings.mockResolvedValue(fullSettings(overrides))
    getSyncStatus.mockResolvedValue({ state: 'IDLE', lastSyncAt: null, itemsSynced: 0, error: null })
    getPlexLibraries.mockResolvedValue([])
    putSettings.mockResolvedValue(undefined)
    relocateOutput.mockResolvedValue({ moved: 2, updatedOnly: 1, failed: 0 })
    fsList.mockResolvedValue({ path: '/', parent: null, entries: [] })
    fsMkdir.mockResolvedValue({})
    getQualityProfiles.mockResolvedValue([])
    return mount(SettingsView, { global: { plugins: [pinia] } })
  }

  it('does NOT show relocate modal when dir is unchanged', async () => {
    const w = factoryWithChangedDir()
    await flushPromises()
    await w.find('[data-testid="save-output-dirs-btn"]').trigger('click')
    await flushPromises()
    expect(w.find('[data-testid="relocate-modal"]').exists()).toBe(false)
    expect(putSettings).toHaveBeenCalled()
  })

  it('shows relocate modal when movies dir changes', async () => {
    const w = factoryWithChangedDir()
    await flushPromises()
    await w.find('input[name="moviesDir"]').setValue('/new/movies')
    await w.find('[data-testid="save-output-dirs-btn"]').trigger('click')
    await flushPromises()
    expect(w.find('[data-testid="relocate-modal"]').exists()).toBe(true)
  })

  it('Move calls relocateOutput then saves settings', async () => {
    const w = factoryWithChangedDir()
    await flushPromises()
    await w.find('input[name="moviesDir"]').setValue('/new/movies')
    await w.find('[data-testid="save-output-dirs-btn"]').trigger('click')
    await flushPromises()
    await w.find('[data-testid="relocate-move-btn"]').trigger('click')
    await flushPromises()
    expect(relocateOutput).toHaveBeenCalledWith('MOVIE', '/plex-conversion/libraries/movies', '/new/movies')
    expect(putSettings).toHaveBeenCalled()
  })

  it('Keep saves settings without calling relocateOutput', async () => {
    const w = factoryWithChangedDir()
    await flushPromises()
    await w.find('input[name="moviesDir"]').setValue('/new/movies')
    await w.find('[data-testid="save-output-dirs-btn"]').trigger('click')
    await flushPromises()
    await w.find('[data-testid="relocate-keep-btn"]').trigger('click')
    await flushPromises()
    expect(relocateOutput).not.toHaveBeenCalled()
    expect(putSettings).toHaveBeenCalled()
  })

  it('Cancel reverts field and does NOT save', async () => {
    const w = factoryWithChangedDir()
    await flushPromises()
    await w.find('input[name="moviesDir"]').setValue('/new/movies')
    await w.find('[data-testid="save-output-dirs-btn"]').trigger('click')
    await flushPromises()
    await w.find('[data-testid="relocate-cancel-btn"]').trigger('click')
    await flushPromises()
    expect(relocateOutput).not.toHaveBeenCalled()
    expect(putSettings).not.toHaveBeenCalled()
    // field reverted
    expect(w.find('input[name="moviesDir"]').element.value).toBe('/plex-conversion/libraries/movies')
  })

  it('shows relocate result after move', async () => {
    const w = factoryWithChangedDir()
    await flushPromises()
    await w.find('input[name="moviesDir"]').setValue('/new/movies')
    await w.find('[data-testid="save-output-dirs-btn"]').trigger('click')
    await flushPromises()
    await w.find('[data-testid="relocate-move-btn"]').trigger('click')
    await flushPromises()
    expect(w.find('[data-testid="relocate-result"]').exists()).toBe(true)
    expect(w.find('[data-testid="relocate-result"]').text()).toContain('2')
  })

  it('Move movies + Cancel tvshows: relocates movies, putSettings uses NEW movies dir and OLD tvshows dir', async () => {
    const w = factoryWithChangedDir()
    await flushPromises()

    // Change both dirs
    await w.find('input[name="moviesDir"]').setValue('/new/movies')
    await w.find('input[name="tvshowsDir"]').setValue('/new/tvshows')
    await w.find('[data-testid="save-output-dirs-btn"]').trigger('click')
    await flushPromises()

    // First modal (movies) → Move
    await w.find('[data-testid="relocate-move-btn"]').trigger('click')
    await flushPromises()

    // Second modal (tvshows) → Cancel
    await w.find('[data-testid="relocate-cancel-btn"]').trigger('click')
    await flushPromises()

    // relocateOutput was called for movies only
    expect(relocateOutput).toHaveBeenCalledTimes(1)
    expect(relocateOutput).toHaveBeenCalledWith('MOVIE', '/plex-conversion/libraries/movies', '/new/movies')

    // putSettings must include NEW movies dir and OLD tvshows dir (no desync)
    expect(putSettings).toHaveBeenCalledWith(expect.objectContaining({
      'output.movies.dir':  '/new/movies',
      'output.tvshows.dir': '/plex-conversion/libraries/tvshows',
    }))
  })

  it('Move both dirs accumulates counts from both relocations', async () => {
    relocateOutput
      .mockResolvedValueOnce({ moved: 3, updatedOnly: 1, failed: 0 })  // movies
      .mockResolvedValueOnce({ moved: 5, updatedOnly: 2, failed: 1 })  // tvshows

    const w = factoryWithChangedDir()
    await flushPromises()

    await w.find('input[name="moviesDir"]').setValue('/new/movies')
    await w.find('input[name="tvshowsDir"]').setValue('/new/tvshows')
    await w.find('[data-testid="save-output-dirs-btn"]').trigger('click')
    await flushPromises()

    // Move movies
    await w.find('[data-testid="relocate-move-btn"]').trigger('click')
    await flushPromises()

    // Move tvshows
    await w.find('[data-testid="relocate-move-btn"]').trigger('click')
    await flushPromises()

    expect(relocateOutput).toHaveBeenCalledTimes(2)

    // Result should show combined totals: 8 moved, 3 updatedOnly, 1 failed
    const resultEl = w.find('[data-testid="relocate-result"]')
    expect(resultEl.exists()).toBe(true)
    expect(resultEl.text()).toContain('8')  // 3+5 moved
    expect(resultEl.text()).toContain('3')  // 1+2 updatedOnly
    expect(resultEl.text()).toContain('1')  // 0+1 failed
  })
})

describe('SettingsView – Subtitles section', () => {
  function factory() {
    const pinia = createTestingPinia({ createSpy: vi.fn, initialState: { auth: { role: 'ADMIN' } } })
    getSettings.mockResolvedValue({
      'plex.server.url':      'http://localhost:32400',
      'plex.sync.cron':       '0 0 */6 * * *',
      'plex.sync.libraries':  '1',
      'output.movies.dir':    '/plex-conversion/libraries/movies',
      'output.tvshows.dir':   '/plex-conversion/libraries/tvshows',
      'subtitles.scan.cron':  '0 0 4 * * *'
    })
    getSyncStatus.mockResolvedValue({ state: 'IDLE', lastSyncAt: null, itemsSynced: 0, error: null })
    getPlexLibraries.mockResolvedValue([])
    putSettings.mockResolvedValue(undefined)
    fsList.mockResolvedValue({ path: '/', parent: null, entries: [] })
    fsMkdir.mockResolvedValue({})
    getQualityProfiles.mockResolvedValue([])
    relocateOutput.mockResolvedValue({ moved: 0, updatedOnly: 0, failed: 0 })
    getSubtitleScanStatus.mockResolvedValue({ running: false, lastRunAt: '2026-06-20T10:00:00Z', scanned: 42, failed: 1, remainingUnknown: 5 })
    runSubtitleScan.mockResolvedValue(undefined)
    return mount(SettingsView, { global: { plugins: [pinia] } })
  }

  it('renders Subtitles section heading', async () => {
    const w = factory()
    await flushPromises()
    expect(w.text()).toContain('Subtitles')
  })

  it('"Scan now" button calls runSubtitleScan(false)', async () => {
    const w = factory()
    await flushPromises()
    await w.find('[data-testid="subtitle-scan-now-btn"]').trigger('click')
    await flushPromises()
    expect(runSubtitleScan).toHaveBeenCalledWith(false)
  })

  it('"Rescan all" button calls runSubtitleScan(true)', async () => {
    const w = factory()
    await flushPromises()
    await w.find('[data-testid="subtitle-rescan-all-btn"]').trigger('click')
    await flushPromises()
    expect(runSubtitleScan).toHaveBeenCalledWith(true)
  })

  it('status line shows scanned / failed / remainingUnknown from getSubtitleScanStatus', async () => {
    const w = factory()
    await flushPromises()
    const text = w.text()
    expect(text).toContain('42')   // scanned
    expect(text).toContain('1')    // failed
    expect(text).toContain('5')    // remainingUnknown
  })

  it('saving settings includes subtitles.scan.cron in the payload', async () => {
    const w = factory()
    await flushPromises()
    await w.find('[data-testid="subtitle-save-btn"]').trigger('click')
    await flushPromises()
    const payload = putSettings.mock.calls[0][0]
    expect(payload).toHaveProperty('subtitles.scan.cron', '0 0 4 * * *')
  })

  it('saving settings includes subtitles.scan.cron as empty string when Disabled selected', async () => {
    getSettings.mockResolvedValue({
      'plex.server.url':      'http://localhost:32400',
      'plex.sync.cron':       '0 0 */6 * * *',
      'plex.sync.libraries':  '',
      'output.movies.dir':    '/plex-conversion/libraries/movies',
      'output.tvshows.dir':   '/plex-conversion/libraries/tvshows',
      'subtitles.scan.cron':  ''
    })
    const pinia = createTestingPinia({ createSpy: vi.fn, initialState: { auth: { role: 'ADMIN' } } })
    getSyncStatus.mockResolvedValue({ state: 'IDLE', lastSyncAt: null, itemsSynced: 0, error: null })
    getPlexLibraries.mockResolvedValue([])
    putSettings.mockResolvedValue(undefined)
    fsList.mockResolvedValue({ path: '/', parent: null, entries: [] })
    getQualityProfiles.mockResolvedValue([])
    getSubtitleScanStatus.mockResolvedValue({ running: false, lastRunAt: null, scanned: 0, failed: 0, remainingUnknown: 0 })
    runSubtitleScan.mockResolvedValue(undefined)
    const w = mount(SettingsView, { global: { plugins: [pinia] } })
    await flushPromises()
    // Select Disabled option
    const select = w.find('select[name="subtitleScanCron"]')
    await select.setValue('')
    await w.find('[data-testid="subtitle-save-btn"]').trigger('click')
    await flushPromises()
    const payload = putSettings.mock.calls[0][0]
    expect(payload).toHaveProperty('subtitles.scan.cron', '')
  })
})
