package org.lolobored.plexdownloader.controller;

import tools.jackson.databind.ObjectMapper;
import org.lolobored.plexdownloader.config.JwtAuthFilter;
import org.lolobored.plexdownloader.model.User;
import org.lolobored.plexdownloader.repository.UserRepository;
import org.lolobored.plexdownloader.service.JwtService;
import org.lolobored.plexdownloader.transcode.FileBrowserService;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
class FileBrowserControllerTest {

    MockMvc mockMvc;
    final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired WebApplicationContext webApplicationContext;
    @MockitoBean FileBrowserService fileBrowserService;
    @MockitoBean JwtService jwtService;
    @MockitoBean UserRepository userRepository;
    @MockitoBean JwtAuthFilter jwtAuthFilter;

    @BeforeEach
    void setupAdminAuth() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
            .apply(SecurityMockMvcConfigurers.springSecurity())
            .build();
        User admin = new User();
        admin.setId(1L); admin.setPlexAccountId("admin-plex-id");
        admin.setUsername("admin"); admin.setRole(User.Role.ADMIN);

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
    void list_returnsPathParentAndEntries() throws Exception {
        when(fileBrowserService.listDirectories(anyString())).thenReturn(List.of(
            new FileBrowserService.DirEntry("movies", "/plex-conversion/movies"),
            new FileBrowserService.DirEntry("tvshows", "/plex-conversion/tvshows")
        ));
        when(fileBrowserService.listDirectories(null)).thenReturn(List.of(
            new FileBrowserService.DirEntry("movies", "/plex-conversion/movies")
        ));
        when(fileBrowserService.getAllowedRoot()).thenReturn("/plex-conversion");

        mockMvc.perform(get("/api/admin/fs/list"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.path").value("/plex-conversion"))
            .andExpect(jsonPath("$.parent").isEmpty())
            .andExpect(jsonPath("$.entries[0].name").value("movies"));
    }

    @Test
    void list_withPath_returnsParentPath() throws Exception {
        when(fileBrowserService.listDirectories("/plex-conversion/libraries")).thenReturn(List.of(
            new FileBrowserService.DirEntry("movies", "/plex-conversion/libraries/movies")
        ));
        when(fileBrowserService.getAllowedRoot()).thenReturn("/plex-conversion");

        mockMvc.perform(get("/api/admin/fs/list").param("path", "/plex-conversion/libraries"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.path").value("/plex-conversion/libraries"))
            .andExpect(jsonPath("$.parent").value("/plex-conversion"))
            .andExpect(jsonPath("$.entries[0].name").value("movies"));
    }

    @Test
    void mkdir_returnsCreatedPath() throws Exception {
        when(fileBrowserService.createDirectory("/plex-conversion/new")).thenReturn("/plex-conversion/new");

        mockMvc.perform(post("/api/admin/fs/mkdir")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("path", "/plex-conversion/new"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.path").value("/plex-conversion/new"));
    }

    @Test
    void mkdir_withEmptyPath_returns400() throws Exception {
        mockMvc.perform(post("/api/admin/fs/mkdir")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("path", ""))))
            .andExpect(status().isBadRequest());
    }
}
