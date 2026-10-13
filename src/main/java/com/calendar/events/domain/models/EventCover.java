package com.calendar.events.domain.models;

import java.util.UUID;

/**
 * An event's cover as stored: a wely-media key for an uploaded picture, or a path the web app
 * serves itself, like the demo events' covers.
 */
public record EventCover(String reference) {

    /** Uploaded covers live under this prefix; anything else is served as it is. */
    public static final String STORED_PREFIX = "events/";

    public static EventCover of(Event event) {
        return new EventCover(event.getImage());
    }

    /** A fresh key, so a new cover never reuses the URL a browser may still have cached. */
    public static String newKey(String eventId) {
        return STORED_PREFIX + eventId + "/cover-" + UUID.randomUUID() + ".jpg";
    }

    public boolean isStored() {
        return reference != null && reference.startsWith(STORED_PREFIX);
    }

    /** The key to sign or delete; null when nothing is stored. */
    public String storedKey() {
        return isStored() ? reference : null;
    }

    /** What a client loads: the signed URL of a stored cover (null without one), anything else unchanged. */
    public String shownWith(SignedUrls urls) {
        return isStored() ? urls.urlOf(reference) : reference;
    }
}
