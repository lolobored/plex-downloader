package org.lolobored.plexdownloader.controller;

import org.lolobored.plexdownloader.dto.QualityProfileRequest;
import org.lolobored.plexdownloader.model.QualityProfile;
import org.lolobored.plexdownloader.service.QualityProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class QualityProfileController {

    private final QualityProfileService service;

    @GetMapping("/api/quality-profiles")
    public List<QualityProfile> list() {
        return service.findAll();
    }

    @PostMapping("/api/admin/quality-profiles")
    @PreAuthorize("hasRole('ADMIN')")
    public QualityProfile create(@RequestBody QualityProfileRequest req) {
        return service.create(req.toEntity());
    }

    @PutMapping("/api/admin/quality-profiles/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public QualityProfile update(@PathVariable Long id, @RequestBody QualityProfileRequest req) {
        return service.update(id, req.toEntity());
    }

    @DeleteMapping("/api/admin/quality-profiles/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }

    @PutMapping("/api/admin/quality-profiles/{id}/default")
    @PreAuthorize("hasRole('ADMIN')")
    public QualityProfile setDefault(@PathVariable Long id) {
        return service.setDefault(id);
    }
}
