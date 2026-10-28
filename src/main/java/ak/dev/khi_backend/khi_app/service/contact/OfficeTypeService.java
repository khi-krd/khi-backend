package ak.dev.khi_backend.khi_app.service.contact;

import ak.dev.khi_backend.khi_app.dto.contact.OfficeTypeDtos.OfficeTypeRequest;
import ak.dev.khi_backend.khi_app.dto.contact.OfficeTypeDtos.OfficeTypeResponse;
import ak.dev.khi_backend.khi_app.exceptions.BadRequestException;
import ak.dev.khi_backend.khi_app.exceptions.ResourceNotFoundException;
import ak.dev.khi_backend.khi_app.model.contact.OfficeType;
import ak.dev.khi_backend.khi_app.repository.contact.ContactRepository;
import ak.dev.khi_backend.khi_app.repository.contact.OfficeTypeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Admin CRUD for the office-type vocabulary. Contact pages store the type as
 * a free-text {@code officeType} column; renaming a type here rewrites the
 * matching contact rows so the text stays in sync, while deleting a type
 * only removes the suggestion from the picker — offices that already carry
 * the name keep it.
 */
@Service
@RequiredArgsConstructor
public class OfficeTypeService {

    private final OfficeTypeRepository officeTypeRepository;
    private final ContactRepository contactRepository;

    @Transactional(readOnly = true)
    public List<OfficeTypeResponse> list() {
        return officeTypeRepository.findAllByOrderByNameCkbAsc().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public OfficeTypeResponse create(OfficeTypeRequest request) {
        String ckb = requireName(request == null ? null : request.getNameCkb());
        String kmr = kmrOr(request == null ? null : request.getNameKmr(), "");
        officeTypeRepository.findByNameCkb(ckb).ifPresent(existing -> {
            throw new BadRequestException("contact.officeType.duplicate", ckb);
        });
        return toResponse(officeTypeRepository.save(OfficeType.builder()
                .nameCkb(ckb)
                .nameKmr(kmr)
                .build()));
    }

    @Transactional
    public OfficeTypeResponse update(Long id, OfficeTypeRequest request) {
        OfficeType type = officeTypeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("contact.officeType.notFound", id));
        String ckb = requireName(request == null ? null : request.getNameCkb());
        String kmr = kmrOr(request == null ? null : request.getNameKmr(), "");
        officeTypeRepository.findByNameCkb(ckb).ifPresent(existing -> {
            if (!existing.getId().equals(id)) {
                throw new BadRequestException("contact.officeType.duplicate", ckb);
            }
        });
        String oldCkb = type.getNameCkb();
        type.setNameCkb(ckb);
        type.setNameKmr(kmr);
        if (!oldCkb.equalsIgnoreCase(ckb)) {
            contactRepository.renameOfficeType(oldCkb, ckb);
        }
        return toResponse(officeTypeRepository.save(type));
    }

    @Transactional
    public void delete(Long id) {
        OfficeType type = officeTypeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("contact.officeType.notFound", id));
        officeTypeRepository.delete(type);
    }

    /**
     * Register a typed-new office type into the vocabulary — called when a
     * contact page is saved with a type that is not in the picker list yet.
     * No-op on duplicates.
     */
    @Transactional
    public void ensureExists(String nameCkb) {
        String ckb = nameCkb == null ? "" : nameCkb.trim();
        if (ckb.isEmpty()) return;
        officeTypeRepository.findByNameCkb(ckb).orElseGet(() ->
                officeTypeRepository.save(OfficeType.builder()
                        .nameCkb(ckb)
                        .nameKmr("")
                        .build()));
    }

    private OfficeTypeResponse toResponse(OfficeType type) {
        return OfficeTypeResponse.builder()
                .id(type.getId())
                .nameCkb(type.getNameCkb())
                .nameKmr(type.getNameKmr())
                .officeCount(contactRepository.countByOfficeType(type.getNameCkb()))
                .build();
    }

    private String requireName(String raw) {
        String ckb = raw == null ? "" : raw.trim();
        if (ckb.isEmpty()) {
            throw new BadRequestException("contact.officeType.nameRequired");
        }
        return ckb;
    }

    private String kmrOr(String raw, String fallback) {
        String kmr = raw == null ? "" : raw.trim();
        return kmr.isEmpty() ? fallback : kmr;
    }
}
