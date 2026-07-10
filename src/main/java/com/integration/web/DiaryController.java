package com.integration.web;

import com.integration.domain.Diary;
import com.integration.repository.DiaryRepository;
import com.integration.security.CurrentUserService;
import jakarta.transaction.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/diaries")
public class DiaryController {

    private final DiaryRepository repo;
    private final CurrentUserService currentUser;

    public DiaryController(DiaryRepository repo, CurrentUserService currentUser) {
        this.repo = repo;
        this.currentUser = currentUser;
    }

    public record DiaryDto(String dateKey, String title, String body, String mood) {
        static DiaryDto of(Diary d) {
            return new DiaryDto(d.getDateKey(), d.getTitle(), d.getBody(), d.getMood());
        }
    }

    @GetMapping
    public List<DiaryDto> list() {
        return repo.findByUserId(currentUser.requireId()).stream().map(DiaryDto::of).toList();
    }

    /** Upsert a diary entry for the given calendar date key. */
    @PutMapping("/{dateKey}")
    public DiaryDto upsert(@PathVariable String dateKey, @RequestBody DiaryDto dto) {
        Long userId = currentUser.requireId();
        Diary d = repo.findByUserIdAndDateKey(userId, dateKey).orElseGet(Diary::new);
        d.setUserId(userId);
        d.setDateKey(dateKey);
        d.setTitle(dto.title() == null ? "" : dto.title());
        d.setBody(dto.body() == null ? "" : dto.body());
        d.setMood(dto.mood() == null ? "" : dto.mood());
        return DiaryDto.of(repo.save(d));
    }

    @DeleteMapping("/{dateKey}")
    @Transactional
    public void delete(@PathVariable String dateKey) {
        repo.deleteByUserIdAndDateKey(currentUser.requireId(), dateKey);
    }
}
