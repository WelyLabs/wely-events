package com.calendar.events.infrastructure.persistence.adapters;

import com.calendar.events.domain.models.Expense;
import com.calendar.events.domain.ports.ExpenseRepository;
import com.calendar.events.exception.EventErrorCode;
import com.calendar.events.exception.EventException;
import com.calendar.events.infrastructure.persistence.mappers.ExpensePersistenceMapper;
import com.calendar.events.infrastructure.persistence.repositories.ReactiveExpenseMongoRepository;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Collection;

@Component
public class MongoExpenseRepositoryAdapter implements ExpenseRepository {

    private final ReactiveExpenseMongoRepository mongoRepository;
    private final ExpensePersistenceMapper mapper;

    public MongoExpenseRepositoryAdapter(ReactiveExpenseMongoRepository mongoRepository,
                                         ExpensePersistenceMapper mapper) {
        this.mongoRepository = mongoRepository;
        this.mapper = mapper;
    }

    @Override
    public Flux<Expense> findByEvent(String eventId) {
        return mongoRepository.findAllByEventIdOrderBySpentAtDesc(eventId).map(mapper::toDomain);
    }

    @Override
    public Mono<Expense> findByIdInEvent(String eventId, String expenseId) {
        return mongoRepository.findByIdAndEventId(expenseId, eventId).map(mapper::toDomain);
    }

    /** A stale version, or an update of an expense deleted meanwhile, is a conflict for the caller to resolve. */
    @Override
    public Mono<Expense> save(Expense expense) {
        return mongoRepository.save(mapper.toEntity(expense))
                .map(mapper::toDomain)
                .onErrorMap(OptimisticLockingFailureException.class,
                        error -> new EventException(EventErrorCode.EXPENSE_CONFLICT));
    }

    @Override
    public Mono<Void> delete(String eventId, String expenseId) {
        return mongoRepository.deleteByIdAndEventId(expenseId, eventId).then();
    }

    @Override
    public Mono<Void> deleteByEvent(String eventId) {
        return mongoRepository.deleteAllByEventId(eventId).then();
    }

    @Override
    public Flux<Expense> findByEvents(Collection<String> eventIds) {
        return mongoRepository.findAllByEventIdIn(eventIds).map(mapper::toDomain);
    }
}
