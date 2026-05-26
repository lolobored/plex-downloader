package org.lolobored.plexdownloader.controller;

import tools.jackson.databind.ObjectMapper;
import org.lolobored.plexdownloader.config.JwtAuthFilter;
import org.lolobored.plexdownloader.model.DownloadQueueItem;
import org.lolobored.plexdownloader.model.User;
import org.lolobored.plexdownloader.repository.DownloadQueueRepository;
import org.lolobored.plexdownloader.service.DownloadService;
import org.lolobored.plexdownloader.service.JwtService;
import org.lolobored.plexdownloader.service.TdarrSyncScheduler;
import org.lolobored.plexdownloader.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
class DownloadControllerTest {

    MockMvc mockMvc;
    @Autowired WebApplicationContext webApplicationContext;
    @MockitoBean DownloadService downloadService;
    @MockitoBean TdarrSyncScheduler tdarrSync;
    @MockitoBean DownloadQueueRepository queueRepo;
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
    void retry_returns200AndUpdatedItem_whenOwnerAndTdarrError() throws Exception {
        DownloadQueueItem item = new DownloadQueueItem();
        item.setId(5L);
        item.setUser(user);
        item.setStatus(DownloadQueueItem.Status.ERROR);
        item.setTdarrStatus(DownloadQueueItem.TdarrStatus.TDARR_ERROR);

        DownloadQueueItem reset = new DownloadQueueItem();
        reset.setId(5L);
        reset.setStatus(DownloadQueueItem.Status.DONE);
        reset.setTdarrStatus(DownloadQueueItem.TdarrStatus.NONE);

        when(queueRepo.findById(5L)).thenReturn(Optional.of(item));
        when(tdarrSync.requeueOne(5L)).thenReturn(reset);

        mockMvc.perform(post("/api/download/5/retry"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("DONE"))
            .andExpect(jsonPath("$.tdarrStatus").value("NONE"));
    }

    @Test
    void retry_returns403_whenNotOwner() throws Exception {
        User other = new User(); other.setId(99L);
        DownloadQueueItem item = new DownloadQueueItem();
        item.setId(6L);
        item.setUser(other);
        item.setStatus(DownloadQueueItem.Status.ERROR);
        item.setTdarrStatus(DownloadQueueItem.TdarrStatus.TDARR_ERROR);

        when(queueRepo.findById(6L)).thenReturn(Optional.of(item));

        mockMvc.perform(post("/api/download/6/retry"))
            .andExpect(status().isForbidden());
    }

    @Test
    void retry_returns404_whenItemNotFound() throws Exception {
        when(queueRepo.findById(99L)).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/download/99/retry"))
            .andExpect(status().isNotFound());
    }

    @Test
    void retry_returns403_whenAdminTriesOtherUsersItem() throws Exception {
        user.setRole(User.Role.ADMIN);
        User other = new User(); other.setId(99L);
        DownloadQueueItem item = new DownloadQueueItem();
        item.setId(8L);
        item.setUser(other);
        item.setStatus(DownloadQueueItem.Status.ERROR);
        item.setTdarrStatus(DownloadQueueItem.TdarrStatus.TDARR_ERROR);

        when(queueRepo.findById(8L)).thenReturn(Optional.of(item));

        mockMvc.perform(post("/api/download/8/retry"))
            .andExpect(status().isForbidden());
    }

    @Test
    void retry_returns400_whenNotInTdarrErrorState() throws Exception {
        DownloadQueueItem item = new DownloadQueueItem();
        item.setId(7L);
        item.setUser(user);
        item.setStatus(DownloadQueueItem.Status.DONE);
        item.setTdarrStatus(DownloadQueueItem.TdarrStatus.PROCESSING);

        when(queueRepo.findById(7L)).thenReturn(Optional.of(item));
        when(tdarrSync.requeueOne(7L)).thenThrow(
            new ResponseStatusException(HttpStatus.BAD_REQUEST, "Item is not in TDARR_ERROR state"));

        mockMvc.perform(post("/api/download/7/retry"))
            .andExpect(status().isBadRequest());
    }
}
