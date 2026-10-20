package com.calendar.events.domain.models;

import com.calendar.events.exception.BusinessErrorCode;

/** How wely-media prepares a picture; each profile is one use case, refused with its own code. */
public enum MediaProfile {
    /** An event's cover: at most 1600 px wide. */
    COVER(BusinessErrorCode.COVER_INVALID),
    /** An expense's receipt: longest side at most 2000 px, so the small print stays readable. */
    RECEIPT(BusinessErrorCode.RECEIPT_INVALID);

    private final BusinessErrorCode invalidError;

    MediaProfile(BusinessErrorCode invalidError) {
        this.invalidError = invalidError;
    }

    /** What an upload that is not a usable image answers with. */
    public BusinessErrorCode invalidError() {
        return invalidError;
    }
}
