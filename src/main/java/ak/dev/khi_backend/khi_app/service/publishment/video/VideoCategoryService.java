package ak.dev.khi_backend.khi_app.service.publishment.video;

import ak.dev.khi_backend.khi_app.model.publishment.video.VideoCategory;
import ak.dev.khi_backend.khi_app.repository.publishment.video.VideoCategoryRepository;
import ak.dev.khi_backend.khi_app.service.publishment.writing.ManagedOptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class VideoCategoryService extends ManagedOptionService<VideoCategory> {

    private final VideoCategoryRepository repository;

    @Override protected VideoCategoryRepository repository() { return repository; }
    @Override protected VideoCategory newRow() { return new VideoCategory(); }
    @Override protected String kind() { return "CATEGORY"; }
}
