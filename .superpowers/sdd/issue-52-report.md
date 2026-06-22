# Issue #52: Live Transcode Concurrency Control — Implementation Report

**Date:** 2026-06-22
**Branch:** main
**Status:** DONE — all tests green, build successful

---

## Files Changed

### Backend (new)
- `backend/src/main/java/org/lolobored/plexdownloader/transcode/ResizableSemaphore.java`
- `backend/src/test/java/org/lolobored/plexdownloader/transcode/ResizableSemaphoreTest.java`
- `backend/src/main/java/org/lolobored/plexdownloader/controller/TranscodeController.java`
- `backend/src/test/java/org/lolobored/plexdownloader/controller/TranscodeControllerTest.java`

### Backend (modified)
- `backend/src/main/java/org/lolobored/plexdownloader/transcode/TranscodeQueueRunner.java`
  - Switched `Semaphore` → `ResizableSemaphore`
  - Added `settings` field (was absent previously, despite being passed to constructor)
  - Added `getMaxConcurrent()` and `setMaxConcurrent(int n)` with clamp + persist
- `backend/src/test/java/org/lolobored/plexdownloader/transcode/TranscodeQueueRunnerTest.java`
  - Added 3 new tests: `setMaxConcurrentUpdatesGetterAndPersists`, `setMaxConcurrentClampsToOneAndPersists`, `getMaxConcurrentReflectsInitialSetting`

### Frontend (new)
- `frontend/src/api/transcode.js` — `getConcurrency()` / `setConcurrency(n)`

### Frontend (modified)
- `frontend/src/views/QueueView.vue`
  - Added concurrency stepper widget inline with queue header (admin-only −/N/+ buttons)
  - Loads via `getConcurrency()` on mount; updates via `setConcurrency(n)` on click
- `frontend/src/views/__tests__/QueueView.test.js`
  - Added `vi.mock` for `transcode.js`, updated `factory()` to accept `role` param
  - Added 6 new concurrency tests
- `frontend/src/views/SettingsView.vue`
  - Removed `maxConcurrent` field from template, reactive `form`, `onMounted`, and `save()` payload
- `frontend/src/views/__tests__/SettingsView.test.js`
  - Removed `transcode.max.concurrent` from `fullSettings()` helper
  - Removed 2 tests: `max-concurrent input reflects...` and `save payload includes transcode.max.concurrent...`

### Plan doc
- `docs/superpowers/plans/2026-06-22-live-transcode-concurrency.md`

---

## ResizableSemaphore Resize Semantics — Test Evidence

All 7 `ResizableSemaphoreTest` tests pass:
- `initialPermitsAvailable` — constructor sets maxPermits and availablePermits correctly
- `increaseReleasesExtraPermits` — `setMaxPermits(4)` from 2 → availablePermits = 4
- `decreaseWhileIdleReducesAvailable` — `setMaxPermits(2)` from 4 → availablePermits = 2
- `decreaseWhileAllHeldGoesNegativeOrZero_thenReleaseAllRestoresToNewMax` — key spec: shrink to 2 while 4 are held → permits go to -2; after release(4) → permits = 2 (new max, NOT old max of 4). In-flight transcodes are unaffected (hold their permits); new acquisitions block until drain.
- `setMaxPermitsZeroClampsToOne` — clamp to 1 on 0
- `setMaxPermitsNegativeClampsToOne` — clamp to 1 on -5
- `increaseFromOneToThreeAvailableIsThree` — increase path

---

## Test Results

### Backend
```
BUILD SUCCESSFUL
All tests passed (full suite including new TranscodeControllerTest x5, ResizableSemaphoreTest x7, TranscodeQueueRunnerTest x5)
```

### Frontend
```
Test Files  19 passed (19)
Tests       185 passed (185)
Build       ✓ built in 142ms
```

---

## Deviations from Plan

1. **`TranscodeQueueRunner` `settings` field**: The original code did NOT store the `settings` parameter as a field (it was only used in the constructor). The plan assumed it was already a field. Fix: added `this.settings = settings` assignment in constructor alongside the new `settings` field declaration.

2. **Controller test pattern**: The plan's `TranscodeControllerTest` matches the existing `AdminControllerTest` pattern exactly (MockMvc + `@SpringBootTest` + `@MockitoBean JwtAuthFilter`). No deviation needed — the existing codebase already used this pattern.

3. **`SettingsView.vue` save button**: The plan mentioned removing "the Save button and ok paragraph that lived directly below the field." In the real template there was indeed a `<button class="btn-save">Save</button>` and `<p v-if="saveOk">Saved.</p>` after the field and before the `<hr>`. These were removed as specified.

---

## Concerns

None. Implementation is complete and straightforward. The double-clamp (in both `setMaxConcurrent` and `ResizableSemaphore.setMaxPermits`) is intentional — belt-and-suspenders for public API contracts.
