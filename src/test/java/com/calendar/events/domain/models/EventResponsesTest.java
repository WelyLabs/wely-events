package com.calendar.events.domain.models;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventResponsesTest {

    private static Event communityEvent(List<String> participants, Map<String, PollAnswer> responses) {
        return Event.builder().id("e").organizerId("org").communityId("c")
                .participantIds(new ArrayList<>(participants)).responses(responses).build();
    }

    @Test
    void of_shouldBeNull_forAnEventOutsideAnyCommunity() {
        assertThat(EventResponses.of(Event.builder().id("e").organizerId("org").build())).isNull();
    }

    @Test
    void of_shouldCountEveryParticipantAsYes_andSortTheOtherAnswers() {
        Map<String, PollAnswer> answers = new LinkedHashMap<>();
        answers.put("ana", PollAnswer.MAYBE);
        answers.put("bob", PollAnswer.NO);
        answers.put("cyd", PollAnswer.MAYBE);

        EventResponses responses = EventResponses.of(communityEvent(List.of("org", "dan"), answers));

        assertThat(responses.yes()).containsExactly("org", "dan");
        assertThat(responses.maybe()).containsExactly("ana", "cyd");
        assertThat(responses.no()).containsExactly("bob");
    }

    @Test
    void of_shouldNotCountAKeptAnswerTwice_onceTheMemberJoined() {
        // Joined through another path (a poll's fix) after answering maybe: taking part wins.
        EventResponses responses = EventResponses.of(communityEvent(List.of("org", "ana"), Map.of("ana", PollAnswer.MAYBE)));

        assertThat(responses.yes()).containsExactly("org", "ana");
        assertThat(responses.maybe()).isEmpty();
    }

    @Test
    void of_shouldReadNoStoredAnswersAsNone() {
        EventResponses responses = EventResponses.of(communityEvent(List.of("org"), null));

        assertThat(responses.maybe()).isEmpty();
        assertThat(responses.no()).isEmpty();
    }

    @Test
    void shouldCopyItsLists() {
        List<String> yes = new ArrayList<>(List.of("a"));
        EventResponses responses = new EventResponses(yes, List.of(), List.of());
        yes.add("b");

        assertThat(responses.yes()).containsExactly("a");
        assertThatThrownBy(() -> responses.yes().add("c")).isInstanceOf(UnsupportedOperationException.class);
    }
}
