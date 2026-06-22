import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import FolderPicker from '../FolderPicker.vue'

vi.mock('../../api/admin.js', () => ({
  fsList:  vi.fn(),
  fsMkdir: vi.fn()
}))

import { fsList, fsMkdir } from '../../api/admin.js'

beforeEach(() => {
  vi.clearAllMocks()
})

const sampleResult = {
  path: '/plex-conversion',
  parent: null,
  entries: [
    { name: 'libraries', path: '/plex-conversion/libraries' },
    { name: 'tmp',       path: '/plex-conversion/tmp' }
  ]
}

describe('FolderPicker', () => {
  function factory(initialPath = null) {
    fsList.mockResolvedValue(sampleResult)
    return mount(FolderPicker, {
      props: { initialPath },
      global: { stubs: { teleport: true } }
    })
  }

  it('calls fsList on mount and renders entries', async () => {
    const w = factory('/plex-conversion')
    await flushPromises()
    expect(fsList).toHaveBeenCalledWith('/plex-conversion')
    expect(w.text()).toContain('libraries')
    expect(w.text()).toContain('tmp')
  })

  it('shows current path in the path bar', async () => {
    const w = factory(null)
    await flushPromises()
    expect(w.text()).toContain('/plex-conversion')
  })

  it('clicking an entry navigates into it', async () => {
    fsList.mockResolvedValue({
      path: '/plex-conversion/libraries',
      parent: '/plex-conversion',
      entries: [{ name: 'movies', path: '/plex-conversion/libraries/movies' }]
    })
    const w = factory(null)
    await flushPromises()

    // Reset so first call returns root, second returns libraries
    fsList.mockResolvedValueOnce(sampleResult)
    fsList.mockResolvedValueOnce({
      path: '/plex-conversion/libraries',
      parent: '/plex-conversion',
      entries: [{ name: 'movies', path: '/plex-conversion/libraries/movies' }]
    })
    // Reload from scratch
    const w2 = factory(null)
    await flushPromises()
    const entry = w2.find('[data-testid="fp-entry-libraries"]')
    await entry.trigger('click')
    await flushPromises()
    expect(fsList).toHaveBeenCalledWith('/plex-conversion/libraries')
  })

  it('up button navigates to parent', async () => {
    fsList.mockResolvedValueOnce({
      path: '/plex-conversion/libraries',
      parent: '/plex-conversion',
      entries: []
    })
    const w = factory('/plex-conversion/libraries')
    await flushPromises()

    fsList.mockResolvedValueOnce(sampleResult)
    await w.find('.fp-btn').trigger('click') // Up button
    await flushPromises()
    expect(fsList).toHaveBeenCalledWith('/plex-conversion')
  })

  it('up button is disabled at root (no parent)', async () => {
    const w = factory(null)
    await flushPromises()
    const upBtn = w.findAll('.fp-btn')[0]
    expect(upBtn.attributes('disabled')).toBeDefined()
  })

  it('new folder button reveals input; create calls fsMkdir', async () => {
    fsMkdir.mockResolvedValue({ path: '/plex-conversion/new-dir' })
    fsList.mockResolvedValue(sampleResult)
    const w = factory(null)
    await flushPromises()

    // Click "+ New Folder"
    await w.findAll('.fp-btn')[1].trigger('click')
    await flushPromises()
    expect(w.find('[data-testid="new-folder-input"]').exists()).toBe(true)

    // Type a name and click Create
    await w.find('[data-testid="new-folder-input"]').setValue('new-dir')
    const createBtn = w.findAll('.fp-btn').find(b => b.text() === 'Create')
    await createBtn.trigger('click')
    await flushPromises()
    expect(fsMkdir).toHaveBeenCalledWith('/plex-conversion/new-dir')
  })

  it('select button emits select with current path', async () => {
    const w = factory(null)
    await flushPromises()
    await w.find('[data-testid="fp-select-btn"]').trigger('click')
    expect(w.emitted('select')).toBeTruthy()
    expect(w.emitted('select')[0]).toEqual(['/plex-conversion'])
  })

  it('close button emits close', async () => {
    const w = factory(null)
    await flushPromises()
    await w.find('.fp-close').trigger('click')
    expect(w.emitted('close')).toBeTruthy()
  })
})
