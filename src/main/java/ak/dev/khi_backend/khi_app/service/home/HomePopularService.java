package ak.dev.khi_backend.khi_app.service.home;

import ak.dev.khi_backend.khi_app.dto.news.NewsDto;
import ak.dev.khi_backend.khi_app.dto.project.ProjectResponse;
import ak.dev.khi_backend.khi_app.dto.publishment.image.ImageCollectionDTO;
import ak.dev.khi_backend.khi_app.dto.publishment.sound.SoundTrackDtos;
import ak.dev.khi_backend.khi_app.dto.publishment.video.VideoDTO;
import ak.dev.khi_backend.khi_app.dto.publishment.writing.WritingDtos;
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
import ak.dev.khi_backend.khi_app.service.news.NewsService;
import ak.dev.khi_backend.khi_app.service.project.ProjectService;
import ak.dev.khi_backend.khi_app.service.publishment.image.ImageCollectionService;
import ak.dev.khi_backend.khi_app.service.publishment.sound.SoundTrackService;
import ak.dev.khi_backend.khi_app.service.publishment.video.VideoService;
import ak.dev.khi_backend.khi_app.service.publishment.writing.WritingService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Popular-first homepage selection.
 *
 * Each module's existing list service supplies the DTOs (identical shape to
 * the public listing endpoints), while the repositories supply the set of
 * {@code isPopular} ids. Popular records are rotated through a fixed time
 * bucket so different popular items surface over time without a random query
 * per request; remaining slots are filled with regular records in the order
 * the listings already return. Never more than {@code limit} items, never a
 * duplicate.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HomePopularService {

    /** Popular rotation bucket — selections shift every 6 hours. */
    private static final long ROTATE_BUCKET_MS = Duration.ofHours(6).toMillis();
    private static final int FETCH_ALL = 1000;

    private final NewsService newsService;
    private final ProjectService projectService;
    private final VideoService videoService;
    private final SoundTrackService soundTrackService;
    private final WritingService writingService;
    private final ImageCollectionService imageCollectionService;

    private final NewsRepository newsRepository;
    private final ProjectRepository projectRepository;
    private final VideoRepository videoRepository;
    private final SoundTrackRepository soundTrackRepository;
    private final WritingRepository writingRepository;
    private final ImageCollectionRepository imageCollectionRepository;

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "id");

    public List<?> section(String section, VideoType videoType, int limit) {
        return switch (section) {
            case "news" -> pick(newsService.getAllNews(0, FETCH_ALL).getContent(),
                    popularIds(newsRepository.findAll(NEWEST_FIRST), News::isPopular, News::getId),
                    NewsDto::getId, limit);
            case "projects" -> pick(projectService.getAllResponse(0, FETCH_ALL).getContent(),
                    popularIds(projectRepository.findAll(NEWEST_FIRST), Project::isPopular, Project::getId),
                    ProjectResponse::getId, limit);
            case "writings" -> pick(writingService.getAllWritings(PageRequest.of(0, FETCH_ALL)).getContent(),
                    popularIds(writingRepository.findAll(NEWEST_FIRST), Writing::isPopular, Writing::getId),
                    WritingDtos.Response::getId, limit);
            case "sounds" -> pick(soundTrackService.getAll(0, FETCH_ALL).getContent(),
                    popularIds(soundTrackRepository.findAll(NEWEST_FIRST), SoundTrack::isPopular,
                            SoundTrack::getId),
                    SoundTrackDtos.Response::getId, limit);
            case "collections" -> pick(imageCollectionService.getAll(0, FETCH_ALL).getContent(),
                    popularIds(imageCollectionRepository.findAll(NEWEST_FIRST),
                            ImageCollection::isPopular, ImageCollection::getId),
                    ImageCollectionDTO.Response::getId, limit);
            case "videos" -> {
                List<VideoDTO> videos = videoService.getAllVideos(0, FETCH_ALL).getContent().stream()
                        .filter(v -> videoType == null || v.getVideoType() == videoType)
                        .toList();
                yield pick(videos,
                        popularIds(videoRepository.findAll(NEWEST_FIRST), Video::isPopular, Video::getId),
                        VideoDTO::getId, limit);
            }
            default -> List.of();
        };
    }

    /**
     * Random selection from the same published section lists the popular feed
     * draws from — shuffled per request, capped at {@code limit}, no
     * duplicates. Unpublished rows never reach these list services, so a
     * plain shuffle over them is safe.
     */
    public List<?> random(String section, VideoType videoType, int limit) {
        List<?> all = switch (section) {
            case "news" -> newsService.getAllNews(0, FETCH_ALL).getContent();
            case "projects" -> projectService.getAllResponse(0, FETCH_ALL).getContent();
            case "writings" -> writingService.getAllWritings(PageRequest.of(0, FETCH_ALL)).getContent();
            case "sounds" -> soundTrackService.getAll(0, FETCH_ALL).getContent();
            case "collections" -> imageCollectionService.getAll(0, FETCH_ALL).getContent();
            case "videos" -> videoService.getAllVideos(0, FETCH_ALL).getContent().stream()
                    .filter(v -> videoType == null || v.getVideoType() == videoType).toList();
            default -> List.of();
        };
        if (limit <= 0 || all.isEmpty()) {
            return List.of();
        }
        List<Object> shuffled = new ArrayList<>(all);
        Collections.shuffle(shuffled);
        return shuffled.subList(0, Math.min(limit, shuffled.size()));
    }

    private static <E> Set<Long> popularIds(List<E> all,
                                            java.util.function.Predicate<E> isPopular,
                                            Function<E, Long> id) {
        Set<Long> ids = new LinkedHashSet<>();
        for (E e : all) {
            if (isPopular.test(e)) {
                ids.add(id.apply(e));
            }
        }
        return ids;
    }

    /**
     * Popular-first selection with time-bucketed rotation over the popular
     * subset; regular records fill the remaining slots in listing order.
     */
    private <T> List<T> pick(List<T> all, Set<Long> popularIds,
                             Function<T, Long> id, int limit) {
        if (limit <= 0 || all == null || all.isEmpty()) {
            return List.of();
        }

        List<T> popular = new ArrayList<>();
        List<T> regular = new ArrayList<>();
        for (T item : all) {
            Long itemId = id.apply(item);
            (itemId != null && popularIds.contains(itemId) ? popular : regular).add(item);
        }

        List<T> selected = new ArrayList<>(Math.min(limit, all.size()));
        Set<Long> seen = new LinkedHashSet<>();

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
