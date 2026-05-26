package org.lolobored.plexdownloader.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.ToString;
import java.time.Instant;

@Data
@ToString(exclude = {"user", "season"})
@Entity
@Table(name = "season_subscriptions",
    uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "season_id"}))
public class SeasonSubscription {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "season_id", nullable = false)
    private Season season;

    @Column(name = "target_count", nullable = false)
    private Integer targetCount;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt = Instant.now();
}
