package ru.example.inconsensu.iam.infrastructure;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.example.inconsensu.iam.domain.OperatorSetting;

public interface OperatorSettingRepository extends JpaRepository<OperatorSetting, String> {

    List<OperatorSetting> findAllByOrderByKeyAsc();
}
