package ak.dev.khi_backend.khi_app.repository.publishment.topic;

import ak.dev.khi_backend.khi_app.model.publishment.topic.PublishmentTopic;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * PublishmentTopicRepository
 *
 * ─── Autocomplete ─────────────────────────────────────────────────────────────
 * The frontend sends whatever the admin typed in the topic field.
 * The backend calls searchByNameCkb / searchByNameKmr with that text
 * (partial, case-insensitive LIKE), and returns the matching topics so the
 * admin can pick an existing one instead of creating a near-duplicate.
 *
 * entityType values:  "VIDEO" | "SOUND" | "IMAGE" | "WRITING"
 */
@Repository
public interface PublishmentTopicRepository extends JpaRepository<PublishmentTopic, Long> {
    List<PublishmentTopic> findByEntityType(String entityType);

    /**
     * Removes every many-to-many link to this topic across all four entity
     * join tables. The Hibernate-generated FKs are NO ACTION, so linked
     * topics cannot be deleted until these rows are gone.
     */
    @Modifying
    @Query(value = "DELETE FROM sound_track_topics WHERE topic_id = :id", nativeQuery = true)
    int unlinkFromSoundTracks(@Param("id") Long id);

    @Modifying
    @Query(value = "DELETE FROM video_topics WHERE topic_id = :id", nativeQuery = true)
    int unlinkFromVideos(@Param("id") Long id);

    @Modifying
    @Query(value = "DELETE FROM writing_topics WHERE topic_id = :id", nativeQuery = true)
    int unlinkFromWritings(@Param("id") Long id);

    @Modifying
    @Query(value = "DELETE FROM image_collection_topics WHERE topic_id = :id", nativeQuery = true)
    int unlinkFromCollections(@Param("id") Long id);
}
