package ak.dev.khi_backend.khi_app.dto.site;

import lombok.*;

import java.io.Serializable;

/**
 * DTOs for the managed location vocabulary.
 */
public class LocationDtos {

    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor
    public static class LocationRequest implements Serializable {
        private static final long serialVersionUID = 1L;
        private String nameCkb;
        private String nameKmr;
    }

    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor
    @Builder
    public static class LocationResponse implements Serializable {
        private static final long serialVersionUID = 1L;
        private Long id;
        private String nameCkb;
        private String nameKmr;
        private Integer displayOrder;
    }
}
