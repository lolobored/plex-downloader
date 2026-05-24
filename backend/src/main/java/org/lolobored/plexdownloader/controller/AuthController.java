package org.lolobored.plexdownloader.controller;

import org.lolobored.plexdownloader.dto.JwtResponse;
import org.lolobored.plexdownloader.dto.PlexPinInitResponse;
import org.lolobored.plexdownloader.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/plex/pin")
    public PlexPinInitResponse initPin() {
        return authService.initPin();
    }

    @GetMapping("/plex/pin/{pinId}")
    public ResponseEntity<?> checkPin(@PathVariable Long pinId) {
        Optional<JwtResponse> result = authService.checkPin(pinId);
        if (result.isPresent()) {
            return ResponseEntity.ok(result.get());
        }
        return ResponseEntity.accepted().body(Map.of("status", "pending"));
    }
}
