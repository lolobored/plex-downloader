package org.lolobored.plexdownloader.controller;

import org.lolobored.plexdownloader.transcode.FileBrowserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/fs")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class FileBrowserController {

    private final FileBrowserService fileBrowserService;

    @GetMapping("/list")
    public ResponseEntity<Map<String, Object>> list(@RequestParam(required = false) String path) {
        List<FileBrowserService.DirEntry> entries = fileBrowserService.listDirectories(path);

        String effectivePath = (path == null || path.isBlank()) ? fileBrowserService.getAllowedRoot() : path;
        java.nio.file.Path current = java.nio.file.Paths.get(effectivePath).normalize();
        java.nio.file.Path allowedRoot = java.nio.file.Paths.get(fileBrowserService.getAllowedRoot()).normalize();

        String parent = null;
        if (!current.equals(allowedRoot) && current.getParent() != null) {
            parent = current.getParent().toString();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", current.toString());
        result.put("parent", parent);
        result.put("entries", entries);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/mkdir")
    public ResponseEntity<Map<String, String>> mkdir(@RequestBody Map<String, String> body) {
        String path = body.get("path");
        if (path == null || path.isBlank()) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "path is required");
        }
        String created = fileBrowserService.createDirectory(path);
        return ResponseEntity.ok(Map.of("path", created));
    }
}
