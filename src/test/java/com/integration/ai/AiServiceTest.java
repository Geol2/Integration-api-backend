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

    @Test
    void splitExtractsTodoSteps() {
        String raw = "save()에 검증 추가\n@@상세@@\n코드...\n@@할일@@\n- OrderService.save() 검증\n2. 예외 메시지 정의\n3) 테스트 추가\n\n";
        AiService.Answer a = AiService.split(raw);
        assertThat(a.summary()).isEqualTo("save()에 검증 추가");
        assertThat(a.detail()).isEqualTo("코드...");
        assertThat(a.steps()).containsExactly("OrderService.save() 검증", "예외 메시지 정의", "테스트 추가");
        assertThat(AiService.split(a.full()).steps()).isEqualTo(a.steps());
    }

    @Test
    void parseStepsCapsCountAndLength() {
        String many = "- a\n".repeat(20) + "- " + "x".repeat(200);
        assertThat(AiService.parseSteps(many)).hasSize(AiService.MAX_STEPS);
        assertThat(AiService.parseSteps("- " + "x".repeat(200)).get(0)).hasSize(AiService.MAX_STEP_CHARS).endsWith("...");
        assertThat(AiService.split("짧은 답").steps()).isEmpty();
    }

    @Test
    void strangerWithoutOwnKeyIsRefusedBeforeAnyCall() {
        // 서버 키가 있어도, 허용 목록 밖 사용자는 자기 키가 없으면 서버 키로 넘어가지 않고 403 이어야 함
        AiService ai = new AiService("owner-key", "claude-opus-5-5", "medium", "", 100, 300, "me@example.com", "Asia/Seoul", "http://127.0.0.1:9");
        org.springframework.web.server.ResponseStatusException e = org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> ai.ask(7L, "stranger@example.com", "안녕", java.util.List.of(), java.util.List.of()));
        assertThat(e.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void onlyAllowedEmailsCanUseClaude() {
        AiService ai = new AiService("test-key", "claude-opus-5-5", "medium", "", 100, 300, " Me@Example.com , ", "Asia/Seoul");
        assertThat(ai.isEnabledFor("me@example.com")).isTrue();
        assertThat(ai.isEnabledFor("ME@EXAMPLE.COM")).isTrue();
        assertThat(ai.isEnabledFor("stranger@example.com")).isFalse();
        assertThat(ai.isEnabledFor(null)).isFalse();

        // 허용 목록을 비우면 아무도 못 씁니다(fail-closed).
        AiService closed = new AiService("test-key", "claude-opus-5-5", "medium", "", 100, 300, "", "Asia/Seoul");
        assertThat(closed.isEnabledFor("me@example.com")).isFalse();
        // 키가 없으면 허용 목록에 있어도 꺼짐.
        AiService noKey = new AiService("", "claude-opus-5-5", "medium", "", 100, 300, "me@example.com", "Asia/Seoul");
        assertThat(noKey.isEnabledFor("me@example.com")).isFalse();
    }
}
