package com.integration.web;

import com.integration.domain.Track;
import com.integration.repository.TrackRepository;
import com.integration.security.CurrentUserService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/tracks")
public class TrackController {

    private final TrackRepository repo;
    private final CurrentUserService currentUser;

    public TrackController(TrackRepository repo, CurrentUserService currentUser) {
        this.repo = repo;
        this.currentUser = currentUser;
    }

    public record TrackDto(Long id, String videoId, String title, int sortOrder, long ts) {
        static TrackDto of(Track t) {
            return new TrackDto(t.getId(), t.getVideoId(), t.getTitle(), t.getSortOrder(), t.getTs());
        }
    }

    public record CreateTrackRequest(String videoId, String title, long ts) {}
    public record UpdateTrackRequest(String title, Integer sortOrder) {}

    @GetMapping
    public List<TrackDto> list() {
        return repo.findByUserIdOrderBySortOrderAsc(currentUser.requireId())
                .stream().map(TrackDto::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TrackDto create(@RequestBody CreateTrackRequest req) {
        if (req.videoId() == null || req.videoId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "videoId is required");
        }
        Long userId = currentUser.requireId();
        Track t = new Track();
        t.setUserId(userId);
        t.setVideoId(req.videoId());
        t.setTitle(req.title() == null || req.title().isBlank() ? req.videoId() : req.title());
        // Append to the end of the current playlist.
        t.setSortOrder(repo.findByUserIdOrderBySortOrderAsc(userId).size());
        t.setTs(req.ts());
        return TrackDto.of(repo.save(t));
    }

    @PatchMapping("/{id}")
    public TrackDto update(@PathVariable Long id, @RequestBody UpdateTrackRequest req) {
        Track t = repo.findByIdAndUserId(id, currentUser.requireId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (req.title() != null) t.setTitle(req.title());
        if (req.sortOrder() != null) t.setSortOrder(req.sortOrder());
        return TrackDto.of(repo.save(t));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        Track t = repo.findByIdAndUserId(id, currentUser.requireId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        repo.delete(t);
    }
}
