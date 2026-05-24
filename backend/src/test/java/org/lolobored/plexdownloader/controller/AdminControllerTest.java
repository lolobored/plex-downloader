package org.lolobored.plexdownloader.controller;

import tools.jackson.databind.ObjectMapper;
import org.lolobored.plexdownloader.client.PlexMediaServerClient;
import org.lolobored.plexdownloader.client.TdarrClient;
import org.lolobored.plexdownloader.client.TdarrClient.PingResult;
import org.lolobored.plexdownloader.client.dto.PlexLibrary;
import org.lolobored.plexdownloader.config.JwtAuthFilter;
import org.lolobored.plexdownloader.dto.SyncStatusResponse;
import org.lolobored.plexdownloader.model.User;
import org.lolobored.plexdownloader.repository.UserRepository;
import org.lolobored.plexdownloader.service.*;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
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
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
class AdminControllerTest {

    MockMvc mockMvc;
    final ObjectMapper objectMapper = new ObjectMapper();
    @Autowired WebApplicationContext webApplicationContext;
    @MockitoBean SettingsService settingsService;
    @MockitoBean LibrarySyncService syncService;
    @MockitoBean LibrarySyncScheduler syncScheduler;
    @MockitoBean PlexMediaServerClient plexClient;
    @MockitoBean TdarrClient tdarrClient;
    @MockitoBean JwtService jwtService;
    @MockitoBean UserRepository userRepository;
    @MockitoBean JwtAuthFilter jwtAuthFilter;

    @BeforeEach
    void setupAdminAuth() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
            .apply(SecurityMockMvcConfigurers.springSecurity())
            .build();
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
        when(settingsService.get(anyString())).thenReturn(Optional.empty());
        when(settingsService.get("plex.server.url")).thenReturn(Optional.of("http://plex:32400"));

        mockMvc.perform(get("/api/admin/settings"))
            .andExpect(status().isOk());
    }

    @Test
    void getSettingsReturnsExpectedKeysWithoutToken() throws Exception {
        when(settingsService.get(anyString())).thenReturn(Optional.empty());
        when(settingsService.get("plex.server.url")).thenReturn(Optional.of("http://plex:32400"));
        when(settingsService.get("plex.sync.libraries")).thenReturn(Optional.of("1,2"));
        when(settingsService.get("tdarr.server.url")).thenReturn(Optional.of("http://tdarr:8265"));
        when(settingsService.get("tdarr.sync.cron")).thenReturn(Optional.of("0 */30 * * * *"));

        mockMvc.perform(get("/api/admin/settings"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$['plex.server.url']").value("http://plex:32400"))
            .andExpect(jsonPath("$['plex.sync.libraries']").value("1,2"))
            .andExpect(jsonPath("$['tdarr.server.url']").value("http://tdarr:8265"))
            .andExpect(jsonPath("$['tdarr.sync.cron']").value("0 */30 * * * *"))
            .andExpect(jsonPath("$['plex.server.token']").doesNotExist())
            .andExpect(jsonPath("$['plex.path.prefix.movies.plex']").doesNotExist())
            .andExpect(jsonPath("$['tdarr.path.prefix.conversion']").doesNotExist());
    }

    @Test
    void getPlexLibrariesReturnsList() throws Exception {
        PlexLibrary lib1 = new PlexLibrary();
        lib1.setKey("1"); lib1.setTitle("Movies"); lib1.setType("movie");
        PlexLibrary lib2 = new PlexLibrary();
        lib2.setKey("2"); lib2.setTitle("TV Shows"); lib2.setType("show");
        when(plexClient.getLibraries()).thenReturn(List.of(lib1, lib2));

        mockMvc.perform(get("/api/admin/plex/libraries"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].key").value("1"))
            .andExpect(jsonPath("$[0].title").value("Movies"))
            .andExpect(jsonPath("$[1].title").value("TV Shows"));
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
        when(syncService.status()).thenReturn(new SyncStatusResponse("IDLE", null, 0, 0, null, null));

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

    @Test
    void getTdarrTestReturnsOkWhenPingSucceeds() throws Exception {
        when(tdarrClient.ping(eq("http://tdarr:8265"), any()))
            .thenReturn(new TdarrClient.PingResult(true, "Connected"));

        mockMvc.perform(get("/api/admin/tdarr/test").param("url", "http://tdarr:8265"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void getTdarrTestReturnsNotOkWhenPingFails() throws Exception {
        when(tdarrClient.ping(eq("http://tdarr:8265"), any()))
            .thenReturn(new TdarrClient.PingResult(false, "Connection failed: refused"));

        mockMvc.perform(get("/api/admin/tdarr/test").param("url", "http://tdarr:8265"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(false))
            .andExpect(jsonPath("$.error").exists());
    }
}
