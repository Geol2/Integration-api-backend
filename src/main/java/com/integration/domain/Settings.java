package com.integration.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 1:1 with {@link User}. Mirrors the frontend DEFAULT_SETTINGS object
 * (byeolbit_settings in localStorage). userId is both PK and FK.
 */
@Entity
@Table(name = "settings")
@Getter
@Setter
@NoArgsConstructor
public class Settings {

    @Id
    private Long userId;

    @Column(nullable = false)
    private String userName = "";

    @Column(nullable = false)
    private boolean use24h = true;

    @Column(nullable = false)
    private boolean showSeconds = true;

    @Column(nullable = false)
    private String tempUnit = "C";

    @Column(nullable = false)
    private boolean showQuote = true;

    @Column(nullable = false)
    private String background = "mountain";

    /**
     * How many minutes before a todo's time its reminder push fires. Nullable so the
     * column can be added to existing rows without a backfill; readers coalesce null
     * to the configured default.
     */
    @Column(name = "remind_lead_minutes")
    private Integer remindLeadMinutes = 10;

    public Settings(Long userId) {
        this.userId = userId;
    }
}
