package org.lolobored.plexdownloader.service;

import org.lolobored.plexdownloader.dto.JwtResponse;
import org.lolobored.plexdownloader.dto.PlexPinInitResponse;
import org.lolobored.plexdownloader.dto.PlexUserInfo;
import org.lolobored.plexdownloader.model.User;
import org.lolobored.plexdownloader.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final PlexPinClient plexPinClient;
    private final UserRepository userRepository;
    private final JwtService jwtService;

    public PlexPinInitResponse initPin() {
        return plexPinClient.createPin();
    }

    public String getAuthUrl(String code) {
        return plexPinClient.buildAuthUrl(code);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public Optional<JwtResponse> checkPin(Long pinId) {
        String authToken = plexPinClient.pollPin(pinId);
        if (authToken == null || authToken.isBlank()) {
            return Optional.empty();
        }
        PlexUserInfo info = plexPinClient.getUserInfo(authToken);
        User user = upsertUser(info, authToken);
        return Optional.of(new JwtResponse(
            jwtService.generateToken(user),
            user.getUsername(),
            user.getRole().name()
        ));
    }

    private User upsertUser(PlexUserInfo info, String authToken) {
        return userRepository.findByPlexAccountId(info.id())
            .map(existing -> {
                existing.setUsername(info.username());
                existing.setAvatarUrl(info.thumb());
                existing.setPlexToken(authToken);
                existing.setLastLoginAt(Instant.now());
                return userRepository.save(existing);
            })
            .orElseGet(() -> {
                User u = new User();
                u.setPlexAccountId(info.id());
                u.setUsername(info.username());
                u.setAvatarUrl(info.thumb());
                u.setPlexToken(authToken);
                u.setLastLoginAt(Instant.now());
                u.setRole(userRepository.count() == 0 ? User.Role.ADMIN : User.Role.USER);
                return userRepository.save(u);
            });
    }
}
