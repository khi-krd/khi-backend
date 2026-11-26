package ak.dev.khi_backend.khi_app.service.publishment;

import ak.dev.khi_backend.khi_app.model.publishment.sound.SoundState;
import ak.dev.khi_backend.khi_app.model.publishment.video.VideoCategory;
import ak.dev.khi_backend.khi_app.model.publishment.writing.ManagedOption;
import ak.dev.khi_backend.khi_app.repository.publishment.sound.SoundStateRepository;
import ak.dev.khi_backend.khi_app.repository.publishment.video.VideoCategoryRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds the managed type bars and backfills records that predate them:
 *
 *   video_categories (جۆری ڤیدیۆ) → فیلم (FILM), ڤیدیۆ کلیپ (VIDEO_CLIP)
 *   sound_states     (جۆری دەنگ)  → تاک (SINGLE), ئەلبووم (MULTI)
 *
 * The seeded slugs equal the structural enum names, so a missing seed row is
 * added on boot and every untyped record is linked to the row matching its
 * structural type. Admin-added rows and existing assignments are never
 * touched.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MediaTypeOptionsSeeder implements ApplicationRunner {

    private final VideoCategoryRepository videoCategoryRepository;
    private final SoundStateRepository    soundStateRepository;
    private final EntityManager           em;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seed(videoCategoryRepository.findBySlug("FILM").isEmpty(),
                () -> videoCategoryRepository.save(fill(new VideoCategory(), "FILM", "فیلم", "Fîlm", 0)));
        seed(videoCategoryRepository.findBySlug("VIDEO_CLIP").isEmpty(),
                () -> videoCategoryRepository.save(fill(new VideoCategory(), "VIDEO_CLIP", "ڤیدیۆ کلیپ", "Klîpa vîdyoyê", 1)));
        seed(soundStateRepository.findBySlug("SINGLE").isEmpty(),
                () -> soundStateRepository.save(fill(new SoundState(), "SINGLE", "تاک", "Tek", 0)));
        seed(soundStateRepository.findBySlug("MULTI").isEmpty(),
                () -> soundStateRepository.save(fill(new SoundState(), "MULTI", "ئەلبووم", "Albûm", 1)));
        em.flush();

        int videos = em.createNativeQuery("""
                UPDATE videos SET category_id =
                    (SELECT c.id FROM video_categories c WHERE c.slug = videos.video_type)
                WHERE category_id IS NULL
                  AND EXISTS (SELECT 1 FROM video_categories c WHERE c.slug = videos.video_type)
                """).executeUpdate();
        int sounds = em.createNativeQuery("""
                UPDATE sound_tracks SET state_option_id =
                    (SELECT o.id FROM sound_states o WHERE o.slug = sound_tracks.track_state)
                WHERE state_option_id IS NULL
                  AND EXISTS (SELECT 1 FROM sound_states o WHERE o.slug = sound_tracks.track_state)
                """).executeUpdate();
        if (videos + sounds > 0) {
            log.info("Backfilled type on {} video(s) and {} sound track(s)", videos, sounds);
        }
    }

    private static void seed(boolean missing, Runnable save) {
        if (missing) save.run();
    }

    private static <T extends ManagedOption> T fill(T row, String slug, String ckb, String kmr, int order) {
        row.setSlug(slug);
        row.setNameCkb(ckb);
        row.setNameKmr(kmr);
        row.setDisplayOrder(order);
        row.setActive(true);
        return row;
    }
}
