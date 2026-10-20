package ak.dev.khi_backend.khi_app.repository.publishment.sound;

import ak.dev.khi_backend.khi_app.model.publishment.sound.SoundTrackType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SoundTrackTypeRepository extends JpaRepository<SoundTrackType, Long> {

    Optional<SoundTrackType> findByNameCkb(String nameCkb);

    List<SoundTrackType> findAllByOrderByNameCkbAsc();
}
