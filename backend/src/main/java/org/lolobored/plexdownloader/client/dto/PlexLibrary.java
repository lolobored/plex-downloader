package org.lolobored.plexdownloader.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data @JsonIgnoreProperties(ignoreUnknown = true)
public class PlexLibrary {
    private String key;
    private String title;
    private String type;
    private String agent;
}
