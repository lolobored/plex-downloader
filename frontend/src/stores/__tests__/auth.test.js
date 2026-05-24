// @vitest-environment happy-dom
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useAuthStore } from '../auth.js'

vi.mock('../../api/auth.js', () => ({
  initPin: vi.fn(),
  checkPin: vi.fn()
}))

describe('auth store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
  })

  it('isLoggedIn is false with no token', () => {
    const store = useAuthStore()
    expect(store.isLoggedIn).toBe(false)
  })

  it('hydrates from localStorage on init', () => {
    localStorage.setItem('jwt', 'abc')
    localStorage.setItem('jwt_username', 'alice')
    localStorage.setItem('jwt_role', 'ADMIN')
    const store = useAuthStore()
    expect(store.isLoggedIn).toBe(true)
    expect(store.username).toBe('alice')
    expect(store.role).toBe('ADMIN')
  })

  it('isAdmin returns true when role is ADMIN', () => {
    localStorage.setItem('jwt', 'abc')
    localStorage.setItem('jwt_role', 'ADMIN')
    const store = useAuthStore()
    expect(store.isAdmin).toBe(true)
  })

  it('logout clears localStorage and state', () => {
    localStorage.setItem('jwt', 'abc')
    localStorage.setItem('jwt_username', 'alice')
    localStorage.setItem('jwt_role', 'USER')
    const store = useAuthStore()
    store.logout()
    expect(store.isLoggedIn).toBe(false)
    expect(localStorage.getItem('jwt')).toBeNull()
  })

  it('saveToken stores to localStorage and updates state', () => {
    const store = useAuthStore()
    store.saveToken({ token: 'xyz', username: 'bob', role: 'USER' })
    expect(store.isLoggedIn).toBe(true)
    expect(store.username).toBe('bob')
    expect(localStorage.getItem('jwt')).toBe('xyz')
  })
})
