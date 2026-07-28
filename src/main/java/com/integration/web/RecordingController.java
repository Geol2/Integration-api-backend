package com.integration.web;

import com.integration.domain.User;
import com.integration.recording.RecordingUploadService;
import com.integration.security.CurrentUserService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * 회의록 녹음 업로드. SecurityConfig의 anyRequest().authenticated() 아래에 있으므로
 * 로그인하지 않은 요청은 이 컨트롤러에 닿기도 전에 401로 끊깁니다.
 */
@RestController
@RequestMapping("/api/recordings")
public class RecordingController {

    private final CurrentUserService currentUser;
    private final RecordingUploadService uploads;

    public RecordingController(CurrentUserService currentUser, RecordingUploadService uploads) {
        this.currentUser = currentUser;
        this.uploads = uploads;
    }

    public record UploadResponse(String title, long size) {}

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public UploadResponse upload(@RequestParam("file") MultipartFile file,
                                 @RequestParam(name = "title", required = false) String title) {
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Empty recording");
        }
        User user = currentUser.require();
        String name = (title == null || title.isBlank()) ? "무제" : title.trim();

        uploads.forward(file, name, user.getId(), user.getEmail());
        return new UploadResponse(name, file.getSize());
    }

    /**
     * 크기 초과는 멀티파트 파싱 단계에서 터지므로 기본값이면 500으로 나갑니다.
     * 프론트가 "파일이 너무 커요"를 구분해서 보여줄 수 있게 413으로 바꿔줍니다.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
    public void tooLarge() {
    }
}
