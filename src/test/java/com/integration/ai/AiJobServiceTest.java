package com.integration.ai;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class AiJobServiceTest {

    private static AiJobService.JobView await(AiJobService s, Long user, String id) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            var v = s.get(user, id);
            if (v.status() != AiJobService.Status.running) return v;
            Thread.sleep(20);
        }
        throw new AssertionError("timeout");
    }

    @Test
    void conversationContinuesAcrossScreensAndResetsOnProjectChange() throws Exception {
        AiJobService s = new AiJobService();
        List<List<AiService.Turn>> seen = new CopyOnWriteArrayList<>();
        AiJobService.Work work = (history, progress) -> {
            seen.add(new ArrayList<>(history));
            progress.accept("읽기: A.java");
            return new AiService.Answer("답" + seen.size(), null, List.of());
        };

        // PC에서 질문
        String j1 = s.start(1L, "첫 질문", "proj", 0, null, work);
        var v1 = await(s, 1L, j1);
        assertThat(v1.answer()).isEqualTo("답1");
        assertThat(v1.progress()).containsExactly("읽기: A.java");
        assertThat(v1.turns()).isEqualTo(1);
        // 안경 앱은 latest로 같은 질문을 봅니다.
        assertThat(s.latest(1L).id()).isEqualTo(j1);
        assertThat(s.latest(1L).question()).isEqualTo("첫 질문");
        assertThat(s.latest(2L)).isNull();

        // 폰에서 이어서 질문 → 앞 대화가 history로 들어감
        String j2 = s.start(1L, "이어서", "proj", 2, null, work);
        await(s, 1L, j2);
        assertThat(seen.get(1)).extracting(AiService.Turn::text).containsExactly("첫 질문", "답1");
        assertThat(s.latest(1L).turns()).isEqualTo(2);

        // 프로젝트를 바꾸면 새 대화
        String j3 = s.start(1L, "다른 프로젝트", "other", 0, null, work);
        await(s, 1L, j3);
        assertThat(seen.get(2)).isEmpty();

        // 새 대화 버튼
        s.reset(1L);
        assertThat(s.latest(1L)).isNull();
        String j4 = s.start(1L, "새로", "other", 0, null, work);
        await(s, 1L, j4);
        assertThat(seen.get(3)).isEmpty();

        // 사진 질문은 "[사진 N장 첨부]"로 기록
        String j5 = s.start(1L, "사진 봐줘", "other", 2, null, work);
        await(s, 1L, j5);
        String j6 = s.start(1L, "다음", "other", 0, null, work);
        await(s, 1L, j6);
        assertThat(seen.get(5)).extracting(AiService.Turn::text).contains("사진 봐줘\n[사진 2장 첨부]");
    }

    @Test
    void legacyClientHistoryIsUsedAsIsAndNotStored() throws Exception {
        AiJobService s = new AiJobService();
        List<List<AiService.Turn>> seen = new CopyOnWriteArrayList<>();
        AiJobService.Work work = (history, progress) -> {
            seen.add(history);
            return new AiService.Answer("ok", null, List.of());
        };
        List<AiService.Turn> old = List.of(new AiService.Turn("user", "q"), new AiService.Turn("assistant", "a"));
        await(s, 1L, s.start(1L, "x", null, 0, old, work));
        await(s, 1L, s.start(1L, "y", null, 0, null, work));
        assertThat(seen.get(0)).isEqualTo(old);
        assertThat(seen.get(1)).isEmpty();
    }

    @Test
    void otherUsersJobsAreHidden() throws Exception {
        AiJobService s = new AiJobService();
        String j = s.start(1L, "q", null, 0, null, (h, p) -> new AiService.Answer("a", null, List.of()));
        await(s, 1L, j);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> s.get(2L, j)).hasMessageContaining("작업이 없어요");
    }
}
