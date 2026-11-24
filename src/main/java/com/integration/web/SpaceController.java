package com.integration.web;

import com.integration.space.NeoService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 우주 탐험용 실시간 자료. GET /api/space/neo : 지난 7일~앞으로 60일 지구 근접 소행성(0.05 AU 안). */
@RestController
@RequestMapping("/api/space")
public class SpaceController {

    private final NeoService neo;

    public SpaceController(NeoService neo) {
        this.neo = neo;
    }

    @GetMapping("/neo")
    public NeoService.NeoList neo() {
        try {
            return neo.list();
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getMessage());
        }
    }
}
