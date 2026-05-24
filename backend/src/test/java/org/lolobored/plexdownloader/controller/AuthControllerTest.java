package org.lolobored.plexdownloader.controller;

import org.lolobored.plexdownloader.config.JwtAuthFilter;
import org.lolobored.plexdownloader.dto.JwtResponse;
import org.lolobored.plexdownloader.dto.PlexPinInitResponse;
import org.lolobored.plexdownloader.repository.UserRepository;
import org.lolobored.plexdownloader.service.AuthService;
import org.lolobored.plexdownloader.service.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
class AuthControllerTest {

    MockMvc mockMvc;
    @Autowired WebApplicationContext webApplicationContext;
    @MockitoBean AuthService authService;
    @MockitoBean JwtService jwtService;
    @MockitoBean UserRepository userRepository;
    @MockitoBean JwtAuthFilter jwtAuthFilter;

    @BeforeEach
    void setupFilterPassThrough() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
            .apply(SecurityMockMvcConfigurers.springSecurity())
            .build();
        doAnswer((InvocationOnMock inv) -> {
            HttpServletRequest req = inv.getArgument(0);
            HttpServletResponse res = inv.getArgument(1);
            FilterChain chain = inv.getArgument(2);
            chain.doFilter(req, res);
            return null;
        }).when(jwtAuthFilter).doFilter(any(), any(), any());
    }

    @Test
    void initPinReturnsIdAndCode() throws Exception {
        when(authService.initPin()).thenReturn(new PlexPinInitResponse(42L, "abc123", "https://app.plex.tv/auth/#!?clientID=plex-downloader-app&code=abc123"));

        mockMvc.perform(post("/api/auth/plex/pin"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.pinId").value(42))
            .andExpect(jsonPath("$.code").value("abc123"));
    }

    @Test
    void checkPinReturnsPendingWhenNotYetAuthorized() throws Exception {
        when(authService.checkPin(42L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/auth/plex/pin/42"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.status").value("pending"));
    }

    @Test
    void checkPinReturnsJwtWhenAuthorized() throws Exception {
        when(authService.checkPin(42L))
            .thenReturn(Optional.of(new JwtResponse("jwt-token", "Laurent", "ADMIN")));

        mockMvc.perform(get("/api/auth/plex/pin/42"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.token").value("jwt-token"))
            .andExpect(jsonPath("$.username").value("Laurent"));
    }
}
