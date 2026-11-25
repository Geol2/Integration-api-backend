package com.integration.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class CloudSttServiceTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void Deepgram_응답에서_받아적은_문장을_꺼낸다() throws Exception {
        String body = "{\"results\":{\"channels\":[{\"alternatives\":[{\"transcript\":\" How are you doing today? \",\"confidence\":0.98}]}]}}";
        assertEquals("How are you doing today?", CloudSttService.transcriptOf(json.readTree(body)));
    }

    @Test
    void 결과가_없으면_빈_문자열() throws Exception {
        assertEquals("", CloudSttService.transcriptOf(json.readTree("{\"results\":{\"channels\":[]}}")));
        assertEquals("", CloudSttService.transcriptOf(json.readTree("{}")));
    }

    @Test
    void 키가_없으면_쓰지_않는다() {
        assertFalse(new CloudSttService("", "nova-3").isAvailable());
        assertFalse(new CloudSttService("  ", null).isAvailable());
    }
}
