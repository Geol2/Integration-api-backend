package com.integration.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectToolsTest {

    @TempDir Path tmp;

    private Path sampleProject() throws Exception {
        Path p = tmp.resolve("proj");
        Files.createDirectories(p.resolve("src/main/java/app"));
        Files.createDirectories(p.resolve("node_modules/lib"));
        Files.writeString(p.resolve("src/main/java/app/OrderService.java"),
                "package app;\n\npublic class OrderService {\n    public void save(Order o) {\n        repo.save(o);\n    }\n}\n");
        Files.writeString(p.resolve("README.md"), "# 주문 서비스\n수량 검증이 필요함\n");
        Files.writeString(p.resolve("node_modules/lib/index.js"), "OrderService everywhere");
        Files.write(p.resolve("logo.png"), new byte[]{(byte) 0x89, 'P', 'N', 'G', 0, 0, 1});
        // 윈도우에서 만든 한글 파일(MS949)
        Files.write(p.resolve("legacy.txt"), "레거시 주문 처리".getBytes(Charset.forName("MS949")));
        Files.writeString(tmp.resolve("secret.txt"), "TOP SECRET");
        return p;
    }

    @Test
    void listReadSearchInsideProject() throws Exception {
        ProjectTools t = new ProjectTools(sampleProject());

        String list = t.listFiles(".", 4);
        assertThat(list).contains("README.md", "src/", "src/main/java/app/")
                .doesNotContain("node_modules")
                .doesNotContain("OrderService.java");   // 5단계 아래라 depth 4에는 안 보임
        assertThat(t.listFiles("src/main/java/app", 1)).isEqualTo("src/main/java/app/OrderService.java");

        String read = t.readFile("src/main/java/app/OrderService.java", 3, 5);
        assertThat(read).contains("(3-5 / 총 7줄)", "    3  public class OrderService {", "    5          repo.save(o);")
                .contains("start_line=6");

        assertThat(t.search("orderservice", ".", false))
                .contains("src/main/java/app/OrderService.java:3:")
                .doesNotContain("node_modules");
        assertThat(t.search("void\\s+save", ".", true)).contains("OrderService.java:4:");
        assertThat(t.search("레거시", ".", false)).contains("legacy.txt:1:");
        assertThat(t.readFile("README.md", 1, 0)).contains("수량 검증");
        assertThat(t.search("없는문자열", ".", false)).startsWith("결과 없음");
    }

    @Test
    void cannotEscapeProjectOrReadBinary() throws Exception {
        Path proj = sampleProject();
        Files.createSymbolicLink(proj.resolve("link"), tmp.resolve("secret.txt"));
        ProjectTools t = new ProjectTools(proj);

        assertThatThrownBy(() -> t.readFile("../secret.txt", 1, 0)).hasMessageContaining("프로젝트 밖");
        assertThatThrownBy(() -> t.readFile("/../../secret.txt", 1, 0)).hasMessageContaining("프로젝트 밖");
        assertThatThrownBy(() -> t.readFile("link", 1, 0)).hasMessageContaining("밖을 가리키는 링크");
        assertThatThrownBy(() -> t.readFile("logo.png", 1, 0)).hasMessageContaining("바이너리");
        assertThatThrownBy(() -> t.listFiles("nope", 2)).hasMessageContaining("없는 경로");
        assertThatThrownBy(() -> t.search("(", ".", true)).hasMessageContaining("정규식");
    }

    private static byte[] zip(Map<String, String> files) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var z = new ZipOutputStream(bytes)) {
            for (var e : files.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue().getBytes());
                z.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    @Test
    void uploadUnwrapsTopFolderAndSkipsJunk() throws Exception {
        ProjectStore store = new ProjectStore(tmp.resolve("ws").toString());
        byte[] z = zip(Map.of(
                "work-app/src/A.java", "class A {}",
                "work-app/node_modules/x.js", "junk",
                "work-app/.git/HEAD", "ref",
                "__MACOSX/work-app/._A.java", "junk"));
        var p = store.upload("work-app", new MockMultipartFile("file", "w.zip", "application/zip", z));
        assertThat(p.status()).isEqualTo(ProjectStore.Status.ready);
        Path dir = store.resolveReady("work-app");
        assertThat(dir.resolve("src/A.java")).exists();
        assertThat(dir.resolve("node_modules")).doesNotExist();
        assertThat(dir.resolve(".git")).doesNotExist();

        // 다시 올리면 통째로 바뀝니다.
        store.upload("work-app", new MockMultipartFile("file", "w.zip", "application/zip", zip(Map.of("B.java", "class B {}"))));
        assertThat(dir.resolve("B.java")).exists();
        assertThat(dir.resolve("src")).doesNotExist();

        // 서버를 다시 띄워도 목록이 남아 있습니다.
        assertThat(new ProjectStore(tmp.resolve("ws").toString()).list())
                .extracting(ProjectStore.Project::name).containsExactly("work-app");
    }

    @Test
    void uploadRejectsZipSlipAndBadNames() throws Exception {
        ProjectStore store = new ProjectStore(tmp.resolve("ws").toString());
        byte[] evil = zip(Map.of("../../evil.txt", "x"));
        assertThatThrownBy(() -> store.upload("p", new MockMultipartFile("file", "e.zip", "application/zip", evil)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("잘못된 경로");
        assertThat(tmp.resolve("evil.txt")).doesNotExist();
        assertThat(store.list()).isEmpty();

        assertThatThrownBy(() -> store.upload("../x", new MockMultipartFile("file", "a.zip", "application/zip", zip(Map.of("a", "b")))))
                .hasMessageContaining("프로젝트 이름");
        assertThatThrownBy(() -> store.addGit("https://evil.com/a/b", null)).hasMessageContaining("github.com");
        assertThatThrownBy(() -> store.addGit("https://github.com/a/b; rm -rf /", null)).hasMessageContaining("github.com");
    }
}
