    package ak.dev.khi_backend.khi_app.service.publishment.topic;

    import ak.dev.khi_backend.khi_app.model.publishment.topic.PublishmentTopic;
    import ak.dev.khi_backend.khi_app.repository.publishment.topic.PublishmentTopicRepository;
    import lombok.RequiredArgsConstructor;
    import lombok.extern.slf4j.Slf4j;
    import org.springframework.stereotype.Service;
    import org.springframework.transaction.annotation.Transactional;

    import java.util.List;

    @Slf4j
    @Service
    @RequiredArgsConstructor
    public class PublishmentTopicService {

        private final PublishmentTopicRepository topicRepository;

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

            PublishmentTopic topic = PublishmentTopic.builder()
                    .entityType(entityType.toUpperCase())
                    .nameCkb(nameCkb)
                    .nameKmr(nameKmr)
                    .build();

            return topicRepository.save(topic);
        }

        // ─── UPDATE ───────────────────────────────────────────────────────────────

        @Transactional
        public PublishmentTopic update(Long id, String nameCkb, String nameKmr) {
            log.info("Updating topic id={}", id);

            PublishmentTopic topic = topicRepository.findById(id)
                    .orElseThrow(() -> new RuntimeException("Topic not found: " + id));

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