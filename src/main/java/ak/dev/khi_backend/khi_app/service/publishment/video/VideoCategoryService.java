package ak.dev.khi_backend.khi_app.service.publishment.video;

import ak.dev.khi_backend.khi_app.exceptions.Errors;
import ak.dev.khi_backend.khi_app.model.publishment.video.VideoCategory;
import ak.dev.khi_backend.khi_app.model.publishment.video.VideoType;
import ak.dev.khi_backend.khi_app.repository.publishment.video.VideoCategoryRepository;
import ak.dev.khi_backend.khi_app.service.publishment.writing.ManagedOptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class VideoCategoryService extends ManagedOptionService<VideoCategory> {

    private final VideoCategoryRepository repository;

    @Override protected VideoCategoryRepository repository() { return repository; }
    @Override protected VideoCategory newRow() { return new VideoCategory(); }
    @Override protected String kind() { return "CATEGORY"; }

    /** The seeded فیلم / ڤیدیۆ کلیپ rows back the editor layout — rename only. */
    @Override
    @Transactional
    public void delete(Long id) {
        repository.findById(id).ifPresent(row -> {
            for (VideoType t : VideoType.values()) {
                if (t.name().equals(row.getSlug())) {
                    throw Errors.conflict("option.protected", Map.of("kind", kind()));
                }
            }
        });
        super.delete(id);
    }
}
