package com.integration.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 1:N with {@link User}. Mirrors byeolbit_diaries in localStorage, which was a
 * map keyed by a date string — here that key becomes {@code dateKey}, unique per user.
 */
@Entity
@Table(
    name = "diaries",
    uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "date_key"})
)
@Getter
@Setter
@NoArgsConstructor
public class Diary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** e.g. "2026-7-10" — the calendar cell key from the frontend. */
    @Column(name = "date_key", nullable = false)
    private String dateKey;

    @Column(nullable = false)
    private String title = "";

    // Plain TEXT column, not @Lob: Hibernate maps @Lob String to Postgres's
    // large-object (oid) type, which requires an active transaction to read back
    // and throws "Unable to access lob stream" once the request-scoped tx has
    // closed (h2 never surfaced this since it doesn't have Postgres's oid mechanism).
    @Column(nullable = false, columnDefinition = "TEXT")
    private String body = "";

    @Column(nullable = false)
    private String mood = "";
}
