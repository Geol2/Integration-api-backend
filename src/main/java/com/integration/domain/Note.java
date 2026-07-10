package com.integration.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

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

    /** original client timestamp (ms). */
    @Column(nullable = false)
    private long ts = 0;
}
