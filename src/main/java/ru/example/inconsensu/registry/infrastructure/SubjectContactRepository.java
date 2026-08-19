package ru.example.inconsensu.registry.infrastructure;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.example.inconsensu.registry.domain.SubjectContact;

public interface SubjectContactRepository extends JpaRepository<SubjectContact, UUID> {

    /** Порционный обход для перешифрования (NFR-3). */
    Page<SubjectContact> findAllByOrderByIdAsc(Pageable pageable);
}
