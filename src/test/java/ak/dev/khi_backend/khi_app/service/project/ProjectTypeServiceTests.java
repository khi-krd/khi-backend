package ak.dev.khi_backend.khi_app.service.project;

import ak.dev.khi_backend.khi_app.dto.project.ProjectTypeDtos.ProjectTypeRequest;
import ak.dev.khi_backend.khi_app.model.project.ProjectType;
import ak.dev.khi_backend.khi_app.repository.project.ProjectRepository;
import ak.dev.khi_backend.khi_app.repository.project.ProjectTypeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression test for the bilingual vocabulary mirror bug: a blank/absent KMR
 * name must stay empty rather than being silently filled from the CKB name.
 * The column is NOT NULL, so "empty" is the empty string.
 */
@ExtendWith(MockitoExtension.class)
class ProjectTypeServiceTests {

    @Mock private ProjectTypeRepository projectTypeRepository;
    @Mock private ProjectRepository projectRepository;

    @InjectMocks
    private ProjectTypeService projectTypeService;

    @Test
    void createWithBlankKmrStoresEmptyNotCkbMirror() {
        when(projectTypeRepository.findByNameCkb("پڕۆژە")).thenReturn(Optional.empty());
        when(projectTypeRepository.save(any(ProjectType.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ProjectTypeRequest request = new ProjectTypeRequest();
        request.setNameCkb("پڕۆژە");
        request.setNameKmr("   ");

        projectTypeService.create(request);

        ArgumentCaptor<ProjectType> captor = ArgumentCaptor.forClass(ProjectType.class);
        verify(projectTypeRepository).save(captor.capture());
        assertEquals("پڕۆژە", captor.getValue().getNameCkb());
        assertEquals("", captor.getValue().getNameKmr());
    }

    @Test
    void updateWithBlankKmrClearsNotMirrors() {
        ProjectType type = ProjectType.builder()
                .nameCkb("کۆن")
                .nameKmr("Kevin")
                .build();
        type.setId(4L);
        when(projectTypeRepository.findById(4L)).thenReturn(Optional.of(type));
        when(projectTypeRepository.findByNameCkb("نوێ")).thenReturn(Optional.empty());
        when(projectTypeRepository.save(any(ProjectType.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ProjectTypeRequest request = new ProjectTypeRequest();
        request.setNameCkb("نوێ");
        request.setNameKmr("");

        projectTypeService.update(4L, request);

        assertEquals("نوێ", type.getNameCkb());
        assertEquals("", type.getNameKmr());
    }

    @Test
    void updateWithTypedKmrKeepsTranslation() {
        ProjectType type = ProjectType.builder()
                .nameCkb("کۆن")
                .nameKmr("Kevin")
                .build();
        type.setId(5L);
        when(projectTypeRepository.findById(5L)).thenReturn(Optional.of(type));
        when(projectTypeRepository.findByNameCkb("کۆن")).thenReturn(Optional.of(type));
        when(projectTypeRepository.save(any(ProjectType.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ProjectTypeRequest request = new ProjectTypeRequest();
        request.setNameCkb("کۆن");
        request.setNameKmr("Îmarî");

        projectTypeService.update(5L, request);

        assertEquals("Îmarî", type.getNameKmr());
    }
}
