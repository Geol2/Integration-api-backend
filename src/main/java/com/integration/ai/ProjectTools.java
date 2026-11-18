package com.integration.ai;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Claude에게 주는 읽기 전용 도구: 목록 보기, 파일 읽기, 검색.
 * 모든 경로는 프로젝트 폴더 안으로 제한합니다(../ 나 심볼릭 링크로 밖을 읽지 못함).
 */
public class ProjectTools {

    static final int MAX_LIST_ENTRIES = 400;
    static final int MAX_READ_LINES = 400;
    static final int MAX_READ_CHARS = 60_000;
    static final int MAX_SEARCH_HITS = 80;
    static final int MAX_SEARCH_FILES = 20_000;
    private static final long MAX_SEARCH_FILE_BYTES = 2L * 1024 * 1024;

    /** 도구 실행 실패 — 메시지를 Claude에게 그대로 돌려줍니다(is_error). */
    public static class ToolException extends Exception {
        public ToolException(String message) { super(message); }
    }

    private final Path base;

    public ProjectTools(Path projectDir) throws IOException {
        this.base = projectDir.toRealPath();
    }

    Path resolve(String rel) throws ToolException {
        String r = rel == null || rel.isBlank() ? "." : rel.strip().replace('\\', '/');
        if (r.startsWith("/")) r = r.substring(1);
        Path p = base.resolve(r).normalize();
        if (!p.startsWith(base)) throw new ToolException("프로젝트 밖 경로는 읽을 수 없습니다: " + rel);
        if (!Files.exists(p)) throw new ToolException("없는 경로입니다: " + rel);
        try {
            Path real = p.toRealPath();
            if (!real.startsWith(base)) throw new ToolException("프로젝트 밖을 가리키는 링크입니다: " + rel);
            return real;
        } catch (IOException e) {
            throw new ToolException("경로를 열 수 없습니다: " + rel);
        }
    }

    private String rel(Path p) {
        String s = base.relativize(p).toString().replace('\\', '/');
        return s.isEmpty() ? "." : s;
    }

    private static boolean skipped(Path p) {
        Path name = p.getFileName();
        return name != null && ProjectStore.SKIP_DIRS.contains(name.toString());
    }

    /** 폴더 구조. depth 1~4. 폴더는 끝에 / 를 붙입니다. */
    public String listFiles(String path, int depth) throws ToolException {
        Path start = resolve(path);
        if (!Files.isDirectory(start)) throw new ToolException("폴더가 아닙니다: " + path);
        int maxDepth = Math.max(1, Math.min(4, depth));
        List<String> out = new ArrayList<>();
        try {
            Files.walkFileTree(start, java.util.EnumSet.noneOf(FileVisitOption.class), maxDepth, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(start)) {
                        if (skipped(dir)) return FileVisitResult.SKIP_SUBTREE;
                        out.add(rel(dir) + "/");
                    }
                    return out.size() >= MAX_LIST_ENTRIES ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    // 깊이 제한에 걸린 폴더도 visitFile로 들어오므로 / 를 붙여 구분합니다.
                    out.add(rel(file) + (attrs.isDirectory() ? "/" : ""));
                    return out.size() >= MAX_LIST_ENTRIES ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new ToolException("목록을 읽지 못했습니다: " + e.getMessage());
        }
        out.sort(String::compareTo);
        String head = out.size() >= MAX_LIST_ENTRIES ? "(앞 " + MAX_LIST_ENTRIES + "개만 표시 — 하위 폴더를 따로 보세요)\n" : "";
        return out.isEmpty() ? "(빈 폴더)" : head + String.join("\n", out);
    }

    /** 줄 번호를 붙여 읽습니다. 한 번에 최대 400줄. */
    public String readFile(String path, int startLine, int endLine) throws ToolException {
        Path file = resolve(path);
        if (Files.isDirectory(file)) throw new ToolException("폴더입니다. list_files를 쓰세요: " + path);
        List<String> lines = readLines(file);
        if (lines == null) throw new ToolException("텍스트 파일이 아닙니다(바이너리): " + path);
        int total = lines.size();
        int from = Math.max(1, startLine);
        int to = endLine <= 0 ? from + MAX_READ_LINES - 1 : Math.min(endLine, from + MAX_READ_LINES - 1);
        to = Math.min(to, total);
        if (total == 0) return "(빈 파일)";
        if (from > total) throw new ToolException("파일은 " + total + "줄입니다. start_line을 줄이세요.");
        StringBuilder sb = new StringBuilder();
        sb.append(rel(file)).append(" (").append(from).append("-").append(to).append(" / 총 ").append(total).append("줄)\n");
        for (int i = from; i <= to; i++) {
            String line = String.format("%5d  %s\n", i, lines.get(i - 1));
            if (sb.length() + line.length() > MAX_READ_CHARS) {
                sb.append("... (글자 수 제한으로 ").append(i - 1).append("줄까지만 표시)\n");
                break;
            }
            sb.append(line);
        }
        if (to < total) sb.append("... 이어서 읽으려면 start_line=").append(to + 1);
        return sb.toString();
    }

    /** 문자열(기본, 대소문자 무시) 또는 정규식으로 검색합니다. */
    public String search(String query, String path, boolean regex) throws ToolException {
        if (query == null || query.isBlank()) throw new ToolException("검색어가 비었습니다.");
        Pattern pattern;
        try {
            pattern = regex ? Pattern.compile(query) : Pattern.compile(Pattern.quote(query), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        } catch (PatternSyntaxException e) {
            throw new ToolException("정규식 오류: " + e.getDescription());
        }
        Path start = resolve(path);
        List<String> hits = new ArrayList<>();
        int[] scanned = {0};
        try {
            Files.walkFileTree(start, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    return !dir.equals(start) && skipped(dir) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (++scanned[0] > MAX_SEARCH_FILES || hits.size() >= MAX_SEARCH_HITS) return FileVisitResult.TERMINATE;
                    if (!attrs.isRegularFile() || attrs.size() > MAX_SEARCH_FILE_BYTES) return FileVisitResult.CONTINUE;
                    List<String> lines = readLines(file);
                    if (lines == null) return FileVisitResult.CONTINUE;
                    for (int i = 0; i < lines.size() && hits.size() < MAX_SEARCH_HITS; i++) {
                        if (pattern.matcher(lines.get(i)).find()) {
                            String text = lines.get(i).strip();
                            if (text.length() > 200) text = text.substring(0, 200) + "...";
                            hits.add(rel(file) + ":" + (i + 1) + ": " + text);
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new ToolException("검색 실패: " + e.getMessage());
        }
        if (hits.isEmpty()) return "결과 없음: " + query;
        String tail = hits.size() >= MAX_SEARCH_HITS ? "\n(결과가 많아 " + MAX_SEARCH_HITS + "개만 표시 — 범위를 좁히세요)" : "";
        return String.join("\n", hits) + tail;
    }

    /** UTF-8로 읽고, 실패하면 MS949(윈도우 한글)로 다시 읽습니다. NUL이 있으면 바이너리로 보고 null. */
    static List<String> readLines(Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            int check = Math.min(bytes.length, 8000);
            for (int i = 0; i < check; i++) if (bytes[i] == 0) return null;
            String text;
            try {
                text = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            } catch (CharacterCodingException e) {
                text = new String(bytes, java.nio.charset.Charset.forName("MS949"));
            }
            if (text.startsWith("﻿")) text = text.substring(1);
            return text.lines().toList();
        } catch (IOException | java.nio.charset.UnsupportedCharsetException e) {
            return null;
        }
    }

}
