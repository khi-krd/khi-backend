package ak.dev.khi_backend.khi_app.repository.project;

import ak.dev.khi_backend.khi_app.model.project.ProjectType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProjectTypeRepository extends JpaRepository<ProjectType, Long> {

    Optional<ProjectType> findByNameCkb(String nameCkb);

    List<ProjectType> findAllByOrderByNameCkbAsc();
}
