package com.integration.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 1:N with {@link User}. A YouTube track in the user's music playlist. */
@Entity
@Table(name = "tracks")
@Getter
@Setter
@NoArgsConstructor
public class Track {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** YouTube video id, e.g. "dQw4w9WgXcQ". */
    @Column(nullable = false)
    private String videoId;

    /** User-facing label for the track. */
    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private int sortOrder = 0;

    /** original client timestamp (ms). */
    @Column(nullable = false)
    private long ts = 0;
}
