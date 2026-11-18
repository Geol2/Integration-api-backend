package com.integration.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Claude가 읽을 프로젝트 폴더를 서버에 보관합니다.
 *
 *  - GitHub: 저장소 주소로 얕은 클론(--depth 1). "동기화"로 최신 커밋을 다시 받습니다.
 *  - 업로드: 출장 등으로 GitHub에 없는 코드를 zip으로 올리면 풀어서 둡니다.
 *
 * 폴더 구조: {root}/{name}/ (실제 코드), {root}/{name}.meta (출처·상태)
 * Claude는 이 폴더를 읽기만 합니다(ProjectTools). 쓰기·실행 도구는 없습니다.
 */
@Service
public class ProjectStore {

    private static final Logger log = LoggerFactory.getLogger(ProjectStore.class);

    static final Pattern NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,39}$");
    static final Pattern GITHUB_URL = Pattern.compile("^https://github\\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:\\.git)?/?$");

    /** zip을 풀 때 건너뛰는 폴더 — 크기만 크고 읽을 이유가 없습니다. */
    static final Set<String> SKIP_DIRS = Set.of(
            ".git", "node_modules", "build", "dist", "target", "out", ".gradle", ".idea",
            ".vscode", "__pycache__", ".venv", "venv", ".next", "coverage");

    private static final long MAX_UNZIPPED_BYTES = 300L * 1024 * 1024;
    private static final long MAX_FILE_BYTES = 10L * 1024 * 1024;
    private static final int MAX_FILES = 30_000;

    public enum Source { git, upload }
    public enum Status { syncing, ready, error }

    public record Project(String name, Source source, String url, Status status, String message, Instant updatedAt) {}

    private final Path root;
    private final Map<String, Project> projects = new ConcurrentHashMap<>();
    private final ExecutorService gitWorker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ai-project-git");
        t.setDaemon(true);
        return t;
    });

    public ProjectStore(@Value("${app.ai.workspace-root:}") String rootDir) {
        String dir = rootDir == null || rootDir.isBlank()
                ? Path.of(System.getProperty("user.home"), ".momentum-ai", "projects").toString()
                : rootDir;
        this.root = Path.of(dir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
            loadMeta();
            log.info("AI 프로젝트 폴더: {} ({}개)", root, projects.size());
        } catch (IOException e) {
            log.warn("AI 프로젝트 폴더를 만들 수 없습니다: {} ({})", root, e.getMessage());
        }
    }

    public List<Project> list() {
        return projects.values().stream().sorted(Comparator.comparing(Project::name)).toList();
    }

    /** 읽기 도구에 넘길 프로젝트 폴더. 준비되지 않았으면 400. */
    public Path resolveReady(String name) {
        Project p = projects.get(name);
        if (p == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "프로젝트가 없어요: " + name);
        if (p.status() != Status.ready) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    p.status() == Status.syncing ? "프로젝트를 받는 중이에요. 잠시 후 다시 물어봐 주세요." : "프로젝트 준비에 실패했어요: " + p.message());
        }
        return root.resolve(name);
    }

    // ---------- GitHub ----------

    public Project addGit(String url, String requestedName) {
        var m = GITHUB_URL.matcher(url == null ? "" : url.strip());
        if (!m.matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "https://github.com/소유자/저장소 형식의 주소만 넣을 수 있어요.");
        }
        String name = requestedName == null || requestedName.isBlank() ? m.group(2) : requestedName.strip();
        checkName(name);
        if (projects.containsKey(name)) throw new ResponseStatusException(HttpStatus.CONFLICT, "같은 이름의 프로젝트가 있어요: " + name);
        String clean = "https://github.com/" + m.group(1) + "/" + m.group(2) + ".git";
        Project p = put(new Project(name, Source.git, clean, Status.syncing, "받는 중", Instant.now()));
        gitWorker.submit(() -> runGit(p));
        return p;
    }

    public Project sync(String name) {
        Project p = projects.get(name);
        if (p == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "프로젝트가 없어요: " + name);
        if (p.source() != Source.git) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "업로드한 프로젝트는 zip을 다시 올려 주세요.");
        if (p.status() == Status.syncing) return p;
        Project next = put(new Project(name, Source.git, p.url(), Status.syncing, "동기화 중", Instant.now()));
        gitWorker.submit(() -> runGit(next));
        return next;
    }

    private void runGit(Project p) {
        Path dir = root.resolve(p.name());
        try {
            if (Files.isDirectory(dir.resolve(".git"))) {
                git(dir, "fetch", "--depth", "1", "origin", "HEAD");
                git(dir, "reset", "--hard", "FETCH_HEAD");
            } else {
                deleteTree(dir);
                git(root, "clone", "--depth", "1", "--", p.url(), p.name());
            }
            String head = git(dir, "log", "-1", "--format=%h %s").strip();
            put(new Project(p.name(), Source.git, p.url(), Status.ready, head, Instant.now()));
        } catch (Exception e) {
            log.warn("git 동기화 실패 {}: {}", p.name(), e.getMessage());
            put(new Project(p.name(), Source.git, p.url(), Status.error, e.getMessage(), Instant.now()));
        }
    }

    private static String git(Path cwd, String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(cwd.toFile()).redirectErrorStream(true);
        // 비밀번호를 묻는 대화창이 뜨면 영원히 멈추므로 끕니다(공개 저장소만 지원).
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        Process proc = pb.start();
        byte[] out = proc.getInputStream().readAllBytes();
        if (!proc.waitFor(5, TimeUnit.MINUTES)) {
            proc.destroyForcibly();
            throw new IOException("git 시간 초과");
        }
        String text = new String(out, StandardCharsets.UTF_8);
        if (proc.exitValue() != 0) {
            String last = text.strip().lines().reduce((a, b) -> b).orElse("");
            throw new IOException("git " + args[0] + " 실패: " + last);
        }
        return text;
    }

    // ---------- zip 업로드 ----------

    public Project upload(String name, MultipartFile file) {
        checkName(name);
        Project old = projects.get(name);
        if (old != null && old.source() == Source.git) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "GitHub 프로젝트와 이름이 같아요. 다른 이름을 써 주세요.");
        }
        if (file == null || file.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "zip 파일을 골라 주세요.");

        Path tmp = root.resolve("." + name + ".uploading");
        Path target = root.resolve(name);
        try {
            deleteTree(tmp);
            Files.createDirectories(tmp);
            int files;
            try (InputStream in = file.getInputStream()) {
                files = unzip(in, tmp);
            }
            Path content = singleTopFolder(tmp);
            deleteTree(target);
            Files.move(content, target, StandardCopyOption.ATOMIC_MOVE);
            deleteTree(tmp);
            return put(new Project(name, Source.upload, null, Status.ready, "파일 " + files + "개", Instant.now()));
        } catch (ResponseStatusException e) {
            quietDelete(tmp);
            throw e;
        } catch (IOException e) {
            quietDelete(tmp);
            log.warn("zip 업로드 실패 {}: {}", name, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "zip을 풀지 못했어요: " + e.getMessage());
        }
    }

    /** 반환값: 풀어 놓은 파일 수. 경로 탈출(zip slip)·용량 폭탄을 막습니다. */
    static int unzip(InputStream in, Path dest) throws IOException {
        Path base = dest.toAbsolutePath().normalize();
        long total = 0;
        int files = 0;
        byte[] buf = new byte[64 * 1024];
        try (ZipInputStream zip = new ZipInputStream(in, StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                String entry = e.getName().replace('\\', '/');
                if (entry.isBlank() || isSkipped(entry)) continue;
                Path out = base.resolve(entry).normalize();
                if (!out.startsWith(base)) throw new IOException("잘못된 경로가 들어 있어요: " + entry);
                if (e.isDirectory()) {
                    Files.createDirectories(out);
                    continue;
                }
                if (++files > MAX_FILES) {
                    throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "파일이 너무 많아요(최대 " + MAX_FILES + "개). node_modules 같은 폴더를 빼고 압축해 주세요.");
                }
                Files.createDirectories(out.getParent());
                long size = 0;
                boolean tooBig = false;
                try (var os = Files.newOutputStream(out)) {
                    int n;
                    while ((n = zip.read(buf)) > 0) {
                        size += n;
                        total += n;
                        if (total > MAX_UNZIPPED_BYTES) {
                            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "압축을 푼 크기가 300MB를 넘어요.");
                        }
                        if (size > MAX_FILE_BYTES) { tooBig = true; break; }
                        os.write(buf, 0, n);
                    }
                }
                // 10MB 넘는 파일(데이터·바이너리)은 읽을 일이 없어 버립니다.
                if (tooBig) { Files.deleteIfExists(out); files--; }
            }
        }
        if (files == 0) throw new IOException("zip 안에 파일이 없어요");
        return files;
    }

    static boolean isSkipped(String entry) {
        for (String part : entry.split("/")) {
            if (SKIP_DIRS.contains(part) || part.equals("__MACOSX")) return true;
        }
        return false;
    }

    /** "project/..." 처럼 최상위 폴더 하나로 압축했으면 그 안을 프로젝트 루트로 씁니다. */
    private static Path singleTopFolder(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            List<Path> children = s.toList();
            if (children.size() == 1 && Files.isDirectory(children.get(0))) {
                Path inner = children.get(0);
                Path moved = dir.resolveSibling(dir.getFileName() + ".inner");
                deleteTree(moved);
                Files.move(inner, moved);
                deleteTree(dir);
                Files.move(moved, dir);
            }
        }
        return dir;
    }

    public void delete(String name) {
        Project p = projects.get(name);
        if (p == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "프로젝트가 없어요: " + name);
        if (p.status() == Status.syncing) throw new ResponseStatusException(HttpStatus.CONFLICT, "받는 중에는 지울 수 없어요.");
        projects.remove(name);
        quietDelete(root.resolve(name));
        quietDelete(root.resolve(name + ".meta"));
    }

    // ---------- 상태 저장 ----------

    private Project put(Project p) {
        projects.put(p.name(), p);
        Properties props = new Properties();
        props.setProperty("source", p.source().name());
        if (p.url() != null) props.setProperty("url", p.url());
        // 서버가 받는 도중에 재시작되면 다음 기동 때 오류로 보여 줍니다.
        props.setProperty("status", p.status() == Status.syncing ? Status.error.name() : p.status().name());
        props.setProperty("message", p.status() == Status.syncing ? "서버 재시작으로 중단됨 — 동기화를 다시 눌러 주세요" : p.message());
        props.setProperty("updatedAt", p.updatedAt().toString());
        try (var w = Files.newBufferedWriter(root.resolve(p.name() + ".meta"), StandardCharsets.UTF_8)) {
            props.store(w, null);
        } catch (IOException e) {
            log.warn("프로젝트 상태 저장 실패 {}: {}", p.name(), e.getMessage());
        }
        return p;
    }

    private void loadMeta() throws IOException {
        try (Stream<Path> s = Files.list(root)) {
            for (Path meta : s.filter(f -> f.getFileName().toString().endsWith(".meta")).toList()) {
                String name = meta.getFileName().toString().replaceFirst("\\.meta$", "");
                if (!NAME.matcher(name).matches()) continue;
                Properties props = new Properties();
                try (var r = Files.newBufferedReader(meta, StandardCharsets.UTF_8)) {
                    props.load(r);
                }
                try {
                    projects.put(name, new Project(name,
                            Source.valueOf(props.getProperty("source", "upload")),
                            props.getProperty("url"),
                            Status.valueOf(props.getProperty("status", "error")),
                            props.getProperty("message", ""),
                            Instant.parse(props.getProperty("updatedAt", Instant.EPOCH.toString()))));
                } catch (IllegalArgumentException | java.time.DateTimeException e) {
                    log.warn("프로젝트 상태 파일을 읽지 못했습니다: {}", meta);
                }
            }
        }
    }

    static void checkName(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "프로젝트 이름은 영문·숫자·.-_ 로 40자까지예요.");
        }
    }

    static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }

    private static void quietDelete(Path p) {
        try {
            deleteTree(p);
            Files.deleteIfExists(p);
        } catch (IOException e) {
            log.warn("삭제 실패 {}: {}", p, e.getMessage());
        }
    }
}
