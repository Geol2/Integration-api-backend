package com.integration.web;

import com.integration.domain.Settings;
import com.integration.repository.SettingsRepository;
import com.integration.security.CurrentUserService;
import org.springframework.web.bind.annotation.*;

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
                              String tempUnit, boolean showQuote, String background) {
        static SettingsDto of(Settings s) {
            return new SettingsDto(s.getUserName(), s.isUse24h(), s.isShowSeconds(),
                    s.getTempUnit(), s.isShowQuote(), s.getBackground());
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
        return SettingsDto.of(repo.save(s));
    }
}
