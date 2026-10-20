package com.calendar.events.infrastructure.media.adapters;

import com.calendar.events.configuration.MediaApiClientConfig;
import com.calendar.events.domain.models.MediaProfile;
import com.calendar.events.domain.models.StoredMedia;
import com.calendar.events.exception.BusinessErrorCode;
import com.calendar.events.exception.BusinessException;
import com.calendar.events.exception.TechnicalErrorCode;
import com.calendar.events.exception.TechnicalException;
import com.calendar.events.infrastructure.media.mappers.MediaMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Against a real HTTP server on a free port: the multipart body, the secret and the error codes are what is tested. */
class MediaApiAdapterTest {

    private static final String SECRET = "s3cret";

    private HttpServer server;
    private MediaApiAdapter adapter;

    private volatile int status = 200;
    private volatile String contentType = "application/json";
    private volatile String body = "";
    private volatile String method;
    private volatile String path;
    private volatile String query;
    private volatile String secret;
    private volatile String requestContentType;
    private volatile String requestBody;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            method = exchange.getRequestMethod();
            path = exchange.getRequestURI().getPath();
            query = exchange.getRequestURI().getQuery();
            secret = exchange.getRequestHeaders().getFirst(MediaApiClientConfig.SECRET_HEADER);
            requestContentType = exchange.getRequestHeaders().getFirst("Content-Type");
            requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
            exchange.close();
        });
        server.start();
        adapter = adapterFor("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static MediaApiAdapter adapterFor(String url) {
        return new MediaApiAdapter(new MediaApiClientConfig().mediaWebClient(url, SECRET), Mappers.getMapper(MediaMapper.class));
    }

    private void answers(int status, String code) {
        this.status = status;
        this.contentType = "application/problem+json";
        this.body = "{\"type\":\"about:blank\",\"status\":" + status + ",\"code\":\"" + code + "\"}";
    }

    private static boolean failsWith(Throwable error, BusinessErrorCode code) {
        return error instanceof BusinessException exception && exception.getErrorCode() == code;
    }

    private static boolean failsWith(Throwable error, TechnicalErrorCode code) {
        return error instanceof TechnicalException exception && exception.getErrorCode() == code;
    }

    @Test
    void store_shouldSendTheFileKeyAndProfile_withTheSecret() {
        status = 201;
        body = "{\"key\":\"events/1/cover-a.jpg\",\"width\":1600,\"height\":900,\"contentType\":\"image/jpeg\"}";

        StepVerifier.create(adapter.store("events/1/cover-a.jpg", "JPEGDATA".getBytes(StandardCharsets.ISO_8859_1), MediaProfile.COVER))
                .expectNext(new StoredMedia("events/1/cover-a.jpg", 1600, 900))
                .expectComplete().verify(Duration.ofSeconds(10));

        assertThat(method).isEqualTo("POST");
        assertThat(path).isEqualTo(MediaApiAdapter.MEDIA_PATH);
        assertThat(secret).isEqualTo(SECRET);
        assertThat(requestContentType).startsWith("multipart/form-data");
        assertThat(requestBody)
                .contains("name=\"file\"; filename=\"upload\"").contains("JPEGDATA")
                .contains("name=\"key\"").contains("events/1/cover-a.jpg")
                .contains("name=\"profile\"").contains("COVER");
    }

    @Test
    void store_shouldAnswerCoverInvalid_whenWelyMediaRefusesACover() {
        answers(400, "MED-BUS-001");

        StepVerifier.create(adapter.store("events/1/cover-a.jpg", new byte[]{1}, MediaProfile.COVER))
                .expectErrorMatches(error -> failsWith(error, BusinessErrorCode.COVER_INVALID))
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void store_shouldAnswerReceiptInvalid_whenWelyMediaRefusesAReceipt() {
        answers(400, "MED-BUS-001");

        StepVerifier.create(adapter.store("events/1/receipts/x1-a.jpg", new byte[]{1}, MediaProfile.RECEIPT))
                .expectErrorMatches(error -> failsWith(error, BusinessErrorCode.RECEIPT_INVALID))
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void store_shouldAnswerTooLarge_whenWelyMediaFindsItTooLarge() {
        answers(413, "MED-BUS-002");

        StepVerifier.create(adapter.store("events/1/cover-a.jpg", new byte[]{1}, MediaProfile.COVER))
                .expectErrorMatches(error -> failsWith(error, BusinessErrorCode.MEDIA_TOO_LARGE))
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void store_shouldAnswerUnavailable_forEveryOtherRefusal() {
        for (Map.Entry<Integer, String> refusal : Map.of(403, "MED-BUS-003", 401, "MED-REQ-001", 502, "MED-TEC-001").entrySet()) {
            answers(refusal.getKey(), refusal.getValue());

            // wely-media answered: its refusal is the whole story, there is no exception underneath.
            StepVerifier.create(adapter.store("events/1/cover-a.jpg", new byte[]{1}, MediaProfile.COVER))
                    .expectErrorMatches(error -> failsWith(error, TechnicalErrorCode.MEDIA_UNAVAILABLE)
                            && error.getCause() == null)
                    .verify(Duration.ofSeconds(10));
        }
    }

    @Test
    void store_shouldAnswerUnavailable_whenTheErrorIsNotAProblem() {
        status = 500;
        contentType = "text/html";
        body = "<html>oops</html>";

        StepVerifier.create(adapter.store("events/1/cover-a.jpg", new byte[]{1}, MediaProfile.COVER))
                .expectErrorMatches(error -> failsWith(error, TechnicalErrorCode.MEDIA_UNAVAILABLE))
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void store_shouldAnswerUnavailable_whenWelyMediaCannotBeReached() {
        server.stop(0);
        MediaApiAdapter unreachable = adapterFor("http://127.0.0.1:" + server.getAddress().getPort());

        StepVerifier.create(unreachable.store("events/1/cover-a.jpg", new byte[]{1}, MediaProfile.COVER))
                .expectErrorMatches(error -> failsWith(error, TechnicalErrorCode.MEDIA_UNAVAILABLE)
                        && error.getCause() instanceof WebClientRequestException)
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void delete_shouldNameTheKeyInTheQuery() {
        status = 204;

        StepVerifier.create(adapter.delete("events/1/cover-a.jpg")).expectComplete().verify(Duration.ofSeconds(10));

        assertThat(method).isEqualTo("DELETE");
        assertThat(path).isEqualTo(MediaApiAdapter.MEDIA_PATH);
        assertThat(query).isEqualTo("key=events/1/cover-a.jpg");
        assertThat(secret).isEqualTo(SECRET);
    }

    @Test
    void delete_shouldAnswerUnavailable_whenWelyMediaFails() {
        answers(502, "MED-TEC-001");

        StepVerifier.create(adapter.delete("events/1/cover-a.jpg"))
                .expectErrorMatches(error -> failsWith(error, TechnicalErrorCode.MEDIA_UNAVAILABLE))
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void urls_shouldSendTheKeysAndLifetime_andReadTheUrlsByKey() {
        body = "{\"urls\":{\"events/1/cover-a.jpg\":\"https://media/a\"}}";

        StepVerifier.create(adapter.urls(List.of("events/1/cover-a.jpg", "events/2/cover-b.jpg")))
                .expectNext(Map.of("events/1/cover-a.jpg", "https://media/a"))
                .expectComplete().verify(Duration.ofSeconds(10));

        assertThat(method).isEqualTo("POST");
        assertThat(path).isEqualTo(MediaApiAdapter.URLS_PATH);
        assertThat(secret).isEqualTo(SECRET);
        assertThat(requestBody).contains("\"keys\":[\"events/1/cover-a.jpg\",\"events/2/cover-b.jpg\"]")
                .contains("\"ttlSeconds\":" + MediaApiAdapter.URL_TTL_SECONDS);
    }

    @Test
    void urls_shouldBeEmpty_whenTheAnswerHasNoUrls() {
        body = "{}";

        StepVerifier.create(adapter.urls(List.of("events/1/cover-a.jpg"))).expectNext(Map.of()).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void urls_shouldAnswerUnavailable_whenWelyMediaRefuses() {
        answers(401, "MED-REQ-001");

        StepVerifier.create(adapter.urls(List.of("events/1/cover-a.jpg")))
                .expectErrorMatches(error -> failsWith(error, TechnicalErrorCode.MEDIA_UNAVAILABLE))
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void refusalOf_shouldOnlyBlameTheImage_whenAnImageWasSent() {
        assertThat(MediaApiAdapter.refusalOf("MED-BUS-001", null)).isEmpty();
        assertThat(MediaApiAdapter.refusalOf(null, MediaProfile.COVER)).isEmpty();
        assertThat(MediaApiAdapter.refusalOf("MED-BUS-001", MediaProfile.RECEIPT)).contains(BusinessErrorCode.RECEIPT_INVALID);
        assertThat(MediaApiAdapter.refusalOf("MED-BUS-002", MediaProfile.COVER)).contains(BusinessErrorCode.MEDIA_TOO_LARGE);
    }
}
