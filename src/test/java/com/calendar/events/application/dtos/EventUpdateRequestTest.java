package com.calendar.events.application.dtos;

import com.calendar.events.domain.models.Visibility;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class EventUpdateRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void shouldAcceptATitleAndALocation_withoutTheRest() {
        assertThat(validator.validate(new EventUpdateRequest("Afterwork", "Ninkasi", null, null, null))).isEmpty();
    }

    @Test
    void shouldRefuseABlankTitleOrLocation() {
        Set<ConstraintViolation<EventUpdateRequest>> violations =
                validator.validate(new EventUpdateRequest(" ", "", null, "d", Visibility.PUBLIC));

        assertThat(violations).extracting(ConstraintViolation::getMessage)
                .containsExactlyInAnyOrder("Title is required", "Location is required");
    }

    @Test
    void shouldRefuseCoordinatesOutOfRange() {
        assertThat(validator.validate(new EventUpdateRequest("T", "L", new CoordinatesDto(91.0, 0.0), null, null))).hasSize(1);
    }
}
