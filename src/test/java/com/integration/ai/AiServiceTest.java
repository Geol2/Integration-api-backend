package com.integration.ai;

import com.integration.ai.AiService.Turn;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AiServiceTest {

    @Test
    void sanitizeKeepsAlternatingPairs() {
        List<Turn> h = List.of(new Turn("user", "q1"), new Turn("assistant", "a1"), new Turn("user", "q2"));
        // 마지막 user(답 없는 질문)는 버립니다 — 새 질문이 뒤에 붙기 때문입니다.
        assertThat(AiService.sanitize(h)).containsExactly(new Turn("user", "q1"), new Turn("assistant", "a1"));
    }

    @Test
    void sanitizeDropsBrokenHistory() {
        List<Turn> h = List.of(new Turn("user", "q1"), new Turn("user", "q2"), new Turn("assistant", "a"));
        assertThat(AiService.sanitize(h)).isEmpty();
        assertThat(AiService.sanitize(null)).isEmpty();
        assertThat(AiService.sanitize(List.of(new Turn("system", "x")))).isEmpty();
    }

    @Test
    void sanitizeKeepsOnlyRecentTurnsStartingWithUser() {
        List<Turn> h = new ArrayList<>();
        for (int i = 0; i < 8; i++) { h.add(new Turn("user", "q" + i)); h.add(new Turn("assistant", "a" + i)); }
        List<Turn> out = AiService.sanitize(h);
        assertThat(out).hasSize(AiService.MAX_HISTORY_TURNS);
        assertThat(out.get(0)).isEqualTo(new Turn("user", "q3"));
        assertThat(out.get(out.size() - 1)).isEqualTo(new Turn("assistant", "a7"));
    }

    @Test
    void splitSeparatesGlassesSummaryFromPhoneDetail() {
        AiService.Answer a = AiService.split("OrderService.save()에 검증 추가\n@@상세@@\nif (qty <= 0) throw ...");
        assertThat(a.summary()).isEqualTo("OrderService.save()에 검증 추가");
        assertThat(a.detail()).isEqualTo("if (qty <= 0) throw ...");
        assertThat(a.full()).contains(AiService.DETAIL_MARK);

        assertThat(AiService.split("짧은 답").detail()).isNull();
        assertThat(AiService.split("@@상세@@\n전체").summary()).isEqualTo("전체 답은 폰 화면에서 보세요.");
    }
}
