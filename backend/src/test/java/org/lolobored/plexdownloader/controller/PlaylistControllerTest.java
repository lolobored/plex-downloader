package org.lolobored.plexdownloader.controller;

import org.lolobored.plexdownloader.config.JwtAuthFilter;
import org.lolobored.plexdownloader.model.*;
import org.lolobored.plexdownloader.repository.*;
import org.lolobored.plexdownloader.service.PlaylistSyncService;
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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.lolobored.plexdownloader.service.JwtService;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
class PlaylistControllerTest {

    MockMvc mockMvc;
    @Autowired WebApplicationContext webApplicationContext;
    @MockitoBean PlaylistRepository playlistRepo;
    @MockitoBean PlaylistItemRepository itemRepo;
    @MockitoBean PlaylistSubscriptionRepository subRepo;
    @MockitoBean MovieRepository movieRepo;
    @MockitoBean EpisodeRepository episodeRepo;
    @MockitoBean DownloadQueueRepository queueRepo;
    @MockitoBean PlaylistSyncService playlistSyncService;
    @MockitoBean JwtService jwtService;
    @MockitoBean UserRepository userRepository;
    @MockitoBean JwtAuthFilter jwtAuthFilter;

    User user;

    @BeforeEach
    void setupAuth() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
            .apply(SecurityMockMvcConfigurers.springSecurity())
            .build();
        user = new User();
        user.setId(1L);
        user.setUsername("alice");
        user.setRole(User.Role.USER);

        doAnswer((InvocationOnMock inv) -> {
            HttpServletRequest req   = inv.getArgument(0);
            HttpServletResponse res  = inv.getArgument(1);
            FilterChain chain        = inv.getArgument(2);
            SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null,
                    List.of(new SimpleGrantedAuthority("ROLE_USER"))));
            chain.doFilter(req, res);
            return null;
        }).when(jwtAuthFilter).doFilter(any(), any(), any());
    }

    @Test
    void getPlaylists_returnsList_withSubscribedFlag() throws Exception {
        Playlist p = new Playlist();
        p.setId(1L); p.setPlexId("pl1"); p.setTitle("Action"); p.setPlaylistType("video"); p.setLeafCount(5);
        when(playlistRepo.findAll()).thenReturn(List.of(p));
        when(itemRepo.findTop4ByPlaylistIdOrderByOrdinalAsc(1L)).thenReturn(List.of());
        when(subRepo.existsByUserIdAndPlaylistId(1L, 1L)).thenReturn(true);

        mockMvc.perform(get("/api/playlists"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(1))
            .andExpect(jsonPath("$[0].title").value("Action"))
            .andExpect(jsonPath("$[0].subscribed").value(true))
            .andExpect(jsonPath("$[0].posterPlexIds").isArray());
    }

    @Test
    void getPlaylist_returnsDetailWithItems() throws Exception {
        Playlist p = new Playlist();
        p.setId(1L); p.setPlexId("pl1"); p.setTitle("Action"); p.setPlaylistType("video"); p.setLeafCount(1);
        when(playlistRepo.findById(1L)).thenReturn(Optional.of(p));
        when(subRepo.existsByUserIdAndPlaylistId(1L, 1L)).thenReturn(false);
        when(itemRepo.findTop4ByPlaylistIdOrderByOrdinalAsc(1L)).thenReturn(List.of());

        PlaylistItem pi = new PlaylistItem();
        pi.setId(10L); pi.setPlexId("m1"); pi.setMediaType("MOVIE"); pi.setOrdinal(0);
        when(itemRepo.findByPlaylistIdOrderByOrdinalAsc(1L)).thenReturn(List.of(pi));

        Movie m = new Movie(); m.setId(100L); m.setTitle("Inception"); m.setYear(2010);
        when(movieRepo.findByPlexId("m1")).thenReturn(Optional.of(m));
        when(queueRepo.findByUser_IdAndMediaTypeAndMediaId(1L, DownloadQueueItem.MediaType.MOVIE, 100L))
            .thenReturn(Optional.empty());

        mockMvc.perform(get("/api/playlists/1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.title").value("Action"))
            .andExpect(jsonPath("$.items[0].title").value("Inception"))
            .andExpect(jsonPath("$.items[0].year").value(2010))
            .andExpect(jsonPath("$.items[0].mediaType").value("MOVIE"));
    }

    @Test
    void getPlaylist_returns404_whenNotFound() throws Exception {
        when(playlistRepo.findById(99L)).thenReturn(Optional.empty());
        mockMvc.perform(get("/api/playlists/99")).andExpect(status().isNotFound());
    }

    @Test
    void subscribe_createsSubscription_andQueuesItems() throws Exception {
        Playlist p = new Playlist(); p.setId(1L); p.setPlexId("pl1"); p.setTitle("A");
        when(playlistRepo.findById(1L)).thenReturn(Optional.of(p));
        when(subRepo.existsByUserIdAndPlaylistId(1L, 1L)).thenReturn(false);
        doNothing().when(playlistSyncService).enqueueForSubscription(anyLong(), any());

        mockMvc.perform(post("/api/playlists/1/subscribe"))
            .andExpect(status().isNoContent());

        verify(subRepo).save(argThat(s -> s.getPlaylist().getId().equals(1L) && s.getUser().getId().equals(1L)));
        verify(playlistSyncService).enqueueForSubscription(1L, user);
    }

    @Test
    void subscribe_isIdempotent_whenAlreadySubscribed() throws Exception {
        Playlist p = new Playlist(); p.setId(1L);
        when(playlistRepo.findById(1L)).thenReturn(Optional.of(p));
        when(subRepo.existsByUserIdAndPlaylistId(1L, 1L)).thenReturn(true);

        mockMvc.perform(post("/api/playlists/1/subscribe")).andExpect(status().isNoContent());

        verify(subRepo, never()).save(any());
        verify(playlistSyncService, never()).enqueueForSubscription(anyLong(), any());
    }

    @Test
    void unsubscribe_cancelsQueueAndDeletesSubscription() throws Exception {
        when(playlistSyncService.cancelAllForUser(1L, 1L)).thenReturn(2);

        mockMvc.perform(delete("/api/playlists/1/subscribe")).andExpect(status().isNoContent());

        verify(playlistSyncService).cancelAllForUser(1L, 1L);
        verify(subRepo).deleteByUserIdAndPlaylistId(1L, 1L);
    }

    @Test
    void getQueueCount_returnsCount() throws Exception {
        when(playlistSyncService.countQueuedForUser(1L, 5L)).thenReturn(3);

        mockMvc.perform(get("/api/playlists/5/queue-count"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.count").value(3));
    }
}
