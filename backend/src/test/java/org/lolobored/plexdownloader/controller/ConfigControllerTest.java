package org.lolobored.plexdownloader.controller;

import org.lolobored.plexdownloader.config.JwtAuthFilter;
import org.lolobored.plexdownloader.model.User;
import org.lolobored.plexdownloader.repository.UserRepository;
import org.lolobored.plexdownloader.service.JwtService;
import org.lolobored.plexdownloader.service.SettingsService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
class ConfigControllerTest {

    MockMvc mockMvc;
    @Autowired WebApplicationContext webApplicationContext;
    @MockitoBean SettingsService settingsService;
    @MockitoBean JwtService jwtService;
    @MockitoBean UserRepository userRepository;
    @MockitoBean JwtAuthFilter jwtAuthFilter;

    void setupAuth(User user, String role) throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
            .apply(SecurityMockMvcConfigurers.springSecurity())
            .build();
        doAnswer((InvocationOnMock inv) -> {
            HttpServletRequest req = inv.getArgument(0);
            HttpServletResponse res = inv.getArgument(1);
            FilterChain chain = inv.getArgument(2);
            SecurityContext ctx = SecurityContextHolder.createEmptyContext();
            ctx.setAuthentication(new UsernamePasswordAuthenticationToken(
                user, null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
            SecurityContextHolder.setContext(ctx);
            chain.doFilter(req, res);
            return null;
        }).when(jwtAuthFilter).doFilter(any(), any(), any());
    }

    @BeforeEach
    void setup() throws Exception {
        User user = new User();
        user.setId(1L); user.setUsername("user1"); user.setRole(User.Role.USER);
        setupAuth(user, "USER");
    }

    @Test
    void outputStatus_configuredWhenBothDirsSet() throws Exception {
        when(settingsService.get("output.movies.dir")).thenReturn(Optional.of("/movies"));
        when(settingsService.get("output.tvshows.dir")).thenReturn(Optional.of("/tvshows"));

        mockMvc.perform(get("/api/output-status"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.configured").value(true));
    }

    @Test
    void outputStatus_notConfiguredWhenBothDirsBlank() throws Exception {
        when(settingsService.get(anyString())).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/output-status"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.configured").value(false));
    }

    @Test
    void outputStatus_notConfiguredWhenOnlyMoviesDirSet() throws Exception {
        when(settingsService.get("output.movies.dir")).thenReturn(Optional.of("/movies"));
        when(settingsService.get("output.tvshows.dir")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/output-status"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.configured").value(false));
    }

    @Test
    void outputStatus_accessibleByNonAdmin() throws Exception {
        // Auth is set to USER role in @BeforeEach
        when(settingsService.get(anyString())).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/output-status"))
            .andExpect(status().isOk());
    }
}
