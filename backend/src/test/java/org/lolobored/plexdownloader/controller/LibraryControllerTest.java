package org.lolobored.plexdownloader.controller;

import org.lolobored.plexdownloader.config.JwtAuthFilter;
import org.lolobored.plexdownloader.model.Movie;
import org.lolobored.plexdownloader.model.User;
import org.lolobored.plexdownloader.repository.*;
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
import org.springframework.data.domain.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
class LibraryControllerTest {

    MockMvc mockMvc;
    @Autowired WebApplicationContext webApplicationContext;
    @MockitoBean MovieRepository movieRepo;
    @MockitoBean TvShowRepository showRepo;
    @MockitoBean SeasonRepository seasonRepo;
    @MockitoBean EpisodeRepository episodeRepo;
    @MockitoBean UserMovieWatchedRepository movieWatchedRepo;
    @MockitoBean UserEpisodeWatchedRepository episodeWatchedRepo;
    @MockitoBean JwtService jwtService;
    @MockitoBean UserRepository userRepository;
    @MockitoBean JwtAuthFilter jwtAuthFilter;

    @BeforeEach
    void setupUserAuth() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
            .apply(SecurityMockMvcConfigurers.springSecurity())
            .build();
        User user = new User();
        user.setId(2L);
        user.setPlexAccountId("user-plex-id");
        user.setUsername("user");
        user.setRole(User.Role.USER);

        doAnswer((InvocationOnMock inv) -> {
            HttpServletRequest req = inv.getArgument(0);
            HttpServletResponse res = inv.getArgument(1);
            FilterChain chain = inv.getArgument(2);

            SecurityContext ctx = SecurityContextHolder.createEmptyContext();
            ctx.setAuthentication(new UsernamePasswordAuthenticationToken(
                user, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
            SecurityContextHolder.setContext(ctx);

            chain.doFilter(req, res);
            return null;
        }).when(jwtAuthFilter).doFilter(any(), any(), any());
    }

    @Test
    void getMoviesReturnsPagedResults() throws Exception {
        Movie movie = new Movie();
        movie.setId(1L);
        movie.setPlexId("12345");
        movie.setTitle("Inception");
        movie.setYear(2010);
        movie.setActors(new ArrayList<>());
        movie.setGenres(new ArrayList<>());
        movie.setDirectors(new ArrayList<>());

        Page<Movie> page = new PageImpl<>(List.of(movie));
        when(movieRepo.search(eq(""), isNull(), any(Pageable.class))).thenReturn(page);

        mockMvc.perform(get("/api/movies"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].title").value("Inception"));
    }

    @Test
    void getMovieByIdReturns404WhenNotFound() throws Exception {
        when(movieRepo.findById(99L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/movies/99"))
            .andExpect(status().isNotFound());
    }
}
