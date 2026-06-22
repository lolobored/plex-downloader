package org.lolobored.plexdownloader.controller;

import org.lolobored.plexdownloader.config.JwtAuthFilter;
import org.lolobored.plexdownloader.repository.*;
import org.lolobored.plexdownloader.service.JwtService;
import org.lolobored.plexdownloader.service.QualityProfileService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import org.springframework.http.HttpStatus;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies that the SecurityConfig correctly requires authentication for /api/**
 * routes beyond /api/auth/**. Before the fix, the SPA fallback matcher
 * (/{p1:[^\\.]*}/{p2:[^\\.]*}) would match two-segment paths like
 * /api/quality-profiles and grant permitAll(), bypassing auth.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@DirtiesContext
class SecurityConfigIntegrationTest {

    MockMvc mockMvc;

    @Autowired WebApplicationContext webApplicationContext;

    @MockitoBean JwtAuthFilter jwtAuthFilter;
    @MockitoBean JwtService jwtService;
    @MockitoBean UserRepository userRepository;
    @MockitoBean MovieRepository movieRepo;
    @MockitoBean TvShowRepository showRepo;
    @MockitoBean SeasonRepository seasonRepo;
    @MockitoBean EpisodeRepository episodeRepo;
    @MockitoBean UserMovieWatchedRepository movieWatchedRepo;
    @MockitoBean UserEpisodeWatchedRepository episodeWatchedRepo;
    @MockitoBean QualityProfileService qualityProfileService;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
            .apply(SecurityMockMvcConfigurers.springSecurity())
            .build();

        // Stub the service so the controller layer doesn't throw even if somehow reached
        when(qualityProfileService.findAll()).thenReturn(List.of());

        // Configure the JwtAuthFilter mock to pass through the request WITHOUT setting
        // any authentication — simulating an unauthenticated (no token) request.
        // This is the critical difference from other test classes that inject a user.
        doAnswer((InvocationOnMock inv) -> {
            HttpServletRequest req = inv.getArgument(0);
            HttpServletResponse res = inv.getArgument(1);
            FilterChain chain = inv.getArgument(2);

            // Clear any auth that might have leaked from a context-sharing test
            SecurityContextHolder.clearContext();

            chain.doFilter(req, res);
            return null;
        }).when(jwtAuthFilter).doFilter(any(), any(), any());
    }

    @Test
    void unauthenticatedRequestToApiQualityProfiles_isDenied() throws Exception {
        // Before the fix, the SPA fallback /{p1}/{p2} matched /api/quality-profiles
        // and returned 200. After the fix, /api/** authenticated() catches it first.
        // Spring Security returns 401 when an AuthenticationEntryPoint is configured,
        // or 403 for anonymous access without an entry point — both indicate access denied.
        int status = mockMvc.perform(get("/api/quality-profiles"))
            .andReturn().getResponse().getStatus();
        org.assertj.core.api.Assertions.assertThat(status)
            .as("Unauthenticated GET /api/quality-profiles must be denied (401 or 403), not 200")
            .isIn(HttpStatus.UNAUTHORIZED.value(), HttpStatus.FORBIDDEN.value());
    }
}
