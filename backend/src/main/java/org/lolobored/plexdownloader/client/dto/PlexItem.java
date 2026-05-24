package org.lolobored.plexdownloader.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.util.List;

@Data @JsonIgnoreProperties(ignoreUnknown = true)
public class PlexItem {
    private String ratingKey;
    private String type;
    private String title;
    private Integer year;
    private Integer index;
    @JsonProperty("originallyAvailableAt")
    private String airDate;
    private String summary;
    private Float rating;
    private String studio;
    private String thumb;
    private Long duration;
    private Long updatedAt;
    private Integer leafCount;

    @JsonProperty("Guid")
    private List<PlexGuidRaw> guid;

    @JsonProperty("Media")
    private List<PlexMediaRaw> media;

    @JsonProperty("Genre")
    private List<PlexTag> genre;

    @JsonProperty("Director")
    private List<PlexTag> director;

    @JsonProperty("Writer")
    private List<PlexTag> writer;

    @JsonProperty("Role")
    private List<PlexRole> role;

    public String firstFilePath() {
        if (media == null || media.isEmpty()) return null;
        var parts = media.get(0).getPart();
        if (parts == null || parts.isEmpty()) return null;
        return parts.get(0).getFile();
    }

    public String firstVideoResolution() {
        if (media == null || media.isEmpty()) return null;
        var parts = media.get(0).getPart();
        if (parts == null || parts.isEmpty()) return null;
        return parts.get(0).getVideoResolution();
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PlexGuidRaw {
        private String id;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PlexMediaRaw {
        @JsonProperty("Part")
        private List<PlexPartRaw> part;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PlexPartRaw {
        private String file;
        private String videoResolution;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PlexTag {
        private String tag;
    }

    public Long parseTmdbId() { return parseGuid("tmdb://"); }
    public Long parseTvdbId() { return parseGuid("tvdb://"); }

    public String parseImdbId() {
        if (guid == null) return null;
        return guid.stream()
            .map(PlexGuidRaw::getId)
            .filter(id -> id != null && id.startsWith("imdb://"))
            .map(id -> id.substring("imdb://".length()))
            .findFirst().orElse(null);
    }

    private Long parseGuid(String prefix) {
        if (guid == null) return null;
        return guid.stream()
            .map(PlexGuidRaw::getId)
            .filter(id -> id != null && id.startsWith(prefix))
            .map(id -> {
                try { return Long.parseLong(id.substring(prefix.length())); }
                catch (NumberFormatException e) { return null; }
            })
            .filter(id -> id != null)
            .findFirst().orElse(null);
    }
}
