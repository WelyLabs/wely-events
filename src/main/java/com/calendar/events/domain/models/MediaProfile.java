package com.calendar.events.domain.models;

import com.calendar.events.exception.EventErrorCode;

/** How wely-media prepares a picture; each profile is one use case, refused with its own code. */
public enum MediaProfile {
    /** An event's cover: at most 1600 px wide. */
    COVER(EventErrorCode.COVER_INVALID),
    /** An expense's receipt: longest side at most 2000 px, so the small print stays readable. */
    RECEIPT(EventErrorCode.RECEIPT_INVALID);

    private final EventErrorCode invalidError;

    MediaProfile(EventErrorCode invalidError) {
        this.invalidError = invalidError;
    }

    /** What an upload that is not a usable image answers with. */
    public EventErrorCode invalidError() {
        return invalidError;
    }
}
