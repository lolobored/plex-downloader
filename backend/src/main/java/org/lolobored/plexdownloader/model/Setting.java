package org.lolobored.plexdownloader.model;

import jakarta.persistence.*;
import lombok.Data;

@Data @Entity @Table(name = "settings")
public class Setting {
    @Id
    private String key;
    @Column(columnDefinition = "TEXT")
    private String value;
}
