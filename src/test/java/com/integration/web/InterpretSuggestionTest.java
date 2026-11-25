package com.integration.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InterpretSuggestionTest {

    @Test
    void EN_KO_두_줄을_꺼낸다() {
        var s = InterpretController.parseSuggestion("EN: Sure, three works for me.\nKO: 네, 3시 좋아요.");
        assertEquals("Sure, three works for me.", s.english());
        assertEquals("네, 3시 좋아요.", s.korean());
    }

    @Test
    void 순서가_바뀌거나_표시가_없어도_영어를_영어_자리에() {
        var s = InterpretController.parseSuggestion("네, 좋아요.\nSounds good to me.");
        assertEquals("Sounds good to me.", s.english());
        assertEquals("네, 좋아요.", s.korean());
        var t = InterpretController.parseSuggestion("EN: 네 좋아요\nKO: Sounds good.");
        assertEquals("Sounds good.", t.english());
    }

    @Test
    void 요점을_최대_3개까지_꺼낸다() {
        var s = InterpretController.parseSuggestion("SUM: 회의를 3시로 옮기자 | 장소는 2층 | 자료 준비 | 넷째\nEN: Sure, see you at three.\nKO: 네, 3시에 봬요.");
        assertEquals(java.util.List.of("회의를 3시로 옮기자", "장소는 2층", "자료 준비"), s.summary());
        assertEquals("Sure, see you at three.", s.english());
    }

    @Test
    void 영어가_없으면_다시_누르라고_안내() {
        assertTrue(InterpretController.parseSuggestion("좋아요!").english().contains("다시"));
    }
}
