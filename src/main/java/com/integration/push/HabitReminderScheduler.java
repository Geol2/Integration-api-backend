package com.integration.push;

import com.integration.domain.Habit;
import com.integration.domain.HabitLog;
import com.integration.domain.Settings;
import com.integration.repository.HabitLogRepository;
import com.integration.repository.HabitRepository;
import com.integration.repository.PushSubscriptionRepository;
import com.integration.repository.SettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 습관 트래커의 하루 두 번 알림.
 *
 * <ul>
 *   <li>아침(기본 07:00) — 오늘 체크할 습관이 뭔지 미리 알려줍니다.</li>
 *   <li>밤(기본 22:00) — 아직 체크하지 않은 습관이 남아 있으면 알려줍니다.</li>
 * </ul>
 *
 * <p>{@link ReminderScheduler}(할 일 시간 알림)와 구조가 비슷하지만 "언제 보냈는지"를
 * 기록하는 방식이 다릅니다. 할 일은 자기 행에 remindedAt을 남길 수 있는 반면 습관 알림은
 * 특정 습관이 아니라 "그 사람의 하루"에 한 번 나가므로, 마지막으로 보낸 날짜를
 * {@link Settings}에 남겨 하루 한 번을 보장합니다.
 *
 * <p>보낼 게 없으면(습관이 없거나 이미 다 체크했으면) 푸시는 건너뛰지만 발송 표시는 남깁니다 —
 * 그래야 남은 시간 동안 매분 같은 조회를 반복하지 않습니다.
 */
@Component
public class HabitReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(HabitReminderScheduler.class);

    /** 알림 본문에 이름을 그대로 적을 습관 수. 넘으면 "외 N개"로 줄입니다. */
    private static final int MAX_NAMES = 3;

    private final HabitRepository habits;
    private final HabitLogRepository habitLogs;
    private final SettingsRepository settings;
    private final PushSubscriptionRepository subs;
    private final WebPushService push;
    private final ZoneId zone;
    private final String defaultMorning;
    private final String defaultEvening;
    private final int graceMinutes;
    private final String appUrl;

    public HabitReminderScheduler(HabitRepository habits,
                                  HabitLogRepository habitLogs,
                                  SettingsRepository settings,
                                  PushSubscriptionRepository subs,
                                  WebPushService push,
                                  @Value("${app.push.zone:Asia/Seoul}") String zone,
                                  @Value("${app.push.habit.morning-time:07:00}") String defaultMorning,
                                  @Value("${app.push.habit.evening-time:22:00}") String defaultEvening,
                                  @Value("${app.push.habit.grace-minutes:60}") int graceMinutes,
                                  @Value("${app.push.app-url:https://momentum.geol2.com}") String appUrl) {
        this.habits = habits;
        this.habitLogs = habitLogs;
        this.settings = settings;
        this.subs = subs;
        this.push = push;
        this.zone = ZoneId.of(zone);
        this.defaultMorning = defaultMorning;
        this.defaultEvening = defaultEvening;
        this.graceMinutes = graceMinutes;
        this.appUrl = appUrl;
    }

    /** 사용자가 분 단위로 시각을 고를 수 있으므로 매분 확인합니다. */
    @Scheduled(cron = "0 * * * * *")
    @Transactional
    public void sendHabitReminders() {
        if (!push.isEnabled()) return;

        List<Long> userIds = subs.findDistinctUserIds();
        if (userIds.isEmpty()) return;

        ZonedDateTime now = ZonedDateTime.now(zone);
        String today = now.toLocalDate().toString();
        LocalTime clock = now.toLocalTime();

        // 설정 행이 없는 계정은 건너뜁니다. 앱은 로그인 직후 GET /api/settings로 행을 만들기
        // 때문에, 구독은 있는데 설정이 없는 상태는 사실상 나오지 않습니다.
        for (Settings s : settings.findAllById(userIds)) {
            if (Boolean.FALSE.equals(s.getHabitRemind())) continue;

            boolean dirty = false;
            if (isDue(clock, s.getHabitMorningTime(), defaultMorning) && !today.equals(s.getHabitMorningSentOn())) {
                remind(s.getUserId(), today, true);
                s.setHabitMorningSentOn(today);
                dirty = true;
            }
            if (isDue(clock, s.getHabitEveningTime(), defaultEvening) && !today.equals(s.getHabitEveningSentOn())) {
                remind(s.getUserId(), today, false);
                s.setHabitEveningSentOn(today);
                dirty = true;
            }
            if (dirty) settings.save(s);
        }
    }

    /**
     * 지금이 그 알림을 보낼 시간대인가.
     *
     * <p>정각 한 틱만 노리면 서버가 재시작 중이었을 때 그날 알림이 통째로 사라집니다. 그래서
     * 예정 시각부터 {@code graceMinutes} 동안은 계속 "보낼 때"로 봅니다(하루 한 번 제한은
     * 발송 표시가 담당). 분 단위 뺄셈이라 자정을 넘기면 음수가 되는데, 그때는 날짜도 이미
     * 바뀌어 있으므로 지난 날 알림을 새벽에 뒤늦게 보내지 않는 게 오히려 맞습니다.
     */
    private boolean isDue(LocalTime now, String configured, String fallback) {
        LocalTime at = parseTime(configured);
        if (at == null) at = parseTime(fallback);
        if (at == null) return false;

        int delta = (now.toSecondOfDay() - at.toSecondOfDay()) / 60;
        return delta >= 0 && delta < graceMinutes;
    }

    /**
     * 아직 체크하지 않은 습관을 모아 한 건 보냅니다. 보낼 게 없으면 조용히 넘어갑니다.
     *
     * <p>{@code today}는 이 서버의 zone 기준 날짜인 반면 habit_logs.date_key는 브라우저의
     * 로컬 날짜입니다. 둘 다 한국 시간이라 지금은 같지만, 해외에서 쓰는 계정이 생기면
     * 그 사람 기준 자정 근처에서 하루가 어긋날 수 있습니다.
     */
    private void remind(Long userId, String today, boolean morning) {
        List<Habit> all = habits.findByUserIdAndArchivedFalseOrderBySortOrderAscIdAsc(userId);
        if (all.isEmpty()) return;

        Set<Long> done = habitLogs.findByUserIdAndDateKey(userId, today).stream()
                .map(HabitLog::getHabitId).collect(Collectors.toCollection(HashSet::new));

        List<Habit> pending = all.stream().filter(h -> !done.contains(h.getId())).toList();
        if (pending.isEmpty()) return;   // 이미 다 했으면 알릴 이유가 없습니다

        String title = morning
                ? "🌅 오늘의 습관 " + pending.size() + "개"
                : "🌙 아직 " + pending.size() + "개 남았어요";
        String body = morning
                ? names(pending)
                : names(pending) + " · 지금 체크하면 기록이 이어져요";

        // 태그에 날짜를 넣어, 아침 알림이 밤 알림을 덮어쓰지 않게 합니다.
        int sent = push.sendToUser(userId, new WebPushService.Payload(
                title, body, appUrl, "habit-" + (morning ? "morning-" : "evening-") + today));
        log.info("Habit reminder ({}) for user {} — {} pending → {} endpoint(s)",
                morning ? "morning" : "evening", userId, pending.size(), sent);
    }

    /** "💪 운동 · 📚 독서 외 2개" — 알림 한 줄에 들어갈 만큼만. */
    private static String names(List<Habit> pending) {
        String head = pending.stream().limit(MAX_NAMES)
                .map(h -> (h.getEmoji() == null || h.getEmoji().isBlank() ? "🌱" : h.getEmoji()) + " " + h.getName())
                .collect(Collectors.joining(" · "));
        int rest = pending.size() - MAX_NAMES;
        return rest > 0 ? head + " 외 " + rest + "개" : head;
    }

    /** "HH:mm" 또는 "HH:mm:ss". 알아볼 수 없으면 null. */
    private static LocalTime parseTime(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            String t = raw.trim();
            return LocalTime.parse(t.length() > 5 ? t.substring(0, 5) : t);
        } catch (Exception e) {
            return null;
        }
    }
}
