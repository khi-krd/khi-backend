    package ak.dev.khi_backend.khi_app.service.publishment.topic;

    import ak.dev.khi_backend.khi_app.model.publishment.topic.PublishmentTopic;
    import ak.dev.khi_backend.khi_app.repository.publishment.topic.PublishmentTopicRepository;
    import jakarta.persistence.EntityManager;
    import jakarta.persistence.PersistenceContext;
    import lombok.RequiredArgsConstructor;
    import lombok.extern.slf4j.Slf4j;
    import org.springframework.stereotype.Service;
    import org.springframework.transaction.annotation.Transactional;

    import java.util.HashMap;
    import java.util.List;
    import java.util.Map;

    @Slf4j
    @Service
    @RequiredArgsConstructor
    public class PublishmentTopicService {

        private final PublishmentTopicRepository topicRepository;

        @PersistenceContext
        private EntityManager entityManager;

        /**
         * Record count per topic for the filter dropdowns — one grouped query
         * on the join table, no per-topic round trips.
         */
        @Transactional(readOnly = true)
        public Map<Long, Long> getTopicCounts(String entityType) {
            String sql = switch (entityType == null ? "" : entityType.toUpperCase()) {
                case "VIDEO" -> "SELECT topic_id, COUNT(DISTINCT video_id) FROM video_topics GROUP BY topic_id";
                case "SOUND" -> "SELECT topic_id, COUNT(DISTINCT sound_track_id) FROM sound_track_topics GROUP BY topic_id";
                case "WRITING" -> "SELECT topic_id, COUNT(DISTINCT writing_id) FROM writing_topics GROUP BY topic_id";
                case "IMAGE" -> "SELECT topic_id, COUNT(DISTINCT collection_id) FROM image_collection_topics GROUP BY topic_id";
                default -> null;
            };
            if (sql == null) {
                return Map.of();
            }
            Map<Long, Long> counts = new HashMap<>();
            for (Object row : entityManager.createNativeQuery(sql).getResultList()) {
                Object[] pair = (Object[]) row;
                counts.put(((Number) pair[0]).longValue(), ((Number) pair[1]).longValue());
            }
            return counts;
        }

        // ─── GET ALL BY TYPE ──────────────────────────────────────────────────────

        @Transactional(readOnly = true)
        public List<PublishmentTopic> getAllByType(String entityType) {
            log.info("Fetching all topics for entityType={}", entityType);
            return topicRepository.findByEntityType(entityType.toUpperCase());
        }

        // ─── CREATE ───────────────────────────────────────────────────────────────

        @Transactional
        public PublishmentTopic create(String entityType, String nameCkb, String nameKmr) {
            log.info("Creating topic entityType={} nameCkb={} nameKmr={}", entityType, nameCkb, nameKmr);

            return topicRepository.findOrCreate(entityType, nameCkb, nameKmr);
        }

        // ─── UPDATE ───────────────────────────────────────────────────────────────

        @Transactional
        public PublishmentTopic update(Long id, String nameCkb, String nameKmr) {
            log.info("Updating topic id={}", id);

            PublishmentTopic topic = topicRepository.findById(id)
                    .orElseThrow(() -> new RuntimeException("Topic not found: " + id));

            if (nameCkb != null && !nameCkb.isBlank()
                    && topicRepository.findByTypeAndNameCkb(topic.getEntityType(), nameCkb).stream()
                            .anyMatch(t -> !t.getId().equals(id))) {
                throw ak.dev.khi_backend.khi_app.exceptions.Errors.conflict("topic.name_taken", java.util.Map.of());
            }
            if (nameCkb != null) topic.setNameCkb(nameCkb);
            if (nameKmr != null) topic.setNameKmr(nameKmr);

            return topicRepository.save(topic);
        }

        // ─── DELETE ───────────────────────────────────────────────────────────────

        @Transactional
        public void delete(Long id) {
            log.info("Deleting topic id={}", id);
            if (!topicRepository.existsById(id)) {
                throw new RuntimeException("Topic not found: " + id);
            }
            topicRepository.unlinkFromSoundTracks(id);
            topicRepository.unlinkFromVideos(id);
            topicRepository.unlinkFromWritings(id);
            topicRepository.unlinkFromCollections(id);
            topicRepository.deleteById(id);
        }

        // ─── GET ONE ──────────────────────────────────────────────────────────────

        @Transactional(readOnly = true)
        public PublishmentTopic getById(Long id) {
            return topicRepository.findById(id)
                    .orElseThrow(() -> new RuntimeException("Topic not found: " + id));
        }

        // ─── FIND OR CREATE (used by SoundTrackService / VideoService) ────────────

        /**
         * Called internally when the editor submits a "newTopic" inline.
         * If a topic with the same entityType + nameCkb already exists, reuse it.
         * Otherwise create a fresh one.
         */
        @Transactional
        public PublishmentTopic findOrCreate(String entityType, String nameCkb, String nameKmr) {
            String type = entityType.toUpperCase();

            // try to find existing by CKB name first, then KMR
            List<PublishmentTopic> existing = topicRepository.findByEntityType(type);

            if (nameCkb != null && !nameCkb.isBlank()) {
                return existing.stream()
                        .filter(t -> nameCkb.trim().equalsIgnoreCase(
                                t.getNameCkb() != null ? t.getNameCkb().trim() : ""))
                        .findFirst()
                        .orElseGet(() -> create(type, nameCkb.trim(),
                                nameKmr != null ? nameKmr.trim() : null));
            }

            if (nameKmr != null && !nameKmr.isBlank()) {
                return existing.stream()
                        .filter(t -> nameKmr.trim().equalsIgnoreCase(
                                t.getNameKmr() != null ? t.getNameKmr().trim() : ""))
                        .findFirst()
                        .orElseGet(() -> create(type, null, nameKmr.trim()));
            }

            throw new IllegalArgumentException("At least one of nameCkb or nameKmr must be provided");
        }
    }