package ak.dev.khi_backend.khi_app.service.publishment.sound;

import ak.dev.khi_backend.khi_app.enums.publishment.TrackState;
import ak.dev.khi_backend.khi_app.exceptions.Errors;
import ak.dev.khi_backend.khi_app.model.publishment.sound.SoundState;
import ak.dev.khi_backend.khi_app.repository.publishment.sound.SoundStateRepository;
import ak.dev.khi_backend.khi_app.service.publishment.writing.ManagedOptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class SoundStateService extends ManagedOptionService<SoundState> {

    private final SoundStateRepository repository;

    @Override protected SoundStateRepository repository() { return repository; }
    @Override protected SoundState newRow() { return new SoundState(); }
    @Override protected String kind() { return "SOUND_STATE"; }

    /** The seeded تاک / ئەلبووم rows back the editor layout — rename only. */
    @Override
    @Transactional
    public void delete(Long id) {
        repository.findById(id).ifPresent(row -> {
            for (TrackState s : TrackState.values()) {
                if (s.name().equals(row.getSlug())) {
                    throw Errors.conflict("option.protected", Map.of("kind", kind()));
                }
            }
        });
        super.delete(id);
    }
}
