package com.stockflow.common.storage;

import com.stockflow.common.error.BusinessException;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UploadInspectionTest {
    @Test void streamsClamdProtocolAndAcceptsOnlyCleanResult() throws Exception {
        run("stream: OK\0", false);
    }
    @Test void rejectsMalwareAndScannerErrors() throws Exception {
        run("stream: TestSignature FOUND\0", true);
        run("INSTREAM size limit exceeded. ERROR\0", true);
    }
    private void run(String response, boolean failure) throws Exception {
        try (var server = new ServerSocket(0)) {
            var peer = CompletableFuture.runAsync(() -> {
                try (var socket = server.accept()) {
                    var input = new DataInputStream(socket.getInputStream());
                    assertThat(new String(input.readNBytes(10), StandardCharsets.US_ASCII)).isEqualTo("zINSTREAM\0");
                    int size = input.readInt();
                    assertThat(new String(input.readNBytes(size), StandardCharsets.UTF_8)).isEqualTo("sample");
                    assertThat(input.readInt()).isZero();
                    socket.getOutputStream().write(response.getBytes(StandardCharsets.UTF_8));
                } catch (java.io.IOException ex) { throw new RuntimeException(ex); }
            });
            var scanner = new UploadInspection("localhost", server.getLocalPort());
            if (failure) {
                assertThatThrownBy(() -> scanner.requireClean(new ByteArrayInputStream("sample".getBytes(StandardCharsets.UTF_8))))
                        .isInstanceOf(BusinessException.class);
            } else { scanner.requireClean(new ByteArrayInputStream("sample".getBytes(StandardCharsets.UTF_8))); }
            peer.get(5, TimeUnit.SECONDS);
        }
    }
}
