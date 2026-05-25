# MoviesView / TvView UX Fixes — Design

**Issue:** #21 — lazy load of posters not working properly

## Problems

1. **Forced line breaks between letter groups** — `letter-anchor` div has `grid-column: 1/-1`, forcing a full-width row between each letter. Movies should flow in a continuous grid.
2. **Watched badge invisible until scroll** — `loading="lazy"` on `<img>` creates a browser compositing layer that hides absolutely-positioned overlays until the image loads.
3. **State and scroll position lost on back navigation** — no `<keep-alive>`; component destroyed on every navigation → movies list re-fetched, scroll reset to top.
4. **Pages not auto-loaded on initial render** — `IntersectionObserver` `rootMargin: '400px'` too small; sentinel sits ~1800px from top, trigger zone only reaches ~1300px, so page 2 never loads without user scrolling.

## Solution

Approach A: flat grid + card-based letter refs + drop `loading="lazy"` + `<keep-alive>` + larger rootMargin.

## Architecture

### 1. Grid restructuring — `MoviesView.vue`

Remove `letter-anchor` divs from the grid entirely. Render `sortedMovies` as a flat `v-for` on `PosterCard`. A computed map `firstMovieOfLetter: Map<letter, movieId>` identifies which card is first for each letter. That card gets `id="letter-X"`, a ref in `sectionRefs`, and `scroll-margin-top: 70px` via a CSS class.

`groupedMovies` computed is removed. `scrollToLetter` and `updateActiveLetter` work identically — they still use `sectionRefs[letter]`, now pointing to the first PosterCard's root element.

On `reset()`, `sectionRefs` is cleared as before.

### 2. PosterCard — drop `loading="lazy"`

Remove `loading="lazy"` from `<img>`. Images load in DOM order; with keep-alive images are fetched once per session and browser-cached on return visits. Eliminates the compositing issue causing the watched badge to be hidden.

No other PosterCard changes.

### 3. Keep-alive — `App.vue` + component naming

`App.vue`: replace `<router-view />` with:
```html
<keep-alive include="MoviesView,TvView">
  <router-view />
</keep-alive>
```

`MoviesView.vue`: add `defineOptions({ name: 'MoviesView' })` + `onActivated` hook that calls `updateActiveLetter()` so the alpha sidebar stays in sync after re-activation.
`TvView.vue`: add `defineOptions({ name: 'TvView' })` only — no alpha sidebar, no `onActivated` needed.

### 4. IntersectionObserver margin — `MoviesView.vue`

Change `{ rootMargin: '400px' }` → `{ rootMargin: '1200px' }`.
Change manual sentinel check: `window.innerHeight + 400` → `window.innerHeight + 1200`.

With viewport ~900px and sentinel at ~1800px, trigger zone bottom = 900 + 1200 = 2100px > 1800px → page 2 loads automatically on initial render without user scrolling.

## Files Modified

| File | Change |
|------|--------|
| `frontend/src/views/MoviesView.vue` | Remove anchor divs, flat grid, card refs, `defineOptions`, `onActivated`, rootMargin 1200px |
| `frontend/src/views/TvView.vue` | Add `defineOptions({ name: 'TvView' })` + `onActivated` |
| `frontend/src/components/PosterCard.vue` | Remove `loading="lazy"` from `<img>` |
| `frontend/src/App.vue` | Add `<keep-alive include="MoviesView,TvView">` |

## Tests

Frontend unit tests cover `MoviesView`:
- `firstMovieOfLetter returns correct ids` — computed map has right letter→id pairs
- `flat grid renders all sorted movies` — sortedMovies directly rendered, no anchor divs in output
- `isFirstOfLetter only true for first card per letter` — second movie of same letter returns false

`PosterCard` tests:
- `watched badge renders when watched=true` — badge present in DOM regardless of image state

Keep-alive and scroll behavior are integration concerns — verified manually via the deployed app at `http://lolobored.local:3615/movies`.

## Ticket

Commits reference `closes #21`. Issue body updated with full description before push.
