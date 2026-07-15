package com.integration.web;

import com.integration.domain.Todo;
import com.integration.repository.TodoRepository;
import com.integration.security.CurrentUserService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/todos")
public class TodoController {

    private final TodoRepository repo;
    private final CurrentUserService currentUser;

    public TodoController(TodoRepository repo, CurrentUserService currentUser) {
        this.repo = repo;
        this.currentUser = currentUser;
    }

    public record TodoDto(Long id, String text, boolean done, int sortOrder, String dateKey) {
        static TodoDto of(Todo t) {
            return new TodoDto(t.getId(), t.getText(), t.isDone(), t.getSortOrder(), t.getDateKey());
        }
    }

    public record CreateTodoRequest(String text, String dateKey) {}
    public record UpdateTodoRequest(String text, Boolean done, Integer sortOrder, String dateKey) {}

    @GetMapping
    public List<TodoDto> list() {
        return repo.findByUserIdOrderBySortOrderAsc(currentUser.requireId())
                .stream().map(TodoDto::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TodoDto create(@RequestBody CreateTodoRequest req) {
        Todo t = new Todo();
        t.setUserId(currentUser.requireId());
        t.setText(req.text());
        t.setDateKey(req.dateKey());
        return TodoDto.of(repo.save(t));
    }

    @PatchMapping("/{id}")
    public TodoDto update(@PathVariable Long id, @RequestBody UpdateTodoRequest req) {
        Todo t = repo.findByIdAndUserId(id, currentUser.requireId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (req.text() != null) t.setText(req.text());
        if (req.done() != null) t.setDone(req.done());
        if (req.sortOrder() != null) t.setSortOrder(req.sortOrder());
        if (req.dateKey() != null) t.setDateKey(req.dateKey());
        return TodoDto.of(repo.save(t));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        Todo t = repo.findByIdAndUserId(id, currentUser.requireId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        repo.delete(t);
    }
}
