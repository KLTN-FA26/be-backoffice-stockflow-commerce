package com.stockflow.assistant.internal.controller;

import com.stockflow.assistant.internal.controller.dto.TranscriptionResponse;
import com.stockflow.assistant.internal.service.VoiceTranscriptions;
import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.ratelimit.RateLimit;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.security.ScopeGuards;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.StorageException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;

/**
 * A spoken question written down, to be read, corrected and sent as a question (ADR-0015, design §8.3). Staff holding
 * goods or wearing gloves can ask without typing; the transcript comes back to them first, so nothing heard is acted on
 * unread.
 *
 * <p><b>The question's guard.</b> {@code assistant-conversations:CREATE}, the permission of asking: whoever may ask may
 * ask out loud. A portal customer is refused first, as on every assistant endpoint.</p>
 *
 * <p><b>The questions' budget.</b> A transcription is a model call, so it draws on the per-user rate-limit bucket of
 * the questions ({@link AssistantResources#QUESTIONS_BUCKET}), and on a day's budget of its own
 * ({@code stockflow.assistant.voice.daily-limit}); the question it becomes counts against the daily question cap when
 * it is sent.</p>
 *
 * <p><b>Off unless asked for.</b> The endpoint exists only with {@code stockflow.assistant.voice.enabled} true
 * (environment variable {@code ASSISTANT_VOICE_ENABLED}); without it the path answers 404. Multipart requests bypass the
 * idempotency filter, as every upload does; a repeated request is transcribed again, and nothing is kept either
 * time.</p>
 */
@RestController
@RequestMapping("/api/v1/assistant/transcriptions")
@Tag(name = "Assistant")
@ConditionalOnProperty(name = "stockflow.assistant.voice.enabled", havingValue = "true")
class VoiceController {

    private final VoiceTranscriptions transcriptions;

    VoiceController(VoiceTranscriptions transcriptions) {
        this.transcriptions = transcriptions;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Write down a spoken question, for the speaker to correct and send",
            description = "Needs assistant-conversations:CREATE, as asking does. Multipart: file, a WAV of 16-bit PCM, "
                    + "at most 60 seconds and 2 MB (16 kHz mono is enough). The recording goes to the assistant's "
                    + "speech model and is not kept, nor is the text. The answer lists the codes and the other words "
                    + "holding a digit or spelling a number, to mark for checking. Shares the assistant's rate limit "
                    + "of 10 a minute and has a daily limit of its own (100 by default). 400 (not PCM, too short or "
                    + "too long), 413, 415; 429 RATE_LIMITED or ASSISTANT_QUOTA_EXHAUSTED; 503 ASSISTANT_UNAVAILABLE "
                    + "(retry), ASSISTANT_MISCONFIGURED or ASSISTANT_DISABLED (do not).")
    @RequiresPermission(resource = AssistantResources.CONVERSATIONS, action = Action.CREATE, scope = DataScope.OWN)
    @RateLimit(limit = AssistantResources.QUESTIONS_PER_MINUTE, perSeconds = 60, key = RateLimit.Key.USER,
            bucket = AssistantResources.QUESTIONS_BUCKET)
    public ApiResponse<TranscriptionResponse> transcribe(@AuthenticatedUser CurrentUser user,
                                                         @RequestPart("file") MultipartFile file) {
        ScopeGuards.requireBackOffice(user);
        try (InputStream content = file.getInputStream()) {
            VoiceTranscriptions.Heard heard = transcriptions.transcribe(new FileUpload(file.getOriginalFilename(),
                    file.getContentType(), file.getSize(), content), user.userId());
            return ApiResponse.ok(new TranscriptionResponse(heard.text(), heard.codes(), heard.numbers(),
                    heard.seconds()));
        } catch (IOException unreadable) {
            throw new StorageException("Could not read the multipart upload", unreadable);
        }
    }
}
