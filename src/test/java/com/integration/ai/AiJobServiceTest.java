package com.integration.ai;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 대화 저장(H2 메모리 DB) + 백그라운드 질문. 답은 다른 스레드에서 저장되므로 테스트 트랜잭션을 끕니다.
 */
@DataJpaTest
@Import({ConversationService.class, AiJobService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AiJobServiceTest {

    @Autowired ConversationService conversations;
    @Autowired AiJobService jobs;

    private AiJobService.JobView await(Long user, String id) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            var v = jobs.get(user, id);
            if (v.status() != AiJobService.Status.running) return v;
            Thread.sleep(20);
        }
        throw new AssertionError("timeout");
    }

    @Test
    void severalConversationsPerProjectKeepTheirOwnHistory() throws Exception {
        List<List<AiService.Turn>> seen = new CopyOnWriteArrayList<>();
        AiJobService.Work work = (history, progress) -> {
            seen.add(new ArrayList<>(history));
            progress.accept("읽기: A.java");
            return new AiService.Answer("답" + seen.size(), "상세" + seen.size(), List.of("단계1"));
        };
        Long user = 1L;
        var a = conversations.create(user, "demo2");
        var b = conversations.create(user, "demo2");

        var v1 = await(user, jobs.start(user, a.id(), "로그인 어디서 해?", "demo2", 0, null, work));
        assertThat(v1.answer()).isEqualTo("답1");
        assertThat(v1.conversationId()).isEqualTo(a.id());
        assertThat(v1.progress()).containsExactly("읽기: A.java");
        assertThat(v1.turns()).isEqualTo(1);

        // 같은 대화에서 이어 묻기 → 앞 질문·답이 history로
        await(user, jobs.start(user, a.id(), "그럼 테스트는?", "demo2", 2, null, work));
        assertThat(seen.get(1)).extracting(AiService.Turn::text)
                .containsExactly("로그인 어디서 해?", "답1\n@@상세@@\n상세1\n@@할일@@\n- 단계1");

        // 다른 대화는 따로
        await(user, jobs.start(user, b.id(), "별개 질문", "demo2", 0, null, work));
        assertThat(seen.get(2)).isEmpty();

        // 저장된 내역: 제목은 첫 질문, 사진 수·단계까지 남음, 최근 대화가 목록 맨 위
        var detail = conversations.detail(user, a.id());
        assertThat(detail.conversation().title()).isEqualTo("로그인 어디서 해?");
        assertThat(detail.turns()).extracting(ConversationService.TurnView::question).containsExactly("로그인 어디서 해?", "그럼 테스트는?");
        assertThat(detail.turns().get(1).imageCount()).isEqualTo(2);
        assertThat(detail.turns().get(0).steps()).containsExactly("단계1");
        assertThat(conversations.list(user)).extracting(ConversationService.ConversationView::id).containsExactly(b.id(), a.id());

        // 사진 질문은 다음 history에 "[사진 N장 첨부]"로
        await(user, jobs.start(user, a.id(), "다음", "demo2", 0, null, work));
        assertThat(seen.get(3).get(2).text()).isEqualTo("그럼 테스트는?\n[사진 2장 첨부]");

        // 안경은 최근 질문을 따라감
        assertThat(jobs.latest(user).conversationId()).isEqualTo(a.id());
        jobs.reset(user);
        assertThat(jobs.latest(user)).isNull();
    }

    @Test
    void conversationsArePrivateAndDeletable() throws Exception {
        var c = conversations.create(11L, null);
        assertThatThrownBy(() -> conversations.detail(12L, c.id())).hasMessageContaining("대화가 없어요");
        assertThat(conversations.list(12L)).isEmpty();

        await(11L, jobs.start(11L, c.id(), "q", null, 0, null, (h, p) -> new AiService.Answer("a", null, List.of())));
        assertThat(conversations.rename(11L, c.id(), "  이름 바꿈 ").title()).isEqualTo("이름 바꿈");
        conversations.delete(11L, c.id());
        assertThat(conversations.list(11L)).isEmpty();
        assertThatThrownBy(() -> jobs.get(12L, jobs.latest(11L).id())).hasMessageContaining("작업이 없어요");
    }

    @Test
    void legacyClientHistoryIsUsedAsIsAndNotStored() throws Exception {
        List<List<AiService.Turn>> seen = new CopyOnWriteArrayList<>();
        List<AiService.Turn> old = List.of(new AiService.Turn("user", "q"), new AiService.Turn("assistant", "a"));
        await(3L, jobs.start(3L, null, "x", null, 0, old, (h, p) -> { seen.add(h); return new AiService.Answer("ok", null, List.of()); }));
        assertThat(seen.get(0)).isEqualTo(old);
        assertThat(conversations.list(3L)).isEmpty();
    }

    @Test
    void titleFromQuestion() {
        assertThat(ConversationService.titleFrom("  여러   줄\n질문  ", 0)).isEqualTo("여러 줄 질문");
        assertThat(ConversationService.titleFrom("", 3)).isEqualTo("사진 3장");
        assertThat(ConversationService.titleFrom("가".repeat(50), 0)).hasSize(43).endsWith("...");
    }
}
