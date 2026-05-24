package org.lolobored.plexdownloader.controller;

import org.lolobored.plexdownloader.config.JwtAuthFilter;
import org.lolobored.plexdownloader.config.SecurityConfig;
import org.lolobored.plexdownloader.model.User;
import org.lolobored.plexdownloader.repository.UserRepository;
import org.lolobored.plexdownloader.service.JwtService;
import org.lolobored.plexdownloader.service.WatchedSyncService;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Set;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(WatchedController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("test")
class WatchedControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean WatchedSyncService watchedSyncService;
    @MockBean JwtService jwtService;
    @MockBean UserRepository userRepository;
    @MockBean JwtAuthFilter jwtAuthFilter;

    @BeforeEach
    void setupAuth() throws Exception {
        User user = new User();
        user.setId(1L);
        user.setUsername("alice");
        user.setRole(User.Role.USER);

        doAnswer((InvocationOnMock inv) -> {
            HttpServletRequest req  = inv.getArgument(0);
            HttpServletResponse res = inv.getArgument(1);
            FilterChain chain       = inv.getArgument(2);
            SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null,
                    List.of(new SimpleGrantedAuthority("ROLE_USER"))));
            chain.doFilter(req, res);
            return null;
        }).when(jwtAuthFilter).doFilter(any(), any(), any());
    }

    @Test
    void getWatched_returnsSyncedIds() throws Exception {
        doNothing().when(watchedSyncService).syncIfStale(1L, 10L);
        when(watchedSyncService.getWatchedEpisodeIds(1L, 10L)).thenReturn(Set.of(3L, 7L));

        mockMvc.perform(get("/api/tv/10/watched"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.watchedEpisodeIds").isArray());
    }

    @Test
    void getWatched_requires401WhenNoAuth() throws Exception {
        doAnswer(inv -> {
            FilterChain chain = inv.getArgument(2);
            chain.doFilter(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(jwtAuthFilter).doFilter(any(), any(), any());

        mockMvc.perform(get("/api/tv/10/watched"))
            .andExpect(status().isForbidden());
    }
}
