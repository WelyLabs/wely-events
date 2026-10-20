package com.calendar.events.domain.services;

import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.Expense;
import com.calendar.events.domain.models.ExpenseKind;
import com.calendar.events.domain.models.MediaProfile;
import com.calendar.events.domain.models.Share;
import com.calendar.events.domain.models.SplitMode;
import com.calendar.events.domain.models.StoredMedia;
import com.calendar.events.domain.ports.MediaStore;
import com.calendar.events.exception.TechnicalErrorCode;
import com.calendar.events.exception.TechnicalException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PictureServiceTest {

    @Mock
    private MediaStore mediaStore;

    private PictureService service;

    @BeforeEach
    void setUp() {
        service = new PictureService(mediaStore);
    }

    private static Event coveredBy(String image) {
        return Event.builder().id("e").image(image).build();
    }

    /** Signs whatever it is asked, as wely-media would. */
    private void signsEverything() {
        when(mediaStore.urls(any())).thenAnswer(call -> Mono.just(((Collection<String>) call.getArgument(0)).stream()
                .collect(Collectors.toMap(key -> key, key -> "https://media/" + key))));
    }

    @Test
    void store_shouldHandTheUploadToWelyMedia() {
        StoredMedia stored = new StoredMedia("events/1/cover-a.jpg", 1600, 900);
        byte[] content = {1, 2};
        when(mediaStore.store("events/1/cover-a.jpg", content, MediaProfile.COVER)).thenReturn(Mono.just(stored));

        StepVerifier.create(service.store("events/1/cover-a.jpg", content, MediaProfile.COVER)).expectNext(stored).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void discard_shouldDeleteEachKeyOnce_andSkipNulls() {
        when(mediaStore.delete(anyString())).thenReturn(Mono.empty());

        StepVerifier.create(service.discard(Arrays.asList("events/a.jpg", null, "events/a.jpg", "events/b.jpg"))).expectComplete().verify(Duration.ofSeconds(10));

        verify(mediaStore).delete("events/a.jpg");
        verify(mediaStore).delete("events/b.jpg");
        verify(mediaStore, times(2)).delete(anyString());
    }

    @Test
    void discard_shouldSwallowAFailure_andStillDeleteTheOthers() {
        when(mediaStore.delete("events/a.jpg")).thenReturn(Mono.error(new TechnicalException(TechnicalErrorCode.MEDIA_UNAVAILABLE)));
        when(mediaStore.delete("events/b.jpg")).thenReturn(Mono.empty());

        StepVerifier.create(service.discard(List.of("events/a.jpg", "events/b.jpg"))).expectComplete().verify(Duration.ofSeconds(10));

        verify(mediaStore).delete("events/b.jpg");
    }

    @Test
    void discard_shouldDoNothing_forANullKey() {
        StepVerifier.create(service.discard((String) null)).expectComplete().verify(Duration.ofSeconds(10));

        verify(mediaStore, never()).delete(anyString());
    }

    @Test
    void discard_shouldDeleteASingleKey() {
        when(mediaStore.delete("events/a.jpg")).thenReturn(Mono.empty());

        StepVerifier.create(service.discard("events/a.jpg")).expectComplete().verify(Duration.ofSeconds(10));

        verify(mediaStore).delete("events/a.jpg");
    }

    @Test
    void signCovers_shouldSignOnlyStoredCovers_inOneCallForTheWholeList() {
        signsEverything();
        List<Event> feed = List.of(coveredBy("events/1/cover-a.jpg"), coveredBy("/demo/events/picnic.jpg"),
                coveredBy(null), coveredBy("events/2/cover-b.jpg"));

        StepVerifier.create(service.signCovers(feed))
                .assertNext(urls -> {
                    assertThat(urls.urlOf("events/1/cover-a.jpg")).isEqualTo("https://media/events/1/cover-a.jpg");
                    assertThat(urls.urlOf("events/2/cover-b.jpg")).isEqualTo("https://media/events/2/cover-b.jpg");
                    assertThat(urls.byKey()).hasSize(2);
                })
                .expectComplete().verify(Duration.ofSeconds(10));

        verify(mediaStore).urls(List.of("events/1/cover-a.jpg", "events/2/cover-b.jpg"));
    }

    @Test
    void signCovers_shouldNotCallWelyMedia_whenNothingIsStored() {
        StepVerifier.create(service.signCovers(List.of(coveredBy("/demo/events/picnic.jpg"), coveredBy(null))))
                .assertNext(urls -> assertThat(urls.byKey()).isEmpty())
                .expectComplete().verify(Duration.ofSeconds(10));

        verify(mediaStore, never()).urls(any());
    }

    @Test
    void signCovers_shouldAskInCallsOfAtMost200Keys() {
        signsEverything();
        List<Event> events = IntStream.range(0, 450).mapToObj(i -> coveredBy("events/" + i + "/cover.jpg")).toList();

        StepVerifier.create(service.signCovers(events))
                .assertNext(urls -> assertThat(urls.byKey()).hasSize(450))
                .expectComplete().verify(Duration.ofSeconds(10));

        verify(mediaStore, times(3)).urls(any());
    }

    @Test
    void signCovers_shouldDropTheUrls_ratherThanFail_whenWelyMediaIsDown() {
        when(mediaStore.urls(any())).thenReturn(Mono.error(new TechnicalException(TechnicalErrorCode.MEDIA_UNAVAILABLE)));

        StepVerifier.create(service.signCovers(List.of(coveredBy("events/1/cover-a.jpg"))))
                .assertNext(urls -> assertThat(urls.urlOf("events/1/cover-a.jpg")).isNull())
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void signReceipts_shouldSignTheReceiptsInOneCall() {
        when(mediaStore.urls(List.of("events/ev/receipts/x1-a.jpg"))).thenReturn(Mono.just(Map.of(
                "events/ev/receipts/x1-a.jpg", "https://media/r")));
        Expense withReceipt = new Expense("x1", "ev", "Courses", 1000, "EUR", "alice", "alice", ExpenseKind.EXPENSE,
                SplitMode.EQUAL, List.of(new Share("alice", 0)), Instant.EPOCH, "events/ev/receipts/x1-a.jpg", null, 0L);
        Expense without = new Expense("x2", "ev", "Essence", 1000, "EUR", "alice", "alice", ExpenseKind.EXPENSE,
                SplitMode.EQUAL, List.of(new Share("alice", 0)), Instant.EPOCH, null, null, 0L);

        StepVerifier.create(service.signReceipts(List.of(withReceipt, without)))
                .assertNext(urls -> assertThat(urls.urlOf("events/ev/receipts/x1-a.jpg")).isEqualTo("https://media/r"))
                .expectComplete().verify(Duration.ofSeconds(10));
    }
}
