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

    public static ProblemDetail of(HttpStatus status, String code, String title, String detail, String instancePath) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(PROBLEM_TYPE_BASE + code.toLowerCase()));
        problem.setTitle(title);
        problem.setInstance(URI.create(instancePath));
        problem.setProperty("code", code);
        problem.setProperty("timestamp", Instant.now().toString());
        return problem;
    }

    public static ProblemDetail of(EventErrorCode error, String instancePath) {
        return of(error.getHttpStatus(), error.getCode(), error.getTitle(), error.getDetail(), instancePath);
    }
}
