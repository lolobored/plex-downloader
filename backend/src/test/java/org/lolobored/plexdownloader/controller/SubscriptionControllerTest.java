package org.lolobored.plexdownloader.controller;

import tools.jackson.databind.ObjectMapper;
import org.lolobored.plexdownloader.config.JwtAuthFilter;
import org.lolobored.plexdownloader.dto.SeasonSubscriptionRequest;
import org.lolobored.plexdownloader.dto.SeasonSubscriptionResponse;
import org.lolobored.plexdownloader.dto.SubscriptionRequest;
import org.lolobored.plexdownloader.dto.SubscriptionResponse;
import org.lolobored.plexdownloader.dto.WatchedResponse;
import org.lolobored.plexdownloader.model.User;
import org.lolobored.plexdownloader.repository.SeasonSubscriptionRepository;
import org.lolobored.plexdownloader.repository.ShowSubscriptionRepository;
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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
class SubscriptionControllerTest {

    MockMvc mockMvc;
    final ObjectMapper objectMapper = new ObjectMapper();
    @Autowired WebApplicationContext webApplicationContext;
    @MockitoBean SubscriptionService subscriptionService;
    @MockitoBean WatchedSyncService watchedSyncService;
    @MockitoBean ShowSubscriptionRepository showSubscriptionRepository;
    @MockitoBean SeasonSubscriptionRepository seasonSubscriptionRepository;
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
    void getSubscriptions_returnsUserList() throws Exception {
        when(subscriptionService.listSubscriptions(1L))
            .thenReturn(List.of(new SubscriptionResponse(1L, 10L, 5, Instant.now())));

        mockMvc.perform(get("/api/subscriptions"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].showId").value(10))
            .andExpect(jsonPath("$[0].targetCount").value(5));
    }

    @Test
    void subscribe_createsSubscription() throws Exception {
        SubscriptionRequest req = new SubscriptionRequest(10L, 5);
        when(subscriptionService.upsert(1L, 10L, 5))
            .thenReturn(new SubscriptionResponse(1L, 10L, 5, Instant.now()));
        doNothing().when(watchedSyncService).syncShow(anyLong(), anyLong());
        when(showSubscriptionRepository.findByUserIdAndShowId(1L, 10L))
            .thenReturn(Optional.empty());

        mockMvc.perform(post("/api/subscriptions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.targetCount").value(5));
    }

    @Test
    void subscribe_returns400ForInvalidTargetCount() throws Exception {
        SubscriptionRequest req = new SubscriptionRequest(10L, 7); // 7 is invalid

        mockMvc.perform(post("/api/subscriptions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void unsubscribe_returns204() throws Exception {
        doNothing().when(subscriptionService).cancel(1L, 10L);

        mockMvc.perform(delete("/api/subscriptions/10"))
            .andExpect(status().isNoContent());
    }

    @Test
    void getQueueCount_returnsCount() throws Exception {
        when(subscriptionService.getQueueCount(1L, 10L)).thenReturn(7);

        mockMvc.perform(get("/api/subscriptions/10/queue-count"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.count").value(7));
    }

    @Test
    void syncNow_returnsWatchedIds() throws Exception {
        doNothing().when(watchedSyncService).syncShow(1L, 10L);
        when(showSubscriptionRepository.findByUserIdAndShowId(1L, 10L))
            .thenReturn(Optional.empty());
        when(watchedSyncService.getWatchedEpisodeIds(1L, 10L)).thenReturn(Set.of(1L, 2L));

        mockMvc.perform(post("/api/subscriptions/10/sync"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.watchedEpisodeIds").isArray());
    }

    @Test
    void getSeasonSubscriptions_returnsList() throws Exception {
        when(subscriptionService.listSeasonSubscriptions(1L))
            .thenReturn(List.of(new SeasonSubscriptionResponse(1L, 100L, 10L, 5, Instant.now())));

        mockMvc.perform(get("/api/subscriptions/seasons"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].seasonId").value(100))
            .andExpect(jsonPath("$[0].targetCount").value(5));
    }

    @Test
    void subscribeSeason_createsSubscription() throws Exception {
        SeasonSubscriptionRequest req = new SeasonSubscriptionRequest(100L, 5);
        when(subscriptionService.upsertSeason(1L, 100L, 5))
            .thenReturn(new SeasonSubscriptionResponse(1L, 100L, 10L, 5, Instant.now()));
        when(seasonSubscriptionRepository.findByUserIdAndSeasonId(1L, 100L))
            .thenReturn(Optional.empty());

        mockMvc.perform(post("/api/subscriptions/seasons")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.seasonId").value(100))
            .andExpect(jsonPath("$.targetCount").value(5));
    }

    @Test
    void subscribeSeason_returns400ForInvalidTargetCount() throws Exception {
        SeasonSubscriptionRequest req = new SeasonSubscriptionRequest(100L, 7);

        mockMvc.perform(post("/api/subscriptions/seasons")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void unsubscribeSeason_returns204() throws Exception {
        doNothing().when(subscriptionService).cancelSeason(1L, 100L);

        mockMvc.perform(delete("/api/subscriptions/seasons/100"))
            .andExpect(status().isNoContent());
    }

    @Test
    void getSeasonQueueCount_returnsCount() throws Exception {
        when(subscriptionService.getSeasonQueueCount(1L, 100L)).thenReturn(3);

        mockMvc.perform(get("/api/subscriptions/seasons/100/queue-count"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.count").value(3));
    }
}
