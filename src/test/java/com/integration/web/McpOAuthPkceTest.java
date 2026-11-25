package com.integration.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** PKCE S256: RFC 7636 부록 B의 예제 값과 같아야 함. */
class McpOAuthPkceTest {

    @Test
    void s256MatchesRfc7636Example() {
        assertThat(McpOAuthController.s256("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
                .isEqualTo("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGECWEtw-M");
    }
}
