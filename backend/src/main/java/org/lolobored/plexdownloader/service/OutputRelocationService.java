package org.lolobored.plexdownloader.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.lolobored.plexdownloader.model.DownloadQueueItem;
import org.lolobored.plexdownloader.repository.DownloadQueueRepository;
import org.lolobored.plexdownloader.transcode.FileBrowserService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class OutputRelocationService {

    public record RelocationResult(int moved, int updatedOnly, int failed) {}

    private final DownloadQueueRepository queueRepo;
    private final FileBrowserService fileBrowserService;

    public RelocationResult relocate(DownloadQueueItem.MediaType mediaType, String oldRoot, String newRoot) {
        if (newRoot == null || newRoot.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid new root: not writable");
        }
        if (!fileBrowserService.isWritable(newRoot)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid new root: not writable");
        }

        Path normalizedOldRoot = Paths.get(oldRoot).normalize();
        Path normalizedNewRoot = Paths.get(newRoot).normalize();

        List<DownloadQueueItem> items = queueRepo.findByMediaType(mediaType);

        int moved = 0;
        int updatedOnly = 0;
        int failed = 0;

        for (DownloadQueueItem item : items) {
            String destFilePath = item.getDestFilePath();
            if (destFilePath == null) {
                continue;
            }
            Path itemPath = Paths.get(destFilePath).normalize();
            if (!itemPath.startsWith(normalizedOldRoot)) {
                continue;
            }

            Path relative = normalizedOldRoot.relativize(itemPath);
            String newPath = normalizedNewRoot.resolve(relative).toString();

            boolean physicallyMoved = false;
            if (Files.exists(itemPath)) {
                try {
                    Path newFilePath = Paths.get(newPath);
                    Files.createDirectories(newFilePath.getParent());
                    moveFile(itemPath, newFilePath);
                    pruneUnderRoot(normalizedOldRoot, itemPath.getParent());
                    physicallyMoved = true;
                    moved++;
                } catch (IOException e) {
                    log.warn("Failed to move file from {} to {}: {}", itemPath, newPath, e.getMessage());
                    failed++;
                    continue;
                }
            } else {
                updatedOnly++;
            }

            item.setDestFilePath(newPath);
            try {
                queueRepo.save(item);
            } catch (Exception e) {
                log.warn("DB save failed after moving {} → {}: {}. Attempting to move file back.", itemPath, newPath, e.getMessage());
                if (physicallyMoved) {
                    try {
                        Files.move(Paths.get(newPath), itemPath, StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException moveBackEx) {
                        log.warn("Could not move file back from {} to {}: {}", newPath, itemPath, moveBackEx.getMessage());
                    }
                    moved--;
                } else {
                    updatedOnly--;
                }
                failed++;
                continue;
            }
        }

        return new RelocationResult(moved, updatedOnly, failed);
    }

    private void moveFile(Path src, Path dest) throws IOException {
        try {
            Files.move(src, dest, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(src, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void pruneUnderRoot(Path root, Path dir) {
        Path current = dir.normalize();
        while (current != null && !current.equals(root) && current.startsWith(root)) {
            try {
                if (!Files.isDirectory(current)) {
                    break;
                }
                try (var stream = Files.list(current)) {
                    if (stream.findFirst().isPresent()) {
                        break;
                    }
                }
                Files.delete(current);
                current = current.getParent();
            } catch (IOException e) {
                log.warn("Could not prune directory {}: {}", current, e.getMessage());
                break;
            }
        }
    }
}
