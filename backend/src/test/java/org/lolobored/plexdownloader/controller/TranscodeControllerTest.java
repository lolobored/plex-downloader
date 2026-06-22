package org.lolobored.plexdownloader.controller;

import tools.jackson.databind.ObjectMapper;
import org.lolobored.plexdownloader.config.JwtAuthFilter;
import org.lolobored.plexdownloader.model.User;
import org.lolobored.plexdownloader.service.JwtService;
import org.lolobored.plexdownloader.repository.UserRepository;
import org.lolobored.plexdownloader.transcode.TranscodeQueueRunner;
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

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
class TranscodeControllerTest {

    MockMvc mockMvc;
    final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired WebApplicationContext webApplicationContext;
    @MockitoBean TranscodeQueueRunner runner;
    @MockitoBean JwtService jwtService;
    @MockitoBean UserRepository userRepository;
    @MockitoBean JwtAuthFilter jwtAuthFilter;

    private void setupAuth(User.Role role) throws Exception {
        User user = new User();
        user.setId(1L);
        user.setUsername("user");
        user.setRole(role);

        String springRole = "ROLE_" + role.name();

        doAnswer((InvocationOnMock inv) -> {
            HttpServletRequest req = inv.getArgument(0);
            HttpServletResponse res = inv.getArgument(1);
            FilterChain chain = inv.getArgument(2);

            SecurityContext ctx = SecurityContextHolder.createEmptyContext();
            ctx.setAuthentication(new UsernamePasswordAuthenticationToken(
                user, null, List.of(new SimpleGrantedAuthority(springRole))));
            SecurityContextHolder.setContext(ctx);

            chain.doFilter(req, res);
            return null;
        }).when(jwtAuthFilter).doFilter(any(), any(), any());
    }

    @BeforeEach
    void setup() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
            .apply(SecurityMockMvcConfigurers.springSecurity())
            .build();
        when(runner.getMaxConcurrent()).thenReturn(2);
    }

    @Test
    void getConcurrencyReturnsCurrentMax() throws Exception {
        setupAuth(User.Role.USER);
        mockMvc.perform(get("/api/transcode/concurrency"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.maxConcurrent").value(2));
    }

    @Test
    void getConcurrencyAccessibleByNonAdmin() throws Exception {
        setupAuth(User.Role.USER);
        mockMvc.perform(get("/api/transcode/concurrency"))
            .andExpect(status().isOk());
    }

    @Test
    void putConcurrencyDelegatesSetAndReturnsNewValue() throws Exception {
        setupAuth(User.Role.ADMIN);
        when(runner.getMaxConcurrent()).thenReturn(4);

        mockMvc.perform(put("/api/transcode/concurrency")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("maxConcurrent", 4))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.maxConcurrent").value(4));

        verify(runner).setMaxConcurrent(4);
    }

    @Test
    void putConcurrencyRequiresAdmin() throws Exception {
        setupAuth(User.Role.USER);
        mockMvc.perform(put("/api/transcode/concurrency")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("maxConcurrent", 4))))
            .andExpect(status().isForbidden());
    }

    @Test
    void putConcurrencyRejectsBelowOne() throws Exception {
        setupAuth(User.Role.ADMIN);
        mockMvc.perform(put("/api/transcode/concurrency")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("maxConcurrent", 0))))
            .andExpect(status().isBadRequest());
    }
}
