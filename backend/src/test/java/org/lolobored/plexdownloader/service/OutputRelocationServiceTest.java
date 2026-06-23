package org.lolobored.plexdownloader.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.lolobored.plexdownloader.model.DownloadQueueItem;
import org.lolobored.plexdownloader.repository.DownloadQueueRepository;
import org.lolobored.plexdownloader.transcode.FileBrowserService;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutputRelocationServiceTest {

    @Mock
    DownloadQueueRepository queueRepo;

    @Mock
    FileBrowserService fileBrowserService;

    @InjectMocks
    OutputRelocationService service;

    @TempDir
    Path tempDir;

    private DownloadQueueItem itemWithPath(String path) {
        DownloadQueueItem item = new DownloadQueueItem();
        item.setMediaType(DownloadQueueItem.MediaType.MOVIE);
        item.setDestFilePath(path);
        return item;
    }

    @Test
    void relocate_movesFileAndUpdatesDestFilePath() throws IOException {
        Path oldRoot = tempDir.resolve("old");
        Path newRoot = tempDir.resolve("new");
        Files.createDirectories(oldRoot);
        Files.createDirectories(newRoot);

        Path sourceFile = oldRoot.resolve("movie.mkv");
        Files.writeString(sourceFile, "content");

        DownloadQueueItem item = itemWithPath(sourceFile.toString());
        when(queueRepo.findByMediaType(DownloadQueueItem.MediaType.MOVIE)).thenReturn(List.of(item));
        when(fileBrowserService.isWritable(newRoot.toString())).thenReturn(true);

        OutputRelocationService.RelocationResult result =
            service.relocate(DownloadQueueItem.MediaType.MOVIE, oldRoot.toString(), newRoot.toString());

        assertThat(result.moved()).isEqualTo(1);
        assertThat(result.updatedOnly()).isEqualTo(0);
        assertThat(result.failed()).isEqualTo(0);

        Path expectedNewPath = newRoot.resolve("movie.mkv");
        assertThat(Files.exists(expectedNewPath)).isTrue();
        assertThat(Files.exists(sourceFile)).isFalse();
        assertThat(item.getDestFilePath()).isEqualTo(expectedNewPath.toString());
        verify(queueRepo).save(item);
    }

    @Test
    void relocate_updatesDestFilePathForItemWithNoFile() throws IOException {
        Path oldRoot = tempDir.resolve("old");
        Path newRoot = tempDir.resolve("new");
        Files.createDirectories(oldRoot);
        Files.createDirectories(newRoot);

        Path missingFile = oldRoot.resolve("missing.mkv");

        DownloadQueueItem item = itemWithPath(missingFile.toString());
        when(queueRepo.findByMediaType(DownloadQueueItem.MediaType.MOVIE)).thenReturn(List.of(item));
        when(fileBrowserService.isWritable(newRoot.toString())).thenReturn(true);

        OutputRelocationService.RelocationResult result =
            service.relocate(DownloadQueueItem.MediaType.MOVIE, oldRoot.toString(), newRoot.toString());

        assertThat(result.moved()).isEqualTo(0);
        assertThat(result.updatedOnly()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(0);

        Path expectedNewPath = newRoot.resolve("missing.mkv");
        assertThat(item.getDestFilePath()).isEqualTo(expectedNewPath.toString());
        verify(queueRepo).save(item);
    }

    @Test
    void relocate_isIdempotentWhenNothingUnderOldRoot() throws IOException {
        Path oldRoot = tempDir.resolve("old");
        Path newRoot = tempDir.resolve("new");
        Path unrelatedDir = tempDir.resolve("other");
        Files.createDirectories(oldRoot);
        Files.createDirectories(newRoot);
        Files.createDirectories(unrelatedDir);

        Path unrelatedFile = unrelatedDir.resolve("movie.mkv");

        DownloadQueueItem item = itemWithPath(unrelatedFile.toString());
        when(queueRepo.findByMediaType(DownloadQueueItem.MediaType.MOVIE)).thenReturn(List.of(item));
        when(fileBrowserService.isWritable(newRoot.toString())).thenReturn(true);

        OutputRelocationService.RelocationResult result =
            service.relocate(DownloadQueueItem.MediaType.MOVIE, oldRoot.toString(), newRoot.toString());

        assertThat(result.moved()).isEqualTo(0);
        assertThat(result.updatedOnly()).isEqualTo(0);
        assertThat(result.failed()).isEqualTo(0);

        assertThat(item.getDestFilePath()).isEqualTo(unrelatedFile.toString());
        verify(queueRepo, never()).save(any());
    }

    @Test
    void relocate_prunesEmptyOldParentDirsAfterMove() throws IOException {
        Path oldRoot = tempDir.resolve("old");
        Path newRoot = tempDir.resolve("new");
        Path showDir = oldRoot.resolve("ShowDir");
        Files.createDirectories(showDir);
        Files.createDirectories(newRoot);

        Path sourceFile = showDir.resolve("episode.mkv");
        Files.writeString(sourceFile, "content");

        DownloadQueueItem item = itemWithPath(sourceFile.toString());
        when(queueRepo.findByMediaType(DownloadQueueItem.MediaType.MOVIE)).thenReturn(List.of(item));
        when(fileBrowserService.isWritable(newRoot.toString())).thenReturn(true);

        service.relocate(DownloadQueueItem.MediaType.MOVIE, oldRoot.toString(), newRoot.toString());

        assertThat(Files.exists(showDir)).isFalse();
        assertThat(Files.exists(oldRoot)).isTrue();
    }

    @Test
    void relocate_deletionAfterRelocateRemovesMovedFile() throws IOException {
        Path oldRoot = tempDir.resolve("old");
        Path newRoot = tempDir.resolve("new");
        Files.createDirectories(oldRoot);
        Files.createDirectories(newRoot);

        Path sourceFile = oldRoot.resolve("movie.mkv");
        Files.writeString(sourceFile, "content");

        DownloadQueueItem item = itemWithPath(sourceFile.toString());
        when(queueRepo.findByMediaType(DownloadQueueItem.MediaType.MOVIE)).thenReturn(List.of(item));
        when(fileBrowserService.isWritable(newRoot.toString())).thenReturn(true);

        service.relocate(DownloadQueueItem.MediaType.MOVIE, oldRoot.toString(), newRoot.toString());

        // After relocate the item's destFilePath should point to the new location
        Path newFilePath = Path.of(item.getDestFilePath());
        assertThat(Files.exists(newFilePath)).isTrue();
        assertThat(newFilePath.startsWith(newRoot)).isTrue();

        // Simulating cancel: deleting the file at the updated destFilePath should work
        Files.delete(newFilePath);
        assertThat(Files.exists(newFilePath)).isFalse();
    }

    @Test
    void relocate_rejectsBlankNewRoot() throws IOException {
        Path oldRoot = tempDir.resolve("old");
        Files.createDirectories(oldRoot);

        assertThatThrownBy(() ->
            service.relocate(DownloadQueueItem.MediaType.MOVIE, oldRoot.toString(), "  "))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("not writable");
    }

    @Test
    void relocate_rejectsNonWritableNewRoot() throws IOException {
        Path oldRoot = tempDir.resolve("old");
        Path newRoot = tempDir.resolve("new");
        Files.createDirectories(oldRoot);

        when(fileBrowserService.isWritable(newRoot.toString())).thenReturn(false);

        assertThatThrownBy(() ->
            service.relocate(DownloadQueueItem.MediaType.MOVIE, oldRoot.toString(), newRoot.toString()))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("not writable");
    }

    @Test
    void relocate_perItemSaveFailure_countedFailedOtherItemsStillMoved() throws IOException {
        Path oldRoot = tempDir.resolve("old");
        Path newRoot = tempDir.resolve("new");
        Files.createDirectories(oldRoot);
        Files.createDirectories(newRoot);

        // Three files exist under oldRoot
        Path file1 = oldRoot.resolve("movie1.mkv");
        Path file2 = oldRoot.resolve("movie2.mkv");
        Path file3 = oldRoot.resolve("movie3.mkv");
        Files.writeString(file1, "content1");
        Files.writeString(file2, "content2");
        Files.writeString(file3, "content3");

        DownloadQueueItem item1 = itemWithPath(file1.toString());
        DownloadQueueItem item2 = itemWithPath(file2.toString());
        DownloadQueueItem item3 = itemWithPath(file3.toString());

        when(queueRepo.findByMediaType(DownloadQueueItem.MediaType.MOVIE))
            .thenReturn(List.of(item1, item2, item3));
        when(fileBrowserService.isWritable(newRoot.toString())).thenReturn(true);

        // item2's save throws — simulate DB failure; lenient to avoid strict-stub PotentialStubbingProblem
        // when save(item3) is called with a different argument
        lenient().doThrow(new QueryTimeoutException("timeout"))
            .when(queueRepo).save(item2);

        OutputRelocationService.RelocationResult result =
            service.relocate(DownloadQueueItem.MediaType.MOVIE, oldRoot.toString(), newRoot.toString());

        // 2 moved+updated, 1 failed
        assertThat(result.moved()).isEqualTo(2);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.updatedOnly()).isEqualTo(0);

        // item1 and item3 should point to newRoot
        assertThat(item1.getDestFilePath()).isEqualTo(newRoot.resolve("movie1.mkv").toString());
        assertThat(item3.getDestFilePath()).isEqualTo(newRoot.resolve("movie3.mkv").toString());

        // item2: DB save failed after move, service attempted to move the file back
        // The file should be back at the old location (best-effort rollback succeeded in test env)
        assertThat(Files.exists(newRoot.resolve("movie1.mkv"))).isTrue();
        assertThat(Files.exists(newRoot.resolve("movie3.mkv"))).isTrue();
        // file2 was moved then moved back — it should be at oldRoot
        assertThat(Files.exists(file2)).isTrue();
        assertThat(Files.exists(newRoot.resolve("movie2.mkv"))).isFalse();
    }
}
