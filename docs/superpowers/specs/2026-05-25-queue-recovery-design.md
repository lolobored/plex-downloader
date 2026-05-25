# Queue Recovery on Restart — Design

**Issue:** #19 — queue for copying to tdarr should be maintained in DB so restart doesn't affect it

## Problem

`DownloadQueueItem` is already persisted in DB with statuses PENDING/IN_PROGRESS/DONE/ERROR.
On restart:
- PENDING items sit in DB forever — `executeCopyAsync` is only called at initial enqueue time
- IN_PROGRESS items are stuck — copy was interrupted, executor gone

## Solution

New `QueueRecoveryService` that fires on `ApplicationReadyEvent`, resets interrupted copies, and re-submits pending work to the existing single-threaded `downloadExecutor`.

## Architecture

**New component:** `QueueRecoveryService`
- Triggered by `@EventListener(ApplicationReadyEvent.class)`
- Injects `DownloadQueueRepository` + `DownloadService` (Spring proxy — `@Async` works correctly)

**Startup sequence:**
1. Find all IN_PROGRESS items → set each to PENDING → save
2. Find all PENDING items ordered by `queuePosition` (includes just-reset ones)
3. Call `downloadService.executeCopyAsync(item.getId())` for each

**Partial `.tmp` files:** overwritten on retry — `StandardCopyOption.REPLACE_EXISTING` already in copy code.

**Skipped statuses:** DONE and ERROR — not re-queued.

## Files

| Action | Path |
|--------|------|
| Create | `backend/src/main/java/org/lolobored/plexdownloader/service/QueueRecoveryService.java` |
| Create | `backend/src/test/java/org/lolobored/plexdownloader/service/QueueRecoveryServiceTest.java` |

No other files modified.

## Tests

| Test | Scenario |
|------|----------|
| `onReady_resubmitsPendingItems` | 2 PENDING in DB → `executeCopyAsync` called twice |
| `onReady_resetsInProgressToPendingAndResubmits` | 1 IN_PROGRESS → reset to PENDING → `executeCopyAsync` called |
| `onReady_skipsDoneAndError` | DONE + ERROR in DB → `executeCopyAsync` never called |
| `onReady_respectsQueuePositionOrder` | 3 PENDING with positions 3,1,2 → submitted in order 1,2,3 |

## Ticket Management

Close GitHub issue #19 when the feature branch is pushed (not before — user controls when to push).
