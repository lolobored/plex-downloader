package org.lolobored.plexdownloader.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Forwards all unmatched non-asset routes to index.html so Vue Router's
 * history mode works when users navigate directly to a deep URL.
 *
 * The regex [^\\.]*  matches path segments WITHOUT a dot, so static assets
 * like /app.abc123.js, /favicon.ico are NOT matched (Spring serves them
 * directly from classpath:/static/).
 *
 * More-specific REST endpoints (/api/**, /actuator/**) take precedence
 * because Spring MVC picks the most specific handler first.
 */
@Controller
public class SpaController {

    // Handles single-segment routes: /, /movies, /playlists, /queue, /settings, /login
    @GetMapping(value = {"/{path:[^\\.]*}"})
    public String spaDepth1() {
        return "forward:/index.html";
    }

    // Handles two-segment routes: /movies/123, /playlists/456, /tv/789
    @GetMapping(value = {"/{path1:[^\\.]*}/{path2:[^\\.]*}"})
    public String spaDepth2() {
        return "forward:/index.html";
    }
}
