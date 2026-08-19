package ru.example.cus.iam.infrastructure;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.example.cus.iam.domain.AppRole;

public interface AppRoleRepository extends JpaRepository<AppRole, UUID> {

    Optional<AppRole> findByCode(String code);

    List<AppRole> findByCodeIn(Collection<String> codes);

    List<AppRole> findAllByOrderByCodeAsc();
}
