package io.agentflow.auth;

import io.agentflow.common.DomainException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 演示令牌必须过期：过期后既不能作为 Bearer 凭据，也不能被后台用登录指纹恢复身份。
 * @author owlzhangfq@gmail.com
 */
class DemoTokenExpiryTest {
    @Test
    void liveTokenAuthenticatesAndCanBeRestoredByFingerprint() {
        var service = new AuthService(true, "demo", Duration.ofHours(1));
        String token = service.login("demo", "alice", "demo").token();
        assertThat(service.authenticate(token).userId()).isEqualTo("alice");
        assertThat(service.actorForLoginReference(service.loginReference(token))).isPresent();
    }

    @Test
    void expiredTokenIsRejectedAndNoLongerRestorable() throws Exception {
        var service = new AuthService(true, "demo", Duration.ofMillis(20));
        String token = service.login("demo", "alice", "demo").token();
        String reference = service.loginReference(token);
        Thread.sleep(50);
        assertThatThrownBy(() -> service.authenticate(token)).isInstanceOf(DomainException.class)
                .extracting(failure -> ((DomainException) failure).code()).isEqualTo("UNAUTHENTICATED");
        assertThat(service.actorForLoginReference(reference)).isEmpty();
    }

    @Test
    void nonPositiveTtlIsRejectedAtStartup() {
        assertThatThrownBy(() -> new AuthService(true, "demo", Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }
}
