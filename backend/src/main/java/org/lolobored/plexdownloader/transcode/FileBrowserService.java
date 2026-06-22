package org.lolobored.plexdownloader.transcode;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.stream.Stream;

@Service
public class FileBrowserService {

    public record DirEntry(String name, String path) {}

    public List<DirEntry> listDirectories(String path) {
        String effectivePath = (path == null || path.isBlank()) ? "/" : path;
        Path dir = Paths.get(effectivePath).normalize();
        if (!dir.isAbsolute()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Path must be absolute");
        }
        if (!Files.isDirectory(dir)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a directory");
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                .filter(Files::isDirectory)
                .map(p -> new DirEntry(p.getFileName().toString(), p.toAbsolutePath().toString()))
                .sorted((a, b) -> a.name().compareToIgnoreCase(b.name()))
                .toList();
        } catch (AccessDeniedException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot list directory");
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot list directory");
        }
    }

    public String createDirectory(String path) {
        if (path == null || path.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Path must not be blank");
        }
        Path dir = Paths.get(path).normalize();
        if (!dir.isAbsolute()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Path must be absolute");
        }
        if (Files.exists(dir) && !Files.isDirectory(dir)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Path exists and is not a directory");
        }
        if (Files.exists(dir)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Directory already exists");
        }
        try {
            Files.createDirectories(dir);
            return dir.toAbsolutePath().toString();
        } catch (AccessDeniedException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot create directory");
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot create directory");
        }
    }

    public boolean isWritable(String path) {
        if (path == null || path.isBlank()) return false;
        Path p = Paths.get(path).normalize();
        if (!p.isAbsolute()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Path must be absolute");
        }
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
}
