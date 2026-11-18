package com.integration.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SttServiceTest {

    @TempDir Path tmp;

    /** 1초짜리 440Hz 사인파 PCM (말소리 대신). */
    private static byte[] tone(double seconds, int amplitude) {
        int n = (int) (16000 * seconds);
        ByteBuffer b = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < n; i++) b.putShort((short) (amplitude * Math.sin(2 * Math.PI * 440 * i / 16000.0)));
        return b.array();
    }

    /** whisper-cli 대역: 받은 인자를 기록하고, -of 경로에 .txt를 씁니다. */
    private Path fakeWhisper(String output) throws Exception {
        Path bin = tmp.resolve("whisper-cli");
        Files.writeString(bin, """
                #!/usr/bin/env bash
                echo "$@" > "%s"
                while [ $# -gt 0 ]; do
                  case "$1" in
                    -of) OUT="$2"; shift ;;
                    -f) IN="$2"; shift ;;
                  esac
                  shift
                done
                head -c 4 "$IN" > "%s"
                printf '%%s\\n' "%s" > "$OUT.txt"
                """.formatted(tmp.resolve("args.txt"), tmp.resolve("magic.txt"), output));
        Files.setPosixFilePermissions(bin, PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.writeString(tmp.resolve("model.bin"), "model");
        return bin;
    }

    @Test
    void runsWhisperWithKoreanAndReturnsCleanText() throws Exception {
        SttService stt = new SttService(fakeWhisper("[음악] OrderService에서 save 어디야?").toString(), tmp.resolve("model.bin").toString(), 2);
        assertThat(stt.isAvailable()).isTrue();
        assertThat(stt.transcribe(tone(1.5, 8000), 16000)).isEqualTo("OrderService에서 save 어디야?");
        String args = Files.readString(tmp.resolve("args.txt"));
        assertThat(args).contains("-l ko", "-t 2", "-nt", "-otxt", "--prompt");
        assertThat(Files.readString(tmp.resolve("magic.txt"))).isEqualTo("RIFF");
    }

    @Test
    void silenceAndHallucinationsBecomeEmpty() throws Exception {
        SttService stt = new SttService(fakeWhisper("시청해 주셔서 감사합니다.").toString(), tmp.resolve("model.bin").toString(), 2);
        assertThat(stt.transcribe(tone(1.5, 8000), 16000)).isEmpty();   // 지어낸 문장
        assertThat(stt.transcribe(tone(1.5, 10), 16000)).isEmpty();     // 거의 무음 → whisper 안 돌림
        assertThat(stt.transcribe(tone(0.2, 8000), 16000)).isEmpty();   // 너무 짧음
        assertThatThrownBy(() -> stt.transcribe(tone(62, 8000), 16000)).hasMessageContaining("60초");
    }

    @Test
    void unavailableWithoutBinaryOrModel() {
        SttService stt = new SttService(tmp.resolve("none").toString(), tmp.resolve("none.bin").toString(), 2);
        assertThat(stt.isAvailable()).isFalse();
        assertThatThrownBy(() -> stt.transcribe(tone(1, 8000), 16000)).hasMessageContaining("설치되지 않았어요");
    }

    @Test
    void wavHeaderIsValid() {
        byte[] w = SttService.wav(new byte[3200], 16000);
        ByteBuffer b = ByteBuffer.wrap(w).order(ByteOrder.LITTLE_ENDIAN);
        assertThat(new String(w, 0, 4)).isEqualTo("RIFF");
        assertThat(new String(w, 8, 4)).isEqualTo("WAVE");
        assertThat(b.getInt(24)).isEqualTo(16000);
        assertThat(b.getShort(34)).isEqualTo((short) 16);
        assertThat(b.getInt(40)).isEqualTo(3200);
        assertThat(w).hasSize(44 + 3200);
    }
}
