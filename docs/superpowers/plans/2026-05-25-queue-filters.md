# Queue Filter Bar Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an always-visible filter bar to `QueueView` — type chips (Movie/TV), status multi-select chips, and a free-text search box — all filtering client-side within existing queue sections.

**Architecture:** Pure Vue 3 `ref`/`computed` filtering in `QueueView.vue`. No store changes, no backend changes. Filter state is local to the view. Each section's computed list gets a filter pass; sections already hide when their list is empty via `v-if`.

**Tech Stack:** Vue 3 Composition API, `@vue/test-utils`, Vitest, existing `useDownloadStore`

---

## File Map

| File | Change |
|------|--------|
| `frontend/src/views/QueueView.vue` | Add filter bar (template + script + style) |
| `frontend/src/views/__tests__/QueueView.test.js` | Add 11 filter tests |

---

### Task 1: Queue filter bar

**Files:**
- Modify: `frontend/src/views/QueueView.vue`
- Modify: `frontend/src/views/__tests__/QueueView.test.js`

**Context:**

`QueueView.vue` has three `computed` sections:
- `inProgress` — `status === 'IN_PROGRESS'`
- `pending` — `status === 'PENDING'`
- `done` — `status === 'DONE' || status === 'ERROR'`

All three sections use `v-if` on their length — they disappear automatically when empty.

The existing factory in the test file:
```js
function factory(items = []) {
  const pinia = createTestingPinia({ createSpy: vi.fn })
  const store = useDownloadStore(pinia)
  store.queueItems = items
  store.fetchQueue = vi.fn()
  return { wrapper: mount(QueueView, { global: { plugins: [pinia] } }), store }
}
```

`DownloadQueueItem` fields relevant to filtering: `title`, `mediaType` (MOVIE/EPISODE/SEASON/SHOW), `status` (PENDING/IN_PROGRESS/DONE/ERROR), `tdarrStatus` (NONE/PROCESSING/TRANSCODED/TDARR_ERROR), `errorMessage`, `tdarrError`.

- [ ] **Step 1: Add failing tests**

Append to the `describe` block in `frontend/src/views/__tests__/QueueView.test.js`, before the closing `})`:

```js
  // ── Filter bar ──────────────────────────────────────────────────────────────

  it('filter bar renders type chips, status chips, and search input', () => {
    const { wrapper } = factory([])
    expect(wrapper.find('[data-testid="chip-type-MOVIE"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="chip-type-TV"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="chip-status-ERROR"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="chip-status-TRANSCODED"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="filter-search"]').exists()).toBe(true)
  })

  it('Movie chip filters out TV items', async () => {
    const { wrapper } = factory([
      { id: 1, mediaType: 'MOVIE', mediaId: 1, title: 'Inception', status: 'DONE',
        tdarrStatus: 'NONE', queuePosition: 1,
        requestedAt: '2026-01-01T00:00:00Z', completedAt: '2026-01-01T01:00:00Z' },
      { id: 2, mediaType: 'EPISODE', mediaId: 2, title: 'Pilot', status: 'DONE',
        tdarrStatus: 'NONE', queuePosition: 2,
        requestedAt: '2026-01-01T00:00:00Z', completedAt: '2026-01-01T01:00:00Z' }
    ])
    await wrapper.find('[data-testid="chip-type-MOVIE"]').trigger('click')
    expect(wrapper.text()).toContain('Inception')
    expect(wrapper.text()).not.toContain('Pilot')
  })

  it('TV chip filters out movie items', async () => {
    const { wrapper } = factory([
      { id: 1, mediaType: 'MOVIE', mediaId: 1, title: 'Inception', status: 'DONE',
        tdarrStatus: 'NONE', queuePosition: 1,
        requestedAt: '2026-01-01T00:00:00Z', completedAt: '2026-01-01T01:00:00Z' },
      { id: 2, mediaType: 'EPISODE', mediaId: 2, title: 'Pilot', status: 'DONE',
        tdarrStatus: 'NONE', queuePosition: 2,
        requestedAt: '2026-01-01T00:00:00Z', completedAt: '2026-01-01T01:00:00Z' }
    ])
    await wrapper.find('[data-testid="chip-type-TV"]').trigger('click')
    expect(wrapper.text()).not.toContain('Inception')
    expect(wrapper.text()).toContain('Pilot')
  })

  it('clicking active type chip resets filter and shows all', async () => {
    const { wrapper } = factory([
      { id: 1, mediaType: 'MOVIE', mediaId: 1, title: 'Inception', status: 'DONE',
        tdarrStatus: 'NONE', queuePosition: 1,
        requestedAt: '2026-01-01T00:00:00Z', completedAt: '2026-01-01T01:00:00Z' },
      { id: 2, mediaType: 'EPISODE', mediaId: 2, title: 'Pilot', status: 'DONE',
        tdarrStatus: 'NONE', queuePosition: 2,
        requestedAt: '2026-01-01T00:00:00Z', completedAt: '2026-01-01T01:00:00Z' }
    ])
    await wrapper.find('[data-testid="chip-type-MOVIE"]').trigger('click')
    await wrapper.find('[data-testid="chip-type-MOVIE"]').trigger('click') // toggle off
    expect(wrapper.text()).toContain('Inception')
    expect(wrapper.text()).toContain('Pilot')
  })

  it('active type chip has active class', async () => {
    const { wrapper } = factory([])
    const chip = wrapper.find('[data-testid="chip-type-MOVIE"]')
    await chip.trigger('click')
    expect(chip.classes()).toContain('active')
  })

  it('status chip ERROR hides non-error items', async () => {
    const { wrapper } = factory([
      { id: 1, mediaType: 'MOVIE', mediaId: 1, title: 'Good', status: 'DONE',
        tdarrStatus: 'TRANSCODED', queuePosition: 1,
        requestedAt: '2026-01-01T00:00:00Z', completedAt: '2026-01-01T01:00:00Z' },
      { id: 2, mediaType: 'MOVIE', mediaId: 2, title: 'Bad', status: 'ERROR',
        tdarrStatus: 'TDARR_ERROR', queuePosition: 2,
        requestedAt: '2026-01-01T00:00:00Z', completedAt: '2026-01-01T01:00:00Z' }
    ])
    await wrapper.find('[data-testid="chip-status-ERROR"]').trigger('click')
    expect(wrapper.text()).not.toContain('Good')
    expect(wrapper.text()).toContain('Bad')
  })

  it('status chips ERROR + TRANSCODED show both', async () => {
    const { wrapper } = factory([
      { id: 1, mediaType: 'MOVIE', mediaId: 1, title: 'Transcoded', status: 'DONE',
        tdarrStatus: 'TRANSCODED', queuePosition: 1,
        requestedAt: '2026-01-01T00:00:00Z', completedAt: '2026-01-01T01:00:00Z' },
      { id: 2, mediaType: 'MOVIE', mediaId: 2, title: 'Failed', status: 'ERROR',
        tdarrStatus: 'TDARR_ERROR', queuePosition: 2,
        requestedAt: '2026-01-01T00:00:00Z', completedAt: '2026-01-01T01:00:00Z' },
      { id: 3, mediaType: 'MOVIE', mediaId: 3, title: 'Queued', status: 'PENDING',
        tdarrStatus: null, queuePosition: 3,
        requestedAt: '2026-01-01T00:00:00Z', completedAt: null }
    ])
    await wrapper.find('[data-testid="chip-status-ERROR"]').trigger('click')
    await wrapper.find('[data-testid="chip-status-TRANSCODED"]').trigger('click')
    expect(wrapper.text()).toContain('Transcoded')
    expect(wrapper.text()).toContain('Failed')
    expect(wrapper.text()).not.toContain('Queued')
  })

  it('active status chip has active class', async () => {
    const { wrapper } = factory([])
    const chip = wrapper.find('[data-testid="chip-status-ERROR"]')
    await chip.trigger('click')
    expect(chip.classes()).toContain('active')
  })

  it('text filter matches title', async () => {
    const { wrapper } = factory([
      { id: 1, mediaType: 'MOVIE', mediaId: 1, title: 'Inception', status: 'DONE',
        tdarrStatus: 'NONE', queuePosition: 1,
        requestedAt: '2026-01-01T00:00:00Z', completedAt: '2026-01-01T01:00:00Z' },
      { id: 2, mediaType: 'MOVIE', mediaId: 2, title: 'Matrix', status: 'DONE',
        tdarrStatus: 'NONE', queuePosition: 2,
        requestedAt: '2026-01-01T00:00:00Z', completedAt: '2026-01-01T01:00:00Z' }
    ])
    await wrapper.find('[data-testid="filter-search"]').setValue('incep')
    expect(wrapper.text()).toContain('Inception')
    expect(wrapper.text()).not.toContain('Matrix')
  })

  it('text filter matches mediaType', async () => {
    const { wrapper } = factory([
      { id: 1, mediaType: 'MOVIE', mediaId: 1, title: 'Inception', status: 'DONE',
        tdarrStatus: 'NONE', queuePosition: 1,
        requestedAt: '2026-01-01T00:00:00Z', completedAt: '2026-01-01T01:00:00Z' },
      { id: 2, mediaType: 'EPISODE', mediaId: 2, title: 'Pilot', status: 'DONE',
        tdarrStatus: 'NONE', queuePosition: 2,
        requestedAt: '2026-01-01T00:00:00Z', completedAt: '2026-01-01T01:00:00Z' }
    ])
    await wrapper.find('[data-testid="filter-search"]').setValue('episode')
    expect(wrapper.text()).not.toContain('Inception')
    expect(wrapper.text()).toContain('Pilot')
  })

  it('text filter matches errorMessage', async () => {
    const { wrapper } = factory([
      { id: 1, mediaType: 'MOVIE', mediaId: 1, title: 'Bad', status: 'ERROR',
        errorMessage: 'disk full', tdarrStatus: 'NONE', queuePosition: 1,
        requestedAt: '2026-01-01T00:00:00Z', completedAt: '2026-01-01T01:00:00Z' },
      { id: 2, mediaType: 'MOVIE', mediaId: 2, title: 'Other', status: 'ERROR',
        errorMessage: 'network timeout', tdarrStatus: 'NONE', queuePosition: 2,
        requestedAt: '2026-01-01T00:00:00Z', completedAt: '2026-01-01T01:00:00Z' }
    ])
    await wrapper.find('[data-testid="filter-search"]').setValue('disk')
    expect(wrapper.text()).toContain('Bad')
    expect(wrapper.text()).not.toContain('Other')
  })
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd frontend && npm run test -- --reporter=verbose 2>&1 | tail -25
```

Expected: 11 new tests FAIL — chips/input not found, items not filtered

- [ ] **Step 3: Replace `<script setup>` in `QueueView.vue`**

Replace the entire `<script setup>` block (lines 74–141) with:

```vue
<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useDownloadStore } from '@/stores/download.js'
import { removeQueueItem, refreshTdarrStatus, retryQueueItem } from '@/api/download.js'

const dlStore = useDownloadStore()
const removing    = ref(new Set())
const refreshing  = ref(new Set())
const retrying    = ref(new Set())

// ── Filter state ─────────────────────────────────────────────────────────────
const typeFilter   = ref('ALL')      // 'ALL' | 'MOVIE' | 'TV'
const statusFilter = ref(new Set())  // Set<string>
const textFilter   = ref('')

const STATUS_CHIPS = [
  { key: 'PENDING',     label: 'Pending' },
  { key: 'COPYING',     label: 'Copying' },
  { key: 'DONE',        label: 'Done' },
  { key: 'TRANSCODING', label: 'Transcoding' },
  { key: 'TRANSCODED',  label: 'Transcoded' },
  { key: 'ERROR',       label: 'Error' },
]

function matchesType(item) {
  if (typeFilter.value === 'ALL') return true
  if (typeFilter.value === 'MOVIE') return item.mediaType === 'MOVIE'
  if (typeFilter.value === 'TV')    return ['EPISODE', 'SEASON', 'SHOW'].includes(item.mediaType)
  return true
}

function matchesStatus(item) {
  if (statusFilter.value.size === 0) return true
  const a = statusFilter.value
  if (a.has('PENDING')     && item.status === 'PENDING') return true
  if (a.has('COPYING')     && item.status === 'IN_PROGRESS') return true
  if (a.has('DONE')        && item.status === 'DONE' && item.tdarrStatus === 'NONE') return true
  if (a.has('TRANSCODING') && item.tdarrStatus === 'PROCESSING') return true
  if (a.has('TRANSCODED')  && item.tdarrStatus === 'TRANSCODED') return true
  if (a.has('ERROR')       && (item.status === 'ERROR' || item.tdarrStatus === 'TDARR_ERROR')) return true
  return false
}

function matchesText(item) {
  const q = textFilter.value.trim().toLowerCase()
  if (!q) return true
  return [item.title, item.mediaType, item.errorMessage, item.tdarrError]
    .filter(Boolean)
    .some(s => s.toLowerCase().includes(q))
}

function setTypeFilter(type) {
  typeFilter.value = typeFilter.value === type ? 'ALL' : type
}

function toggleStatusFilter(status) {
  const next = new Set(statusFilter.value)
  if (next.has(status)) next.delete(status)
  else next.add(status)
  statusFilter.value = next
}

// ── Actions ───────────────────────────────────────────────────────────────────
async function remove(id) {
  removing.value = new Set([...removing.value, id])
  try {
    await removeQueueItem(id)
    await dlStore.fetchQueue()
  } finally {
    const next = new Set(removing.value)
    next.delete(id)
    removing.value = next
  }
}

async function refreshTdarr(id) {
  refreshing.value = new Set([...refreshing.value, id])
  try {
    await refreshTdarrStatus(id)
    await dlStore.fetchQueue()
  } finally {
    const next = new Set(refreshing.value)
    next.delete(id)
    refreshing.value = next
  }
}

async function retryItem(id) {
  retrying.value = new Set([...retrying.value, id])
  try {
    await retryQueueItem(id)
    await dlStore.fetchQueue()
  } catch (e) {
    console.error('Retry failed', e)
  } finally {
    const next = new Set(retrying.value)
    next.delete(id)
    retrying.value = next
  }
}

// ── Filtered section lists ────────────────────────────────────────────────────
const inProgress = computed(() =>
  dlStore.queueItems
    .filter(i => i.status === 'IN_PROGRESS')
    .filter(matchesType)
    .filter(matchesText)
    .filter(matchesStatus)
)
const pending = computed(() =>
  dlStore.queueItems
    .filter(i => i.status === 'PENDING')
    .filter(matchesType)
    .filter(matchesText)
    .filter(matchesStatus)
)
const done = computed(() =>
  dlStore.queueItems
    .filter(i => i.status === 'DONE' || i.status === 'ERROR')
    .filter(matchesType)
    .filter(matchesText)
    .filter(matchesStatus)
)
const allEmpty = computed(() =>
  inProgress.value.length === 0 && pending.value.length === 0 && done.value.length === 0
)

function formatDate(iso) {
  if (!iso) return ''
  return new Date(iso).toLocaleString()
}

let pollTimer = null
onMounted(async () => {
  try {
    await dlStore.fetchQueue()
  } catch (e) {
    console.error('Initial queue fetch failed:', e)
  }
  pollTimer = setInterval(() => dlStore.fetchQueue(), 2000)
})
onUnmounted(() => clearInterval(pollTimer))
</script>
```

- [ ] **Step 4: Add filter bar to `<template>` in `QueueView.vue`**

Replace the opening `<div>` tag and `<h2>` line (currently lines 1–3):

```html
<template>
  <div>
    <h2>Download Queue</h2>

    <div class="filter-bar">
      <div class="filter-group">
        <button type="button" data-testid="chip-type-MOVIE"
                class="chip" :class="{ active: typeFilter === 'MOVIE' }"
                @click="setTypeFilter('MOVIE')">Movie</button>
        <button type="button" data-testid="chip-type-TV"
                class="chip" :class="{ active: typeFilter === 'TV' }"
                @click="setTypeFilter('TV')">TV</button>
      </div>
      <div class="filter-group">
        <button v-for="s in STATUS_CHIPS" :key="s.key"
                type="button"
                :data-testid="'chip-status-' + s.key"
                class="chip" :class="{ active: statusFilter.has(s.key) }"
                @click="toggleStatusFilter(s.key)">{{ s.label }}</button>
      </div>
      <input data-testid="filter-search" v-model="textFilter"
             type="search" class="filter-search" placeholder="Search…" />
    </div>
```

- [ ] **Step 5: Add filter bar styles to `<style scoped>` in `QueueView.vue`**

Add after `h2 { ... }` line (after the existing `h2` rule, before `.empty`):

```css
.filter-bar { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; margin-bottom: 24px; }
.filter-group { display: flex; gap: 6px; flex-wrap: wrap; }
.chip { background: var(--surface2); border: 1px solid var(--border); color: var(--text-muted);
        border-radius: 16px; padding: 4px 12px; font-size: .8rem; cursor: pointer;
        transition: background .15s, color .15s, border-color .15s; }
.chip:hover:not(.active) { border-color: var(--accent-blue); color: var(--text); }
.chip.active { background: var(--accent-blue); border-color: var(--accent-blue); color: #fff; }
.filter-search { background: var(--surface2); border: 1px solid var(--border); color: var(--text);
                 border-radius: 16px; padding: 4px 12px; font-size: .8rem; outline: none;
                 min-width: 160px; }
.filter-search:focus { border-color: var(--accent-blue); }
```

- [ ] **Step 6: Run tests to verify all pass**

```bash
npm run test -- --reporter=verbose 2>&1 | tail -20
```

Expected: all PASS (134+ tests across 19 files)

- [ ] **Step 7: Commit**

```bash
git add frontend/src/views/QueueView.vue \
        frontend/src/views/__tests__/QueueView.test.js
git commit -m "feat: add type/status/text filter bar to queue view closes #20"
```

---

## Verification

```bash
cd frontend && npm run test   # all green
```

Manual:
1. Open queue page
2. Click **Movie** chip → only movie items visible across all sections
3. Click **Movie** again → all items reappear
4. Click **Error** + **Transcoded** → both error and transcoded items visible, others hidden
5. Type in search box → items filter live across all sections
6. Active chips appear with blue filled background
