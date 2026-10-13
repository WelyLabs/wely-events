package com.calendar.events.application.rest;

import com.calendar.events.domain.models.MediaProfile;
import com.calendar.events.exception.EventErrorCode;
import com.calendar.events.exception.EventException;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.multipart.FormFieldPart;
import org.springframework.http.codec.multipart.Part;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UploadsTest {

    static FilePart file(int size) {
        FilePart file = mock(FilePart.class);
        DataBuffer buffer = DefaultDataBufferFactory.sharedInstance.wrap(new byte[size]);
        when(file.content()).thenReturn(Flux.just(buffer));
        return file;
    }

    static Mono<MultiValueMap<String, Part>> form(Part file) {
        MultiValueMap<String, Part> parts = new LinkedMultiValueMap<>();
        if (file != null) {
            parts.add(Uploads.FILE_PART, file);
        }
        return Mono.just(parts);
    }

    static boolean failsWith(Throwable error, EventErrorCode code) {
        return error instanceof EventException exception && exception.getErrorCode() == code;
    }

    @Test
    void fileOf_shouldReadTheWholeFile() {
        StepVerifier.create(Uploads.fileOf(form(file(1234)), MediaProfile.COVER))
                .expectNextMatches(bytes -> bytes.length == 1234)
                .verifyComplete();
    }

    @Test
    void fileOf_shouldAcceptExactly10Megabytes() {
        StepVerifier.create(Uploads.fileOf(form(file(Uploads.MAX_BYTES)), MediaProfile.COVER))
                .expectNextMatches(bytes -> bytes.length == Uploads.MAX_BYTES)
                .verifyComplete();
    }

    @Test
    void fileOf_shouldRefuseAFileOver10Megabytes_asTooLarge() {
        StepVerifier.create(Uploads.fileOf(form(file(Uploads.MAX_BYTES + 1)), MediaProfile.RECEIPT))
                .expectErrorMatches(error -> failsWith(error, EventErrorCode.MEDIA_TOO_LARGE))
                .verify();
    }

    @Test
    void fileOf_shouldRefuseAFormWithoutFile_withTheProfilesCode() {
        StepVerifier.create(Uploads.fileOf(form(null), MediaProfile.COVER))
                .expectErrorMatches(error -> failsWith(error, EventErrorCode.COVER_INVALID))
                .verify();
        StepVerifier.create(Uploads.fileOf(form(null), MediaProfile.RECEIPT))
                .expectErrorMatches(error -> failsWith(error, EventErrorCode.RECEIPT_INVALID))
                .verify();
    }

    @Test
    void fileOf_shouldRefuseATextFieldNamedFile() {
        StepVerifier.create(Uploads.fileOf(form(mock(FormFieldPart.class)), MediaProfile.COVER))
                .expectErrorMatches(error -> failsWith(error, EventErrorCode.COVER_INVALID))
                .verify();
    }

    @Test
    void fileOf_shouldRefuseAnEmptyFile() {
        FilePart empty = mock(FilePart.class);
        when(empty.content()).thenReturn(Flux.empty());

        StepVerifier.create(Uploads.fileOf(form(empty), MediaProfile.COVER))
                .expectErrorMatches(error -> failsWith(error, EventErrorCode.COVER_INVALID))
                .verify();
        StepVerifier.create(Uploads.fileOf(form(file(0)), MediaProfile.COVER))
                .expectErrorMatches(error -> failsWith(error, EventErrorCode.COVER_INVALID))
                .verify();
    }
}
