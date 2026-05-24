package org.lolobored.plexdownloader.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PathMappingService {

    private final SettingsService settings;

    public String translate(String plexPath) {
        String plexPrefix = stripTrailingSlash(settings.getRequired("plex.path.prefix.plex"));
        String appPrefix  = stripTrailingSlash(settings.getRequired("plex.path.prefix.app"));

        if (!plexPath.startsWith(plexPrefix)) {
            throw new IllegalArgumentException(
                "Path '" + plexPath + "' does not start with configured Plex prefix '" + plexPrefix + "'");
        }
        return appPrefix + plexPath.substring(plexPrefix.length());
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
