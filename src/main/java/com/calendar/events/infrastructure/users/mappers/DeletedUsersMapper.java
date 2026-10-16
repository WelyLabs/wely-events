package com.calendar.events.infrastructure.users.mappers;

import com.calendar.events.domain.models.DeletedUser;
import com.calendar.events.domain.models.DeletedUsersPage;
import com.calendar.events.infrastructure.users.models.DeletedUserResponse;
import com.calendar.events.infrastructure.users.models.DeletedUsersPageResponse;
import org.mapstruct.Mapper;

/** wely-users' listing to domain tombstones; a UUID becomes its canonical lowercase form, as in tokens. */
@Mapper(componentModel = "spring")
public interface DeletedUsersMapper {

    DeletedUser toDomain(DeletedUserResponse item);

    DeletedUsersPage toDomain(DeletedUsersPageResponse page);
}
