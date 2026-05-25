# MoviesView / TvView UX Fixes — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix four UX regressions in MoviesView: poster images and watched badge not rendering until scroll, state/scroll lost on back navigation, and pages not auto-loading on initial render. Also apply keep-alive to TvView.

**Architecture:** Remove `loading="lazy"` from PosterCard (fixes compositing bug with watched badge). Restructure MoviesView grid from letter-grouped to flat list with card-based letter refs (removes forced line breaks between letters). Add `<keep-alive>` to App.vue with component names set via `defineOptions`. Increase `rootMargin` to 1200px for aggressive initial pre-loading.

**Tech Stack:** Vue 3 (Composition API, `<script setup>`), Vue Router, `@vue/test-utils`, Vitest, happy-dom

---

### Task 1: PosterCard — remove `loading="lazy"`, add watched badge test

**Files:**
- Modify: `frontend/src/components/PosterCard.vue`
- Modify: `frontend/src/components/__tests__/PosterCard.test.js`

- [ ] **Step 1: Write failing tests**

Open `frontend/src/components/__tests__/PosterCard.test.js`. Add these two tests inside the `describe('PosterCard', ...)` block:

```js
it('shows watched badge when watched=true', () => {
  const w = mount(PosterCard, { props: { title: 'Test', plexId: 'x', watched: true } })
  expect(w.find('.watched-badge').exists()).toBe(true)
  expect(w.find('.watched-badge').text()).toBe('✓')
})

it('does not have loading=lazy on img', () => {
  const w = mount(PosterCard, { props: { title: 'Test', plexId: 'x' } })
  expect(w.find('img').attributes('loading')).toBeUndefined()
})
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd frontend && npm run test -- --reporter=verbose src/components/__tests__/PosterCard.test.js 2>&1 | tail -20
```

Expected: `does not have loading=lazy` FAILS (img currently has `loading="lazy"`). `shows watched badge` PASSES (badge already in component — confirm this test passes, it's a safety net).

- [ ] **Step 3: Remove `loading="lazy"` from PosterCard**

In `frontend/src/components/PosterCard.vue`, change line 4:

```html
<img :src="`/api/posters/${plexId}.jpg`" :alt="title" />
```

(Remove `loading="lazy"` — that's the only change to this file.)

- [ ] **Step 4: Run tests to verify they pass**

```bash
npm run test -- --reporter=verbose src/components/__tests__/PosterCard.test.js 2>&1 | tail -20
```

Expected: all 7 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/components/PosterCard.vue src/components/__tests__/PosterCard.test.js
git commit -m "fix: remove loading=lazy from PosterCard, fixes watched badge compositing (refs #21)"
```

---

### Task 2: MoviesView — flat grid, firstMovieOfLetter computed, rootMargin

**Files:**
- Modify: `frontend/src/views/MoviesView.vue`
- Modify: `frontend/src/views/__tests__/MoviesView.test.js`

The `groupedMovies` computed is replaced by `firstMovieOfLetter` (a Map of letter→first-movie-id). The grid renders `sortedMovies` directly. Each movie that is the first of its letter gets `id="letter-X"` and a ref stored in `sectionRefs`. All other grid behaviour (infinite scroll, alpha sidebar, `scrollToLetter`, `updateActiveLetter`) is unchanged.

- [ ] **Step 1: Write failing tests**

Open `frontend/src/views/__tests__/MoviesView.test.js`. Replace its full contents with:

```js
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'
import MoviesView from '../MoviesView.vue'

vi.mock('../../api/library.js', () => ({
  getMovies: vi.fn()
}))
import { getMovies } from '../../api/library.js'

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn() }),
  RouterLink: { template: '<a><slot /></a>' }
}))

const fakeMovies = {
  content: [
    { id: 1, plexId: 'pk1', title: 'Inception',     year: 2010, watched: false },
    { id: 2, plexId: 'pk2', title: 'The Matrix',     year: 1999, watched: false },
    { id: 3, plexId: 'pk3', title: 'Interstellar',   year: 2014, watched: false },
  ],
  totalPages: 1, number: 0
}

// Stub that passes through fallthrough attrs (including `id`) to root element
const PcStub = { template: '<div class="pc">{{ title }}</div>', props: ['title','plexId','watched','subtitle'] }

describe('MoviesView', () => {
  beforeEach(() => { vi.clearAllMocks(); getMovies.mockResolvedValue(fakeMovies) })

  it('fetches movies on mount and renders poster cards', async () => {
    const w = mount(MoviesView, { global: { plugins: [createTestingPinia()],
      stubs: { PosterCard: PcStub, SearchFilter: true, DownloadButton: true } } })
    await flushPromises()
    expect(getMovies).toHaveBeenCalledOnce()
    expect(w.findAll('.pc')).toHaveLength(3)
  })

  it('shows empty state when no results', async () => {
    getMovies.mockResolvedValue({ content: [], totalPages: 0, number: 0 })
    const w = mount(MoviesView, { global: { plugins: [createTestingPinia()],
      stubs: { PosterCard: PcStub, SearchFilter: true, DownloadButton: true } } })
    await flushPromises()
    expect(w.text()).toContain('No movies found')
  })

  it('renders no letter-anchor divs in grid', async () => {
    const w = mount(MoviesView, { global: { plugins: [createTestingPinia()],
      stubs: { PosterCard: PcStub, SearchFilter: true, DownloadButton: true } } })
    await flushPromises()
    expect(w.find('.letter-anchor').exists()).toBe(false)
  })

  it('assigns letter id to first card of each letter group only', async () => {
    // Inception(I), Interstellar(I), Matrix(M) — only Inception and Matrix get ids
    const w = mount(MoviesView, { global: { plugins: [createTestingPinia()],
      stubs: { PosterCard: PcStub, SearchFilter: true, DownloadButton: true } } })
    await flushPromises()
    expect(w.find('#letter-I').exists()).toBe(true)   // Inception is first 'I'
    expect(w.find('#letter-M').exists()).toBe(true)   // The Matrix is first 'M'
    expect(w.findAll('.pc[id]')).toHaveLength(2)      // only 2 of 3 cards have id
  })

  it('has name MoviesView for keep-alive', () => {
    expect(MoviesView.name ?? MoviesView.__name).toBe('MoviesView')
  })
})
```

- [ ] **Step 2: Run tests to verify new tests fail**

```bash
npm run test -- --reporter=verbose src/views/__tests__/MoviesView.test.js 2>&1 | tail -25
```

Expected: `renders no letter-anchor divs` FAILS (they exist now). `assigns letter id` FAILS. `has name MoviesView` may fail. Existing 2 tests still pass.

- [ ] **Step 3: Rewrite MoviesView.vue**

Replace the full contents of `frontend/src/views/MoviesView.vue` with:

```vue
<template>
  <div class="movies-root">
    <div class="toolbar">
      <h2>Movies</h2>
      <SearchFilter v-model:search="search" v-model:year="year" />
    </div>

    <div class="content-wrap">
      <!-- Movie list -->
      <div class="movie-list">
        <div v-if="!allMovies.length && loading" class="loading">Loading…</div>
        <div v-else-if="!allMovies.length && !loading" class="empty">No movies found.</div>

        <div class="grid">
          <PosterCard
            v-for="m in sortedMovies"
            :key="m.id"
            :id="isFirstOfLetter(m) ? 'letter-' + firstLetter(m.title) : undefined"
            :class="isFirstOfLetter(m) ? 'letter-start' : undefined"
            :ref="el => {
              const l = firstLetter(m.title)
              if (firstMovieOfLetter.get(l) !== m.id) return
              if (el) sectionRefs[l] = el.$el ?? el
              else delete sectionRefs[l]
            }"
            :plexId="m.plexId"
            :title="m.title"
            :subtitle="m.year?.toString()"
            :watched="m.watched"
            @click="router.push(`/movies/${m.id}`)"
          >
            <template #badge>
              <DownloadButton type="MOVIE" :mediaId="m.id" small />
            </template>
          </PosterCard>
        </div>

        <div ref="sentinel" class="sentinel" />
        <div v-if="loading && allMovies.length" class="loading-more">Loading more…</div>
      </div>

      <!-- Alphabet sidebar — always show A-Z; grey out empty letters -->
      <nav class="alpha-sidebar" aria-label="Jump to letter">
        <button
          v-for="letter in ALPHABET"
          :key="letter"
          :class="['alpha-btn', { active: letter === activeLetter, empty: !loadedLetters.has(letter) }]"
          :title="letter"
          @click="scrollToLetter(letter)"
        >{{ letter }}</button>
      </nav>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, onUnmounted, onActivated, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { getMovies } from '@/api/library.js'
import PosterCard from '@/components/PosterCard.vue'
import SearchFilter from '@/components/SearchFilter.vue'
import DownloadButton from '@/components/DownloadButton.vue'

defineOptions({ name: 'MoviesView' })

const router   = useRouter()
const ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ'.split('')

const allMovies    = ref([])
const loading      = ref(false)
const page         = ref(0)
const hasMore      = ref(true)
const search       = ref('')
const year         = ref(null)
const sentinel     = ref(null)
const activeLetter = ref('')
const sectionRefs  = {}   // plain object — DOM refs only, no reactivity needed

// Strip leading articles for sort/grouping
function sortTitle(t) {
  return (t || '').replace(/^(the|a|an)\s+/i, '').trim()
}
function firstLetter(t) {
  const c = sortTitle(t).charAt(0).toUpperCase()
  return /[A-Z]/.test(c) ? c : '#'
}

const sortedMovies = computed(() =>
  [...allMovies.value].sort((a, b) =>
    sortTitle(a.title).localeCompare(sortTitle(b.title), undefined, { sensitivity: 'base' })
  )
)

// Map of letter → id of the first movie (alphabetically) for that letter
const firstMovieOfLetter = computed(() => {
  const map = new Map()
  for (const m of sortedMovies.value) {
    const l = firstLetter(m.title)
    if (!map.has(l)) map.set(l, m.id)
  }
  return map
})

function isFirstOfLetter(m) {
  return firstMovieOfLetter.value.get(firstLetter(m.title)) === m.id
}

const allLetters = computed(() => {
  const letters = [...firstMovieOfLetter.value.keys()]
  return letters.sort((a, b) => a === '#' ? 1 : b === '#' ? -1 : a.localeCompare(b))
})
const loadedLetters = computed(() => new Set(allLetters.value))

async function loadMore() {
  if (loading.value || !hasMore.value) return
  loading.value = true
  try {
    const data = await getMovies({
      search: search.value || undefined,
      year: year.value || undefined,
      page: page.value,
      size: 100
    })
    allMovies.value.push(...data.content)
    hasMore.value = page.value < data.totalPages - 1
    page.value++
    await nextTick()
    updateActiveLetter()
  } finally {
    loading.value = false
  }
  // IntersectionObserver won't re-fire if sentinel never left the viewport.
  // Check manually: if sentinel is still on-screen and there are more pages, keep loading.
  if (hasMore.value && sentinel.value) {
    const rect = sentinel.value.getBoundingClientRect()
    if (rect.top < window.innerHeight + 1200) loadMore()
  }
}

function reset() {
  allMovies.value = []
  page.value = 0
  hasMore.value = true
  activeLetter.value = ''
  Object.keys(sectionRefs).forEach(k => delete sectionRefs[k])
}

watch([search, year], () => { reset(); loadMore() })

// Infinite scroll via IntersectionObserver on sentinel
let scrollObserver = null
onMounted(() => {
  loadMore()
  scrollObserver = new IntersectionObserver(
    ([entry]) => { if (entry.isIntersecting) loadMore() },
    { rootMargin: '1200px' }
  )
  if (sentinel.value) scrollObserver.observe(sentinel.value)
  window.addEventListener('scroll', updateActiveLetter, { passive: true })
})

onActivated(() => {
  updateActiveLetter()
})

onUnmounted(() => {
  scrollObserver?.disconnect()
  window.removeEventListener('scroll', updateActiveLetter)
})

// Track which letter section is near the top of viewport
// NavBar is sticky at 56px; use 70px as threshold
const SCROLL_OFFSET = 70

function updateActiveLetter() {
  let current = ''
  for (const letter of allLetters.value) {
    const el = sectionRefs[letter]
    if (!el) continue
    if (el.getBoundingClientRect().top <= SCROLL_OFFSET) current = letter
  }
  activeLetter.value = current || allLetters.value[0] || ''
}

function scrollToLetter(letter) {
  if (!loadedLetters.value.has(letter)) return
  const el = sectionRefs[letter]
  if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' })
}
</script>

<style scoped>
.movies-root { position: relative; }

.toolbar { display: flex; align-items: center; gap: 24px; margin-bottom: 24px; }
h2 { font-size: 1.5rem; font-weight: 600; }

.content-wrap {
  display: flex;
  gap: 8px;
  align-items: flex-start;
}

.movie-list { flex: 1; min-width: 0; }

.letter-start { scroll-margin-top: 70px; }

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
  gap: 16px;
}

.loading, .empty { color: var(--text-muted); padding: 40px 0; text-align: center; }
.loading-more { color: var(--text-muted); text-align: center; padding: 20px 0; font-size: .85rem; }
.sentinel { height: 1px; }

/* Alphabet sidebar */
.alpha-sidebar {
  position: sticky;
  top: 70px;
  width: 24px;
  max-height: calc(100vh - 90px);
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 1px;
  flex-shrink: 0;
  overflow-y: auto;
  scrollbar-width: none;
  padding: 2px 0;
}
.alpha-sidebar::-webkit-scrollbar { display: none; }

.alpha-btn {
  width: 22px;
  height: 22px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: .65rem;
  font-weight: 600;
  border-radius: 4px;
  border: none;
  background: transparent;
  color: var(--text-muted);
  cursor: pointer;
  padding: 0;
  flex-shrink: 0;
  transition: color .1s, background .1s;
}
.alpha-btn:hover:not(.empty) {
  color: var(--text);
  background: var(--surface2);
}
.alpha-btn.active {
  color: var(--accent);
  background: var(--surface2);
  border-radius: 4px;
}
.alpha-btn.empty {
  color: var(--border);
  cursor: default;
}
</style>
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
npm run test -- --reporter=verbose src/views/__tests__/MoviesView.test.js 2>&1 | tail -25
```

Expected: all 5 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/views/MoviesView.vue src/views/__tests__/MoviesView.test.js
git commit -m "feat: flat grid in MoviesView, card-based letter refs, rootMargin 1200px (refs #21)"
```

---

### Task 3: Keep-alive in App.vue + defineOptions in TvView

**Files:**
- Modify: `frontend/src/App.vue`
- Modify: `frontend/src/views/TvView.vue`
- Modify: `frontend/src/views/__tests__/TvView.test.js`

- [ ] **Step 1: Write failing test for TvView name**

Open `frontend/src/views/__tests__/TvView.test.js`. Add this test inside the `describe('TvView', ...)` block:

```js
it('has name TvView for keep-alive', () => {
  expect(TvView.name ?? TvView.__name).toBe('TvView')
})
```

- [ ] **Step 2: Run test to verify it fails**

```bash
npm run test -- --reporter=verbose src/views/__tests__/TvView.test.js 2>&1 | tail -15
```

Expected: `has name TvView` FAILS (component has no explicit name yet).

- [ ] **Step 3: Add `defineOptions` to TvView**

Open `frontend/src/views/TvView.vue`. In the `<script setup>` block, add directly after the last `import` line:

```js
defineOptions({ name: 'TvView' })
```

- [ ] **Step 4: Add `<keep-alive>` to App.vue**

Open `frontend/src/App.vue`. Replace `<router-view />` with:

```html
<keep-alive include="MoviesView,TvView">
  <router-view />
</keep-alive>
```

Full updated template section of App.vue:

```html
<template>
  <div id="root">
    <NavBar v-if="authStore.isLoggedIn" />
    <main>
      <keep-alive include="MoviesView,TvView">
        <router-view />
      </keep-alive>
    </main>
  </div>
</template>
```

- [ ] **Step 5: Run all frontend tests**

```bash
npm run test -- --reporter=verbose 2>&1 | tail -30
```

Expected: all tests PASS.

- [ ] **Step 6: Commit**

```bash
git add src/App.vue src/views/TvView.vue src/views/__tests__/TvView.test.js
git commit -m "feat: add keep-alive for MoviesView and TvView, preserves scroll and state (closes #21)"
```

---

## Verification

After all tasks, manually verify at `http://lolobored.local:3615/movies`:

1. Posters load immediately without scrolling — no blank cards at top
2. Watched badge (✓) visible on watched movies without any interaction
3. Movies flow A→B→C continuously with no blank rows between letters
4. Navigate to a movie detail and press Back — movies list and scroll position preserved
5. Alpha sidebar still jumps to correct letter on click
6. Initial page load auto-fetches page 2 without user scrolling (check Network tab)
