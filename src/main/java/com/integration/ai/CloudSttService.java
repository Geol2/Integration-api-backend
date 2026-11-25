package com.integration.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 통역용 클라우드 음성 인식(Deepgram). 파이 CPU의 whisper보다 훨씬 빠르고(조각당 1초 안팎) 정확해요.
 * 음성 조각이 Deepgram으로 나가고, 요금은 서버 주인의 Deepgram 계정으로 나갑니다(분당 몇 원).
 */
@Service
public class CloudSttService {

    private static final Logger log = LoggerFactory.getLogger(CloudSttService.class);

    private final String key;
    private final String model;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json = new ObjectMapper();

    public CloudSttService(@Value("${app.ai.deepgram.key:}") String key,
                           @Value("${app.ai.deepgram.model:nova-3}") String model) {
        this.key = key == null ? "" : key.strip();
        this.model = model == null || model.isBlank() ? "nova-3" : model.strip();
        log.info(isAvailable() ? "통역 음성 인식: Deepgram({})" : "통역 음성 인식: Deepgram 키 없음 — 서버 whisper 사용", this.model);
    }

    public boolean isAvailable() { return !key.isEmpty(); }

    /** 16bit 모노 PCM → 텍스트. 못 알아들으면 빈 문자열. */
    public String transcribe(byte[] pcm, int sampleRate, String lang) {
        if (!isAvailable()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "클라우드 음성 인식 키가 없어요.");
        String q = "model=" + enc(model) + "&language=" + enc(lang) + "&smart_format=true&punctuate=true"
                + "&encoding=linear16&sample_rate=" + sampleRate + "&channels=1";
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.deepgram.com/v1/listen?" + q))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Token " + key)
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(pcm))
                .build();
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() == 401 || res.statusCode() == 403) {
                log.error("Deepgram 인증 실패 {}", res.statusCode());
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "클라우드 음성 인식 키가 맞지 않아요.");
            }
            if (res.statusCode() != 200) {
                log.error("Deepgram 실패 {} {}", res.statusCode(), res.body().length() > 300 ? res.body().substring(0, 300) : res.body());
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "음성 인식에 실패했어요(" + res.statusCode() + ").");
            }
            return transcriptOf(json.readTree(res.body()));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "음성 인식이 중단됐어요.");
        } catch (Exception e) {
            log.error("Deepgram 호출 실패", e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "음성 인식 서버에 연결하지 못했어요.");
        }
    }

    /** 실시간 인식용 임시 토큰(30초). 안경 앱이 이걸로 Deepgram에 직접 붙고, 진짜 키는 서버에만 있어요. */
    public record Grant(String token, int expiresIn, String model) {}

    public Grant grant() {
        if (!isAvailable()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "클라우드 음성 인식 키가 없어요.");
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.deepgram.com/v1/auth/grant"))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Token " + key)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"ttl_seconds\":30}"))
                .build();
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() != 200) {
                log.error("Deepgram 토큰 실패 {} {}", res.statusCode(), res.body().length() > 300 ? res.body().substring(0, 300) : res.body());
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "실시간 인식을 시작하지 못했어요(" + res.statusCode() + ").");
            }
            JsonNode n = json.readTree(res.body());
            String token = n.path("access_token").asText("");
            if (token.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "실시간 인식 토큰을 받지 못했어요.");
            return new Grant(token, n.path("expires_in").asInt(30), model);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "실시간 인식이 중단됐어요.");
        } catch (Exception e) {
            log.error("Deepgram 토큰 호출 실패", e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "음성 인식 서버에 연결하지 못했어요.");
        }
    }

    static String transcriptOf(JsonNode root) {
        return root.path("results").path("channels").path(0).path("alternatives").path(0)
                .path("transcript").asText("").strip();
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}
