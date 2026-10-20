package com.calendar.events.configuration;

import com.calendar.events.exception.BusinessErrorCode;
import com.calendar.events.exception.GlobalErrorHandler;
import com.calendar.events.exception.TechnicalErrorCode;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import io.swagger.v3.oas.models.OpenAPI;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers {@link OpenApiConfig#openApi} and the two things that make the documentation useful:
 * that it answers without a token, and that it describes the paths the gateway actually routes.
 *
 * <p>The specification is reachable in-cluster and in dev only. The gateway forwards
 * {@code /api/v1/<service>/**} and nothing else, so none of these paths is exposed publicly.
 */
// A running server, not bindToApplicationContext: springdoc registers its routes on the web
// handler, and the context-bound client does not see them — the first version of this test 404ed
// for that reason, which masked the real defect the last test here guards against.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class OpenApiConfigTest {

    @Autowired
    private ApplicationContext context;

    @Value("${local.server.port}")
    private int port;

    private WebTestClient client() {
        return WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Test
    @DisplayName("the generated document names the service rather than the application class")
    void openApi_shouldDescribeTheService() {
        var info = context.getBean(OpenAPI.class).getInfo();

        assertThat(info.getTitle()).isEqualTo("wely-events");
        assertThat(info.getVersion()).isEqualTo("v1");
        assertThat(info.getDescription()).isNotBlank();
    }

    @Test
    @DisplayName("the specification answers without a token")
    void apiDocs_shouldBeReachableUnauthenticated() {
        client().get().uri("/v3/api-docs")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.info.title").isEqualTo("wely-events");
    }

    @Test
    @DisplayName("the Swagger UI and its assets load without a token")
    void swaggerUi_shouldBeReachableUnauthenticated() {
        client().get().uri("/v3/api-docs/swagger-config")
                .exchange()
                .expectStatus().isOk();

        client().get().uri("/webjars/swagger-ui/index.html")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    @DisplayName("the documented paths carry the service prefix the gateway strips")
    void apiDocs_shouldDocumentPrefixedPaths() {
        // The regression this guards: WebConfig used to prefix every @RestController on the
        // classpath, springdoc's own included. That moved the specification itself to
        // /events-service/v3/api-docs behind authentication, and /v3/api-docs answered 404.
        byte[] body = client().get().uri("/v3/api-docs")
                .exchange()
                .expectStatus().isOk()
                .expectBody().returnResult().getResponseBody();

        assertThat(body).isNotNull();
        assertThat(new String(body, java.nio.charset.StandardCharsets.UTF_8))
                .contains("\"/events-service/");
    }
    @Test
    @DisplayName("the error body is documented once, as the Problem schema, with every code this service answers")
    void problemResponses_shouldRegisterTheProblemSchema() {
        client().get().uri("/v3/api-docs")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.components.schemas.Problem.properties.code.enum").value(codes -> assertThat(
                        (java.util.List<Object>) codes).contains(BusinessErrorCode.EVENT_NOT_FOUND.getCode(),
                        TechnicalErrorCode.MEDIA_UNAVAILABLE.getCode(), GlobalErrorHandler.UNEXPECTED_FAILURE_CODE))
                .jsonPath("$.components.schemas.Problem.properties.requestId").exists()
                .jsonPath("$.components.schemas.Problem.properties.errors").exists();
    }

    @Test
    @DisplayName("every operation answers its errors as application/problem+json")
    void problemResponses_shouldAddProblemResponsesToOperations() {
        String feed = "$.paths['/events-service/events/me/feed'].get.responses";
        client().get().uri("/v3/api-docs")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath(feed + "['4XX'].content['application/problem+json'].schema['$ref']")
                .isEqualTo("#/components/schemas/Problem")
                .jsonPath(feed + "['5XX'].content['application/problem+json'].schema['$ref']")
                .isEqualTo("#/components/schemas/Problem");
    }

    @Test
    @DisplayName("a response an operation declares by hand is kept, not overwritten")
    void addProblemResponses_shouldKeepADeclaredResponse() {
        ApiResponse declared = new ApiResponse().description("hand-written");
        Operation operation = new Operation().responses(new ApiResponses().addApiResponse(OpenApiConfig.CLIENT_ERRORS, declared));

        OpenApiConfig.addProblemResponses(operation);

        assertThat(operation.getResponses().get(OpenApiConfig.CLIENT_ERRORS)).isSameAs(declared);
        assertThat(operation.getResponses().get(OpenApiConfig.SERVER_ERRORS).getContent())
                .containsKey(OpenApiConfig.PROBLEM_JSON);
    }

    @Test
    @DisplayName("the code list is read from the enums and the handler, without duplicates")
    void errorCodes_shouldListEveryCodeOnce() {
        assertThat(OpenApiConfig.errorCodes())
                .hasSize(BusinessErrorCode.values().length + TechnicalErrorCode.values().length
                        + GlobalErrorHandler.HANDLER_CODES.size())
                .doesNotHaveDuplicates()
                .containsAll(GlobalErrorHandler.HANDLER_CODES);
    }
}
