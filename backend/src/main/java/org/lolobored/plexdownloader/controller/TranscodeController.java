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
