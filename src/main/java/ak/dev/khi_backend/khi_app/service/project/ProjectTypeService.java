package ak.dev.khi_backend.khi_app.service.project;

import ak.dev.khi_backend.khi_app.dto.project.ProjectTypeDtos.ProjectTypeRequest;
import ak.dev.khi_backend.khi_app.dto.project.ProjectTypeDtos.ProjectTypeResponse;
import ak.dev.khi_backend.khi_app.exceptions.BadRequestException;
import ak.dev.khi_backend.khi_app.exceptions.ResourceNotFoundException;
import ak.dev.khi_backend.khi_app.model.project.ProjectType;
import ak.dev.khi_backend.khi_app.repository.project.ProjectRepository;
import ak.dev.khi_backend.khi_app.repository.project.ProjectTypeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Admin CRUD for the project-type vocabulary. Projects store the type as
 * free-text CKB/KMR columns; renaming a type here rewrites the matching
 * project rows so the text stays in sync, while deleting a type only removes
 * the suggestion from the picker — projects that already carry the name keep
 * it (it is still displayed verbatim).
 */
@Service
@RequiredArgsConstructor
public class ProjectTypeService {

    private final ProjectTypeRepository projectTypeRepository;
    private final ProjectRepository projectRepository;

    @Transactional(readOnly = true)
    public List<ProjectTypeResponse> list() {
        return projectTypeRepository.findAllByOrderByNameCkbAsc().stream()
                .map(this::toResponse)
                .toList();
    }

    @CacheEvict(value = "projects", allEntries = true)
    @Transactional
    public ProjectTypeResponse create(ProjectTypeRequest request) {
        String ckb = requireName(request == null ? null : request.getNameCkb());
        String kmr = kmrOr(request == null ? null : request.getNameKmr(), "");
        projectTypeRepository.findByNameCkb(ckb).ifPresent(existing -> {
            throw new BadRequestException("project.type.duplicate", ckb);
        });
        return toResponse(projectTypeRepository.save(ProjectType.builder()
                .nameCkb(ckb)
                .nameKmr(kmr)
                .build()));
    }

    @CacheEvict(value = "projects", allEntries = true)
    @Transactional
    public ProjectTypeResponse update(Long id, ProjectTypeRequest request) {
        ProjectType type = projectTypeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("project.type.notFound", id));
        String ckb = requireName(request == null ? null : request.getNameCkb());
        String kmr = kmrOr(request == null ? null : request.getNameKmr(), "");
        projectTypeRepository.findByNameCkb(ckb).ifPresent(existing -> {
            if (!existing.getId().equals(id)) {
                throw new BadRequestException("project.type.duplicate", ckb);
            }
        });
        String oldCkb = type.getNameCkb();
        type.setNameCkb(ckb);
        type.setNameKmr(kmr);
        if (!oldCkb.equals(ckb)) {
            projectRepository.renameType(oldCkb, ckb, kmr);
            projectRepository.renameTypeInSelections(oldCkb, ckb, kmr);
        }
        return toResponse(projectTypeRepository.save(type));
    }

    @CacheEvict(value = "projects", allEntries = true)
    @Transactional
    public void delete(Long id) {
        ProjectType type = projectTypeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("project.type.notFound", id));
        projectTypeRepository.delete(type);
    }

    private ProjectTypeResponse toResponse(ProjectType type) {
        return ProjectTypeResponse.builder()
                .id(type.getId())
                .nameCkb(type.getNameCkb())
                .nameKmr(type.getNameKmr())
                .projectCount(projectRepository.countProjectsUsingType(type.getNameCkb()))
                .build();
    }

    private String requireName(String raw) {
        String ckb = raw == null ? "" : raw.trim();
        if (ckb.isEmpty()) {
            throw new BadRequestException("project.type.nameRequired");
        }
        return ckb;
    }

    private String kmrOr(String raw, String fallback) {
        String kmr = raw == null ? "" : raw.trim();
        return kmr.isEmpty() ? fallback : kmr;
    }
}
