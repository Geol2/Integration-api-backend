package com.integration.translate;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 논문 PDF 번역을 n8n 웹훅으로 넘기고, 번역된 문단을 돌려받습니다.
 *
 * 회의록 업로드(RecordingUploadService)와 같은 이유로 브라우저가 웹훅을 직접 부르지
 * 않습니다: 프론트에 넣은 값은 번들에 인라인되어 공개되고, 공유 키로는 누가 불렀는지도
 * 알 수 없습니다. LLM API 키는 n8n 자격증명에만 두고, 웹훅은 인터넷에 열지 않습니다.
 *
 * 녹음과 결정적으로 다른 점: 이쪽은 **응답을 기다립니다**. n8n Webhook 노드를
 * Respond=Immediately로 두면 빈 응답이 즉시 돌아와 번역문을 영영 못 받습니다.
 * "Respond to Webhook 노드 사용"으로 설정해야 합니다.
 */
@Service
public class TranslateService {

    private static final Logger log = LoggerFactory.getLogger(TranslateService.class);

    /** n8n Webhook 노드의 Header Auth에 설정한 헤더 이름과 같아야 합니다. */
    private static final String KEY_HEADER = "X-Translate-Key";

    private final String webhookUrl;
    private final String webhookKey;
    private final RestClient client;

    public TranslateService(
            @Value("${app.translate.webhook-url:}") String webhookUrl,
            @Value("${app.translate.webhook-key:}") String webhookKey,
            @Value("${app.translate.timeout-seconds:85}") int timeoutSeconds) {
        this.webhookUrl = webhookUrl;
        this.webhookKey = webhookKey;

        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder()
                        // HTTP/1.1 고정 — JDK 클라이언트는 기본이 HTTP/2라 평문 http:// 로
                        // 보낼 때 Upgrade: h2c를 시도하고, n8n(Express)은 그걸 지원하지 않아
                        // 본문이 실린 POST가 응답 없이 멈춥니다. (녹음 업로드에서 겪은 문제)
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(Duration.ofSeconds(10))
                        .build());
        // 브라우저 → 이 API 구간이 Cloudflare Tunnel(약 100초 상한)을 지나므로 그 아래로
        // 잡습니다. 더 오래 기다려봐야 사용자 쪽 연결은 이미 끊긴 뒤고 톰캣 스레드만 잡힙니다.
        // 프론트가 문단을 3500자씩 나눠 보내는 것도 한 요청이 이 시간을 넘지 않게 하려는 것.
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    public boolean isConfigured() {
        return !webhookUrl.isBlank();
    }

    /**
     * @param texts 원문 문단들. 순서와 개수가 그대로 유지되어 돌아와야 합니다.
     * @return texts와 같은 길이의 번역문 목록
     */
    public List<String> translate(List<String> texts, String source, String target,
                                  Long userId, String userEmail) {
        if (!isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Translate webhook is not configured");
        }

        Map<String, Object> body = Map.of(
                "texts", texts,
                "source", source,
                "target", target,
                // 누가 얼마나 번역했는지 n8n 쪽에서도 남길 수 있게. 공유 키로는 알 수 없던 정보입니다.
                "userId", String.valueOf(userId),
                "userEmail", userEmail);

        JsonNode response;
        try {
            response = client.post()
                    .uri(webhookUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(h -> {
                        if (!webhookKey.isBlank()) h.set(KEY_HEADER, webhookKey);
                    })
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            // 응답 본문에 n8n이 거부한 이유가 담겨 있습니다. 남기지 않으면 화면에는 502만
            // 뜨고 서버 로그는 조용해서 원인을 알 수 없습니다.
            log.warn("n8n webhook rejected the translate request: {} {}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Translate webhook rejected the request (" + e.getStatusCode() + ")");
        } catch (RestClientException e) {
            // 연결 실패·타임아웃. n8n이 죽었거나 LLM이 느린 것은 우리 API의 잘못이 아니므로
            // 502로 구분해 알립니다. 프론트는 이 상태를 보고 재시도를 안내합니다.
            log.warn("n8n translate webhook is unreachable or too slow", e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Translate webhook is unreachable");
        }

        return readTranslations(response, texts.size());
    }

    /**
     * n8n 응답에서 번역문 배열을 꺼냅니다.
     *
     * 모양을 두 가지 받아주는 이유: Respond to Webhook 노드는 설정에 따라 객체
     * {"translations": [...]} 로도, 아이템 배열 [{"translations": [...]}] 로도 내보냅니다.
     * 둘 중 뭘 쓰는지는 워크플로를 만든 사람 손에 달려 있어서, 여기서 흡수합니다.
     */
    private List<String> readTranslations(JsonNode response, int expected) {
        JsonNode root = response;
        if (root != null && root.isArray() && root.size() == 1) root = root.get(0);

        JsonNode array = root != null ? root.path("translations") : null;
        if (array == null || !array.isArray()) {
            log.warn("translate webhook returned an unexpected shape: {}", response);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Translate webhook returned an unexpected response");
        }
        // 개수가 어긋나면 어느 문단이 밀렸는지 알 수 없습니다. 원문과 엉뚱하게 짝지어진
        // 번역을 조용히 보여주느니 실패로 알립니다.
        if (array.size() != expected) {
            log.warn("translate webhook returned {} paragraphs, expected {}", array.size(), expected);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Translate webhook returned " + array.size() + " paragraphs, expected " + expected);
        }

        List<String> out = new ArrayList<>(array.size());
        for (JsonNode node : array) out.add(node.asText(""));
        return out;
    }
}
