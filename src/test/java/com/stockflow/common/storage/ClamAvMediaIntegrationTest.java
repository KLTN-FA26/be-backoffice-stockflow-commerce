package com.stockflow.common.storage;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThatCode;

/** Exercises the real engine; signature downloads may make the first run slow. */
@Testcontainers(disabledWithoutDocker = true)
class ClamAvMediaIntegrationTest {
    @Container static final GenericContainer<?> CLAMAV = new GenericContainer<>("clamav/clamav:stable")
            .withExposedPorts(3310).waitingFor(Wait.forSuccessfulCommand("clamdcheck.sh").withStartupTimeout(Duration.ofMinutes(5)));
    @Test void cleanBytesPassRealEngine() {
        var scanner = new UploadInspection(CLAMAV.getHost(), CLAMAV.getMappedPort(3310), true);
        assertThatCode(() -> scanner.requireClean(new ByteArrayInputStream("StockFlow clean fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8))))
                .doesNotThrowAnyException();
    }
}
