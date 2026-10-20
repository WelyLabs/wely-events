package com.calendar.events.configuration;

import com.calendar.events.exception.BusinessErrorCode;
import com.calendar.events.exception.GlobalErrorHandler;
import com.calendar.events.exception.Problems;
import com.calendar.events.exception.TechnicalErrorCode;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MapSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * Names the API that springdoc generates from the controllers, and documents its errors.
 *
 * <p>Without this the document is titled after the application and says nothing about what the
 * service is for. The operations themselves are derived from the handler signatures, so what is
 * worth writing by hand is the part no signature carries — the error body above all, which every
 * operation shares and none declares.
 */
@Configuration
public class OpenApiConfig {

    static final String PROBLEM_SCHEMA = "Problem";
    static final String PROBLEM_JSON = org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON_VALUE;
    static final String CLIENT_ERRORS = "4XX";
    static final String SERVER_ERRORS = "5XX";

    static final String CODE_DESCRIPTION = """
            Stable identifier a client branches on. The family says what to do:
            BUS, a business rule refused the request: change the request;
            VAL, fields are invalid (listed in errors): fix them;
            REQ, the framework refused the request (unknown path, wrong method, unreadable body);
            TEC, a dependency failed: retry later, it is an incident on our side.""";

    @Bean
    public OpenAPI openApi() {
        return new OpenAPI().info(new Info()
                .title("wely-events")
                .version("v1")
                .description("Events and subscriptions. Signing up is an atomic update of the attendee array, not a read-modify-write."));
    }

    /** Registers the {@code Problem} schema and points every operation's 4XX and 5XX at it. */
    @Bean
    public OpenApiCustomizer problemResponses() {
        return openApi -> {
            if (openApi.getComponents() == null) {
                openApi.setComponents(new Components());
            }
            openApi.getComponents().addSchemas(PROBLEM_SCHEMA, problemSchema());
            if (openApi.getPaths() != null) {
                openApi.getPaths().values().forEach(path -> path.readOperations().forEach(OpenApiConfig::addProblemResponses));
            }
        };
    }

    /** The body {@link Problems} builds, field for field. */
    static Schema<?> problemSchema() {
        StringSchema code = new StringSchema();
        code.setEnum(errorCodes());
        code.setDescription(CODE_DESCRIPTION);
        return new ObjectSchema()
                .description("RFC 7807 problem, as every error of this service answers.")
                .addProperty("type", new StringSchema().format("uri"))
                .addProperty("title", new StringSchema())
                .addProperty("status", new IntegerSchema())
                .addProperty("detail", new StringSchema())
                .addProperty("instance", new StringSchema().format("uri"))
                .addProperty("code", code)
                .addProperty("timestamp", new StringSchema().format("date-time"))
                .addProperty(Problems.REQUEST_ID_PROPERTY, new StringSchema()
                        .description("Request id, the one that prefixes this request's log lines."))
                .addProperty("errors", new MapSchema().additionalProperties(new StringSchema())
                        .description("Only for " + GlobalErrorHandler.VALIDATION_FAILED_CODE + ": each invalid field and why."));
    }

    /** Every code this service can answer with, read from the enums and the handler: no list to keep in sync. */
    static List<String> errorCodes() {
        return Stream.of(
                        Arrays.stream(BusinessErrorCode.values()).map(BusinessErrorCode::getCode),
                        Arrays.stream(TechnicalErrorCode.values()).map(TechnicalErrorCode::getCode),
                        GlobalErrorHandler.HANDLER_CODES.stream())
                .flatMap(codes -> codes)
                .sorted()
                .toList();
    }

    /** Adds the two families an operation does not already declare; a hand-written response wins. */
    static void addProblemResponses(Operation operation) {
        if (operation.getResponses() == null) {
            operation.setResponses(new ApiResponses());
        }
        ApiResponses responses = operation.getResponses();
        if (!responses.containsKey(CLIENT_ERRORS)) {
            responses.addApiResponse(CLIENT_ERRORS, problemResponse("The request was refused; see code."));
        }
        if (!responses.containsKey(SERVER_ERRORS)) {
            responses.addApiResponse(SERVER_ERRORS, problemResponse("The request could not be served; see code."));
        }
    }

    private static ApiResponse problemResponse(String description) {
        return new ApiResponse()
                .description(description)
                .content(new Content().addMediaType(PROBLEM_JSON,
                        new MediaType().schema(new Schema<>().$ref("#/components/schemas/" + PROBLEM_SCHEMA))));
    }
}
