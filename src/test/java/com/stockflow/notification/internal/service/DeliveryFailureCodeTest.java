package com.stockflow.notification.internal.service;

import com.stockflow.notification.internal.domain.NotificationChannel;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailSendException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class DeliveryFailureCodeTest {
    @Test
    void storesStableCodesWithoutTransportDetails() {
        assertThat(DeliveryAttemptRecorder.failureCode(NotificationChannel.EMAIL,
                new MailSendException("Private SMTP diagnostics"))).isEqualTo("MAIL_SEND_FAILED");
        assertThat(DeliveryAttemptRecorder.failureCode(NotificationChannel.API,
                new ResourceAccessException("Private URL", new SocketTimeoutException())))
                .isEqualTo("API_TIMEOUT");
        assertThat(DeliveryAttemptRecorder.failureCode(NotificationChannel.API,
                new HttpClientErrorException(HttpStatus.BAD_REQUEST))).isEqualTo("API_REJECTED");
        assertThat(DeliveryAttemptRecorder.failureCode(NotificationChannel.API,
                new ResourceAccessException("Private URL", new ConnectException())))
                .isEqualTo("API_SEND_FAILED");
        assertThat(DeliveryAttemptRecorder.failureCode(NotificationChannel.EMAIL,
                new IllegalArgumentException("Invalid destination"))).isEqualTo("DELIVERY_INVALID");
    }
}
