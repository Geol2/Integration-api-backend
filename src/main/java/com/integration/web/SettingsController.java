package com.integration.web;

import com.integration.domain.Settings;
import com.integration.repository.SettingsRepository;
import com.integration.security.CurrentUserService;
import org.springframework.web.bind.annotation.*;

import java.time.LocalTime;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final SettingsRepository repo;
    private final CurrentUserService currentUser;

    public SettingsController(SettingsRepository repo, CurrentUserService currentUser) {
        this.repo = repo;
        this.currentUser = currentUser;
    }

    public record SettingsDto(String userName, boolean use24h, boolean showSeconds,
                              String tempUnit, boolean showQuote, String background,
                              Integer remindLeadMinutes, Boolean shuffleBgOnReturn,
                              Boolean habitRemind, String habitMorningTime, String habitEveningTime) {
        static SettingsDto of(Settings s) {
            return new SettingsDto(s.getUserName(), s.isUse24h(), s.isShowSeconds(),
                    s.getTempUnit(), s.isShowQuote(), s.getBackground(), s.getRemindLeadMinutes(),
                    s.getShuffleBgOnReturn(),
                    s.getHabitRemind(), s.getHabitMorningTime(), s.getHabitEveningTime());
        }
    }

    /** Returns the user's settings, creating defaults on first access. */
    @GetMapping
    public SettingsDto get() {
        Long userId = currentUser.requireId();
        Settings s = repo.findById(userId).orElseGet(() -> repo.save(new Settings(userId)));
        return SettingsDto.of(s);
    }

    @PutMapping
    public SettingsDto update(@RequestBody SettingsDto dto) {
        Long userId = currentUser.requireId();
        Settings s = repo.findById(userId).orElseGet(() -> new Settings(userId));
        s.setUserName(dto.userName());
        s.setUse24h(dto.use24h());
        s.setShowSeconds(dto.showSeconds());
        s.setTempUnit(dto.tempUnit());
        s.setShowQuote(dto.showQuote());
        s.setBackground(dto.background());
        // Omitted (null) keeps the stored value — an older client that doesn't know about
        // reminders must not silently wipe the user's lead time on every settings save.
        if (dto.remindLeadMinutes() != null && dto.remindLeadMinutes() >= 0) {
            s.setRemindLeadMinutes(dto.remindLeadMinutes());
        }
        // Omitted (null) keeps the stored value, same reasoning as remindLeadMinutes above.
        if (dto.shuffleBgOnReturn() != null) {
            s.setShuffleBgOnReturn(dto.shuffleBgOnReturn());
        }
        if (dto.habitRemind() != null) {
            s.setHabitRemind(dto.habitRemind());
        }
        // 시각은 스케줄러가 매분 파싱하는 값이라, 알아볼 수 없는 문자열은 저장 단계에서 막습니다.
        // (잘못 들어가도 스케줄러가 서버 기본값으로 떨어지지만, 사용자는 자기가 고른 시각에
        //  안 오는 이유를 알 길이 없습니다.)
        if (isValidTime(dto.habitMorningTime())) {
            s.setHabitMorningTime(dto.habitMorningTime().trim());
        }
        if (isValidTime(dto.habitEveningTime())) {
            s.setHabitEveningTime(dto.habitEveningTime().trim());
        }
        return SettingsDto.of(repo.save(s));
    }

    /** "HH:mm" 인지. null(=이 필드는 그대로 두라는 뜻)이면 false. */
    private static boolean isValidTime(String raw) {
        if (raw == null || raw.isBlank()) return false;
        try {
            LocalTime.parse(raw.trim());
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
