package com.calendar.events.infrastructure.persistence.mappers;

import com.calendar.events.domain.models.Expense;
import com.calendar.events.infrastructure.persistence.entities.ExpenseEntity;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface ExpensePersistenceMapper {

    Expense toDomain(ExpenseEntity entity);

    ExpenseEntity toEntity(Expense domain);
}
