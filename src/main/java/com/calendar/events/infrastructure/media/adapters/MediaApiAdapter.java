package com.calendar.events.infrastructure.media.adapters;

import com.calendar.events.domain.models.MediaProfile;
import com.calendar.events.domain.models.StoredMedia;
import com.calendar.events.domain.ports.MediaStore;
import com.calendar.events.exception.BusinessErrorCode;
import com.calendar.events.exception.BusinessException;
import com.calendar.events.exception.TechnicalErrorCode;
import com.calendar.events.exception.TechnicalException;
import com.calendar.events.infrastructure.media.mappers.MediaMapper;
import com.calendar.events.infrastructure.media.models.MediaProblem;
import com.calendar.events.infrastructure.media.models.SignUrlsRequest;
import com.calendar.events.infrastructure.media.models.SignedUrlsResponse;
import com.calendar.events.infrastructure.media.models.StoredMediaResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * Stores, removes and signs pictures through wely-media's internal API, which owns the bucket.
 * A refused image answers with the profile's own code, an oversized one with MEDIA_TOO_LARGE;
 * anything else becomes the technical MEDIA_UNAVAILABLE (502).
 */
@Slf4j
@Component
public class MediaApiAdapter implements MediaStore {

    static final String MEDIA_PATH = "/internal/media";
    static final String URLS_PATH = "/internal/media/urls";
    /** Long enough to read a page, short enough that a shared link stops working. */
    static final long URL_TTL_SECONDS = 3600;
    /** wely-media wants a file name on the part; the key already names the object. */
    static final String UPLOAD_FILENAME = "upload";
    static final String INVALID_IMAGE = "MED-BUS-001";
    static final String IMAGE_TOO_LARGE = "MED-BUS-002";

    private final WebClient client;
    private final MediaMapper mapper;

    public MediaApiAdapter(@Qualifier("mediaWebClient") WebClient client, MediaMapper mapper) {
        this.client = client;
        this.mapper = mapper;
    }

    @Override
    public Mono<StoredMedia> store(String key, byte[] content, MediaProfile profile) {
        MultipartBodyBuilder parts = new MultipartBodyBuilder();
        parts.part("file", content, MediaType.APPLICATION_OCTET_STREAM).filename(UPLOAD_FILENAME);
        parts.part("key", key);
        parts.part("profile", profile.name());
        return Mono.defer(() -> client.post()
                        .uri(MEDIA_PATH)
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .body(BodyInserters.fromMultipartData(parts.build()))
                        .retrieve()
                        .onStatus(HttpStatusCode::isError, response -> toException(response, profile))
                        .bodyToMono(StoredMediaResponse.class))
                .map(mapper::toStoredMedia)
                .onErrorMap(MediaApiAdapter::isUnexpected, error -> unavailable("store " + key, error));
    }

    /** wely-media answers 204 for a missing key too, so deleting twice is not an error. */
    @Override
    public Mono<Void> delete(String key) {
        return Mono.defer(() -> client.delete()
                        .uri(uri -> uri.path(MEDIA_PATH).queryParam("key", "{key}").build(key))
                        .retrieve()
                        .onStatus(HttpStatusCode::isError, response -> toException(response, null))
                        .toBodilessEntity()
                        .then())
                .onErrorMap(MediaApiAdapter::isUnexpected, error -> unavailable("delete " + key, error));
    }

    /** One call: the caller keeps to 200 keys. */
    @Override
    public Mono<Map<String, String>> urls(Collection<String> keys) {
        return Mono.defer(() -> client.post()
                        .uri(URLS_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(new SignUrlsRequest(keys, URL_TTL_SECONDS))
                        .retrieve()
                        .onStatus(HttpStatusCode::isError, response -> toException(response, null))
                        .bodyToMono(SignedUrlsResponse.class))
                .map(response -> response.urls() == null ? Map.<String, String>of() : response.urls())
                .defaultIfEmpty(Map.of())
                .onErrorMap(MediaApiAdapter::isUnexpected, error -> unavailable("sign " + keys.size() + " keys", error));
    }

    /** wely-media's code decides: a refused image is the client's fault, the rest is ours. */
    private static Mono<RuntimeException> toException(ClientResponse response, MediaProfile profile) {
        int status = response.statusCode().value();
        return response.bodyToMono(MediaProblem.class)
                .map(MediaProblem::code)
                .onErrorReturn("")
                .defaultIfEmpty("")
                .map(code -> refusalOf(code, profile)
                        .<RuntimeException>map(BusinessException::new)
                        .orElseGet(() -> {
                            log.warn("wely-media answered {} {}", status, code);
                            return new TechnicalException(TechnicalErrorCode.MEDIA_UNAVAILABLE);
                        }));
    }

    /**
     * The business code when wely-media refused the image itself; empty when the failure is ours.
     * Without a profile (delete, sign) no image is involved: any refusal is ours.
     */
    static Optional<BusinessErrorCode> refusalOf(String mediaCode, MediaProfile profile) {
        if (profile == null) {
            return Optional.empty();
        }
        return switch (mediaCode == null ? "" : mediaCode) {
            case INVALID_IMAGE -> Optional.of(profile.invalidError());
            case IMAGE_TOO_LARGE -> Optional.of(BusinessErrorCode.MEDIA_TOO_LARGE);
            default -> Optional.empty();
        };
    }

    /** Anything not already translated: a refused image or a failure wely-media reported passes as it is. */
    private static boolean isUnexpected(Throwable error) {
        return !(error instanceof BusinessException || error instanceof TechnicalException);
    }

    private static TechnicalException unavailable(String what, Throwable error) {
        log.warn("Could not {} on wely-media: {}", what, error.toString());
        return new TechnicalException(TechnicalErrorCode.MEDIA_UNAVAILABLE, error);
    }
}
