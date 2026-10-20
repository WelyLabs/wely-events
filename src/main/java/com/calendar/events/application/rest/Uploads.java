package com.calendar.events.application.rest;

import com.calendar.events.domain.models.MediaProfile;
import com.calendar.events.exception.BusinessErrorCode;
import com.calendar.events.exception.BusinessException;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.multipart.Part;
import org.springframework.util.MultiValueMap;
import reactor.core.publisher.Mono;

/** Reads the picture of a multipart upload: its part {@code file}, at most 10 MB. */
final class Uploads {

    /** Above this, refused before wely-media is called. The web app sends pictures already scaled down. */
    static final int MAX_BYTES = 10 * 1024 * 1024;
    static final String FILE_PART = "file";

    private Uploads() {
    }

    /** Fails with the profile's invalid code when there is no file or it is empty, MEDIA_TOO_LARGE above 10 MB. */
    static Mono<byte[]> fileOf(Mono<MultiValueMap<String, Part>> form, MediaProfile profile) {
        return form
                .flatMap(parts -> parts.getFirst(FILE_PART) instanceof FilePart file
                        ? DataBufferUtils.join(file.content(), MAX_BYTES).map(Uploads::bytesOf)
                        : Mono.error(new BusinessException(profile.invalidError())))
                .onErrorMap(DataBufferLimitException.class, error -> new BusinessException(BusinessErrorCode.MEDIA_TOO_LARGE))
                .filter(bytes -> bytes.length > 0)
                .switchIfEmpty(Mono.error(() -> new BusinessException(profile.invalidError())));
    }

    private static byte[] bytesOf(DataBuffer buffer) {
        byte[] bytes = new byte[buffer.readableByteCount()];
        buffer.read(bytes);
        DataBufferUtils.release(buffer);
        return bytes;
    }
}
