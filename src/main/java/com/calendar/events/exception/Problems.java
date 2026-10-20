package com.calendar.events.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.net.URI;
import java.time.Instant;

/** The service's RFC 7807 body, shared by {@link GlobalErrorHandler} and the request filters. */
public final class Problems {

    /** Namespace of the {@code type} URI, which identifies and is never dereferenced. */
    static final String PROBLEM_TYPE_BASE = "https://welylabs.app/problems/";

    private Problems() {
    }

    /** Name of the property that ties a response to the log lines of the same request. */
    public static final String REQUEST_ID = "requestId";

    public static ProblemDetail of(HttpStatus status, String code, String title, String detail, String instancePath) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(PROBLEM_TYPE_BASE + code.toLowerCase()));
        problem.setTitle(title);
        problem.setInstance(URI.create(instancePath));
        problem.setProperty("code", code);
        problem.setProperty("timestamp", Instant.now().toString());
        return problem;
    }

    /**
     * Same body, plus the WebFlux request id: the one that prefixes the framework's own log lines,
     * so a client quoting it leads straight to the logs of its request.
     */
    public static ProblemDetail of(HttpStatus status, String code, String title, String detail, String instancePath,
                                   String requestId) {
        ProblemDetail problem = of(status, code, title, detail, instancePath);
        problem.setProperty(REQUEST_ID, requestId);
        return problem;
    }

    public static ProblemDetail of(BusinessErrorCode error, String instancePath) {
        return of(error.getHttpStatus(), error.getCode(), error.getTitle(), error.getDetail(), instancePath);
    }

    public static ProblemDetail of(BusinessErrorCode error, String instancePath, String requestId) {
        return of(error.getHttpStatus(), error.getCode(), error.getTitle(), error.getDetail(), instancePath, requestId);
    }

    public static ProblemDetail of(TechnicalErrorCode error, String instancePath, String requestId) {
        return of(error.getHttpStatus(), error.getCode(), error.getTitle(), error.getDetail(), instancePath, requestId);
    }
}
