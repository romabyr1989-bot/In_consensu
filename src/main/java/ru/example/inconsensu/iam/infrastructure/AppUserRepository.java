package ru.example.inconsensu.iam.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.example.inconsensu.iam.domain.AppUser;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    Optional<AppUser> findByLoginIgnoreCase(String login);

    boolean existsByLoginIgnoreCase(String login);

    /** Recipients of notifications addressed to a role (FR-9.2). */
    @Query(
            "select u from AppUser u join u.roles r where r.code in :roleCodes and u.active = true and u.email is not null")
    List<AppUser> findActiveByRoleCodes(@Param("roleCodes") List<String> roleCodes);
}
