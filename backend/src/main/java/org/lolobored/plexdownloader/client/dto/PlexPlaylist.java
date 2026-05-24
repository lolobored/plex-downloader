package org.lolobored.plexdownloader.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data @JsonIgnoreProperties(ignoreUnknown = true)
public class PlexPlaylist {
    @JsonProperty("ratingKey")    private String ratingKey;
    @JsonProperty("title")        private String title;
    @JsonProperty("playlistType") private String playlistType;  // "video", "audio", "photo"
    @JsonProperty("leafCount")    private int leafCount;
    @JsonProperty("thumb")        private String thumb;
}
