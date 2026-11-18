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

    /** 개발 용어를 미리 알려 주면 "자바 스프링" 대신 "Java Spring"처럼 받아 적습니다. */
    private static final String PROMPT =
            "개발 질문입니다. Java, Spring Boot, JPA, Python, SQL, SELECT, JOIN, React, Vite, 컨트롤러, 서비스, 리포지토리, 엔티티, 커밋.";

    /** 조용한 녹음에 whisper가 지어내는 대표 문장들 — 이것만 나오면 못 알아들은 것으로 봅니다. */
    private static final Set<String> HALLUCINATIONS = Set.of(
            "감사합니다", "감사합니다.", "시청해주셔서 감사합니다.", "시청해 주셔서 감사합니다.",
            "구독과 좋아요 부탁드립니다.", "MBC 뉴스 이덕영입니다.", "끝.", "음.", "네.");

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
        if (isSilent(pcm)) return "";

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
                    "-l", "ko", "-t", String.valueOf(threads),
                    "--prompt", PROMPT,
                    "-nt", "-np", "-otxt", "-of", outBase.toString()));
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
    static boolean isSilent(byte[] pcm) {
        ByteBuffer b = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
        long sum = 0;
        int n = pcm.length / 2;
        for (int i = 0; i < n; i++) sum += Math.abs(b.getShort(i * 2));
        return n == 0 || sum / n < 60;   // int16 최대 32767 기준, 60 ≈ -55dB
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
