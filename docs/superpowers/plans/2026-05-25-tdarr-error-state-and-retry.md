# Tdarr Error State + Retry Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix two backend bugs that prevent Tdarr Error/Cancelled items from transitioning to `ERROR` status, and add a Retry button in the queue UI that requeues the file in Tdarr.

**Architecture:** Fix `mapStatus()` to handle `"Cancelled"`, fix `applyTdarrStatus()` to set `status=ERROR` when Tdarr errors, add `requeueFile()` to `TdarrClient`, add `requeueOne()` to `TdarrSyncScheduler`, add `POST /api/download/{id}/retry` endpoint, add retry button in `QueueView.vue`.

**Tech Stack:** Spring Boot 3, RestClient, Vue 3 + Vitest, existing `TdarrClient` / `TdarrSyncScheduler` / `DownloadController`

**GitHub issue:** refs #25

---

## File Map

| File | Change |
|------|--------|
| `backend/src/main/java/org/lolobored/plexdownloader/client/TdarrClient.java` | add `"Cancelled"` mapping; add `callRequeue()` + `requeueFile()` |
| `backend/src/test/java/org/lolobored/plexdownloader/client/TdarrClientTest.java` | test `"Cancelled"` mapping; test `requeueFile()` |
| `backend/src/main/java/org/lolobored/plexdownloader/service/TdarrSyncScheduler.java` | set `status=ERROR` in `applyTdarrStatus()`; add `requeueOne()` |
| `backend/src/test/java/org/lolobored/plexdownloader/service/TdarrSyncSchedulerTest.java` | test `status=ERROR` transition; test `requeueOne()` |
| `backend/src/main/java/org/lolobored/plexdownloader/controller/DownloadController.java` | add `POST /{id}/retry` |
| `backend/src/test/java/org/lolobored/plexdownloader/controller/DownloadControllerTest.java` | create; test retry endpoint |
| `frontend/src/api/download.js` | add `retryQueueItem(id)` |
| `frontend/src/views/QueueView.vue` | add retry button for `ERROR+TDARR_ERROR` items; remove dead DONE+TDARR_ERROR badge |
| `frontend/src/views/__tests__/QueueView.test.js` | update TDARR_ERROR test; add retry button tests |

---

### Task 1: Fix `TdarrClient.mapStatus()` — add `"Cancelled"` mapping

**Files:**
- Modify: `backend/src/main/java/org/lolobored/plexdownloader/client/TdarrClient.java`
- Modify: `backend/src/test/java/org/lolobored/plexdownloader/client/TdarrClientTest.java`

**Context:** `mapStatus()` is a private method in `TdarrClient` at line 163. It handles `healthCheck` and `transcode` strings from Tdarr's API. `"Cancelled"` in `TranscodeDecisionMaker` falls through to `NONE` — it should map to `TDARR_ERROR`.

**Java setup:** Before any Gradle command:
```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java $(cat backend/.sdkmanrc | grep java | cut -d= -f2)
```

- [ ] **Step 1: Write failing test**

In `backend/src/test/java/org/lolobored/plexdownloader/client/TdarrClientTest.java`, add after the `getFileStatus_returnsTdarrError_whenTranscodeError` test:

```java
@Test
void getFileStatus_returnsTdarrError_whenCancelled() {
    when(settings.get("tdarr.server.url")).thenReturn(Optional.of("http://tdarr:8265"));
    TdarrClient.TdarrFileResponse resp = new TdarrClient.TdarrFileResponse();
    resp.setTranscodeDecisionMaker("Cancelled");
    doReturn(resp).when(client).fetchStatus(anyString(), anyString());

    assertThat(client.getFileStatus("/file.mkv").get().status())
        .isEqualTo(DownloadQueueItem.TdarrStatus.TDARR_ERROR);
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd backend && source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java $(cat .sdkmanrc | grep java | cut -d= -f2) && ./gradlew test --tests "*TdarrClientTest*" --no-daemon 2>&1 | tail -15
```

Expected: FAIL — `"Cancelled"` maps to `NONE`, not `TDARR_ERROR`

- [ ] **Step 3: Fix `mapStatus()`**

In `TdarrClient.java`, replace the `mapStatus` method (currently starting ~line 163):

```java
private DownloadQueueItem.TdarrStatus mapStatus(String healthCheck, String transcode) {
    if ("HealthError".equals(healthCheck)) return DownloadQueueItem.TdarrStatus.TDARR_ERROR;
    if ("TranscodeError".equals(transcode) || "Cancelled".equals(transcode))
        return DownloadQueueItem.TdarrStatus.TDARR_ERROR;
    if ("Transcoded".equals(transcode) || "Transcode success".equals(transcode)
            || "Not required".equals(transcode))
        return DownloadQueueItem.TdarrStatus.TRANSCODED;
    if ("Queued".equals(transcode) || "Processing".equals(transcode)
            || "Queued".equals(healthCheck) || "Processing".equals(healthCheck))
        return DownloadQueueItem.TdarrStatus.PROCESSING;
    return DownloadQueueItem.TdarrStatus.NONE;
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew test --tests "*TdarrClientTest*" --no-daemon 2>&1 | tail -10
```

Expected: PASS (all TdarrClientTest tests green)

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/org/lolobored/plexdownloader/client/TdarrClient.java \
        backend/src/test/java/org/lolobored/plexdownloader/client/TdarrClientTest.java
git commit -m "fix: map Tdarr Cancelled status to TDARR_ERROR refs #25"
```

---

### Task 2: Fix `applyTdarrStatus()` — set `status=ERROR` when Tdarr errors

**Files:**
- Modify: `backend/src/main/java/org/lolobored/plexdownloader/service/TdarrSyncScheduler.java`
- Modify: `backend/src/test/java/org/lolobored/plexdownloader/service/TdarrSyncSchedulerTest.java`

**Context:** `applyTdarrStatus()` is at line 74 in `TdarrSyncScheduler.java`. It currently saves `tdarrStatus=TDARR_ERROR` and `tdarrError` but leaves `item.status=DONE`. The fix: also set `item.status=ERROR` and `item.errorMessage` when `newStatus==TDARR_ERROR`.

`DownloadQueueItem` has both `errorMessage` (String) and `tdarrError` (String) fields — `errorMessage` is the primary human-readable error shown in the queue UI (`item.errorMessage` displayed in `QueueView.vue` line 41), while `tdarrError` stores the raw Tdarr error string for the retry flow.

- [ ] **Step 1: Write failing test**

In `TdarrSyncSchedulerTest.java`, add after `syncAll_updatesStatusToError_withMessage`:

```java
@Test
void syncAll_setsItemStatusToError_whenTdarrError() {
    DownloadQueueItem item = doneItem("/conversion/in-flight/movies/test/movie.mkv");
    when(queueRepo.findByStatusAndTdarrStatusNotIn(any(), any())).thenReturn(List.of(item));
    when(tdarrClient.getFileStatus(anyString()))
        .thenReturn(Optional.of(new TdarrClient.TdarrFileStatus(
            DownloadQueueItem.TdarrStatus.TDARR_ERROR, "codec not supported", null)));
    when(queueRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

    scheduler.syncAll();

    verify(queueRepo).save(argThat(i ->
        i.getStatus() == DownloadQueueItem.Status.ERROR &&
        i.getErrorMessage() != null &&
        i.getErrorMessage().contains("codec not supported")
    ));
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd backend && source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java $(cat .sdkmanrc | grep java | cut -d= -f2) && ./gradlew test --tests "*TdarrSyncSchedulerTest*" --no-daemon 2>&1 | tail -15
```

Expected: FAIL — `item.status` stays `DONE`, not `ERROR`

- [ ] **Step 3: Fix `applyTdarrStatus()`**

In `TdarrSyncScheduler.java`, replace the save block (currently lines 104-110):

```java
item.setTdarrStatus(newStatus);
item.setTdarrError(ts.errorMessage());
if (outputPath != null) {
    item.setOutputFilePath(outputPath);
}
if (newStatus == DownloadQueueItem.TdarrStatus.TDARR_ERROR) {
    item.setStatus(DownloadQueueItem.Status.ERROR);
    String detail = ts.errorMessage() != null ? ts.errorMessage() : "unknown error";
    item.setErrorMessage("Tdarr transcoding failed: " + detail);
}
queueRepo.save(item);
log.info("Tdarr status updated: item={} tdarrStatus={} status={}",
    item.getId(), newStatus, item.getStatus());
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew test --tests "*TdarrSyncSchedulerTest*" --no-daemon 2>&1 | tail -10
```

Expected: PASS (all TdarrSyncSchedulerTest tests green)

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/org/lolobored/plexdownloader/service/TdarrSyncScheduler.java \
        backend/src/test/java/org/lolobored/plexdownloader/service/TdarrSyncSchedulerTest.java
git commit -m "fix: set item status=ERROR when Tdarr reports TDARR_ERROR refs #25"
```

---

### Task 3: Add `TdarrClient.requeueFile()`

**Files:**
- Modify: `backend/src/main/java/org/lolobored/plexdownloader/client/TdarrClient.java`
- Modify: `backend/src/test/java/org/lolobored/plexdownloader/client/TdarrClientTest.java`

**Context:** Follows the exact same pattern as the existing `callDelete()` / `deleteFile()` pair (lines 129–156 of `TdarrClient.java`). `callRequeue` is package-private (no `public`) so tests can stub it with `@Spy`. `requeueFile` is public. Unlike `deleteFile` which swallows exceptions, `requeueFile` rethrows — the caller needs to know if requeue failed.

The Tdarr cruddb `update` mode body: `collection="FileJSONDB"`, `mode="update"`, `docID=<filePath>`, `obj={"TranscodeDecisionMaker":"Queued","HealthCheck":"Queued","errors":""}`.

- [ ] **Step 1: Write failing tests**

In `TdarrClientTest.java`, add at the end:

```java
// ---------- requeueFile ----------

@Test
void requeueFile_doesNotCallRequeue_whenUrlBlank() {
    when(settings.get("tdarr.server.url")).thenReturn(Optional.empty());
    client.requeueFile("/some/file.mkv");
    verify(client, never()).callRequeue(anyString(), anyString());
}

@Test
void requeueFile_callsRequeueWithCorrectArgs_whenUrlSet() {
    when(settings.get("tdarr.server.url")).thenReturn(Optional.of("http://tdarr:8265"));
    doNothing().when(client).callRequeue(anyString(), anyString());
    client.requeueFile("/some/file.mkv");
    verify(client).callRequeue("http://tdarr:8265", "/some/file.mkv");
}

@Test
void requeueFile_rethrows_whenRestClientException() {
    when(settings.get("tdarr.server.url")).thenReturn(Optional.of("http://tdarr:8265"));
    doThrow(new RestClientException("conn refused")).when(client).callRequeue(anyString(), anyString());
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> client.requeueFile("/some/file.mkv"))
        .isInstanceOf(RestClientException.class);
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd backend && source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java $(cat .sdkmanrc | grep java | cut -d= -f2) && ./gradlew test --tests "*TdarrClientTest*" --no-daemon 2>&1 | tail -15
```

Expected: FAIL — `callRequeue` and `requeueFile` don't exist

- [ ] **Step 3: Add `callRequeue()` and `requeueFile()` to `TdarrClient.java`**

Add after the `callDelete` method (after line ~143):

```java
/** Package-private for testing — stub with @Spy. */
void callRequeue(String baseUrl, String filePath) {
    Map<String, Object> body = Map.of(
        "data", Map.of(
            "collection", "FileJSONDB",
            "mode",       "update",
            "docID",      filePath,
            "obj",        Map.of(
                "TranscodeDecisionMaker", "Queued",
                "HealthCheck",            "Queued",
                "errors",                 ""
            )
        )
    );
    withAuth(HTTP_CLIENT.post()
        .uri(baseUrl + "/api/v2/cruddb")
        .contentType(MediaType.APPLICATION_JSON)
        .body(body))
        .retrieve()
        .toBodilessEntity();
}

public void requeueFile(String filePath) {
    String baseUrl = settings.get("tdarr.server.url").orElse("").trim();
    if (baseUrl.isBlank()) {
        log.warn("Tdarr URL not configured, skipping requeueFile for {}", filePath);
        return;
    }
    callRequeue(baseUrl, filePath);  // throws RestClientException on failure — caller handles
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew test --tests "*TdarrClientTest*" --no-daemon 2>&1 | tail -10
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/org/lolobored/plexdownloader/client/TdarrClient.java \
        backend/src/test/java/org/lolobored/plexdownloader/client/TdarrClientTest.java
git commit -m "feat: add TdarrClient.requeueFile() using cruddb update mode refs #25"
```

---

### Task 4: Add `TdarrSyncScheduler.requeueOne()`

**Files:**
- Modify: `backend/src/main/java/org/lolobored/plexdownloader/service/TdarrSyncScheduler.java`
- Modify: `backend/src/test/java/org/lolobored/plexdownloader/service/TdarrSyncSchedulerTest.java`

**Context:** `requeueOne` validates the item is in `status=ERROR, tdarrStatus=TDARR_ERROR`, calls `tdarrClient.requeueFile()`, resets the item to `status=DONE, tdarrStatus=NONE, errorMessage=null, tdarrError=null`, saves, and returns it. Ownership check is done in the controller (not here). Tdarr call failure → `ResponseStatusException(502)`.

`DownloadQueueItem` is `@Data` (Lombok) — use `item.setStatus(...)`, `item.setTdarrStatus(...)`, etc.

- [ ] **Step 1: Write failing tests**

In `TdarrSyncSchedulerTest.java`, add at the end:

```java
// ---------- requeueOne ----------

@Test
void requeueOne_resetsItemAndCallsTdarr() {
    DownloadQueueItem item = new DownloadQueueItem();
    item.setId(1L);
    item.setStatus(DownloadQueueItem.Status.ERROR);
    item.setTdarrStatus(DownloadQueueItem.TdarrStatus.TDARR_ERROR);
    item.setErrorMessage("Tdarr transcoding failed: codec error");
    item.setTdarrError("codec error");
    item.setDestFilePath("/conversion/in-flight/movies/film/film.mkv");

    when(queueRepo.findById(1L)).thenReturn(Optional.of(item));
    when(queueRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    doNothing().when(tdarrClient).requeueFile(anyString());

    DownloadQueueItem result = scheduler.requeueOne(1L);

    verify(tdarrClient).requeueFile("/conversion/in-flight/movies/film/film.mkv");
    assertThat(result.getStatus()).isEqualTo(DownloadQueueItem.Status.DONE);
    assertThat(result.getTdarrStatus()).isEqualTo(DownloadQueueItem.TdarrStatus.NONE);
    assertThat(result.getErrorMessage()).isNull();
    assertThat(result.getTdarrError()).isNull();
}

@Test
void requeueOne_throws404_whenItemNotFound() {
    when(queueRepo.findById(99L)).thenReturn(Optional.empty());

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> scheduler.requeueOne(99L))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .hasMessageContaining("not found");
}

@Test
void requeueOne_throws400_whenStatusNotError() {
    DownloadQueueItem item = new DownloadQueueItem();
    item.setId(2L);
    item.setStatus(DownloadQueueItem.Status.DONE);
    item.setTdarrStatus(DownloadQueueItem.TdarrStatus.TDARR_ERROR);
    when(queueRepo.findById(2L)).thenReturn(Optional.of(item));

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> scheduler.requeueOne(2L))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .hasMessageContaining("TDARR_ERROR");
}

@Test
void requeueOne_throws400_whenTdarrStatusNotError() {
    DownloadQueueItem item = new DownloadQueueItem();
    item.setId(3L);
    item.setStatus(DownloadQueueItem.Status.ERROR);
    item.setTdarrStatus(DownloadQueueItem.TdarrStatus.PROCESSING);
    when(queueRepo.findById(3L)).thenReturn(Optional.of(item));

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> scheduler.requeueOne(3L))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .hasMessageContaining("TDARR_ERROR");
}

@Test
void requeueOne_throws502_whenTdarrCallFails() {
    DownloadQueueItem item = new DownloadQueueItem();
    item.setId(4L);
    item.setStatus(DownloadQueueItem.Status.ERROR);
    item.setTdarrStatus(DownloadQueueItem.TdarrStatus.TDARR_ERROR);
    item.setDestFilePath("/conversion/in-flight/movies/film/film.mkv");
    when(queueRepo.findById(4L)).thenReturn(Optional.of(item));
    doThrow(new org.springframework.web.client.RestClientException("timeout"))
        .when(tdarrClient).requeueFile(anyString());

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> scheduler.requeueOne(4L))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .hasMessageContaining("502");
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd backend && source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java $(cat .sdkmanrc | grep java | cut -d= -f2) && ./gradlew test --tests "*TdarrSyncSchedulerTest*" --no-daemon 2>&1 | tail -15
```

Expected: FAIL — `requeueOne` doesn't exist

- [ ] **Step 3: Add `requeueOne()` to `TdarrSyncScheduler.java`**

Add after the `syncOne` method (after line ~72):

```java
/** Requeue a TDARR_ERROR item back to Tdarr. Resets status to DONE/NONE. */
public DownloadQueueItem requeueOne(Long id) {
    DownloadQueueItem item = queueRepo.findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
            "Queue item not found: " + id));
    if (item.getStatus() != DownloadQueueItem.Status.ERROR
            || item.getTdarrStatus() != DownloadQueueItem.TdarrStatus.TDARR_ERROR) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "Item is not in TDARR_ERROR state (status=" + item.getStatus()
            + " tdarrStatus=" + item.getTdarrStatus() + ")");
    }
    try {
        tdarrClient.requeueFile(item.getDestFilePath());
    } catch (Exception e) {
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
            "Tdarr requeue failed: " + e.getMessage());
    }
    item.setStatus(DownloadQueueItem.Status.DONE);
    item.setTdarrStatus(DownloadQueueItem.TdarrStatus.NONE);
    item.setErrorMessage(null);
    item.setTdarrError(null);
    queueRepo.save(item);
    log.info("Tdarr requeue: item={} reset to DONE/NONE", id);
    return item;
}
```

`HttpStatus` and `ResponseStatusException` are already imported in this file (check existing imports — add if missing).

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew test --tests "*TdarrSyncSchedulerTest*" --no-daemon 2>&1 | tail -10
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/org/lolobored/plexdownloader/service/TdarrSyncScheduler.java \
        backend/src/test/java/org/lolobored/plexdownloader/service/TdarrSyncSchedulerTest.java
git commit -m "feat: add TdarrSyncScheduler.requeueOne() to reset item and requeue in Tdarr refs #25"
```

---

### Task 5: Add `POST /api/download/{id}/retry` to `DownloadController`

**Files:**
- Modify: `backend/src/main/java/org/lolobored/plexdownloader/controller/DownloadController.java`
- Create: `backend/src/test/java/org/lolobored/plexdownloader/controller/DownloadControllerTest.java`

**Context:** `DownloadController` is at `@RequestMapping("/api/download")`. It already injects `tdarrSync` (TdarrSyncScheduler). The retry endpoint does an ownership check (same pattern as the `cancel` in `DownloadService`): if `item.user.id != user.id` AND `user.role != ADMIN` → 403. Since `requeueOne` doesn't load the item again, the controller needs to load it for the ownership check.

Use the existing `DownloadQueueRepository` — but `DownloadController` doesn't inject it. Use `downloadService.getQueue()` is too heavy. Best: inject a `DownloadQueueRepository` directly in the controller, or delegate to a new service method.

**Simpler approach:** Add an `@AuthenticationPrincipal User user` to the endpoint and pass it to a new `requeueOneForUser(Long id, User user)` overload in `TdarrSyncScheduler`. But that couples TdarrSyncScheduler to User auth.

**Cleanest approach:** The controller loads the item via `downloadService` — but `DownloadService` doesn't have `findById`. Add a `getQueueItem(Long id)` method to `DownloadService` that does `queueRepo.findById(id)`.

Actually — the simplest: inject `DownloadQueueRepository` directly into `DownloadController` just for this check. `DownloadController` is already in the service layer boundary. Do this:

```java
// In DownloadController — add to constructor fields:
private final DownloadQueueRepository queueRepo;

@PostMapping("/{id}/retry")
public DownloadQueueItem retryTdarr(@PathVariable Long id,
                                     @AuthenticationPrincipal User user) {
    DownloadQueueItem item = queueRepo.findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Queue item not found"));
    if (!item.getUser().getId().equals(user.getId()) && user.getRole() != User.Role.ADMIN) {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your queue item");
    }
    return tdarrSync.requeueOne(id);
}
```

Add import: `import org.lolobored.plexdownloader.repository.DownloadQueueRepository;`

- [ ] **Step 1: Write failing tests**

Create `backend/src/test/java/org/lolobored/plexdownloader/controller/DownloadControllerTest.java`:

```java
package org.lolobored.plexdownloader.controller;

import tools.jackson.databind.ObjectMapper;
import org.lolobored.plexdownloader.config.JwtAuthFilter;
import org.lolobored.plexdownloader.model.DownloadQueueItem;
import org.lolobored.plexdownloader.model.User;
import org.lolobored.plexdownloader.repository.DownloadQueueRepository;
import org.lolobored.plexdownloader.service.DownloadService;
import org.lolobored.plexdownloader.service.JwtService;
import org.lolobored.plexdownloader.service.SubscriptionService;
import org.lolobored.plexdownloader.service.TdarrSyncScheduler;
import org.lolobored.plexdownloader.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
class DownloadControllerTest {

    MockMvc mockMvc;
    @Autowired WebApplicationContext webApplicationContext;
    @MockitoBean DownloadService downloadService;
    @MockitoBean SubscriptionService subscriptionService;
    @MockitoBean TdarrSyncScheduler tdarrSync;
    @MockitoBean DownloadQueueRepository queueRepo;
    @MockitoBean JwtService jwtService;
    @MockitoBean UserRepository userRepository;
    @MockitoBean JwtAuthFilter jwtAuthFilter;

    User user;

    @BeforeEach
    void setupAuth() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
            .apply(SecurityMockMvcConfigurers.springSecurity())
            .build();
        user = new User();
        user.setId(1L);
        user.setUsername("alice");
        user.setRole(User.Role.USER);

        doAnswer((InvocationOnMock inv) -> {
            HttpServletRequest req  = inv.getArgument(0);
            HttpServletResponse res = inv.getArgument(1);
            FilterChain chain       = inv.getArgument(2);
            SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null,
                    List.of(new SimpleGrantedAuthority("ROLE_USER"))));
            chain.doFilter(req, res);
            return null;
        }).when(jwtAuthFilter).doFilter(any(), any(), any());
    }

    @Test
    void retry_returns200AndUpdatedItem_whenOwnerAndTdarrError() throws Exception {
        DownloadQueueItem item = new DownloadQueueItem();
        item.setId(5L);
        item.setUser(user);
        item.setStatus(DownloadQueueItem.Status.ERROR);
        item.setTdarrStatus(DownloadQueueItem.TdarrStatus.TDARR_ERROR);

        DownloadQueueItem reset = new DownloadQueueItem();
        reset.setId(5L);
        reset.setStatus(DownloadQueueItem.Status.DONE);
        reset.setTdarrStatus(DownloadQueueItem.TdarrStatus.NONE);

        when(queueRepo.findById(5L)).thenReturn(Optional.of(item));
        when(tdarrSync.requeueOne(5L)).thenReturn(reset);

        mockMvc.perform(post("/api/download/5/retry"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("DONE"))
            .andExpect(jsonPath("$.tdarrStatus").value("NONE"));
    }

    @Test
    void retry_returns403_whenNotOwner() throws Exception {
        User other = new User(); other.setId(99L);
        DownloadQueueItem item = new DownloadQueueItem();
        item.setId(6L);
        item.setUser(other);
        item.setStatus(DownloadQueueItem.Status.ERROR);
        item.setTdarrStatus(DownloadQueueItem.TdarrStatus.TDARR_ERROR);

        when(queueRepo.findById(6L)).thenReturn(Optional.of(item));

        mockMvc.perform(post("/api/download/6/retry"))
            .andExpect(status().isForbidden());
    }

    @Test
    void retry_returns404_whenItemNotFound() throws Exception {
        when(queueRepo.findById(99L)).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/download/99/retry"))
            .andExpect(status().isNotFound());
    }

    @Test
    void retry_returns400_whenNotInTdarrErrorState() throws Exception {
        DownloadQueueItem item = new DownloadQueueItem();
        item.setId(7L);
        item.setUser(user);
        item.setStatus(DownloadQueueItem.Status.DONE);
        item.setTdarrStatus(DownloadQueueItem.TdarrStatus.PROCESSING);

        when(queueRepo.findById(7L)).thenReturn(Optional.of(item));
        when(tdarrSync.requeueOne(7L)).thenThrow(
            new ResponseStatusException(HttpStatus.BAD_REQUEST, "Item is not in TDARR_ERROR state"));

        mockMvc.perform(post("/api/download/7/retry"))
            .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd backend && source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java $(cat .sdkmanrc | grep java | cut -d= -f2) && ./gradlew test --tests "*DownloadControllerTest*" --no-daemon 2>&1 | tail -15
```

Expected: FAIL — endpoint doesn't exist, test class won't compile

- [ ] **Step 3: Add `POST /{id}/retry` to `DownloadController.java`**

Add `DownloadQueueRepository` import and field. The full updated `DownloadController.java`:

```java
package org.lolobored.plexdownloader.controller;

import org.lolobored.plexdownloader.dto.DownloadRequest;
import org.lolobored.plexdownloader.dto.DownloadResponse;
import org.lolobored.plexdownloader.dto.UnwatchedEnqueueRequest;
import org.lolobored.plexdownloader.model.DownloadQueueItem;
import org.lolobored.plexdownloader.model.User;
import org.lolobored.plexdownloader.repository.DownloadQueueRepository;
import org.lolobored.plexdownloader.service.DownloadService;
import org.lolobored.plexdownloader.service.SubscriptionService;
import org.lolobored.plexdownloader.service.TdarrSyncScheduler;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/download")
@RequiredArgsConstructor
public class DownloadController {

    private final DownloadService downloadService;
    private final SubscriptionService subscriptionService;
    private final TdarrSyncScheduler tdarrSync;
    private final DownloadQueueRepository queueRepo;

    @PostMapping
    public DownloadResponse download(@RequestBody DownloadRequest req,
                                     @AuthenticationPrincipal User user) {
        List<Long> jobIds = switch (req.type()) {
            case "MOVIE"   -> downloadService.enqueueMovie(req.id(), user);
            case "EPISODE" -> downloadService.enqueueEpisode(req.id(), user);
            case "SEASON"  -> downloadService.enqueueSeason(req.id(), user);
            case "SHOW"    -> downloadService.enqueueShow(req.id(), user);
            default -> throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "Unknown type: " + req.type());
        };
        return new DownloadResponse(jobIds, "QUEUED");
    }

    @PostMapping("/show/{showId}/unwatched")
    public DownloadResponse enqueueUnwatched(@PathVariable Long showId,
                                              @RequestBody UnwatchedEnqueueRequest req,
                                              @AuthenticationPrincipal User user) {
        if (req.limit() == null || !List.of(5, 10, 15, 20).contains(req.limit())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "limit must be 5, 10, 15, or 20");
        }
        List<Long> jobIds = subscriptionService.enqueueUnwatched(user.getId(), showId, req.limit());
        return new DownloadResponse(jobIds, "QUEUED");
    }

    @GetMapping("/queue")
    public List<DownloadQueueItem> getQueue() {
        return downloadService.getQueue();
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable Long id,
                       @AuthenticationPrincipal User user) {
        downloadService.cancel(id, user);
    }

    @PostMapping("/{id}/tdarr-refresh")
    public DownloadQueueItem refreshTdarrStatus(@PathVariable Long id) {
        return tdarrSync.syncOne(id);
    }

    @PostMapping("/{id}/retry")
    public DownloadQueueItem retryTdarr(@PathVariable Long id,
                                         @AuthenticationPrincipal User user) {
        DownloadQueueItem item = queueRepo.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Queue item not found"));
        if (!item.getUser().getId().equals(user.getId())
                && user.getRole() != User.Role.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your queue item");
        }
        return tdarrSync.requeueOne(id);
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew test --tests "*DownloadControllerTest*" --no-daemon 2>&1 | tail -10
```

Expected: PASS

- [ ] **Step 5: Run full backend suite**

```bash
./gradlew test --no-daemon 2>&1 | tail -15
```

Expected: all green

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/org/lolobored/plexdownloader/controller/DownloadController.java \
        backend/src/test/java/org/lolobored/plexdownloader/controller/DownloadControllerTest.java
git commit -m "feat: add POST /api/download/{id}/retry endpoint refs #25"
```

---

### Task 6: Frontend — retry button in `QueueView.vue`

**Files:**
- Modify: `frontend/src/api/download.js`
- Modify: `frontend/src/views/QueueView.vue`
- Modify: `frontend/src/views/__tests__/QueueView.test.js`

**Context:** After the backend fix, items with `tdarrStatus=TDARR_ERROR` will have `status=ERROR`. In `QueueView.vue`, `ERROR` items appear in the "Completed" section (`done` computed, line 101: `i.status === 'DONE' || i.status === 'ERROR'`). The Tdarr badges are currently only shown inside `v-if="item.status === 'DONE'"` — so TDARR_ERROR items (now with status=ERROR) get no badge or retry button.

Also: the existing test `shows TDARR_ERROR badge with error message` uses `status: 'DONE'` — that's an impossible state after the fix. That test needs to be updated.

**What changes in the template:**

1. The `v-else-if="item.tdarrStatus === 'TDARR_ERROR'"` badge inside `v-if="item.status === 'DONE'"` is now dead code — remove it.

2. Add a retry button for `status=ERROR && tdarrStatus=TDARR_ERROR` items, placed **outside** the `v-if="item.status === 'DONE'"` block, right after the existing error-msg span.

- [ ] **Step 1: Add `retryQueueItem` to `frontend/src/api/download.js`**

Add at the end of the file:

```js
export async function retryQueueItem(id) {
  const { data } = await http.post(`/api/download/${id}/retry`)
  return data  // updated DownloadQueueItem
}
```

- [ ] **Step 2: Write failing tests**

In `QueueView.test.js`, update the mock at the top to include `retryQueueItem`:

```js
vi.mock('../../api/download.js', () => ({
  getQueue: vi.fn().mockResolvedValue([]),
  enqueue: vi.fn().mockResolvedValue({}),
  removeQueueItem: vi.fn().mockResolvedValue(undefined),
  refreshTdarrStatus: vi.fn().mockResolvedValue({}),
  retryQueueItem: vi.fn().mockResolvedValue({})
}))
```

Also add `retryQueueItem` to the import at the top of the file:
```js
import * as downloadApi from '../../api/download.js'
```
(already present — just ensure `retryQueueItem` is accessible via `downloadApi.retryQueueItem`)

**Update** the existing `shows TDARR_ERROR badge with error message` test (the item now has `status: 'ERROR'`, not `status: 'DONE'`):

```js
it('shows tdarr error message on ERROR item with TDARR_ERROR', () => {
  const { wrapper } = factory([
    { id: 13, mediaType: 'MOVIE', mediaId: 5, status: 'ERROR',
      tdarrStatus: 'TDARR_ERROR', tdarrError: 'codec not supported',
      errorMessage: 'Tdarr transcoding failed: codec not supported',
      queuePosition: 1, requestedAt: '2026-05-24T10:00:00Z', completedAt: '2026-05-24T10:05:00Z' }
  ])
  expect(wrapper.text()).toContain('Tdarr transcoding failed: codec not supported')
})
```

**Add** these new tests at the end of the `describe` block:

```js
it('shows retry button on ERROR item with TDARR_ERROR', () => {
  const { wrapper } = factory([
    { id: 20, mediaType: 'MOVIE', mediaId: 5, status: 'ERROR',
      tdarrStatus: 'TDARR_ERROR', tdarrError: 'codec not supported',
      errorMessage: 'Tdarr transcoding failed: codec not supported',
      queuePosition: 1, requestedAt: '2026-05-24T10:00:00Z', completedAt: '2026-05-24T10:05:00Z' }
  ])
  expect(wrapper.find('[data-testid="retry-btn-20"]').exists()).toBe(true)
})

it('does not show retry button on DONE item without TDARR_ERROR', () => {
  const { wrapper } = factory([
    { id: 21, mediaType: 'MOVIE', mediaId: 5, status: 'DONE',
      tdarrStatus: 'TRANSCODED', tdarrError: null,
      queuePosition: 1, requestedAt: '2026-05-24T10:00:00Z', completedAt: '2026-05-24T10:05:00Z' }
  ])
  expect(wrapper.find('[data-testid="retry-btn-21"]').exists()).toBe(false)
})

it('calls retryQueueItem and refreshes queue on retry click', async () => {
  const { wrapper, store } = factory([
    { id: 22, mediaType: 'MOVIE', mediaId: 5, status: 'ERROR',
      tdarrStatus: 'TDARR_ERROR', tdarrError: 'codec not supported',
      errorMessage: 'Tdarr transcoding failed: codec not supported',
      queuePosition: 1, requestedAt: '2026-05-24T10:00:00Z', completedAt: '2026-05-24T10:05:00Z' }
  ])
  await wrapper.find('[data-testid="retry-btn-22"]').trigger('click')
  await flushPromises()
  expect(downloadApi.retryQueueItem).toHaveBeenCalledWith(22)
  expect(store.fetchQueue).toHaveBeenCalled()
})
```

- [ ] **Step 3: Run tests to verify they fail**

```bash
cd frontend && npm run test -- --reporter=verbose 2>&1 | tail -20
```

Expected: FAIL — retry button doesn't exist, old TDARR_ERROR test broken

- [ ] **Step 4: Update `QueueView.vue`**

**In `<script setup>`:**

Add `retryQueueItem` to imports:
```js
import { removeQueueItem, refreshTdarrStatus, retryQueueItem } from '@/api/download.js'
```

Add `retrying` ref after `refreshing`:
```js
const retrying = ref(new Set())
```

Add `retryTdarr` function after `refreshTdarr`:
```js
async function retryTdarr(id) {
  retrying.value = new Set([...retrying.value, id])
  try {
    await retryQueueItem(id)
    await dlStore.fetchQueue()
  } finally {
    const next = new Set(retrying.value)
    next.delete(id)
    retrying.value = next
  }
}
```

**In `<template>`, in the `done` section — make these two changes:**

1. **Remove** the `v-else-if="item.tdarrStatus === 'TDARR_ERROR'"` span from inside the `v-if="item.status === 'DONE'"` template block. The block should now look like:

```html
<template v-if="item.status === 'DONE'">
  <span v-if="item.tdarrStatus === 'NONE'"        class="tdarr-badge none">Queued in Tdarr</span>
  <span v-else-if="item.tdarrStatus === 'PROCESSING'"  class="tdarr-badge processing">Transcoding…</span>
  <span v-else-if="item.tdarrStatus === 'TRANSCODED'"  class="tdarr-badge transcoded">Transcoded ✓</span>
  <button :data-testid="'tdarr-refresh-btn-' + item.id"
          class="btn-tdarr-refresh"
          :disabled="refreshing.has(item.id)"
          :title="refreshing.has(item.id) ? 'Refreshing…' : 'Check Tdarr status'"
          @click="refreshTdarr(item.id)">
    {{ refreshing.has(item.id) ? '…' : '↻' }}
  </button>
</template>
```

2. **Add** the retry button immediately after `</template>` (and before the remove button):

```html
<button v-if="item.status === 'ERROR' && item.tdarrStatus === 'TDARR_ERROR'"
        :data-testid="'retry-btn-' + item.id"
        class="btn-retry"
        :disabled="retrying.has(item.id)"
        title="Retry Tdarr transcoding"
        @click="retryTdarr(item.id)">
  {{ retrying.has(item.id) ? '…' : '⟳ Retry' }}
</button>
```

**In `<style scoped>`, add:**

```css
.btn-retry { background: none; border: 1px solid var(--red); color: var(--red); cursor: pointer;
             font-size: .8rem; padding: 4px 10px; border-radius: 4px; white-space: nowrap; }
.btn-retry:hover:not(:disabled) { background: rgba(231,76,60,.1); }
.btn-retry:disabled { opacity: 0.3; cursor: not-allowed; }
```

- [ ] **Step 5: Run tests to verify they pass**

```bash
cd frontend && npm run test -- --reporter=verbose 2>&1 | tail -20
```

Expected: PASS (all tests green)

- [ ] **Step 6: Run full frontend suite**

```bash
npm run test 2>&1 | tail -10
```

Expected: all green

- [ ] **Step 7: Commit**

```bash
git add frontend/src/api/download.js \
        frontend/src/views/QueueView.vue \
        frontend/src/views/__tests__/QueueView.test.js
git commit -m "feat: add retry button in queue for Tdarr error items refs #25"
```

---

## Verification

```bash
# Backend
cd backend && source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java $(cat .sdkmanrc | grep java | cut -d= -f2) && ./gradlew test --no-daemon

# Frontend
cd frontend && npm run test
```

Both suites should be all green.

**Manual check:**
1. Find an item in Tdarr's "Transcode: Error/Cancelled" tab
2. Wait for `tdarr.sync.cron` to fire (or call `POST /api/download/{id}/tdarr-refresh`)
3. Queue view should show item as `ERROR` with "Tdarr transcoding failed: …"
4. Click "⟳ Retry" → item resets to `DONE / Queued in Tdarr`
5. Tdarr should pick it up again and re-transcode
