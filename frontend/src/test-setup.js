// Fix localStorage for Node 26 compatibility with happy-dom / jsdom
// Node 26 defines `localStorage` as an undefined getter on the global,
// preventing happy-dom from overriding it via populateGlobal.
// We redefine it here with a simple in-memory implementation.
import { vi } from 'vitest'

function createLocalStorageMock() {
  let store = {}
  return {
    getItem: (key) => store[key] ?? null,
    setItem: (key, value) => { store[key] = String(value) },
    removeItem: (key) => { delete store[key] },
    clear: () => { store = {} },
    get length() { return Object.keys(store).length },
    key: (index) => Object.keys(store)[index] ?? null
  }
}

const mock = createLocalStorageMock()

Object.defineProperty(globalThis, 'localStorage', {
  value: mock,
  writable: true,
  configurable: true
})
