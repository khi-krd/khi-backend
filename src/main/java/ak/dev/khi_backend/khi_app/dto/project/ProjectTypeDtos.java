package ak.dev.khi_backend.khi_app.dto.project;

import lombok.*;

import java.io.Serializable;

/**
 * DTOs for the project-type vocabulary admin endpoints.
 */
public class ProjectTypeDtos {

    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor
    public static class ProjectTypeRequest implements Serializable {
        private static final long serialVersionUID = 1L;
        private String nameCkb;
        private String nameKmr;
    }

    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor
    @Builder
    public static class ProjectTypeResponse implements Serializable {
        private static final long serialVersionUID = 1L;
        private Long id;
        private String nameCkb;
        private String nameKmr;
        private long projectCount;
    }
}
