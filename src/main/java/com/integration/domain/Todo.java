package com.integration.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** 1:N with {@link User}. Mirrors byeolbit_todos in localStorage. */
@Entity
@Table(name = "todos")
@Getter
@Setter
@NoArgsConstructor
public class Todo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private String text;

    @Column(nullable = false)
    private boolean done = false;

    @Column(nullable = false)
    private int sortOrder = 0;

    /** Which day this todo belongs to, e.g. "2026-07-15". Nullable for rows created before per-day todos. */
    @Column(name = "date_key")
    private String dateKey;

    /** Optional time-of-day label, e.g. "14:30". Nullable. */
    @Column(name = "time_label")
    private String timeLabel;

    /** Optional place/location, e.g. "강남역". Nullable. */
    @Column
    private String place;

    /**
     * When the reminder push for this todo was sent. Null = not sent yet, which is what
     * the scheduler scans for, so a todo notifies exactly once. Cleared whenever dateKey
     * or timeLabel changes — a rescheduled appointment gets a fresh reminder.
     */
    @Column(name = "reminded_at")
    private Instant remindedAt;
}
