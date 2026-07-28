package com.integration.recording;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 회의록 녹음을 n8n 웹훅으로 전달합니다.
 *
 * 브라우저가 웹훅을 직접 부르지 않는 이유: 프론트엔드에 넣은 값은 무엇이든 번들에
 * 인라인되어 공개되므로, 거기 담긴 공유 키는 비밀도 아니고 "누가 올렸는지"를
 * 구분하지도 못합니다. 인증은 우리 API가 사용자 JWT로 처리하고, 웹훅 주소와 키는
 * 서버 밖으로 나가지 않습니다. 덕분에 n8n 웹훅을 인터넷에 열어둘 필요도 없습니다.
 */
@Service
public class RecordingUploadService {

    private final String webhookUrl;
    private final String webhookKey;
    private final RestClient client;

    public RecordingUploadService(
            @Value("${app.recording.webhook-url:}") String webhookUrl,
            @Value("${app.recording.webhook-key:}") String webhookKey) {
        this.webhookUrl = webhookUrl;
        this.webhookKey = webhookKey;

        // JDK 클라이언트는 요청 본문을 스트리밍합니다. 1시간짜리 오디오를 통째로
        // 힙에 올리지 않기 위해 이 팩토리를 씁니다.
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
        // n8n이 파일을 받아 저장까지 마친 뒤에 응답하므로 넉넉히 잡습니다.
        factory.setReadTimeout(Duration.ofMinutes(3));
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    public boolean isConfigured() {
        return !webhookUrl.isBlank();
    }

    /**
     * @param userId 누가 올렸는지 n8n 쪽에도 남깁니다 — 공유 키로는 알 수 없던 정보입니다.
     */
    public void forward(MultipartFile file, String title, Long userId, String userEmail) {
        if (!isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Recording webhook is not configured");
        }

        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentDispositionFormData("audio", file.getOriginalFilename());
        if (file.getContentType() != null) {
            partHeaders.setContentType(MediaType.parseMediaType(file.getContentType()));
        }

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        // getResource()는 임시 파일을 가리키는 핸들일 뿐이라, 여기서도 내용이
        // 메모리로 올라오지 않습니다.
        body.add("audio", new HttpEntity<>(file.getResource(), partHeaders));
        body.add("title", title);
        body.add("userId", String.valueOf(userId));
        body.add("userEmail", userEmail);
        // n8n 쪽 웹훅에 Header Auth를 걸어뒀다면 이 값으로 맞춥니다. 서버 간 통신이라
        // 이제는 진짜 비밀로 유지됩니다. 웹훅을 내부망에만 열어뒀다면 비워둬도 됩니다.
        if (!webhookKey.isBlank()) {
            body.add("key", webhookKey);
        }

        try {
            client.post()
                    .uri(webhookUrl)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            // n8n이 죽었거나 느린 것은 우리 API의 잘못이 아니므로 502로 구분해서
            // 알립니다. 프론트는 이 상태를 보고 "다시 시도"를 안내합니다.
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Recording webhook rejected the upload: " + e.getMessage());
        }
    }
}
