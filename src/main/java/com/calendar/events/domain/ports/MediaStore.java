package com.calendar.events.domain.ports;

import com.calendar.events.domain.models.MediaProfile;
import com.calendar.events.domain.models.StoredMedia;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.Map;

/** Where covers and receipts are kept, privately: nothing stored is readable without a signed URL. */
public interface MediaStore {

    /** Prepares and stores an upload under {@code key}. Fails with the profile's invalid code, MEDIA_TOO_LARGE or MEDIA_UNAVAILABLE. */
    Mono<StoredMedia> store(String key, byte[] content, MediaProfile profile);

    /** Removing a missing key is not an error. */
    Mono<Void> delete(String key);

    /** Short-lived signed URLs, by key, for at most 200 keys; a key the store does not know is left out. */
    Mono<Map<String, String>> urls(Collection<String> keys);
}
