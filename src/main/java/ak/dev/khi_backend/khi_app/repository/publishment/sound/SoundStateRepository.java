package ak.dev.khi_backend.khi_app.repository.publishment.sound;

import ak.dev.khi_backend.khi_app.model.publishment.sound.SoundState;
import ak.dev.khi_backend.khi_app.repository.publishment.writing.ManagedOptionRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SoundStateRepository extends ManagedOptionRepository<SoundState> {

    @Query("SELECT s.stateOption.id, COUNT(s) FROM SoundTrack s WHERE s.stateOption IS NOT NULL GROUP BY s.stateOption.id")
    List<Object[]> countBooksPerOption();

    @Modifying
    @Query("UPDATE SoundTrack s SET s.stateOption = null WHERE s.stateOption.id = :id")
    void detachFromBooks(@Param("id") Long id);
}
