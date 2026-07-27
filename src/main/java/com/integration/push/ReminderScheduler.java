package com.integration.push;

import com.integration.domain.Settings;
import com.integration.domain.Todo;
import com.integration.repository.SettingsRepository;
import com.integration.repository.TodoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fires the "곧 약속이에요" push. Runs once a minute and looks for todos whose reminder
 * moment has arrived.
 *
 * <p>Timing model — a todo's dateKey ("2026-07-27") and timeLabel ("14:30") are local
 * wall-clock in {@code app.push.zone}, not UTC, because that is what the user typed into
 * the picker. The reminder fires at {@code due - leadMinutes} and stays eligible for
 * {@code graceMinutes} after that, so a backend restart or a few slow ticks still deliver
 * a late alert rather than silently swallowing it. Past that window the todo is skipped
 * for good: a 09:00 appointment must not buzz at 18:00.
 */
@Component
public class ReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReminderScheduler.class);

    private final TodoRepository todos;
    private final SettingsRepository settings;
    private final WebPushService push;
    private final ZoneId zone;
    private final int defaultLeadMinutes;
    private final int graceMinutes;
    private final String appUrl;

    public ReminderScheduler(TodoRepository todos,
                             SettingsRepository settings,
                             WebPushService push,
                             @Value("${app.push.zone:Asia/Seoul}") String zone,
                             @Value("${app.push.default-lead-minutes:10}") int defaultLeadMinutes,
                             @Value("${app.push.grace-minutes:30}") int graceMinutes,
                             @Value("${app.push.app-url:https://momentum.geol2.com}") String appUrl) {
        this.todos = todos;
        this.settings = settings;
        this.push = push;
        this.zone = ZoneId.of(zone);
        this.defaultLeadMinutes = defaultLeadMinutes;
        this.graceMinutes = graceMinutes;
        this.appUrl = appUrl;
    }

    /** Top of every minute — the finest granularity the time picker offers. */
    @Scheduled(cron = "0 * * * * *")
    @Transactional
    public void sendDueReminders() {
        if (!push.isEnabled()) return;

        Instant now = Instant.now();
        LocalDate today = now.atZone(zone).toLocalDate();
        // A large lead time can pull a 00:30 reminder into the previous day, and the grace
        // window can carry one into the next — scan three days and let the maths decide.
        List<String> dateKeys = List.of(
                today.minusDays(1).toString(), today.toString(), today.plusDays(1).toString());

        List<Todo> candidates = todos
                .findByDoneFalseAndRemindedAtIsNullAndTimeLabelIsNotNullAndDateKeyIn(dateKeys);
        if (candidates.isEmpty()) return;

        // One settings lookup per user per tick, not per todo.
        Map<Long, Integer> leadCache = new HashMap<>();

        for (Todo todo : candidates) {
            Instant due = dueAt(todo);
            if (due == null) continue;   // unparseable time — treat as "no reminder"

            int lead = leadCache.computeIfAbsent(todo.getUserId(), this::leadMinutesFor);
            Instant notifyAt = due.minus(Duration.ofMinutes(lead));
            if (now.isBefore(notifyAt)) continue;                                  // not yet
            if (now.isAfter(notifyAt.plus(Duration.ofMinutes(graceMinutes)))) continue;  // too stale

            String when = lead > 0 ? lead + "분 후" : "지금";
            StringBuilder body = new StringBuilder(todo.getText()).append(" · ").append(when);
            if (todo.getPlace() != null && !todo.getPlace().isBlank()) {
                body.append(" · 📍").append(todo.getPlace());
            }

            int sent = push.sendToUser(todo.getUserId(), new WebPushService.Payload(
                    "🕐 " + todo.getTimeLabel() + " 약속",
                    body.toString(),
                    appUrl,
                    "todo-" + todo.getId()));

            // Marked regardless of `sent`: with zero live subscriptions there is nothing to
            // retry, and re-scanning the same todo every minute for a day would only spam
            // the log. Re-enabling alerts is what a user does *before* the appointment.
            todo.setRemindedAt(now);
            todos.save(todo);
            log.info("Reminder for todo {} (user {}) → {} endpoint(s)", todo.getId(), todo.getUserId(), sent);
        }
    }

    /** Absolute instant of the appointment, or null if timeLabel isn't a usable "HH:mm". */
    private Instant dueAt(Todo todo) {
        try {
            LocalDate date = LocalDate.parse(todo.getDateKey());
            String raw = todo.getTimeLabel().trim();
            // <input type="time"> yields "HH:mm", but "HH:mm:ss" round-trips through some clients.
            LocalTime time = LocalTime.parse(raw.length() > 5 ? raw.substring(0, 5) : raw);
            return date.atTime(time).atZone(zone).toInstant();
        } catch (DateTimeParseException | NullPointerException e) {
            return null;
        }
    }

    /** The user's chosen lead time, falling back to the configured default when unset. */
    private int leadMinutesFor(Long userId) {
        return settings.findById(userId)
                .map(Settings::getRemindLeadMinutes)
                .filter(m -> m >= 0)
                .orElse(defaultLeadMinutes);
    }
}
