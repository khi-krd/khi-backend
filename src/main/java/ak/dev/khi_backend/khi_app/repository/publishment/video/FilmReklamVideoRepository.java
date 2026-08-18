package ak.dev.khi_backend.khi_app.repository.publishment.video;

import ak.dev.khi_backend.khi_app.model.publishment.video.FilmReklamVideo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FilmReklamVideoRepository extends JpaRepository<FilmReklamVideo, Long> {

    /** The film reklam video is a singleton — this is the only way it is ever read. */
    Optional<FilmReklamVideo> findTopByOrderByIdAsc();
}
