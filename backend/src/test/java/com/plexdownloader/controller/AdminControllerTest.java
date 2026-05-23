package com.plexdownloader.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.plexdownloader.config.JwtAuthFilter;
import com.plexdownloader.config.SecurityConfig;
import com.plexdownloader.dto.SyncStatusResponse;
import com.plexdownloader.model.User;
import com.plexdownloader.repository.UserRepository;
import com.plexdownloader.service.*;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AdminController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("test")
class AdminControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean SettingsService settingsService;
    @MockBean LibrarySyncService syncService;
    @MockBean LibrarySyncScheduler syncScheduler;
    @MockBean JwtService jwtService;
    @MockBean UserRepository userRepository;
    @MockBean JwtAuthFilter jwtAuthFilter;

    @BeforeEach
    void setupAdminAuth() throws Exception {
        User admin = new User();
        admin.setId(1L);
        admin.setPlexAccountId("admin-plex-id");
        admin.setUsername("admin");
        admin.setRole(User.Role.ADMIN);

        doAnswer((InvocationOnMock inv) -> {
            HttpServletRequest req = inv.getArgument(0);
            HttpServletResponse res = inv.getArgument(1);
            FilterChain chain = inv.getArgument(2);

            SecurityContext ctx = SecurityContextHolder.createEmptyContext();
            ctx.setAuthentication(new UsernamePasswordAuthenticationToken(
                admin, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
            SecurityContextHolder.setContext(ctx);

            chain.doFilter(req, res);
            return null;
        }).when(jwtAuthFilter).doFilter(any(), any(), any());
    }

    @Test
    void getSettingsReturnsMap() throws Exception {
        when(settingsService.get("plex.server.url")).thenReturn(java.util.Optional.of("http://plex:32400"));

        mockMvc.perform(get("/api/admin/settings"))
            .andExpect(status().isOk());
    }

    @Test
    void putSettingsSavesValue() throws Exception {
        mockMvc.perform(put("/api/admin/settings")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("plex.server.url", "http://plex:32400"))))
            .andExpect(status().isNoContent());

        verify(settingsService).set("plex.server.url", "http://plex:32400");
    }

    @Test
    void getSyncStatusReturnsCurrentState() throws Exception {
        when(syncService.status()).thenReturn(new SyncStatusResponse("IDLE", null, 0, null));

        mockMvc.perform(get("/api/admin/sync/status"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("IDLE"));
    }

    @Test
    void postSyncTriggersWith202WhenIdle() throws Exception {
        when(syncService.isRunning()).thenReturn(false);

        mockMvc.perform(post("/api/admin/sync"))
            .andExpect(status().isAccepted());

        verify(syncScheduler).triggerManual();
    }

    @Test
    void postSyncReturnsWith409WhenAlreadyRunning() throws Exception {
        when(syncService.isRunning()).thenReturn(true);

        mockMvc.perform(post("/api/admin/sync"))
            .andExpect(status().isConflict());
    }
}
