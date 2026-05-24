package org.lolobored.plexdownloader.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PathMappingService {

    private final SettingsService settings;

    /** Translate a Plex-namespace path to an app-namespace path.
     *  Tries movies prefix first, then TV prefix. */
    public String translate(String plexPath) {
        String moviesPlex = strip(settings.getRequired("plex.path.prefix.movies.plex"));
        String moviesApp  = strip(settings.getRequired("plex.path.prefix.movies.app"));
        if (plexPath.startsWith(moviesPlex)) {
            return moviesApp + plexPath.substring(moviesPlex.length());
        }
        String tvPlex = strip(settings.getRequired("plex.path.prefix.tv.plex"));
        String tvApp  = strip(settings.getRequired("plex.path.prefix.tv.app"));
        if (plexPath.startsWith(tvPlex)) {
            return tvApp + plexPath.substring(tvPlex.length());
        }
        throw new IllegalArgumentException(
            "Path '" + plexPath + "' does not match any configured prefix");
    }

    /** Translate an app-namespace path (under conversion dir) to Tdarr's namespace. */
    public String appToTdarr(String appPath) {
        String convDir   = strip(settings.getRequired("plex.conversion.dir"));
        String tdarrRoot = strip(settings.getRequired("tdarr.path.prefix.conversion"));
        if (!appPath.startsWith(convDir)) {
            throw new IllegalArgumentException(
                "Path '" + appPath + "' is not under conversion dir '" + convDir + "'");
        }
        return tdarrRoot + appPath.substring(convDir.length());
    }

    /** Translate a Tdarr-namespace path back to an app-namespace path. */
    public String tdarrToApp(String tdarrPath) {
        String tdarrRoot = strip(settings.getRequired("tdarr.path.prefix.conversion"));
        String convDir   = strip(settings.getRequired("plex.conversion.dir"));
        if (!tdarrPath.startsWith(tdarrRoot)) {
            throw new IllegalArgumentException(
                "Path '" + tdarrPath + "' does not start with Tdarr prefix '" + tdarrRoot + "'");
        }
        return convDir + tdarrPath.substring(tdarrRoot.length());
    }

    private static String strip(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
