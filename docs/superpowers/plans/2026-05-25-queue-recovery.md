# Queue Recovery on Restart — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** On application startup, reset interrupted (IN_PROGRESS) queue items to PENDING and re-submit all PENDING items to the download executor so copies resume after a restart.

**Architecture:** New `QueueRecoveryService` component fires on `ApplicationReadyEvent` (after full Spring context init). Resets IN_PROGRESS→PENDING, then calls `downloadService.executeCopyAsync()` for every PENDING item in queue-position order. Two new Spring Data derived-query methods added to `DownloadQueueRepository`.

**Tech Stack:** Spring Boot 4, Spring Data JPA, JUnit 5, Mockito, Java 21, Gradle. Activate Java with SDKMAN before running any Gradle command: `source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem`

---

### Task 1: Add repository query methods

**Files:**
- Modify: `backend/src/main/java/org/lolobored/plexdownloader/repository/DownloadQueueRepository.java`

These are Spring Data derived-query methods — no test needed (they're resolved by the framework).

- [ ] **Step 1: Add the two methods to `DownloadQueueRepository`**

Open `backend/src/main/java/org/lolobored/plexdownloader/repository/DownloadQueueRepository.java` and add these two method signatures inside the interface (after the existing methods):

```java
List<DownloadQueueItem> findByStatus(DownloadQueueItem.Status status);

List<DownloadQueueItem> findByStatusOrderByQueuePositionAsc(DownloadQueueItem.Status status);
```

- [ ] **Step 2: Verify it compiles**

```bash
cd backend && source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem && ./gradlew compileJava --no-daemon 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/org/lolobored/plexdownloader/repository/DownloadQueueRepository.java
git commit -m "feat: add findByStatus repository methods for queue recovery"
```

---

### Task 2: `QueueRecoveryService` — test then implement

**Files:**
- Create: `backend/src/main/java/org/lolobored/plexdownloader/service/QueueRecoveryService.java`
- Create: `backend/src/test/java/org/lolobored/plexdownloader/service/QueueRecoveryServiceTest.java`

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/org/lolobored/plexdownloader/service/QueueRecoveryServiceTest.java`:

```java
package org.lolobored.plexdownloader.service;

import org.lolobored.plexdownloader.model.DownloadQueueItem;
import org.lolobored.plexdownloader.repository.DownloadQueueRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class QueueRecoveryServiceTest {

    @Mock DownloadQueueRepository queueRepo;
    @Mock DownloadService downloadService;
    @InjectMocks QueueRecoveryService service;

    private DownloadQueueItem item(Long id, DownloadQueueItem.Status status) {
        DownloadQueueItem i = new DownloadQueueItem();
        i.setId(id);
        i.setStatus(status);
        i.setTitle("Item " + id);
        i.setQueuePosition(id.intValue());
        return i;
    }

    @Test
    void onReady_resubmitsPendingItems() {
        DownloadQueueItem p1 = item(1L, DownloadQueueItem.Status.PENDING);
        DownloadQueueItem p2 = item(2L, DownloadQueueItem.Status.PENDING);
        when(queueRepo.findByStatus(DownloadQueueItem.Status.IN_PROGRESS)).thenReturn(List.of());
        when(queueRepo.findByStatusOrderByQueuePositionAsc(DownloadQueueItem.Status.PENDING))
            .thenReturn(List.of(p1, p2));

        service.onApplicationReady();

        verify(downloadService).executeCopyAsync(1L);
        verify(downloadService).executeCopyAsync(2L);
        verify(queueRepo, never()).save(any());
    }

    @Test
    void onReady_resetsInProgressToPendingAndResubmits() {
        DownloadQueueItem inProgress = item(3L, DownloadQueueItem.Status.IN_PROGRESS);
        when(queueRepo.findByStatus(DownloadQueueItem.Status.IN_PROGRESS)).thenReturn(List.of(inProgress));
        when(queueRepo.findByStatusOrderByQueuePositionAsc(DownloadQueueItem.Status.PENDING))
            .thenReturn(List.of(inProgress));

        service.onApplicationReady();

        assertThat(inProgress.getStatus()).isEqualTo(DownloadQueueItem.Status.PENDING);
        verify(queueRepo).save(inProgress);
        verify(downloadService).executeCopyAsync(3L);
    }

    @Test
    void onReady_skipsDoneAndError() {
        when(queueRepo.findByStatus(DownloadQueueItem.Status.IN_PROGRESS)).thenReturn(List.of());
        when(queueRepo.findByStatusOrderByQueuePositionAsc(DownloadQueueItem.Status.PENDING))
            .thenReturn(List.of());

        service.onApplicationReady();

        verify(downloadService, never()).executeCopyAsync(any());
    }

    @Test
    void onReady_respectsQueuePositionOrder() {
        DownloadQueueItem p1 = item(1L, DownloadQueueItem.Status.PENDING);
        DownloadQueueItem p2 = item(2L, DownloadQueueItem.Status.PENDING);
        DownloadQueueItem p3 = item(3L, DownloadQueueItem.Status.PENDING);
        when(queueRepo.findByStatus(DownloadQueueItem.Status.IN_PROGRESS)).thenReturn(List.of());
        when(queueRepo.findByStatusOrderByQueuePositionAsc(DownloadQueueItem.Status.PENDING))
            .thenReturn(List.of(p1, p2, p3));

        service.onApplicationReady();

        InOrder inOrder = Mockito.inOrder(downloadService);
        inOrder.verify(downloadService).executeCopyAsync(1L);
        inOrder.verify(downloadService).executeCopyAsync(2L);
        inOrder.verify(downloadService).executeCopyAsync(3L);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd backend && source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem && ./gradlew test --tests "*QueueRecoveryServiceTest*" --no-daemon 2>&1 | tail -10
```

Expected: FAIL — `QueueRecoveryService` does not exist yet.

- [ ] **Step 3: Implement `QueueRecoveryService`**

Create `backend/src/main/java/org/lolobored/plexdownloader/service/QueueRecoveryService.java`:

```java
package org.lolobored.plexdownloader.service;

import org.lolobored.plexdownloader.model.DownloadQueueItem;
import org.lolobored.plexdownloader.repository.DownloadQueueRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class QueueRecoveryService {

    private final DownloadQueueRepository queueRepo;
    private final DownloadService downloadService;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        // Reset interrupted copies: IN_PROGRESS → PENDING
        List<DownloadQueueItem> interrupted = queueRepo.findByStatus(DownloadQueueItem.Status.IN_PROGRESS);
        for (DownloadQueueItem item : interrupted) {
            log.info("Queue recovery: resetting item {} '{}' from IN_PROGRESS to PENDING",
                item.getId(), item.getTitle());
            item.setStatus(DownloadQueueItem.Status.PENDING);
            queueRepo.save(item);
        }

        // Re-submit all pending items in queue-position order
        List<DownloadQueueItem> pending =
            queueRepo.findByStatusOrderByQueuePositionAsc(DownloadQueueItem.Status.PENDING);
        log.info("Queue recovery: re-submitting {} pending item(s)", pending.size());
        for (DownloadQueueItem item : pending) {
            log.info("Queue recovery: submitting item {} '{}'", item.getId(), item.getTitle());
            downloadService.executeCopyAsync(item.getId());
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd backend && source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem && ./gradlew test --tests "*QueueRecoveryServiceTest*" --no-daemon 2>&1 | tail -10
```

Expected: 4 tests PASS.

- [ ] **Step 5: Run full test suite**

```bash
./gradlew test --no-daemon 2>&1 | tail -20
```

Expected: all green.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/org/lolobored/plexdownloader/service/QueueRecoveryService.java \
        backend/src/test/java/org/lolobored/plexdownloader/service/QueueRecoveryServiceTest.java
git commit -m "feat: add QueueRecoveryService — resume pending copies on restart (closes #19)"
```

---

## Ticket

GitHub issue #19 is closed by the commit message above (`closes #19`). This takes effect when the branch is pushed to the remote — do NOT push until the user explicitly requests it.
