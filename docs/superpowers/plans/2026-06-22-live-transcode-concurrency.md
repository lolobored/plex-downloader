# Live Transcode Concurrency Control Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move "max concurrent transcodes" control from Settings into the Queue view, backed by a resizable semaphore that adjusts the worker pool without a restart.

**Architecture:** A `ResizableSemaphore` subclass of `java.util.concurrent.Semaphore` exposes a synchronized `setMaxPermits(int)` that delta-adjusts permits in-place. `TranscodeQueueRunner` switches to this semaphore and gains `getMaxConcurrent()`/`setMaxConcurrent()`. A new `TranscodeController` exposes GET/PUT `/api/transcode/concurrency`. The frontend adds `getConcurrency()`/`setConcurrency(n)` in a new `transcode.js` API file, wires them into `QueueView.vue`, and removes the max-concurrent input from `SettingsView.vue`.

**Tech Stack:** Java 21, Spring Boot 3, Mockito/JUnit 5, Vue 3 (Options-free Composition API), Pinia, Vitest/Vue Test Utils, Axios.

## Global Constraints

- Java package root: `org.lolobored.plexdownloader`
- Java 21 (SDKMAN, `21.0.4-tem`); activate before every Gradle command: `source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem`
- Frontend root: `/Users/laurentlaborde/projects/plex-downloader/frontend`
- Backend root: `/Users/laurentlaborde/projects/plex-downloader/backend`
- All commits must reference GitHub issue #52; final commit uses `closes #52`
- No `git add -A` — stage files explicitly by path
- Semaphore decrease must NOT kill in-flight transcodes; in-flight finish, new starts are throttled

---

### Task 1: ResizableSemaphore + unit test

**Files:**
- Create: `backend/src/main/java/org/lolobored/plexdownloader/transcode/ResizableSemaphore.java`
- Create: `backend/src/test/java/org/lolobored/plexdownloader/transcode/ResizableSemaphoreTest.java`

**Interfaces:**
- Produces: `ResizableSemaphore(int initialPermits)` constructor; `synchronized void setMaxPermits(int newMax)` (clamp >=1, delta-adjusts); `int getMaxPermits()`

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/org/lolobored/plexdownloader/transcode/ResizableSemaphoreTest.java`:

```java
package org.lolobored.plexdownloader.transcode;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ResizableSemaphoreTest {

    @Test
    void initialPermitsAvailable() {
        ResizableSemaphore s = new ResizableSemaphore(2);
        assertThat(s.getMaxPermits()).isEqualTo(2);
        assertThat(s.availablePermits()).isEqualTo(2);
    }

    @Test
    void increaseReleasesExtraPermits() {
        ResizableSemaphore s = new ResizableSemaphore(2);
        s.setMaxPermits(4);
        assertThat(s.getMaxPermits()).isEqualTo(4);
        assertThat(s.availablePermits()).isEqualTo(4);
    }

    @Test
    void decreaseWhileIdleReducesAvailable() {
        ResizableSemaphore s = new ResizableSemaphore(4);
        s.setMaxPermits(2);
        assertThat(s.getMaxPermits()).isEqualTo(2);
        assertThat(s.availablePermits()).isEqualTo(2);
    }

    @Test
    void decreaseWhileAllHeldGoesNegativeOrZero_thenReleaseAllRestoresToNewMax() throws Exception {
        ResizableSemaphore s = new ResizableSemaphore(2);
        s.setMaxPermits(4);
        // Acquire all 4
        s.acquire(4);
        assertThat(s.availablePermits()).isEqualTo(0);

        // Shrink to 2 while 4 are held → availablePermits goes to -2 (reducePermits reduces by 2)
        s.setMaxPermits(2);
        assertThat(s.availablePermits()).isLessThanOrEqualTo(0);

        // Release all 4 → availablePermits should be 2 (the new max), not 4
        s.release(4);
        assertThat(s.availablePermits()).isEqualTo(2);
        assertThat(s.getMaxPermits()).isEqualTo(2);
    }

    @Test
    void setMaxPermitsZeroClampsToOne() {
        ResizableSemaphore s = new ResizableSemaphore(2);
        s.setMaxPermits(0);
        assertThat(s.getMaxPermits()).isEqualTo(1);
        assertThat(s.availablePermits()).isEqualTo(1);
    }

    @Test
    void setMaxPermitsNegativeClampsToOne() {
        ResizableSemaphore s = new ResizableSemaphore(2);
        s.setMaxPermits(-5);
        assertThat(s.getMaxPermits()).isEqualTo(1);
        assertThat(s.availablePermits()).isEqualTo(1);
    }

    @Test
    void increaseFromOneToThreeAvailableIsThree() {
        ResizableSemaphore s = new ResizableSemaphore(1);
        s.setMaxPermits(3);
        assertThat(s.availablePermits()).isEqualTo(3);
        assertThat(s.getMaxPermits()).isEqualTo(3);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem; cd /Users/laurentlaborde/projects/plex-downloader/backend && ./gradlew test --tests "org.lolobored.plexdownloader.transcode.ResizableSemaphoreTest" 2>&1 | tail -30
```
Expected: COMPILE ERROR or test failure (class does not exist yet)

- [ ] **Step 3: Implement ResizableSemaphore**

Create `backend/src/main/java/org/lolobored/plexdownloader/transcode/ResizableSemaphore.java`:

```java
package org.lolobored.plexdownloader.transcode;

import java.util.concurrent.Semaphore;

/**
 * A Semaphore whose maximum permit count can be adjusted at runtime.
 * <p>
 * Increasing the max releases extra permits immediately, allowing waiting
 * threads to proceed.  Decreasing the max reduces the available permits
 * (possibly going negative if permits are already held), so in-flight
 * work is unaffected but new acquisitions are throttled until the pool
 * drains to the new bound.
 */
public class ResizableSemaphore extends Semaphore {

    private int maxPermits;

    public ResizableSemaphore(int initialPermits) {
        super(initialPermits);
        this.maxPermits = initialPermits;
    }

    public synchronized void setMaxPermits(int newMax) {
        newMax = Math.max(1, newMax);
        int delta = newMax - this.maxPermits;
        this.maxPermits = newMax;
        if (delta > 0) {
            release(delta);
        } else if (delta < 0) {
            reducePermits(-delta);   // reducePermits is protected on Semaphore — accessible here
        }
        // delta == 0: no-op
    }

    public synchronized int getMaxPermits() {
        return maxPermits;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem; cd /Users/laurentlaborde/projects/plex-downloader/backend && ./gradlew test --tests "org.lolobored.plexdownloader.transcode.ResizableSemaphoreTest" 2>&1 | tail -30
```
Expected: BUILD SUCCESSFUL, all 7 tests GREEN

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/org/lolobored/plexdownloader/transcode/ResizableSemaphore.java \
        backend/src/test/java/org/lolobored/plexdownloader/transcode/ResizableSemaphoreTest.java
git commit -m "feat: ResizableSemaphore with runtime permit adjustment, refs #52"
```

---

### Task 2: Wire ResizableSemaphore into TranscodeQueueRunner + extend tests

**Files:**
- Modify: `backend/src/main/java/org/lolobored/plexdownloader/transcode/TranscodeQueueRunner.java`
- Modify: `backend/src/test/java/org/lolobored/plexdownloader/transcode/TranscodeQueueRunnerTest.java`

**Interfaces:**
- Consumes: `ResizableSemaphore(int)`, `setMaxPermits(int)`, `getMaxPermits()` from Task 1
- Consumes: `SettingsService.set(String key, String value)` (existing)
- Produces: `int getMaxConcurrent()`, `void setMaxConcurrent(int n)` on `TranscodeQueueRunner`

- [ ] **Step 1: Write the new failing tests first**

Open `backend/src/test/java/org/lolobored/plexdownloader/transcode/TranscodeQueueRunnerTest.java` and add these two tests (after the existing ones):

```java
    @Test
    void setMaxConcurrentUpdatesGetterAndPersists() {
        TranscodeQueueRunner r = runner();
        r.setMaxConcurrent(4);
        assertThat(r.getMaxConcurrent()).isEqualTo(4);
        verify(settings).set("transcode.max.concurrent", "4");
    }

    @Test
    void setMaxConcurrentClampsToOneAndPersists() {
        TranscodeQueueRunner r = runner();
        r.setMaxConcurrent(0);
        assertThat(r.getMaxConcurrent()).isEqualTo(1);
        verify(settings).set("transcode.max.concurrent", "1");
    }

    @Test
    void getMaxConcurrentReflectsInitialSetting() {
        TranscodeQueueRunner r = runner();
        assertThat(r.getMaxConcurrent()).isEqualTo(2);
    }
```

- [ ] **Step 2: Run tests to see them fail**

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem; cd /Users/laurentlaborde/projects/plex-downloader/backend && ./gradlew test --tests "org.lolobored.plexdownloader.transcode.TranscodeQueueRunnerTest" 2>&1 | tail -30
```
Expected: COMPILE ERROR or failures on the new tests (methods don't exist yet)

- [ ] **Step 3: Update TranscodeQueueRunner**

Replace the contents of `backend/src/main/java/org/lolobored/plexdownloader/transcode/TranscodeQueueRunner.java` with:

```java
package org.lolobored.plexdownloader.transcode;

import org.lolobored.plexdownloader.model.DownloadQueueItem;
import org.lolobored.plexdownloader.repository.DownloadQueueRepository;
import org.lolobored.plexdownloader.service.SettingsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Component
public class TranscodeQueueRunner {

    private final DownloadQueueRepository queueRepo;
    private final TranscodeService transcodeService;
    private final SettingsService settings;
    private final ResizableSemaphore permits;
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "transcode-worker");
        t.setDaemon(true);
        return t;
    });

    public TranscodeQueueRunner(DownloadQueueRepository queueRepo,
                                TranscodeService transcodeService,
                                SettingsService settings) {
        this.queueRepo = queueRepo;
        this.transcodeService = transcodeService;
        this.settings = settings;
        int max = parseMax(settings.get("transcode.max.concurrent").orElse("2"));
        this.permits = new ResizableSemaphore(max);
        log.info("Transcode worker: max concurrent = {}", max);
    }

    private int parseMax(String v) {
        try { return Math.max(1, Integer.parseInt(v.trim())); }
        catch (NumberFormatException e) { return 2; }
    }

    public int getMaxConcurrent() {
        return permits.getMaxPermits();
    }

    public void setMaxConcurrent(int n) {
        int clamped = Math.max(1, n);
        permits.setMaxPermits(clamped);
        settings.set("transcode.max.concurrent", String.valueOf(clamped));
        log.info("Transcode worker: max concurrent changed to {}", clamped);
    }

    @EventListener
    public void onRequested(TranscodeRequestedEvent e) {
        submit(e.itemId());
    }

    public void submit(Long itemId) {
        pool.submit(() -> {
            try {
                permits.acquire();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                transcodeService.transcode(itemId);
            } catch (Exception ex) {
                log.error("Transcode worker error for item {}: {}", itemId, ex.getMessage(), ex);
            } finally {
                permits.release();
            }
        });
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        for (DownloadQueueItem stuck : queueRepo.findByStatus(DownloadQueueItem.Status.TRANSCODING)) {
            stuck.setStatus(DownloadQueueItem.Status.QUEUED);
            stuck.setProgressPercent(null);
            queueRepo.save(stuck);
            log.info("Recovered interrupted transcode: item={}", stuck.getId());
        }
        for (DownloadQueueItem q : queueRepo.findByStatusOrderByQueuePositionAsc(DownloadQueueItem.Status.QUEUED)) {
            submit(q.getId());
        }
    }
}
```

- [ ] **Step 4: Run all transcode tests**

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem; cd /Users/laurentlaborde/projects/plex-downloader/backend && ./gradlew test --tests "org.lolobored.plexdownloader.transcode.*" 2>&1 | tail -30
```
Expected: BUILD SUCCESSFUL, all tests GREEN (existing 2 + new 3 in TranscodeQueueRunnerTest, 7 in ResizableSemaphoreTest)

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/org/lolobored/plexdownloader/transcode/TranscodeQueueRunner.java \
        backend/src/test/java/org/lolobored/plexdownloader/transcode/TranscodeQueueRunnerTest.java
git commit -m "feat: wire ResizableSemaphore into TranscodeQueueRunner with get/setMaxConcurrent, refs #52"
```

---

### Task 3: TranscodeController + unit test

**Files:**
- Create: `backend/src/main/java/org/lolobored/plexdownloader/controller/TranscodeController.java`
- Create: `backend/src/test/java/org/lolobored/plexdownloader/controller/TranscodeControllerTest.java`

**Interfaces:**
- Consumes: `TranscodeQueueRunner.getMaxConcurrent()`, `TranscodeQueueRunner.setMaxConcurrent(int)` from Task 2
- Produces:
  - `GET /api/transcode/concurrency` → `{ "maxConcurrent": N }` (any authenticated user)
  - `PUT /api/transcode/concurrency` body `{ "maxConcurrent": N }` (ADMIN only) → `{ "maxConcurrent": N }`

- [ ] **Step 1: Write the failing controller test**

Create `backend/src/test/java/org/lolobored/plexdownloader/controller/TranscodeControllerTest.java`:

```java
package org.lolobored.plexdownloader.controller;

import tools.jackson.databind.ObjectMapper;
import org.lolobored.plexdownloader.config.JwtAuthFilter;
import org.lolobored.plexdownloader.model.User;
import org.lolobored.plexdownloader.service.JwtService;
import org.lolobored.plexdownloader.repository.UserRepository;
import org.lolobored.plexdownloader.transcode.TranscodeQueueRunner;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
class TranscodeControllerTest {

    MockMvc mockMvc;
    final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired WebApplicationContext webApplicationContext;
    @MockitoBean TranscodeQueueRunner runner;
    @MockitoBean JwtService jwtService;
    @MockitoBean UserRepository userRepository;
    @MockitoBean JwtAuthFilter jwtAuthFilter;

    private void setupAuth(User.Role role) throws Exception {
        User user = new User();
        user.setId(1L);
        user.setUsername("user");
        user.setRole(role);

        String springRole = "ROLE_" + role.name();

        doAnswer((InvocationOnMock inv) -> {
            HttpServletRequest req = inv.getArgument(0);
            HttpServletResponse res = inv.getArgument(1);
            FilterChain chain = inv.getArgument(2);

            SecurityContext ctx = SecurityContextHolder.createEmptyContext();
            ctx.setAuthentication(new UsernamePasswordAuthenticationToken(
                user, null, List.of(new SimpleGrantedAuthority(springRole))));
            SecurityContextHolder.setContext(ctx);

            chain.doFilter(req, res);
            return null;
        }).when(jwtAuthFilter).doFilter(any(), any(), any());
    }

    @BeforeEach
    void setup() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
            .apply(SecurityMockMvcConfigurers.springSecurity())
            .build();
        when(runner.getMaxConcurrent()).thenReturn(2);
    }

    @Test
    void getConcurrencyReturnsCurrentMax() throws Exception {
        setupAuth(User.Role.USER);
        mockMvc.perform(get("/api/transcode/concurrency"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.maxConcurrent").value(2));
    }

    @Test
    void getConcurrencyAccessibleByNonAdmin() throws Exception {
        setupAuth(User.Role.USER);
        mockMvc.perform(get("/api/transcode/concurrency"))
            .andExpect(status().isOk());
    }

    @Test
    void putConcurrencyDelegatesSetAndReturnsNewValue() throws Exception {
        setupAuth(User.Role.ADMIN);
        when(runner.getMaxConcurrent()).thenReturn(4);

        mockMvc.perform(put("/api/transcode/concurrency")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("maxConcurrent", 4))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.maxConcurrent").value(4));

        verify(runner).setMaxConcurrent(4);
    }

    @Test
    void putConcurrencyRequiresAdmin() throws Exception {
        setupAuth(User.Role.USER);
        mockMvc.perform(put("/api/transcode/concurrency")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("maxConcurrent", 4))))
            .andExpect(status().isForbidden());
    }

    @Test
    void putConcurrencyRejectsBelowOne() throws Exception {
        setupAuth(User.Role.ADMIN);
        mockMvc.perform(put("/api/transcode/concurrency")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("maxConcurrent", 0))))
            .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem; cd /Users/laurentlaborde/projects/plex-downloader/backend && ./gradlew test --tests "org.lolobored.plexdownloader.controller.TranscodeControllerTest" 2>&1 | tail -30
```
Expected: COMPILE ERROR (controller class does not exist yet)

- [ ] **Step 3: Create TranscodeController**

Create `backend/src/main/java/org/lolobored/plexdownloader/controller/TranscodeController.java`:

```java
package org.lolobored.plexdownloader.controller;

import org.lolobored.plexdownloader.transcode.TranscodeQueueRunner;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/transcode")
@RequiredArgsConstructor
public class TranscodeController {

    private final TranscodeQueueRunner runner;

    public record ConcurrencyResponse(int maxConcurrent) {}
    public record ConcurrencyRequest(int maxConcurrent) {}

    @GetMapping("/concurrency")
    public ConcurrencyResponse getConcurrency() {
        return new ConcurrencyResponse(runner.getMaxConcurrent());
    }

    @PutMapping("/concurrency")
    @PreAuthorize("hasRole('ADMIN')")
    public ConcurrencyResponse setConcurrency(@RequestBody ConcurrencyRequest req) {
        if (req.maxConcurrent() < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "maxConcurrent must be >= 1");
        }
        runner.setMaxConcurrent(req.maxConcurrent());
        return new ConcurrencyResponse(runner.getMaxConcurrent());
    }
}
```

- [ ] **Step 4: Run controller test**

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem; cd /Users/laurentlaborde/projects/plex-downloader/backend && ./gradlew test --tests "org.lolobored.plexdownloader.controller.TranscodeControllerTest" 2>&1 | tail -30
```
Expected: BUILD SUCCESSFUL, all 5 tests GREEN

- [ ] **Step 5: Run full backend test suite**

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem; cd /Users/laurentlaborde/projects/plex-downloader/backend && ./gradlew test 2>&1 | tail -30
```
Expected: BUILD SUCCESSFUL, all tests GREEN (no regressions)

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/org/lolobored/plexdownloader/controller/TranscodeController.java \
        backend/src/test/java/org/lolobored/plexdownloader/controller/TranscodeControllerTest.java
git commit -m "feat: GET/PUT /api/transcode/concurrency endpoints, refs #52"
```

---

### Task 4: Frontend API module for transcode concurrency

**Files:**
- Create: `frontend/src/api/transcode.js`

**Interfaces:**
- Consumes: `http` axios instance from `./axios.js`
- Produces:
  - `getConcurrency()` → `Promise<number>` (the maxConcurrent value)
  - `setConcurrency(n: number)` → `Promise<number>` (the new maxConcurrent from server response)

- [ ] **Step 1: Create the API module**

Create `frontend/src/api/transcode.js`:

```js
import http from './axios.js'

/**
 * GET /api/transcode/concurrency
 * Returns the current max concurrent transcode count.
 */
export async function getConcurrency() {
  const { data } = await http.get('/api/transcode/concurrency')
  return data.maxConcurrent
}

/**
 * PUT /api/transcode/concurrency
 * Sets the max concurrent transcode count.
 * Returns the confirmed value from the server.
 */
export async function setConcurrency(n) {
  const { data } = await http.put('/api/transcode/concurrency', { maxConcurrent: n })
  return data.maxConcurrent
}
```

- [ ] **Step 2: Verify frontend builds with no errors**

```bash
cd /Users/laurentlaborde/projects/plex-downloader/frontend && npm run build 2>&1 | tail -20
```
Expected: Build succeeded (no import errors for the new file since it's not yet consumed — that comes in Task 5)

- [ ] **Step 3: Commit**

```bash
git add frontend/src/api/transcode.js
git commit -m "feat: transcode concurrency API module (getConcurrency/setConcurrency), refs #52"
```

---

### Task 5: QueueView concurrency stepper

**Files:**
- Modify: `frontend/src/views/QueueView.vue`
- Modify: `frontend/src/views/__tests__/QueueView.test.js`

**Interfaces:**
- Consumes: `getConcurrency()`, `setConcurrency(n)` from `../api/transcode.js`
- Consumes: `useAuthStore` from `@/stores/auth.js` (`.isAdmin` computed)
- Produces: inline concurrency control near queue header (admin-only steppers); read-only value display for non-admins

**UX spec:**
- Displayed inline to the right of the `<h2>Download Queue</h2>` header
- Shows: `Concurrent transcodes: [−] N [+]` where N is the live server value
- Admin sees active − and + buttons; non-admin sees the value but buttons are disabled (or hidden — hidden is simpler and matches the spec's "gate the steppers")
- On mount: call `getConcurrency()` to populate `maxConcurrent` ref
- On click −: if `maxConcurrent > 1`, decrement, call `setConcurrency(newValue)`, update from server response
- On click +: increment, call `setConcurrency(newValue)`, update from server response
- If `setConcurrency` throws (e.g. 403), catch silently (the value stays unchanged)
- data-testid values: `concurrency-label`, `concurrency-btn-dec`, `concurrency-btn-inc`, `concurrency-value`

- [ ] **Step 1: Write the failing tests for the concurrency control**

Open `frontend/src/views/__tests__/QueueView.test.js` and add a new `vi.mock` for the transcode API plus new tests. Add this mock at the top with the existing mocks:

```js
vi.mock('../../api/transcode.js', () => ({
  getConcurrency: vi.fn().mockResolvedValue(2),
  setConcurrency: vi.fn().mockResolvedValue(3)
}))
```

Import it alongside the other imports:

```js
import * as transcodeApi from '../../api/transcode.js'
```

Update the `factory` function to accept a `role` parameter and add auth store setup:

```js
  function factory(items = [], role = 'USER') {
    const pinia = createTestingPinia({ createSpy: vi.fn,
      initialState: { auth: { role } } })
    const store = useDownloadStore(pinia)
    store.queueItems = items
    store.fetchQueue = vi.fn()
    return { wrapper: mount(QueueView, { global: { plugins: [pinia] } }), store }
  }
```

Add these tests inside the `describe('QueueView', ...)` block:

```js
  // ── Concurrency control ──────────────────────────────────────────────────────

  it('loads concurrency on mount and displays value', async () => {
    const { wrapper } = factory([], 'ADMIN')
    await flushPromises()
    expect(transcodeApi.getConcurrency).toHaveBeenCalled()
    expect(wrapper.find('[data-testid="concurrency-value"]').text()).toBe('2')
  })

  it('shows concurrency label', async () => {
    const { wrapper } = factory([], 'ADMIN')
    await flushPromises()
    expect(wrapper.find('[data-testid="concurrency-label"]').exists()).toBe(true)
  })

  it('increment button calls setConcurrency with n+1 and updates display', async () => {
    transcodeApi.setConcurrency.mockResolvedValue(3)
    const { wrapper } = factory([], 'ADMIN')
    await flushPromises()
    await wrapper.find('[data-testid="concurrency-btn-inc"]').trigger('click')
    await flushPromises()
    expect(transcodeApi.setConcurrency).toHaveBeenCalledWith(3)
    expect(wrapper.find('[data-testid="concurrency-value"]').text()).toBe('3')
  })

  it('decrement button calls setConcurrency with n-1 and updates display', async () => {
    transcodeApi.getConcurrency.mockResolvedValue(3)
    transcodeApi.setConcurrency.mockResolvedValue(2)
    const { wrapper } = factory([], 'ADMIN')
    await flushPromises()
    await wrapper.find('[data-testid="concurrency-btn-dec"]').trigger('click')
    await flushPromises()
    expect(transcodeApi.setConcurrency).toHaveBeenCalledWith(2)
    expect(wrapper.find('[data-testid="concurrency-value"]').text()).toBe('2')
  })

  it('decrement button disabled when value is 1', async () => {
    transcodeApi.getConcurrency.mockResolvedValue(1)
    const { wrapper } = factory([], 'ADMIN')
    await flushPromises()
    const btn = wrapper.find('[data-testid="concurrency-btn-dec"]')
    expect(btn.element.disabled).toBe(true)
  })

  it('stepper buttons hidden for non-admin', async () => {
    const { wrapper } = factory([], 'USER')
    await flushPromises()
    expect(wrapper.find('[data-testid="concurrency-btn-dec"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="concurrency-btn-inc"]').exists()).toBe(false)
  })
```

- [ ] **Step 2: Run tests to see them fail**

```bash
cd /Users/laurentlaborde/projects/plex-downloader/frontend && npm run test 2>&1 | grep -E "(FAIL|PASS|Error|concurrency)" | head -30
```
Expected: failures on the new concurrency tests

- [ ] **Step 3: Update QueueView.vue**

In `frontend/src/views/QueueView.vue`, make these changes:

**In `<script setup>` — add imports (after the existing imports):**
```js
import { useAuthStore } from '@/stores/auth.js'
import { getConcurrency, setConcurrency } from '@/api/transcode.js'
```

**In `<script setup>` — add state and handlers (after existing ref declarations):**
```js
const authStore      = useAuthStore()
const maxConcurrent  = ref(2)

async function loadConcurrency() {
  try { maxConcurrent.value = await getConcurrency() } catch (e) { /* ignore */ }
}

async function changeConcurrency(delta) {
  const next = Math.max(1, maxConcurrent.value + delta)
  try {
    maxConcurrent.value = await setConcurrency(next)
  } catch (e) {
    console.error('setConcurrency failed', e)
  }
}
```

**In `onMounted` — add `loadConcurrency()` call:**
```js
onMounted(async () => {
  try { await dlStore.fetchQueue() } catch (e) { console.error('Initial queue fetch failed:', e) }
  pollTimer = setInterval(() => dlStore.fetchQueue(), 2000)
  await loadConcurrency()
})
```

**In `<template>` — replace the `<h2>` line with a header row that includes the concurrency control:**

Replace:
```html
    <h2>Download Queue <span v-if="totalVisible > 0" class="count-badge" data-testid="count-badge">{{ totalVisible }}</span></h2>
```

With:
```html
    <div class="queue-header">
      <h2>Download Queue <span v-if="totalVisible > 0" class="count-badge" data-testid="count-badge">{{ totalVisible }}</span></h2>
      <div class="concurrency-control" data-testid="concurrency-label">
        <span class="concurrency-label-text">Concurrent transcodes</span>
        <template v-if="authStore.isAdmin">
          <button class="concurrency-btn" data-testid="concurrency-btn-dec"
                  :disabled="maxConcurrent <= 1"
                  @click="changeConcurrency(-1)">−</button>
        </template>
        <span class="concurrency-value" data-testid="concurrency-value">{{ maxConcurrent }}</span>
        <template v-if="authStore.isAdmin">
          <button class="concurrency-btn" data-testid="concurrency-btn-inc"
                  @click="changeConcurrency(1)">+</button>
        </template>
      </div>
    </div>
```

**In `<style scoped>` — add styles for the new elements:**
```css
.queue-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 24px; }
.queue-header h2 { margin-bottom: 0; }
.concurrency-control { display: flex; align-items: center; gap: 8px; font-size: .85rem;
                       color: var(--text-muted); background: var(--surface2);
                       border: 1px solid var(--border); border-radius: 8px; padding: 6px 12px; }
.concurrency-label-text { font-size: .8rem; color: var(--text-muted); white-space: nowrap; }
.concurrency-value { font-weight: 700; color: var(--text); min-width: 20px; text-align: center; font-size: .95rem; }
.concurrency-btn { background: var(--surface); border: 1px solid var(--border); color: var(--text);
                   border-radius: 4px; width: 26px; height: 26px; font-size: 1rem; cursor: pointer;
                   display: flex; align-items: center; justify-content: center; }
.concurrency-btn:hover:not(:disabled) { border-color: var(--accent-blue); color: var(--accent-blue); }
.concurrency-btn:disabled { opacity: 0.35; cursor: not-allowed; }
```

Also remove the old standalone `h2` style that had `margin-bottom: 24px` on it (since the header div now owns that margin). Find and update in `<style scoped>`:

Replace:
```css
h2 { font-size: 1.5rem; font-weight: 600; margin-bottom: 24px; }
```
With:
```css
h2 { font-size: 1.5rem; font-weight: 600; margin-bottom: 0; }
```

- [ ] **Step 4: Run frontend tests**

```bash
cd /Users/laurentlaborde/projects/plex-downloader/frontend && npm run test 2>&1 | tail -40
```
Expected: all tests GREEN

- [ ] **Step 5: Build frontend**

```bash
cd /Users/laurentlaborde/projects/plex-downloader/frontend && npm run build 2>&1 | tail -20
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add frontend/src/views/QueueView.vue \
        frontend/src/views/__tests__/QueueView.test.js
git commit -m "feat: concurrency stepper in QueueView (admin-only), refs #52"
```

---

### Task 6: Remove max-concurrent from SettingsView + update its tests

**Files:**
- Modify: `frontend/src/views/SettingsView.vue`
- Modify: `frontend/src/views/__tests__/SettingsView.test.js`

**What to remove from SettingsView.vue:**
1. The `<div class="field">` block containing `<input name="maxConcurrent" .../>` and its label (lines 80–89 in the original)
2. `maxConcurrent: 2` from the `form` reactive object
3. The `form.maxConcurrent = Number(...)` assignment in `onMounted`
4. The `'transcode.max.concurrent': String(form.maxConcurrent)` line from the `save()` payload
5. The `<button class="btn-save">Save</button>` and `<p v-if="saveOk">` that lived inside the Transcoding section directly below the field (the ones between the field and the `<hr>`) — only those, not the ones in other sections

**What to remove from SettingsView.test.js:**
- The test `'max-concurrent input reflects transcode.max.concurrent from settings'` (around line 150)
- The test `'save payload includes transcode.max.concurrent and only expected keys'` (around line 158)
- The `'transcode.max.concurrent': '3'` key from `fullSettings()` helper (the backend still returns it but the frontend no longer reads or writes it)

**What to keep:**
- The Transcoding section heading (`<h3>Transcoding</h3>`)
- The `<hr class="section-divider" />` and the Quality Profiles CRUD below it
- The `fullSettings()` helper itself (minus the transcode key) — other tests use it
- All other tests — they must remain green

- [ ] **Step 1: Write the updated test file first (TDD: remove the assertions that test removed behavior)**

Open `frontend/src/views/__tests__/SettingsView.test.js`.

Update `fullSettings()` — remove `'transcode.max.concurrent': '3'`:
```js
function fullSettings(overrides = {}) {
  return {
    'plex.server.url':           'http://localhost:32400',
    'plex.sync.cron':            '0 0 */6 * * *',
    'plex.sync.libraries':       '1',
    ...overrides
  }
}
```

Remove these two tests entirely:
- `it('max-concurrent input reflects transcode.max.concurrent from settings', ...)`
- `it('save payload includes transcode.max.concurrent and only expected keys', ...)`

After removal, the `// ── Transcoding section ──` comment block should only have:
```js
  // ── Transcoding section ──────────────────────────────────────────────────────

  it('renders Transcoding section heading', async () => {
    const w = factory()
    await flushPromises()
    expect(w.text()).toContain('Transcoding')
  })
```

- [ ] **Step 2: Run SettingsView tests to see them pass as-is (or verify what fails)**

```bash
cd /Users/laurentlaborde/projects/plex-downloader/frontend && npm run test -- --reporter=verbose 2>&1 | grep -E "(SettingsView|PASS|FAIL)" | head -30
```
The two removed tests are gone; remaining tests should pass once SettingsView is updated in Step 3.

- [ ] **Step 3: Update SettingsView.vue**

**Remove from `<template>` — the maxConcurrent field and its immediate save button + ok paragraph inside the Transcoding section:**

Remove this block (approximately lines 80–91):
```html
      <div class="field">
        <label>Max concurrent transcodes</label>
        <input
          name="maxConcurrent"
          v-model.number="form.maxConcurrent"
          type="number"
          min="1"
          placeholder="2"
        />
      </div>
      <button class="btn-save" @click="save" :disabled="saving">Save</button>
      <p v-if="saveOk" class="ok">Saved.</p>

      <hr class="section-divider" />
```

Replace with just:
```html
      <hr class="section-divider" />
```

(The Transcoding section still starts with `<h3>Transcoding</h3>` and now immediately has the divider followed by Quality Profiles.)

**Remove from `<script setup>` — the `maxConcurrent` field in `form`:**

In the `form` reactive object, remove `maxConcurrent: 2`:
```js
const form = reactive({
  plexUrl:  '',
  syncCron: ''
})
```

**Remove from `onMounted` — the maxConcurrent assignment:**

Remove this line:
```js
    form.maxConcurrent = Number(s['transcode.max.concurrent'] ?? '2')
```

**Remove from `save()` — the transcode.max.concurrent key in payload:**

Remove this line from the payload object inside `save()`:
```js
    'transcode.max.concurrent':  String(form.maxConcurrent)
```

The resulting `save()` payload should be:
```js
  const payload = {
    'plex.server.url':           form.plexUrl,
    'plex.sync.cron':            form.syncCron,
    'plex.sync.libraries':       selectedLibraryKeys.value.join(',')
  }
```

- [ ] **Step 4: Run all frontend tests**

```bash
cd /Users/laurentlaborde/projects/plex-downloader/frontend && npm run test 2>&1 | tail -40
```
Expected: all tests GREEN (SettingsView tests pass without the removed assertions; QueueView tests still pass)

- [ ] **Step 5: Build frontend**

```bash
cd /Users/laurentlaborde/projects/plex-downloader/frontend && npm run build 2>&1 | tail -20
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add frontend/src/views/SettingsView.vue \
        frontend/src/views/__tests__/SettingsView.test.js
git commit -m "feat: remove max-concurrent from SettingsView, refs #52"
```

---

### Task 7: Final verification + report commit

**Files:**
- Create: `.superpowers/sdd/issue-52-report.md`

- [ ] **Step 1: Run full backend test suite**

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem; cd /Users/laurentlaborde/projects/plex-downloader/backend && ./gradlew test 2>&1 | tail -40
```
Expected: BUILD SUCCESSFUL, all tests GREEN

- [ ] **Step 2: Run full frontend test suite**

```bash
cd /Users/laurentlaborde/projects/plex-downloader/frontend && npm run test 2>&1 | tail -40
```
Expected: all tests GREEN

- [ ] **Step 3: Build frontend**

```bash
cd /Users/laurentlaborde/projects/plex-downloader/frontend && npm run build 2>&1 | tail -20
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Create report**

Create `.superpowers/sdd/issue-52-report.md` with actual test results pasted in, then commit everything including the closing commit.

- [ ] **Step 5: Final commit**

```bash
git add .superpowers/sdd/issue-52-report.md
git commit -m "$(cat <<'EOF'
feat: live transcode concurrency control from the queue view

closes #52
EOF
)"
```

---

## Self-Review

**Spec coverage check:**

| Spec requirement | Task |
|---|---|
| ResizableSemaphore with setMaxPermits/getMaxPermits | Task 1 |
| Increasing admits waiting QUEUED items | Task 1 (release(delta) semantics) |
| Decreasing does NOT kill in-flight transcodes | Task 1 (reducePermits semantics) |
| TranscodeQueueRunner.getMaxConcurrent() / setMaxConcurrent() | Task 2 |
| setMaxConcurrent persists via SettingsService.set() | Task 2 |
| ResizableSemaphoreTest with all 4 spec scenarios | Task 1 |
| TranscodeQueueRunnerTest extended | Task 2 |
| GET /api/transcode/concurrency (any authenticated user) | Task 3 |
| PUT /api/transcode/concurrency (ADMIN only, N>=1) | Task 3 |
| TranscodeControllerTest | Task 3 |
| Frontend API getConcurrency() / setConcurrency() | Task 4 |
| QueueView concurrency stepper on mount + change | Task 5 |
| Admin-only steppers (isAdmin gate) | Task 5 |
| Remove max-concurrent from SettingsView | Task 6 |
| Update SettingsView.test.js | Task 6 |
| Backend tests GREEN | Task 7 |
| Frontend tests + build GREEN | Task 7 |
| Report at .superpowers/sdd/issue-52-report.md | Task 7 |
| Single commit with closes #52 | Task 7 |

**Placeholder scan:** No TBDs, no "handle edge cases" placeholders, all code is literal.

**Type consistency:** `ConcurrencyResponse(int maxConcurrent)` record used in Task 3; GET and PUT both return it. Frontend `getConcurrency()` returns `data.maxConcurrent` (number); `setConcurrency(n)` sends `{ maxConcurrent: n }` — consistent with the record field name throughout Tasks 4 and 5.

**One concern:** The spec says `setMaxConcurrent` in `TranscodeQueueRunner` should clamp `>=1` before calling `permits.setMaxPermits()`, but `ResizableSemaphore.setMaxPermits()` also clamps. Double-clamping is harmless but the implementation in Task 2 does both explicitly — this is intentional for clarity (the runner's public API documents the contract; the semaphore enforces it internally).
