package org.lolobored.plexdownloader.transcode;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.stream.Stream;

@Service
public class FileBrowserService {

    private final String allowedRoot;

    public FileBrowserService(@Value("${output.allowed.root:/plex-conversion}") String allowedRoot) {
        this.allowedRoot = allowedRoot;
    }

    public record DirEntry(String name, String path) {}

    /** Validate path is within the allowed root. For existing paths uses toRealPath (follows symlinks). */
    private void validateWithinRoot(String input) {
        if (input == null || input.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Path must not be blank");
        }
        Path req = Paths.get(input).normalize();
        if (!req.isAbsolute()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Path must be absolute: " + input);
        }
        try {
            Path allowed = Paths.get(allowedRoot).toRealPath();
            if (Files.exists(req)) {
                Path real = req.toRealPath();
                if (!real.startsWith(allowed)) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Path is outside the allowed root");
                }
            } else {
                // For non-existing paths: find the nearest existing ancestor, resolve it to real path,
                // then reconstruct the full real path by appending the remaining relative segments.
                // This handles symlinked temp directories (e.g. macOS /var -> /private/var).
                Path ancestor = req;
                while (ancestor != null && !Files.exists(ancestor)) {
                    ancestor = ancestor.getParent();
                }
                if (ancestor == null) {
                    // No existing ancestor found — can't validate, reject
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Path is outside the allowed root");
                }
                Path realAncestor = ancestor.toRealPath();
                // Reconstruct the full resolved path
                Path relative = ancestor.relativize(req);
                Path resolvedFull = realAncestor.resolve(relative).normalize();
                if (!resolvedFull.startsWith(allowed)) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Path is outside the allowed root");
                }
            }
        } catch (ResponseStatusException e) {
            throw e;
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot resolve allowed root: " + e.getMessage());
        }
    }

    public List<DirEntry> listDirectories(String path) {
        String effectivePath = (path == null || path.isBlank()) ? allowedRoot : path;
        validateWithinRoot(effectivePath);
        Path dir = Paths.get(effectivePath);
        if (!Files.isDirectory(dir)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a directory: " + effectivePath);
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                .filter(Files::isDirectory)
                .map(p -> new DirEntry(p.getFileName().toString(), p.toAbsolutePath().toString()))
                .sorted((a, b) -> a.name().compareToIgnoreCase(b.name()))
                .toList();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Cannot list directory: " + e.getMessage());
        }
    }

    public String createDirectory(String path) {
        validateWithinRoot(path);
        Path dir = Paths.get(path).normalize();
        if (Files.exists(dir) && !Files.isDirectory(dir)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Path exists and is not a directory: " + path);
        }
        if (Files.exists(dir)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Directory already exists: " + path);
        }
        try {
            Files.createDirectories(dir);
            return dir.toAbsolutePath().toString();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Cannot create directory: " + e.getMessage());
        }
    }

    public boolean isWritableWithinRoot(String path) {
        try {
            validateWithinRoot(path);
        } catch (ResponseStatusException e) {
            return false;
        }
        Path p = Paths.get(path).normalize();
        if (Files.exists(p)) {
            return Files.isWritable(p);
        }
        // Non-existing: check nearest existing ancestor is writable
        Path ancestor = p.getParent();
        while (ancestor != null && !Files.exists(ancestor)) {
            ancestor = ancestor.getParent();
        }
        return ancestor != null && Files.isWritable(ancestor);
    }

    public String getAllowedRoot() {
        return allowedRoot;
    }
}
