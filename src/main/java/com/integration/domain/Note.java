package com.integration.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

/** 1:N with {@link User}. Mirrors byeolbit_notes (sticky notes) in localStorage. */
@Entity
@Table(name = "notes")
@Getter
@Setter
@NoArgsConstructor
public class Note {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private String text;

    @Column(nullable = false)
    private double x = 0;

    @Column(nullable = false)
    private double y = 0;

    @Column(nullable = false)
    private double rot = 0;

    /** Note width in px. Defaults to the legacy fixed size for old rows. */
    @Column(nullable = false)
    @ColumnDefault("180")
    private double width = 180;

    /** Note height in px. Defaults to the legacy min-height for old rows. */
    @Column(nullable = false)
    @ColumnDefault("90")
    private double height = 90;

    /** Which day this note belongs to, e.g. "2026-07-15". Null = legacy note, shown on every day. */
    @Column(name = "date_key")
    private String dateKey;

    /** Pinned notes are shown regardless of the selected day. */
    @Column(nullable = false)
    @ColumnDefault("false")
    private boolean pinned = false;

    /** original client timestamp (ms). */
    @Column(nullable = false)
    private long ts = 0;
}
