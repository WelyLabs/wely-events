package com.calendar.events.application.dtos;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LeaveRequestTest {

    @Test
    void confirmsDeletion_shouldBeNo_whenLeftOut() {
        assertThat(new LeaveRequest("tom", null).confirmsDeletion()).isFalse();
    }

    @Test
    void confirmsDeletion_shouldFollowTheFlag_whenGiven() {
        assertThat(new LeaveRequest(null, true).confirmsDeletion()).isTrue();
        assertThat(new LeaveRequest(null, false).confirmsDeletion()).isFalse();
    }
}
