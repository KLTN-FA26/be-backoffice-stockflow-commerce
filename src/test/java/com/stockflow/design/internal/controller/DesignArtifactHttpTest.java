package com.stockflow.design.internal.controller;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.i18n.Messages;
import com.stockflow.common.security.AuthenticatedUserArgumentResolver;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.storage.StorageException;
import com.stockflow.common.web.GlobalExceptionHandler;
import com.stockflow.design.internal.service.DesignArtifactService;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DesignArtifactHttpTest {
    @Test void anonymousCallerCannotReachArtifactService() throws Exception {
        var artifacts = mock(DesignArtifactService.class);
        var users = mock(CurrentUserProvider.class);
        when(users.current()).thenReturn(Optional.empty());
        var messages = new StaticMessageSource();
        var mvc = MockMvcBuilders.standaloneSetup(new DesignController(artifacts, new DesignArtifactWebMapper()))
                .setCustomArgumentResolvers(new AuthenticatedUserArgumentResolver(users))
                .setControllerAdvice(new GlobalExceptionHandler(new Messages(messages), messages)).build();
        mvc.perform(multipart("/api/v1/designs/{id}/artifacts", UUID.randomUUID())
                .file(new MockMultipartFile("file", "a.pdf", "application/pdf", "%PDF".getBytes())))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(artifacts);
    }

    @Test void preservesStorageAndPolicyErrorCodes() throws Exception {
        var artifacts = mock(DesignArtifactService.class);
        var users = mock(CurrentUserProvider.class);
        when(users.current()).thenReturn(Optional.of(new CurrentUser(UUID.randomUUID(), "owner",
                Set.of(), Set.of(), DataScope.OWN, Set.of())));
        var messages = new StaticMessageSource();
        var mvc = MockMvcBuilders.standaloneSetup(new DesignController(artifacts, new DesignArtifactWebMapper()))
                .setCustomArgumentResolvers(new AuthenticatedUserArgumentResolver(users))
                .setControllerAdvice(new GlobalExceptionHandler(new Messages(messages), messages)).build();
        for (var code : new ErrorCode[]{ErrorCode.PAYLOAD_TOO_LARGE, ErrorCode.UNSUPPORTED_MEDIA_TYPE, ErrorCode.STORAGE_ERROR}) {
            doThrow(code == ErrorCode.STORAGE_ERROR
                    ? new StorageException("private path") : new BusinessException(code))
                    .when(artifacts).upload(any(), any(), any(), any(), any());
            mvc.perform(multipart("/api/v1/designs/{id}/artifacts", UUID.randomUUID())
                    .file(new MockMultipartFile("file", "a.pdf", "application/pdf", "%PDF".getBytes())))
                    .andExpect(status().is(code.httpStatus())).andExpect(jsonPath("$.errorCode").value(code.name()));
        }
    }
}
