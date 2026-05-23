package com.plexdownloader.controller;

import com.plexdownloader.config.JwtAuthFilter;
import com.plexdownloader.config.SecurityConfig;
import com.plexdownloader.dto.JwtResponse;
import com.plexdownloader.dto.PlexPinInitResponse;
import com.plexdownloader.repository.UserRepository;
import com.plexdownloader.service.AuthService;
import com.plexdownloader.service.JwtService;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuthController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("test")
class AuthControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean AuthService authService;
    @MockBean JwtService jwtService;
    @MockBean UserRepository userRepository;
    @MockBean JwtAuthFilter jwtAuthFilter;

    @BeforeEach
    void setupFilterPassThrough() throws Exception {
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
        when(authService.initPin()).thenReturn(new PlexPinInitResponse(42L, "abc123"));

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
