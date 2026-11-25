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
            너는 영어 대화를 돕는 코치야. 아래는 상대방이 나(사용자)에게 영어로 한 말이야(오래된 것 → 최근).
            내가 상대에게 바로 말할 수 있는 자연스럽고 짧은 **영어 대답** 한 문장을 만들어.
            규칙:
            - 상대의 말을 따라 하거나 바꿔 말하지 마. 그 말에 대한 '나의 대답'이어야 해.
            - 질문을 받았으면 답하고, 제안이면 수락하거나 되묻고, 정보면 맞장구치거나 이어서 물어봐.
            - 대답은 반드시 영어로, 20단어 이하, 쉬운 단어로.
            출력은 정확히 아래 두 줄 형식만(다른 말 없이):
            EN: <영어 대답>
            KO: <그 대답의 한국어 뜻>""";

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

    /** 실시간 인식 시작: Deepgram 임시 토큰(주인만 — 요금이 주인 계정으로 나가요). */
    @PostMapping("/live-token")
    public com.integration.ai.CloudSttService.Grant liveToken() {
        User u = currentUser.require();
        if (!cloud.isAvailable() || !ai.isOwner(u.getEmail()))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "실시간 인식을 쓸 수 없어요.");
        return cloud.grant();
    }

    public record TranslateRequest(String text) {}

    /** 실시간 인식으로 끝난 문장 하나를 한국어로. */
    @PostMapping("/translate")
    public Chunk translate(@RequestBody TranslateRequest req) {
        User u = currentUser.require();
        if (!ai.isEnabledFor(u.getEmail()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "통역을 쓰려면 ⋯ → Claude API 키에서 내 API 키를 등록해 주세요.");
        String en = req == null || req.text() == null ? "" : clip(req.text().strip(), 600);
        if (en.length() < 2) return new Chunk("", "");
        long t0 = System.currentTimeMillis();
        String ko = ai.quick(u.getId(), u.getEmail(), TRANSLATE, en, 400).strip();
        log.info("통역 번역 {}ms: \"{}\"", System.currentTimeMillis() - t0, en.length() > 80 ? en.substring(0, 80) : en);
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
        return parseSuggestion(out);
    }

    /** "EN: …" / "KO: …" 두 줄을 꺼냄. 형식이 어긋나도 영어(알파벳이 많은 줄)를 영어 자리에. */
    static Suggestion parseSuggestion(String out) {
        String en = "", ko = "";
        java.util.List<String> rest = new java.util.ArrayList<>();
        for (String line : out.split("\\R+")) {
            String t = line.strip().replaceAll("^[*\\-\\s]+", "");
            if (t.isEmpty()) continue;
            if (t.matches("(?i)^EN\\s*[:：].*")) en = t.replaceFirst("(?i)^EN\\s*[:：]\\s*", "");
            else if (t.matches("(?i)^KO\\s*[:：].*")) ko = t.replaceFirst("(?i)^KO\\s*[:：]\\s*", "");
            else rest.add(t);
        }
        for (String t : rest) {
            if (en.isEmpty() && isEnglish(t)) en = t;
            else if (ko.isEmpty() && !isEnglish(t)) ko = t;
        }
        // 영어 자리에 한국어가 왔으면 바꿔 끼움
        if (!en.isEmpty() && !isEnglish(en) && isEnglish(ko)) { String x = en; en = ko; ko = x; }
        en = en.replaceAll("^[\"“]|[\"”]$", "").strip();
        if (en.isEmpty() || !isEnglish(en)) return new Suggestion("(영어 대답을 만들지 못했어요. 다시 눌러 보세요.)", "");
        return new Suggestion(en, ko.strip());
    }

    static boolean isEnglish(String t) {
        long latin = t.chars().filter(c -> (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')).count();
        long hangul = t.chars().filter(c -> c >= 0xAC00 && c <= 0xD7A3).count();
        return latin > 0 && latin >= hangul * 2;
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
