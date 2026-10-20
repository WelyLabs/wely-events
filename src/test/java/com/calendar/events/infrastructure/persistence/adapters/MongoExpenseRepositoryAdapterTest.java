package com.calendar.events.infrastructure.persistence.adapters;

import com.calendar.events.domain.models.Expense;
import com.calendar.events.exception.BusinessErrorCode;
import com.calendar.events.exception.BusinessException;
import com.calendar.events.infrastructure.persistence.entities.ExpenseEntity;
import com.calendar.events.infrastructure.persistence.mappers.ExpensePersistenceMapper;
import com.calendar.events.infrastructure.persistence.repositories.ReactiveExpenseMongoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MongoExpenseRepositoryAdapterTest {

    @Mock
    private ReactiveExpenseMongoRepository mongoRepository;

    @Mock
    private ExpensePersistenceMapper mapper;

    private MongoExpenseRepositoryAdapter adapter;

    private final Expense expense = mock(Expense.class);
    private final ExpenseEntity entity = new ExpenseEntity();

    @BeforeEach
    void setUp() {
        adapter = new MongoExpenseRepositoryAdapter(mongoRepository, mapper);
    }

    @Test
    void findByEvent_shouldReadTheEventsExpensesNewestFirst() {
        when(mongoRepository.findAllByEventIdOrderBySpentAtDesc("ev-1")).thenReturn(Flux.just(entity));
        when(mapper.toDomain(entity)).thenReturn(expense);

        StepVerifier.create(adapter.findByEvent("ev-1")).expectNext(expense).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void findByIdInEvent_shouldNameTheEventInTheQuery() {
        when(mongoRepository.findByIdAndEventId("x1", "ev-1")).thenReturn(Mono.just(entity));
        when(mapper.toDomain(entity)).thenReturn(expense);

        StepVerifier.create(adapter.findByIdInEvent("ev-1", "x1")).expectNext(expense).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void save_shouldStoreTheExpense() {
        when(mapper.toEntity(expense)).thenReturn(entity);
        when(mongoRepository.save(entity)).thenReturn(Mono.just(entity));
        when(mapper.toDomain(entity)).thenReturn(expense);

        StepVerifier.create(adapter.save(expense)).expectNext(expense).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void save_shouldReportAConflict_whenTheVersionIsStale() {
        when(mapper.toEntity(expense)).thenReturn(entity);
        when(mongoRepository.save(entity)).thenReturn(Mono.error(new OptimisticLockingFailureException("stale")));

        StepVerifier.create(adapter.save(expense))
                .expectErrorMatches(error -> error instanceof BusinessException exception
                        && exception.getErrorCode() == BusinessErrorCode.EXPENSE_CONFLICT)
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void delete_shouldNameTheEventInTheQuery() {
        when(mongoRepository.deleteByIdAndEventId("x1", "ev-1")).thenReturn(Mono.just(1L));

        StepVerifier.create(adapter.delete("ev-1", "x1")).expectComplete().verify(Duration.ofSeconds(10));

        verify(mongoRepository).deleteByIdAndEventId("x1", "ev-1");
    }

    @Test
    void deleteByEvent_shouldRemoveEveryExpenseOfTheEvent() {
        when(mongoRepository.deleteAllByEventId("ev-1")).thenReturn(Mono.just(3L));

        StepVerifier.create(adapter.deleteByEvent("ev-1")).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void findByEvents_shouldReadTheExpensesOfEveryEventAsked() {
        when(mongoRepository.findAllByEventIdIn(List.of("ev-1", "ev-2"))).thenReturn(Flux.just(entity));
        when(mapper.toDomain(entity)).thenReturn(expense);

        StepVerifier.create(adapter.findByEvents(List.of("ev-1", "ev-2"))).expectNext(expense).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void setReceipt_shouldReadTheExpenseBack_whenTheUpdateMatched() {
        when(mongoRepository.setReceiptKey("x1", "ev-1", "events/ev-1/receipts/r.jpg", "alice")).thenReturn(Mono.just(1L));
        when(mongoRepository.findByIdAndEventId("x1", "ev-1")).thenReturn(Mono.just(entity));
        when(mapper.toDomain(entity)).thenReturn(expense);

        StepVerifier.create(adapter.setReceipt("ev-1", "x1", "events/ev-1/receipts/r.jpg", "alice"))
                .expectNext(expense)
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void setReceipt_shouldBeEmpty_whenNoExpenseOfTheEventMatched() {
        when(mongoRepository.setReceiptKey("x1", "ev-1", null, null)).thenReturn(Mono.just(0L));

        StepVerifier.create(adapter.setReceipt("ev-1", "x1", null, null)).expectComplete().verify(Duration.ofSeconds(10));

        verify(mongoRepository, never()).findByIdAndEventId("x1", "ev-1");
    }

    @Test
    void findWithReceiptRecordedBy_shouldMapEveryExpense() {
        when(mongoRepository.findWithReceiptRecordedBy("gone")).thenReturn(Flux.just(entity));
        when(mapper.toDomain(entity)).thenReturn(expense);

        StepVerifier.create(adapter.findWithReceiptRecordedBy("gone")).expectNext(expense).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void clearReceipt_shouldAnswerTrue_whenTheKeyWasStillThere() {
        when(mongoRepository.clearReceiptKey("x1", "ev-1", "events/ev-1/receipts/r.jpg")).thenReturn(Mono.just(1L));

        StepVerifier.create(adapter.clearReceipt("ev-1", "x1", "events/ev-1/receipts/r.jpg")).expectNext(true).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void clearReceipt_shouldAnswerFalse_whenTheReceiptChangedMeanwhile() {
        when(mongoRepository.clearReceiptKey("x1", "ev-1", "events/ev-1/receipts/r.jpg")).thenReturn(Mono.just(0L));

        StepVerifier.create(adapter.clearReceipt("ev-1", "x1", "events/ev-1/receipts/r.jpg")).expectNext(false).expectComplete().verify(Duration.ofSeconds(10));
    }
}
