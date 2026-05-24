package org.lolobored.plexdownloader.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data @JsonIgnoreProperties(ignoreUnknown = true)
public class PlexRole {
    @JsonProperty("tag")
    private String name;
    private String tagKey;
    private String role;
}
