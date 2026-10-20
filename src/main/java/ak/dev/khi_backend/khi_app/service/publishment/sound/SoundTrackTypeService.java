package ak.dev.khi_backend.khi_app.service.publishment.sound;

import ak.dev.khi_backend.khi_app.dto.publishment.sound.SoundTrackTypeDtos.SoundTrackTypeRequest;
import ak.dev.khi_backend.khi_app.dto.publishment.sound.SoundTrackTypeDtos.SoundTrackTypeResponse;
import ak.dev.khi_backend.khi_app.exceptions.BadRequestException;
import ak.dev.khi_backend.khi_app.exceptions.ResourceNotFoundException;
import ak.dev.khi_backend.khi_app.model.publishment.sound.SoundTrackType;
import ak.dev.khi_backend.khi_app.repository.publishment.sound.SoundTrackRepository;
import ak.dev.khi_backend.khi_app.repository.publishment.sound.SoundTrackTypeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Admin CRUD for the sound-type vocabulary. Tracks store the type as a
 * free-text {@code soundType} column; renaming a type here rewrites the
 * matching track rows so the text stays in sync, while deleting a type only
 * removes the suggestion from the picker — tracks that already carry the
 * name keep it (it is still displayed verbatim).
 */
@Service
@RequiredArgsConstructor
public class SoundTrackTypeService {

    private final SoundTrackTypeRepository soundTrackTypeRepository;
    private final SoundTrackRepository soundTrackRepository;

    @Transactional(readOnly = true)
    public List<SoundTrackTypeResponse> list() {
        return soundTrackTypeRepository.findAllByOrderByNameCkbAsc().stream()
                .map(this::toResponse)
                .toList();
    }

    @CacheEvict(value = "soundTracks", allEntries = true)
    @Transactional
    public SoundTrackTypeResponse create(SoundTrackTypeRequest request) {
        String ckb = requireName(request == null ? null : request.getNameCkb());
        String kmr = kmrOr(request == null ? null : request.getNameKmr(), ckb);
        soundTrackTypeRepository.findByNameCkb(ckb).ifPresent(existing -> {
            throw new BadRequestException("soundTrack.type.duplicate", ckb);
        });
        return toResponse(soundTrackTypeRepository.save(SoundTrackType.builder()
                .nameCkb(ckb)
                .nameKmr(kmr)
                .build()));
    }

    @CacheEvict(value = "soundTracks", allEntries = true)
    @Transactional
    public SoundTrackTypeResponse update(Long id, SoundTrackTypeRequest request) {
        SoundTrackType type = soundTrackTypeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("soundTrack.type.notFound", id));
        String ckb = requireName(request == null ? null : request.getNameCkb());
        String kmr = kmrOr(request == null ? null : request.getNameKmr(), ckb);
        soundTrackTypeRepository.findByNameCkb(ckb).ifPresent(existing -> {
            if (!existing.getId().equals(id)) {
                throw new BadRequestException("soundTrack.type.duplicate", ckb);
            }
        });
        String oldCkb = type.getNameCkb();
        type.setNameCkb(ckb);
        type.setNameKmr(kmr);
        if (!oldCkb.equalsIgnoreCase(ckb)) {
            soundTrackRepository.renameSoundType(oldCkb, ckb);
        }
        return toResponse(soundTrackTypeRepository.save(type));
    }

    @CacheEvict(value = "soundTracks", allEntries = true)
    @Transactional
    public void delete(Long id) {
        SoundTrackType type = soundTrackTypeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("soundTrack.type.notFound", id));
        soundTrackTypeRepository.delete(type);
    }

    /**
     * Register a typed-new sound type into the vocabulary — called when a
     * track is saved with a type that is not in the picker list yet, so the
     * vocabulary grows with use. No-op on duplicates.
     */
    @Transactional
    public void ensureExists(String nameCkb) {
        String ckb = nameCkb == null ? "" : nameCkb.trim();
        if (ckb.isEmpty()) return;
        soundTrackTypeRepository.findByNameCkb(ckb).orElseGet(() ->
                soundTrackTypeRepository.save(SoundTrackType.builder()
                        .nameCkb(ckb)
                        .nameKmr(ckb)
                        .build()));
    }

    private SoundTrackTypeResponse toResponse(SoundTrackType type) {
        return SoundTrackTypeResponse.builder()
                .id(type.getId())
                .nameCkb(type.getNameCkb())
                .nameKmr(type.getNameKmr())
                .trackCount(soundTrackRepository.countBySoundType(type.getNameCkb()))
                .build();
    }

    private String requireName(String raw) {
        String ckb = raw == null ? "" : raw.trim();
        if (ckb.isEmpty()) {
            throw new BadRequestException("soundTrack.type.nameRequired");
        }
        return ckb;
    }

    private String kmrOr(String raw, String fallback) {
        String kmr = raw == null ? "" : raw.trim();
        return kmr.isEmpty() ? fallback : kmr;
    }
}
