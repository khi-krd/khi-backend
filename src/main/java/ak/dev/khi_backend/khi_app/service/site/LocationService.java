package ak.dev.khi_backend.khi_app.service.site;

import ak.dev.khi_backend.khi_app.dto.site.LocationDtos.LocationRequest;
import ak.dev.khi_backend.khi_app.dto.site.LocationDtos.LocationResponse;
import ak.dev.khi_backend.khi_app.exceptions.BadRequestException;
import ak.dev.khi_backend.khi_app.exceptions.ResourceNotFoundException;
import ak.dev.khi_backend.khi_app.model.site.Location;
import ak.dev.khi_backend.khi_app.repository.site.LocationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * CRUD for the location vocabulary. Options only — content records store
 * their location as text, so edits and deletes here leave them untouched.
 */
@Service
@RequiredArgsConstructor
public class LocationService {

    private final LocationRepository locationRepository;

    @Transactional(readOnly = true)
    public List<LocationResponse> list() {
        return locationRepository.findAllByOrderByDisplayOrderAscIdAsc().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public LocationResponse create(LocationRequest request) {
        String ckb = requireName(request == null ? null : request.getNameCkb());
        String kmr = trim(request == null ? null : request.getNameKmr());
        locationRepository.findByNameCkbIgnoreCase(ckb).ifPresent(existing -> {
            throw new BadRequestException("location.duplicate", ckb);
        });
        return toResponse(locationRepository.save(Location.builder()
                .nameCkb(ckb)
                .nameKmr(kmr)
                .displayOrder(locationRepository.findMaxDisplayOrder() + 1)
                .build()));
    }

    @Transactional
    public LocationResponse update(Long id, LocationRequest request) {
        Location location = locationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("location.notFound", id));
        String ckb = requireName(request == null ? null : request.getNameCkb());
        String kmr = trim(request == null ? null : request.getNameKmr());
        locationRepository.findByNameCkbIgnoreCase(ckb).ifPresent(existing -> {
            if (!existing.getId().equals(id)) {
                throw new BadRequestException("location.duplicate", ckb);
            }
        });
        location.setNameCkb(ckb);
        location.setNameKmr(kmr);
        return toResponse(locationRepository.save(location));
    }

    @Transactional
    public void delete(Long id) {
        Location location = locationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("location.notFound", id));
        locationRepository.delete(location);
    }

    private LocationResponse toResponse(Location location) {
        return LocationResponse.builder()
                .id(location.getId())
                .nameCkb(location.getNameCkb())
                .nameKmr(location.getNameKmr())
                .displayOrder(location.getDisplayOrder())
                .build();
    }

    private String requireName(String raw) {
        String ckb = trim(raw);
        if (ckb.isEmpty()) {
            throw new BadRequestException("location.nameRequired");
        }
        return ckb;
    }

    private String trim(String raw) {
        return raw == null ? "" : raw.trim();
    }
}
