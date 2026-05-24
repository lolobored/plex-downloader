import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'
import NavBar from '../NavBar.vue'

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn() }),
  RouterLink: { template: '<a><slot /></a>' }
}))

describe('NavBar', () => {
  function factory(overrides = {}) {
    return mount(NavBar, {
      global: {
        plugins: [createTestingPinia({
          initialState: { auth: { username: 'alice', role: 'USER', token: 'tok', ...overrides } }
        })],
        stubs: { RouterLink: { template: '<a><slot /></a>' } }
      }
    })
  }

  it('shows username', () => {
    const w = factory()
    expect(w.text()).toContain('alice')
  })

  it('hides Settings link for non-admin', () => {
    const w = factory({ role: 'USER' })
    expect(w.text()).not.toContain('Settings')
  })

  it('shows Settings link for admin', () => {
    const w = factory({ role: 'ADMIN' })
    expect(w.text()).toContain('Settings')
  })
})
