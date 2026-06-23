package org.lolobored.plexdownloader.controller;

import tools.jackson.databind.ObjectMapper;
import org.lolobored.plexdownloader.config.JwtAuthFilter;
import org.lolobored.plexdownloader.dto.DownloadQueueItemResponse;
import org.lolobored.plexdownloader.model.DownloadQueueItem;
import org.lolobored.plexdownloader.model.User;
import org.lolobored.plexdownloader.service.DownloadService;
import org.lolobored.plexdownloader.service.JwtService;
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
    void getQueue_returnsEnrichedItems() throws Exception {
        DownloadQueueItemResponse resp = new DownloadQueueItemResponse(
            1L, DownloadQueueItem.MediaType.EPISODE, 99L,
            DownloadQueueItem.Status.QUEUED, null, null,
            null, "Show S01E01", 1, null,
            java.time.Instant.parse("2026-01-01T00:00:00Z"), null,
            10L, 20L,
            null, null,
            "Breaking Bad", 1,
            null, null, null, null,
            null, false, null, false
        );
        when(downloadService.getQueue(eq(1L), isNull(), isNull(), isNull(), isNull(), isNull(), isNull())).thenReturn(List.of(resp));

        mockMvc.perform(get("/api/download/queue"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].showId").value(10))
            .andExpect(jsonPath("$[0].seasonId").value(20))
            .andExpect(jsonPath("$[0].mediaId").value(99));
    }

    @Test
    void retry_returns200AndUpdatedItem_whenOwnerAndErrorState() throws Exception {
        DownloadQueueItem reset = new DownloadQueueItem();
        reset.setId(5L);
        reset.setStatus(DownloadQueueItem.Status.QUEUED);

        when(downloadService.retry(5L, user)).thenReturn(reset);

        mockMvc.perform(post("/api/download/5/retry"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("QUEUED"));
    }

    @Test
    void retry_returns403_whenNotOwner() throws Exception {
        when(downloadService.retry(eq(6L), any())).thenThrow(
            new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your queue item"));

        mockMvc.perform(post("/api/download/6/retry"))
            .andExpect(status().isForbidden());
    }

    @Test
    void retry_returns404_whenItemNotFound() throws Exception {
        when(downloadService.retry(eq(99L), any())).thenThrow(
            new ResponseStatusException(HttpStatus.NOT_FOUND, "Queue item not found"));

        mockMvc.perform(post("/api/download/99/retry"))
            .andExpect(status().isNotFound());
    }

    @Test
    void retry_returns400_whenNotInErrorState() throws Exception {
        when(downloadService.retry(eq(7L), any())).thenThrow(
            new ResponseStatusException(HttpStatus.BAD_REQUEST, "Item is not in ERROR state"));

        mockMvc.perform(post("/api/download/7/retry"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void retryAllErrored_returns200WithCount() throws Exception {
        when(downloadService.retryAllErrored(any())).thenReturn(3);

        mockMvc.perform(post("/api/download/retry-all-errored"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.retried").value(3));
    }

    @Test
    void retryAllErrored_returnsZeroWhenNothingErrored() throws Exception {
        when(downloadService.retryAllErrored(any())).thenReturn(0);

        mockMvc.perform(post("/api/download/retry-all-errored"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.retried").value(0));
    }

    @Test
    void transcodeAgain_returns200AndUpdatedItem_whenOwnerAndDoneState() throws Exception {
        DownloadQueueItem reset = new DownloadQueueItem();
        reset.setId(10L);
        reset.setStatus(DownloadQueueItem.Status.QUEUED);

        when(downloadService.transcodeAgain(10L, user)).thenReturn(reset);

        mockMvc.perform(post("/api/download/10/transcode-again"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("QUEUED"));
    }

    @Test
    void transcodeAgain_returns403_whenNotOwner() throws Exception {
        when(downloadService.transcodeAgain(eq(11L), any())).thenThrow(
            new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your queue item"));

        mockMvc.perform(post("/api/download/11/transcode-again"))
            .andExpect(status().isForbidden());
    }

    @Test
    void transcodeAgain_returns404_whenItemNotFound() throws Exception {
        when(downloadService.transcodeAgain(eq(99L), any())).thenThrow(
            new ResponseStatusException(HttpStatus.NOT_FOUND, "Queue item not found"));

        mockMvc.perform(post("/api/download/99/transcode-again"))
            .andExpect(status().isNotFound());
    }

    @Test
    void transcodeAgain_returns400_whenNotDone() throws Exception {
        when(downloadService.transcodeAgain(eq(12L), any())).thenThrow(
            new ResponseStatusException(HttpStatus.BAD_REQUEST, "Item is not DONE"));

        mockMvc.perform(post("/api/download/12/transcode-again"))
            .andExpect(status().isBadRequest());
    }
}
