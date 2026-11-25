package com.integration.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 안경 마이크 음성 → 텍스트. 서버에 설치한 whisper.cpp(whisper-cli)를 실행합니다.
 * 음성은 외부로 나가지 않고, 키·요금도 없습니다. 설치: scripts/install-whisper.sh
 *
 * 입력은 안경이 주는 PCM(16bit little-endian, 모노, 기본 16kHz)이고, WAV 헤더를 붙여 넘깁니다.
 */
@Service
public class SttService {

    private static final Logger log = LoggerFactory.getLogger(SttService.class);

    static final int MAX_SECONDS = 60;
    static final double MIN_SECONDS = 0.4;

    /**
     * 개발 용어를 미리 알려 주면 "자바 스프링" 대신 "Java Spring"처럼 받아 적습니다.
     * 명령줄 인자로 넘기므로 ASCII만 씁니다 — 서버 JVM이 UTF-8 로캘이 아니면 인자 속 한글이 ???로 깨집니다.
     */
    private static final String PROMPT =
            "Java, Spring Boot, JPA, Hibernate, Python, SQL, SELECT, JOIN, GROUP BY, React, Vite, Controller, Service, Repository, Entity, commit, merge.";

    /** 조용한 녹음에 whisper가 지어내는 대표 문장들 — 이것만 나오면 못 알아들은 것으로 봅니다. */
    private static final Set<String> HALLUCINATIONS = Set.of(
            "감사합니다", "감사합니다.", "시청해주셔서 감사합니다.", "시청해 주셔서 감사합니다.",
            "구독과 좋아요 부탁드립니다.", "MBC 뉴스 이덕영입니다.", "끝.", "음.", "네.",
            "Thank you.", "Thank you", "you", "You", "Bye.", "Thanks for watching!", "Thank you for watching.");

    private final Path bin;
    private final Path model;
    private final int threads;
    /** whisper는 CPU를 다 쓰므로 한 번에 하나씩만 돌립니다. */
    private final Semaphore oneAtATime = new Semaphore(1);

    public SttService(
            @Value("${app.ai.whisper.bin:/opt/whisper/bin/whisper-cli}") String bin,
            @Value("${app.ai.whisper.model:/opt/whisper/models/ggml-small.bin}") String model,
            @Value("${app.ai.whisper.threads:4}") int threads) {
        this.bin = Path.of(bin);
        this.model = Path.of(model);
        this.threads = Math.max(1, threads);
        if (isAvailable()) log.info("음성 인식(whisper) 사용: {} / {}", bin, model);
        else log.info("음성 인식(whisper) 없음 — 마이크로 묻기 비활성화 (bin={}, model={})", bin, model);
    }

    public boolean isAvailable() {
        return Files.isExecutable(bin) && Files.isReadable(model);
    }

    /** @return 받아 적은 글. 못 알아들었으면 빈 문자열. */
    public String transcribe(byte[] pcm, int sampleRate) {
        return transcribe(pcm, sampleRate, "ko");
    }

    /** lang: whisper 언어 코드(ko, en, auto). 한국어일 때만 한국어 힌트 문장을 붙여요. */
    public String transcribe(byte[] pcm, int sampleRate, String lang) {
        String language = lang == null || !lang.matches("[a-z]{2}|auto") ? "ko" : lang;
        if (!isAvailable()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "서버에 음성 인식이 설치되지 않았어요.");
        }
        if (sampleRate < 8000 || sampleRate > 48000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 샘플레이트예요: " + sampleRate);
        }
        double seconds = pcm.length / 2.0 / sampleRate;
        if (seconds < MIN_SECONDS) return "";
        if (seconds > MAX_SECONDS + 1) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "한 번에 " + MAX_SECONDS + "초까지 말할 수 있어요.");
        }
        // 통역(en)은 앱이 이미 말소리만 골라 보내고, 멀리서 들린 작은 목소리도 받아야 해서 더 너그럽게
        if (isSilent(pcm, "ko".equals(language) ? 60 : 20)) return "";
        // 작게 들어온 소리는 키워서 넘깁니다(안경 마이크로 상대 목소리를 받으면 아주 작아요).
        pcm = normalize(pcm);

        boolean acquired = false;
        Path dir = null;
        try {
            acquired = oneAtATime.tryAcquire(60, TimeUnit.SECONDS);
            if (!acquired) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "음성 인식이 바빠요. 잠시 후 다시 말해 주세요.");
            dir = Files.createTempDirectory("stt-");
            Path wav = dir.resolve("in.wav");
            Files.write(wav, wav(pcm, sampleRate));
            Path outBase = dir.resolve("out");
            List<String> cmd = new ArrayList<>(List.of(
                    bin.toString(), "-m", model.toString(), "-f", wav.toString(),
                    "-l", language, "-t", String.valueOf(threads),
                    "-nt", "-np", "-otxt", "-of", outBase.toString()));
            if ("ko".equals(language)) { cmd.add("--prompt"); cmd.add(PROMPT); }
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(dir.resolve("log.txt").toFile()).start();
            if (!p.waitFor(90, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "음성 인식이 너무 오래 걸려요. 짧게 말해 주세요.");
            }
            Path txt = dir.resolve("out.txt");
            if (p.exitValue() != 0 || !Files.exists(txt)) {
                String tail = Files.exists(dir.resolve("log.txt")) ? lastLine(Files.readString(dir.resolve("log.txt"))) : "";
                log.error("whisper 실패 exit={} {}", p.exitValue(), tail);
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "음성 인식에 실패했어요.");
            }
            return clean(Files.readString(txt, StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.error("whisper 실행 실패", e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "음성 인식에 실패했어요.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "음성 인식이 중단됐어요.");
        } finally {
            if (acquired) oneAtATime.release();
            if (dir != null) {
                try {
                    ProjectStore.deleteTree(dir);
                } catch (IOException ignored) {
                    // 임시 폴더라 남아도 괜찮습니다.
                }
            }
        }
    }

    /** whisper 출력 정리: 줄바꿈 합치기, [음악]·(박수) 같은 표시 지우기, 지어낸 문장 거르기. */
    static String clean(String raw) {
        String text = raw.replaceAll("[\\[(（][^\\])）]{0,20}[\\])）]", " ")
                .replaceAll("\\s+", " ")
                .strip();
        return HALLUCINATIONS.contains(text) ? "" : text;
    }

    /** 거의 소리가 없으면(평균 진폭이 아주 작으면) whisper에 넘기지 않습니다. */
    static boolean isSilent(byte[] pcm) { return isSilent(pcm, 60); }

    /** 평균 진폭이 min보다 작으면 조용한 것으로 봄(int16 최대 32767 기준, 60 ≈ -55dB). */
    static boolean isSilent(byte[] pcm, int min) {
        ByteBuffer b = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
        long sum = 0;
        int n = pcm.length / 2;
        for (int i = 0; i < n; i++) sum += Math.abs(b.getShort(i * 2));
        return n == 0 || sum / n < min;
    }

    /** 가장 큰 소리가 약 -4dB(20000)이 되게 키웁니다. 최대 16배, 이미 크면 그대로. */
    static byte[] normalize(byte[] pcm) {
        ByteBuffer b = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
        int n = pcm.length / 2, peak = 0;
        for (int i = 0; i < n; i++) peak = Math.max(peak, Math.abs((int) b.getShort(i * 2)));
        if (peak == 0 || peak >= 16000) return pcm;
        double gain = Math.min(16.0, 20000.0 / peak);
        ByteBuffer out = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < n; i++) {
            int v = (int) Math.round(b.getShort(i * 2) * gain);
            out.putShort((short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, v)));
        }
        return out.array();
    }

    static byte[] wav(byte[] pcm, int sampleRate) {
        ByteBuffer h = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        h.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + pcm.length)
                .put("WAVE".getBytes(StandardCharsets.US_ASCII))
                .put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16)
                .putShort((short) 1).putShort((short) 1)          // PCM, 모노
                .putInt(sampleRate).putInt(sampleRate * 2)        // 바이트/초
                .putShort((short) 2).putShort((short) 16)         // 블록 크기, 16bit
                .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(pcm.length)
                .put(pcm);
        return h.array();
    }

    private static String lastLine(String s) {
        return s.strip().lines().reduce((a, b) -> b).orElse("");
    }
}
