package ak.dev.khi_backend.khi_app.dto.contact;

import lombok.*;

import java.io.Serializable;

/**
 * DTOs for the office-type vocabulary admin endpoints.
 */
public class OfficeTypeDtos {

    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor
    public static class OfficeTypeRequest implements Serializable {
        private static final long serialVersionUID = 1L;
        private String nameCkb;
        private String nameKmr;
    }

    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor
    @Builder
    public static class OfficeTypeResponse implements Serializable {
        private static final long serialVersionUID = 1L;
        private Long id;
        private String nameCkb;
        private String nameKmr;
        private long officeCount;
    }
}
