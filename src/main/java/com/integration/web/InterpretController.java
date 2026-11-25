package com.integration.web;

import com.integration.ai.AiService;
import com.integration.ai.SttService;
import com.integration.domain.User;
import com.integration.security.CurrentUserService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;

/**
 * 통역 모드(영어 → 한국어) — 안경 마이크로 들은 상대의 말을 몇 초 단위로 받아 적고 한국어로 옮기고,
 * 누르면 지금 대화에 맞는 짧은 영어 대답을 추천합니다.
 *
 *  - POST /api/ai/interpret/chunk   (audio PCM16 mono, rate) → { original, korean }  못 알아들으면 둘 다 빈 값
 *  - POST /api/ai/interpret/suggest { lines: [{ original, korean }] } → { english, korean }
 *
 * 음성 인식은 서버(whisper, 영어)에서, 번역·추천은 빠른 모델(AiService.quick)로 합니다.
 */
@RestController
@RequestMapping("/api/ai/interpret")
public class InterpretController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(InterpretController.class);

    private static final String TRANSLATE = """
            너는 동시통역사야. 사용자가 준 영어 문장을 자연스러운 한국어 구어체로 옮겨.
            번역문만 출력해(따옴표·설명·원문 없이). 음성 인식이라 틀린 단어가 있으면 문맥에 맞게 추정해.
            의미 없는 잡음이면 빈 줄만 출력해.""";

    private static final String SUGGEST = """
            너는 영어 대화를 돕는 코치야. 아래는 상대방이 방금까지 영어로 한 말이야(오래된 것 → 최근).
            사용자가 바로 말할 수 있는 자연스럽고 짧은 영어 대답 한 문장을 추천해.
            정확히 두 줄로만 출력해:
            1줄: 영어 대답(20단어 이하)
            2줄: 그 대답의 한국어 뜻""";

    public record Chunk(String original, String korean) {}
    public record Line(String original, String korean) {}
    public record SuggestRequest(List<Line> lines) {}
    public record Suggestion(String english, String korean) {}

    private final AiService ai;
    private final SttService stt;
    private final com.integration.ai.CloudSttService cloud;
    private final CurrentUserService currentUser;

    public InterpretController(AiService ai, SttService stt, com.integration.ai.CloudSttService cloud,
                               CurrentUserService currentUser) {
        this.ai = ai;
        this.stt = stt;
        this.cloud = cloud;
        this.currentUser = currentUser;
    }

    @PostMapping("/chunk")
    public Chunk chunk(@RequestParam("audio") MultipartFile audio,
                       @RequestParam(value = "rate", defaultValue = "16000") int rate) throws IOException {
        User u = requireAllowed();
        byte[] pcm = audio.getBytes();
        long t0 = System.currentTimeMillis();
        // 주인은 클라우드(빠름, 주인 요금). 다른 사용자는 주인 요금이 나가지 않게 서버 whisper.
        boolean useCloud = cloud.isAvailable() && ai.isOwner(u.getEmail());
        String en = (useCloud ? cloud.transcribe(pcm, rate, "en") : stt.transcribe(pcm, rate, "en")).strip();
        long t1 = System.currentTimeMillis();
        log.info("통역 조각 {}초 [{} {}ms] → \"{}\"", String.format("%.1f", pcm.length / 2.0 / rate),
                useCloud ? "deepgram" : "whisper", t1 - t0, en.length() > 80 ? en.substring(0, 80) : en);
        if (en.isEmpty() || en.length() < 2) return new Chunk("", "");
        String ko = ai.quick(u.getId(), u.getEmail(), TRANSLATE, en, 400).strip();
        return new Chunk(en, ko);
    }

    @PostMapping("/suggest")
    public Suggestion suggest(@RequestBody SuggestRequest req) {
        User u = requireAllowed();
        List<Line> lines = req.lines() == null ? List.of() : req.lines();
        StringBuilder sb = new StringBuilder();
        int from = Math.max(0, lines.size() - 8);
        for (Line l : lines.subList(from, lines.size())) {
            if (l == null || l.original() == null || l.original().isBlank()) continue;
            sb.append("- ").append(clip(l.original(), 400)).append('\n');
        }
        if (sb.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "아직 들은 말이 없어요.");
        String out = ai.quick(u.getId(), u.getEmail(), SUGGEST, sb.toString(), 200).strip();
        String[] parts = out.split("\\R+", 2);
        String english = parts[0].replaceFirst("^(1줄:|1\\.|-)\\s*", "").strip();
        String korean = parts.length > 1 ? parts[1].replaceFirst("^(2줄:|2\\.|-)\\s*", "").strip() : "";
        return new Suggestion(english, korean);
    }

    private static String clip(String s, int n) { return s.length() > n ? s.substring(0, n) : s; }

    private User requireAllowed() {
        User u = currentUser.require();
        if (!ai.isEnabledFor(u.getEmail()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "통역을 쓰려면 ⋯ → Claude API 키에서 내 API 키를 등록해 주세요.");
        if (!stt.isAvailable() && !(cloud.isAvailable() && ai.isOwner(u.getEmail())))
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "서버에 음성 인식이 설치되지 않았어요.");
        return u;
    }
}
