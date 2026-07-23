package com.integration.web;

import com.integration.domain.Note;
import com.integration.repository.NoteRepository;
import com.integration.security.CurrentUserService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/notes")
public class NoteController {

    private final NoteRepository repo;
    private final CurrentUserService currentUser;

    public NoteController(NoteRepository repo, CurrentUserService currentUser) {
        this.repo = repo;
        this.currentUser = currentUser;
    }

    public record NoteDto(Long id, String text, double x, double y, double rot,
                          double width, double height, String dateKey, boolean pinned, long ts) {
        static NoteDto of(Note n) {
            return new NoteDto(n.getId(), n.getText(), n.getX(), n.getY(), n.getRot(),
                    n.getWidth(), n.getHeight(), n.getDateKey(), n.isPinned(), n.getTs());
        }
    }

    public record CreateNoteRequest(String text, double x, double y, double rot,
                                    Double width, Double height, String dateKey, long ts) {}
    public record MoveNoteRequest(Double x, Double y, Double rot, String text,
                                  Double width, Double height, Boolean pinned) {}

    @GetMapping
    public List<NoteDto> list() {
        return repo.findByUserId(currentUser.requireId()).stream().map(NoteDto::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public NoteDto create(@RequestBody CreateNoteRequest req) {
        Note n = new Note();
        n.setUserId(currentUser.requireId());
        n.setText(req.text());
        n.setX(req.x());
        n.setY(req.y());
        n.setRot(req.rot());
        if (req.width() != null) n.setWidth(req.width());
        if (req.height() != null) n.setHeight(req.height());
        n.setDateKey(req.dateKey());
        n.setTs(req.ts());
        return NoteDto.of(repo.save(n));
    }

    @PatchMapping("/{id}")
    public NoteDto update(@PathVariable Long id, @RequestBody MoveNoteRequest req) {
        Note n = repo.findByIdAndUserId(id, currentUser.requireId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (req.x() != null) n.setX(req.x());
        if (req.y() != null) n.setY(req.y());
        if (req.rot() != null) n.setRot(req.rot());
        if (req.text() != null) n.setText(req.text());
        if (req.width() != null) n.setWidth(req.width());
        if (req.height() != null) n.setHeight(req.height());
        if (req.pinned() != null) n.setPinned(req.pinned());
        return NoteDto.of(repo.save(n));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        Note n = repo.findByIdAndUserId(id, currentUser.requireId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        repo.delete(n);
    }
}
