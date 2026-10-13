package com.calendar.events.domain.services;

import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.EventCover;
import com.calendar.events.domain.models.Expense;
import com.calendar.events.domain.models.MediaProfile;
import com.calendar.events.domain.models.SignedUrls;
import com.calendar.events.domain.models.StoredMedia;
import com.calendar.events.domain.ports.MediaStore;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Covers and receipts as wely-media keeps them: stored, discarded, and signed one batch per response.
 *
 * <p>Signing and discarding never fail what the user asked for: a picture without a URL, or an orphan
 * object in the bucket, costs less than a page that does not load or an event that cannot be deleted.
 */
public class PictureService {

    /** wely-media signs at most this many keys per call. */
    static final int MAX_URLS_PER_CALL = 200;
    /** Deletions in flight at once when an event goes with all its receipts. */
    static final int DISCARD_CONCURRENCY = 8;

    private static final System.Logger LOG = System.getLogger(PictureService.class.getName());

    private final MediaStore mediaStore;

    public PictureService(MediaStore mediaStore) {
        this.mediaStore = mediaStore;
    }

    public Mono<StoredMedia> store(String key, byte[] content, MediaProfile profile) {
        return mediaStore.store(key, content, profile);
    }

    /** Best effort: a failure is logged and swallowed. Null keys are skipped. */
    public Mono<Void> discard(Collection<String> keys) {
        List<String> stored = keys.stream().filter(Objects::nonNull).distinct().toList();
        return Flux.fromIterable(stored)
                .flatMap(key -> mediaStore.delete(key)
                        .onErrorResume(error -> {
                            LOG.log(System.Logger.Level.WARNING, "Picture {0} left in the bucket: {1}", key, error.toString());
                            return Mono.empty();
                        }), DISCARD_CONCURRENCY)
                .then();
    }

    public Mono<Void> discard(String key) {
        return key == null ? Mono.empty() : discard(List.of(key));
    }

    /** One signing round for the stored covers of all these events. */
    public Mono<SignedUrls> signCovers(Collection<Event> events) {
        return sign(events.stream().map(event -> EventCover.of(event).storedKey()).toList());
    }

    /** One signing round for the receipts of all these expenses. */
    public Mono<SignedUrls> signReceipts(Collection<Expense> expenses) {
        return sign(expenses.stream().map(Expense::receiptKey).toList());
    }

    /** In calls of at most 200 keys; wely-media failing costs the URLs, not the response. */
    private Mono<SignedUrls> sign(List<String> candidates) {
        List<String> keys = candidates.stream().filter(Objects::nonNull).distinct().toList();
        if (keys.isEmpty()) {
            return Mono.just(SignedUrls.NONE);
        }
        return Flux.range(0, (keys.size() + MAX_URLS_PER_CALL - 1) / MAX_URLS_PER_CALL)
                .map(chunk -> keys.subList(chunk * MAX_URLS_PER_CALL, Math.min(keys.size(), (chunk + 1) * MAX_URLS_PER_CALL)))
                .concatMap(chunk -> mediaStore.urls(chunk)
                        .onErrorResume(error -> {
                            LOG.log(System.Logger.Level.WARNING, "Pictures sent without URL: signing failed: {0}", error.toString());
                            return Mono.just(Map.of());
                        }))
                .collect(HashMap<String, String>::new, Map::putAll)
                .map(SignedUrls::new);
    }
}
