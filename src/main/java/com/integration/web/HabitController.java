package com.integration.web;

import com.integration.domain.Habit;
import com.integration.domain.HabitLog;
import com.integration.repository.HabitLogRepository;
import com.integration.repository.HabitRepository;
import com.integration.security.CurrentUserService;
import jakarta.transaction.Transactional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/habits")
public class HabitController {

    private final HabitRepository repo;
    private final HabitLogRepository logRepo;
    private final CurrentUserService currentUser;

    public HabitController(HabitRepository repo, HabitLogRepository logRepo, CurrentUserService currentUser) {
        this.repo = repo;
        this.logRepo = logRepo;
        this.currentUser = currentUser;
    }

    /**
     * 연속일을 프론트가 아니라 여기서 계산해 함께 내려줍니다. 프론트가 계산하려면 전 기간
     * 로그가 필요해지는데, 그러면 화면에 보이는 달만 받아오는 의미가 사라집니다.
     */
    public record HabitDto(Long id, String name, String emoji, String color, int sortOrder,
                           int currentStreak, int bestStreak) {
        static HabitDto of(Habit h, Streaks s) {
            return new HabitDto(h.getId(), h.getName(), h.getEmoji(), h.getColor(), h.getSortOrder(),
                    s.current(), s.best());
        }
    }

    public record HabitLogDto(Long habitId, String dateKey) {}

    public record CreateHabitRequest(String name, String emoji, String color) {}
    public record UpdateHabitRequest(String name, String emoji, String color, Integer sortOrder, Boolean archived) {}

    /** 체크/해제 응답. 갱신된 연속일이 들어 있어 프론트가 목록을 다시 부르지 않아도 됩니다. */
    public record CheckResponse(Long habitId, String dateKey, boolean done, int currentStreak, int bestStreak) {}

    // ---- 습관 정의 ----

    @GetMapping
    public List<HabitDto> list(@RequestParam(name = "today", required = false) String today) {
        Long userId = currentUser.requireId();
        LocalDate ref = todayOrServerDate(today);

        // 계정의 로그를 한 번에 읽어 습관별로 나눕니다. 최고 연속일이 전 기간을 봐야 해서
        // 기간을 자를 수 없습니다. 습관 몇 개 × 날짜 수준이라 아직 가볍지만, 몇 년 치가
        // 쌓여 무거워지면 습관별 집계 쿼리로 옮기세요.
        Map<Long, List<String>> byHabit = logRepo.findByUserId(userId).stream()
                .collect(Collectors.groupingBy(HabitLog::getHabitId,
                        Collectors.mapping(HabitLog::getDateKey, Collectors.toList())));

        return repo.findByUserIdAndArchivedFalseOrderBySortOrderAscIdAsc(userId).stream()
                .map(h -> HabitDto.of(h, streaksOf(byHabit.getOrDefault(h.getId(), List.of()), ref)))
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public HabitDto create(@RequestBody CreateHabitRequest req) {
        String name = req.name() == null ? "" : req.name().trim();
        if (name.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "이름이 비어 있습니다.");

        Habit h = new Habit();
        h.setUserId(currentUser.requireId());
        h.setName(name);
        h.setEmoji(req.emoji() == null ? "" : req.emoji());
        h.setColor(req.color() == null ? "" : req.color());
        // 새 습관이니 기록이 없고, 따라서 연속일도 0입니다.
        return HabitDto.of(repo.save(h), new Streaks(0, 0));
    }

    @PatchMapping("/{id}")
    public HabitDto update(@PathVariable Long id, @RequestBody UpdateHabitRequest req,
                           @RequestParam(name = "today", required = false) String today) {
        Long userId = currentUser.requireId();
        Habit h = repo.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        if (req.name() != null && !req.name().isBlank()) h.setName(req.name().trim());
        if (req.emoji() != null) h.setEmoji(req.emoji());
        if (req.color() != null) h.setColor(req.color());
        if (req.sortOrder() != null) h.setSortOrder(req.sortOrder());
        if (req.archived() != null) h.setArchived(req.archived());
        repo.save(h);

        List<String> days = logRepo.findByUserId(userId).stream()
                .filter(l -> l.getHabitId().equals(id)).map(HabitLog::getDateKey).toList();
        return HabitDto.of(h, streaksOf(days, todayOrServerDate(today)));
    }

    /** 습관을 지우면 그 습관의 기록도 함께 사라집니다(기록만 남기려면 archived를 쓰세요). */
    @DeleteMapping("/{id}")
    @Transactional
    public void delete(@PathVariable Long id) {
        Habit h = repo.findByIdAndUserId(id, currentUser.requireId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        logRepo.deleteByHabitId(h.getId());
        repo.delete(h);
    }

    // ---- 날짜별 체크 ----

    /** 화면에 그릴 기간(보통 한 달)의 체크 기록. from·to 모두 포함입니다. */
    @GetMapping("/logs")
    public List<HabitLogDto> logs(@RequestParam String from, @RequestParam String to) {
        return logRepo.findByUserIdAndDateKeyBetween(currentUser.requireId(), from, to)
                .stream().map(l -> new HabitLogDto(l.getHabitId(), l.getDateKey())).toList();
    }

    /**
     * 체크. 이미 체크돼 있으면 아무것도 하지 않고 같은 응답을 돌려줍니다 — 재시도해도
     * 상태가 뒤집히지 않도록 토글 POST 대신 PUT/DELETE로 나눠 뒀습니다.
     */
    @PutMapping("/{id}/logs/{dateKey}")
    public CheckResponse check(@PathVariable Long id, @PathVariable String dateKey,
                               @RequestParam(name = "today", required = false) String today) {
        Long userId = requireOwnedHabit(id);
        if (parseDate(dateKey) == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "날짜 형식이 올바르지 않습니다.");

        if (logRepo.findByHabitIdAndDateKey(id, dateKey).isEmpty()) {
            HabitLog log = new HabitLog();
            log.setUserId(userId);
            log.setHabitId(id);
            log.setDateKey(dateKey);
            try {
                logRepo.save(log);
            } catch (DataIntegrityViolationException dup) {
                // 같은 체크가 동시에 두 번 들어온 경우. (habit_id, date_key) 유니크 제약이
                // 두 번째를 막아주는데, 그건 이미 원하던 상태이므로 오류로 볼 이유가 없습니다.
            }
        }
        return respond(id, dateKey, true, userId, today);
    }

    @DeleteMapping("/{id}/logs/{dateKey}")
    @Transactional
    public CheckResponse uncheck(@PathVariable Long id, @PathVariable String dateKey,
                                 @RequestParam(name = "today", required = false) String today) {
        Long userId = requireOwnedHabit(id);
        logRepo.deleteByHabitIdAndDateKey(id, dateKey);
        return respond(id, dateKey, false, userId, today);
    }

    // ---- 내부 ----

    /** 남의 습관에 기록을 남기지 못하게 소유를 확인하고, 확인된 userId를 돌려줍니다. */
    private Long requireOwnedHabit(Long habitId) {
        Long userId = currentUser.requireId();
        repo.findByIdAndUserId(habitId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return userId;
    }

    private CheckResponse respond(Long habitId, String dateKey, boolean done, Long userId, String today) {
        List<String> days = logRepo.findByUserId(userId).stream()
                .filter(l -> l.getHabitId().equals(habitId)).map(HabitLog::getDateKey).toList();
        Streaks s = streaksOf(days, todayOrServerDate(today));
        return new CheckResponse(habitId, dateKey, done, s.current(), s.best());
    }

    record Streaks(int current, int best) {}

    /** 날짜 문자열 묶음에서 현재 연속일과 최고 연속일을 계산합니다. */
    private static Streaks streaksOf(Collection<String> dateKeys, LocalDate today) {
        TreeSet<LocalDate> days = new TreeSet<>();
        for (String k : dateKeys) {
            LocalDate d = parseDate(k);
            if (d != null) days.add(d);
        }
        if (days.isEmpty()) return new Streaks(0, 0);

        // 오늘 아직 체크하지 않았어도 어제까지 이어진 기록은 살아 있는 것으로 봅니다.
        // 그러지 않으면 자정이 지나는 순간 연속일이 0으로 떨어져 보입니다.
        LocalDate cursor = days.contains(today) ? today : today.minusDays(1);
        int current = 0;
        while (days.contains(cursor)) {
            current++;
            cursor = cursor.minusDays(1);
        }

        int best = 0, run = 0;
        LocalDate prev = null;
        for (LocalDate d : days) {
            run = (prev != null && prev.plusDays(1).equals(d)) ? run + 1 : 1;
            if (run > best) best = run;
            prev = d;
        }
        return new Streaks(current, best);
    }

    /**
     * dateKey는 브라우저의 로컬 날짜입니다. 서버(UTC일 수 있음)의 오늘과 어긋나면 연속일이
     * 하루씩 틀리므로, 프론트가 보내준 today를 기준으로 삼고 없을 때만 서버 날짜를 씁니다.
     */
    private static LocalDate todayOrServerDate(String today) {
        LocalDate d = today == null ? null : parseDate(today);
        return d != null ? d : LocalDate.now();
    }

    private static LocalDate parseDate(String key) {
        try {
            return LocalDate.parse(key);
        } catch (Exception e) {
            return null;
        }
    }
}
