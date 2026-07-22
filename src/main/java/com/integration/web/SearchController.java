package com.integration.web;

import com.integration.domain.Diary;
import com.integration.domain.Note;
import com.integration.domain.Todo;
import com.integration.repository.DiaryRepository;
import com.integration.repository.NoteRepository;
import com.integration.repository.TodoRepository;
import com.integration.security.CurrentUserService;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Unified search across a single account's diaries, todos and notes.
 *
 * "이 계정이 몇년 몇월 며칠에 이런 기록을 남겼나" — one date-sorted list of hits filtered
 * by keyword and/or a date range. Everything is scoped to the current user, so no one can
 * search another account's records.
 *
 * The three record types store their date differently, so we normalise each to a
 * {@link LocalDate} in memory before filtering/sorting (record counts per user are small):
 *   • diary : {@code dateKey} like "2026-7-10" or "2026-07-10" (loose parse)
 *   • todo  : {@code dateKey} like "2026-07-15" (nullable)
 *   • note  : {@code ts} epoch millis → local date
 */
@RestController
@RequestMapping("/api/search")
public class SearchController {

    /** Safety cap so an empty query can't stream an unbounded timeline. */
    private static final int MAX_RESULTS = 300;

    private final DiaryRepository diaries;
    private final TodoRepository todos;
    private final NoteRepository notes;
    private final CurrentUserService currentUser;

    public SearchController(DiaryRepository diaries, TodoRepository todos,
                            NoteRepository notes, CurrentUserService currentUser) {
        this.diaries = diaries;
        this.todos = todos;
        this.notes = notes;
        this.currentUser = currentUser;
    }

    /**
     * One unified hit.
     *
     * @param type    "diary" | "todo" | "note"
     * @param date    normalised ISO date "yyyy-MM-dd" (null if the record has no resolvable date)
     * @param title   short label for the row
     * @param snippet an excerpt of the matched content
     * @param ref     how the frontend opens it — diary: dateKey · todo/note: id
     */
    public record SearchHit(String type, String date, String title, String snippet, String ref) {}

    @GetMapping
    public List<SearchHit> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String types
    ) {
        Long userId = currentUser.requireId();
        String needle = q == null ? "" : q.trim().toLowerCase();
        LocalDate fromD = parseIso(from);
        LocalDate toD = parseIso(to);
        boolean dateFilter = fromD != null || toD != null;
        Set<String> want = (types == null || types.isBlank())
                ? Set.of("diary", "todo", "note")
                : Arrays.stream(types.split(","))
                    .map(s -> s.trim().toLowerCase())
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toSet());

        // Nothing to search on → empty, rather than dumping every record.
        if (needle.isEmpty() && !dateFilter) {
            return List.of();
        }

        List<Scored> hits = new ArrayList<>();

        if (want.contains("diary")) {
            for (Diary d : diaries.findByUserId(userId)) {
                LocalDate date = parseLoose(d.getDateKey());
                if (!inRange(date, fromD, toD, dateFilter)) continue;
                String hay = d.getTitle() + " " + d.getBody() + " " + d.getMood();
                if (!matches(hay, needle)) continue;
                String title = d.getTitle() == null || d.getTitle().isBlank() ? "(제목 없음)" : d.getTitle();
                String body = d.getBody() == null || d.getBody().isBlank() ? d.getMood() : d.getBody();
                hits.add(new Scored(date, new SearchHit("diary", iso(date), title, snippet(body, needle), d.getDateKey())));
            }
        }
        if (want.contains("todo")) {
            for (Todo t : todos.findByUserIdOrderBySortOrderAsc(userId)) {
                LocalDate date = parseLoose(t.getDateKey());
                if (!inRange(date, fromD, toD, dateFilter)) continue;
                if (!matches(t.getText(), needle)) continue;
                String title = (t.isDone() ? "✓ " : "○ ") + t.getText();
                hits.add(new Scored(date, new SearchHit("todo", iso(date), title, snippet(t.getText(), needle), String.valueOf(t.getId()))));
            }
        }
        if (want.contains("note")) {
            for (Note n : notes.findByUserId(userId)) {
                LocalDate date = n.getTs() > 0
                        ? Instant.ofEpochMilli(n.getTs()).atZone(ZoneId.systemDefault()).toLocalDate()
                        : null;
                if (!inRange(date, fromD, toD, dateFilter)) continue;
                if (!matches(n.getText(), needle)) continue;
                hits.add(new Scored(date, new SearchHit("note", iso(date), "메모", snippet(n.getText(), needle), String.valueOf(n.getId()))));
            }
        }

        // Newest first; records with no resolvable date sink to the bottom.
        hits.sort(Comparator.comparing((Scored h) -> h.date == null ? LocalDate.MIN : h.date).reversed());
        return hits.stream().limit(MAX_RESULTS).map(h -> h.hit).toList();
    }

    /** Internal carrier so we can sort by the parsed date without exposing it on the wire. */
    private record Scored(LocalDate date, SearchHit hit) {}

    private static boolean matches(String hay, String needle) {
        if (needle.isEmpty()) return true;
        return hay != null && hay.toLowerCase().contains(needle);
    }

    /** When a date range is active, records with no resolvable date are excluded. */
    private static boolean inRange(LocalDate date, LocalDate from, LocalDate to, boolean active) {
        if (!active) return true;
        if (date == null) return false;
        if (from != null && date.isBefore(from)) return false;
        if (to != null && date.isAfter(to)) return false;
        return true;
    }

    private static LocalDate parseIso(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s.trim());
        } catch (Exception e) {
            return parseLoose(s);
        }
    }

    /** Lenient parse handling both "2026-7-10" and "2026-07-15". */
    private static LocalDate parseLoose(String key) {
        if (key == null || key.isBlank()) return null;
        String[] p = key.split("-");
        if (p.length != 3) return null;
        try {
            return LocalDate.of(Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim()));
        } catch (Exception e) {
            return null;
        }
    }

    private static String iso(LocalDate d) {
        return d == null ? null : d.toString();
    }

    /** A compact excerpt centred on the match (or the head of the text when no keyword). */
    private static String snippet(String text, String needle) {
        if (text == null) return "";
        String flat = text.replaceAll("\\s+", " ").trim();
        if (needle.isEmpty() || !flat.toLowerCase().contains(needle)) {
            return flat.length() > 80 ? flat.substring(0, 80) + "…" : flat;
        }
        int i = flat.toLowerCase().indexOf(needle);
        int start = Math.max(0, i - 24);
        int end = Math.min(flat.length(), i + needle.length() + 44);
        return (start > 0 ? "…" : "") + flat.substring(start, end) + (end < flat.length() ? "…" : "");
    }
}
