package ak.dev.khi_backend.khi_app.service.home;

import ak.dev.khi_backend.khi_app.model.news.News;
import ak.dev.khi_backend.khi_app.model.project.Project;
import ak.dev.khi_backend.khi_app.model.publishment.image.ImageCollection;
import ak.dev.khi_backend.khi_app.model.publishment.sound.SoundTrack;
import ak.dev.khi_backend.khi_app.model.publishment.video.Video;
import ak.dev.khi_backend.khi_app.model.publishment.video.VideoType;
import ak.dev.khi_backend.khi_app.model.publishment.writing.Writing;
import ak.dev.khi_backend.khi_app.repository.news.NewsRepository;
import ak.dev.khi_backend.khi_app.repository.project.ProjectRepository;
import ak.dev.khi_backend.khi_app.repository.publishment.image.ImageCollectionRepository;
import ak.dev.khi_backend.khi_app.repository.publishment.sound.SoundTrackRepository;
import ak.dev.khi_backend.khi_app.repository.publishment.video.VideoRepository;
import ak.dev.khi_backend.khi_app.repository.publishment.writing.WritingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Popular-first homepage selection.
 *
 * Every homepage section keeps its existing item limit. Eligible records are
 * split into popular ({@code isPopular = true}) and regular; popular records
 * are rotated through a fixed time bucket so different popular items surface
 * over time without a random query per request, then remaining slots are
 * filled with regular records in the same newest-first order the public
 * listings already use. No duplicates, never more than {@code limit} items.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HomePopularService {

    /** Popular rotation bucket — selections shift every 6 hours. */
    private static final long ROTATE_BUCKET_MS = Duration.ofHours(6).toMillis();

    private final NewsRepository newsRepository;
    private final ProjectRepository projectRepository;
    private final VideoRepository videoRepository;
    private final SoundTrackRepository soundTrackRepository;
    private final WritingRepository writingRepository;
    private final ImageCollectionRepository imageCollectionRepository;

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "id");

    public List<?> section(String section, VideoType videoType, int limit) {
        return switch (section) {
            case "news" -> pick(newsRepository.findAll(NEWEST_FIRST), News::isPopular, News::getId, limit);
            case "projects" -> pick(projectRepository.findAll(NEWEST_FIRST), Project::isPopular, Project::getId,
                    Math.max(limit, 1000));
            case "writings" -> pick(writingRepository.findAll(NEWEST_FIRST), Writing::isPopular, Writing::getId, limit);
            case "sounds" -> pick(soundTrackRepository.findAll(NEWEST_FIRST), SoundTrack::isPopular,
                    SoundTrack::getId, limit);
            case "collections" -> pick(imageCollectionRepository.findAll(NEWEST_FIRST),
                    ImageCollection::isPopular, ImageCollection::getId, limit);
            case "videos" -> {
                List<Video> videos = videoRepository.findAll(NEWEST_FIRST).stream()
                        .filter(v -> videoType == null || v.getVideoType() == videoType)
                        .toList();
                yield pick(videos, Video::isPopular, Video::getId, limit);
            }
            default -> List.of();
        };
    }

    /**
     * Popular-first selection with time-bucketed rotation.
     *
     * @param all       eligible records, already in fallback (newest-first) order
     * @param isPopular popularity predicate
     * @param id        stable id extractor for de-duplication
     * @param limit     maximum number of items to return
     */
    private <T> List<T> pick(List<T> all, Predicate<T> isPopular, Function<T, Long> id, int limit) {
        if (limit <= 0 || all.isEmpty()) {
            return List.of();
        }

        List<T> popular = new ArrayList<>();
        List<T> regular = new ArrayList<>();
        for (T item : all) {
            (isPopular.test(item) ? popular : regular).add(item);
        }

        List<T> selected = new ArrayList<>(Math.min(limit, all.size()));
        Set<Long> seen = new LinkedHashSet<>();

        // Rotate the popular window: every bucket shifts the starting offset by
        // one, so with P popular records and limit L the visible subset cycles
        // deterministically without any per-request randomness.
        if (!popular.isEmpty()) {
            int take = Math.min(limit, popular.size());
            long bucket = System.currentTimeMillis() / ROTATE_BUCKET_MS;
            int offset = (int) Math.floorMod(bucket, popular.size());
            for (int i = 0; i < take; i++) {
                T item = popular.get((offset + i) % popular.size());
                if (seen.add(id.apply(item))) {
                    selected.add(item);
                }
            }
        }

        for (T item : regular) {
            if (selected.size() >= limit) {
                break;
            }
            if (seen.add(id.apply(item))) {
                selected.add(item);
            }
        }

        return selected;
    }
}
