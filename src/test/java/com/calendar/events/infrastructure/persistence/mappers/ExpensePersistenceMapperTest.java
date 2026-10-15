package com.calendar.events.infrastructure.persistence.mappers;

import com.calendar.events.domain.models.Expense;
import com.calendar.events.domain.models.ExpenseKind;
import com.calendar.events.domain.models.Share;
import com.calendar.events.domain.models.SplitMode;
import com.calendar.events.infrastructure.persistence.entities.ExpenseEntity;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExpensePersistenceMapperTest {

    private final ExpensePersistenceMapper mapper = Mappers.getMapper(ExpensePersistenceMapper.class);

    private final Expense expense = new Expense("x1", "ev-1", "Courses", 1250, "EUR", "alice", "bob",
            ExpenseKind.SETTLEMENT, SplitMode.EXACT, List.of(new Share("carol", 1250)),
            Instant.parse("2026-10-13T10:00:00Z"), "events/ev-1/receipts/x1-a.jpg", "bob", 7L);

    @Test
    void toEntity_shouldStoreTheEnumsAsText() {
        ExpenseEntity entity = mapper.toEntity(expense);

        assertThat(entity.getKind()).isEqualTo("SETTLEMENT");
        assertThat(entity.getSplit()).isEqualTo("EXACT");
        assertThat(entity.getShares()).containsExactly(new ExpenseEntity.ShareEntity("carol", 1250));
        assertThat(entity.getVersion()).isEqualTo(7L);
        assertThat(entity.getReceiptKey()).isEqualTo("events/ev-1/receipts/x1-a.jpg");
        assertThat(entity.getReceiptAddedBy()).isEqualTo("bob");
    }

    @Test
    void toDomain_shouldRoundTrip() {
        assertThat(mapper.toDomain(mapper.toEntity(expense))).isEqualTo(expense);
    }
}
