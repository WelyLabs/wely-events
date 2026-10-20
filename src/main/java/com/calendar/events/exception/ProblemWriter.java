package com.calendar.events.exception;

import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.reactive.result.view.ViewResolver;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Writes a problem straight to the response, for the paths no controller advice reaches: web filters,
 * the security chain and {@link ProblemWebExceptionHandler}. Same body as {@link GlobalErrorHandler}'s,
 * written with the application's own codecs.
 */
@Component
public class ProblemWriter {

    private final ServerResponse.Context context;

    public ProblemWriter(ServerCodecConfigurer codecs) {
        this.context = new ServerResponse.Context() {
            @Override
            public List<HttpMessageWriter<?>> messageWriters() {
                return codecs.getWriters();
            }

            @Override
            public List<ViewResolver> viewResolvers() {
                return List.of();
            }
        };
    }

    /** Sets the problem's status and writes it as {@code application/problem+json}; headers already set are kept. */
    public Mono<Void> write(ServerWebExchange exchange, ProblemDetail problem) {
        return ServerResponse.status(problem.getStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyValue(problem)
                .flatMap(response -> response.writeTo(exchange, context));
    }
}
