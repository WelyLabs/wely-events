package com.calendar.events.exception;

import org.springframework.web.server.ServerWebExchange;

/**
 * The request id every problem and every error log line carries: the gateway's {@code X-Request-Id} when the
 * request came through it, so one id follows the request from the gateway to each service's logs.
 */
public final class RequestIds {

    /** The header the gateway sets, this service reads, and every outgoing call to another Wely service repeats. */
    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    /** Where the id is kept: an exchange attribute, and a Reactor Context key, under this name. */
    public static final String REQUEST_ID_ATTRIBUTE = "wely.requestId";

    private RequestIds() {
    }

    /** The id stored by the request id filter; the WebFlux id when the filter has not run (a test, a failure before it). */
    public static String of(ServerWebExchange exchange) {
        return exchange.getAttribute(REQUEST_ID_ATTRIBUTE) instanceof String id ? id : exchange.getRequest().getId();
    }
}
