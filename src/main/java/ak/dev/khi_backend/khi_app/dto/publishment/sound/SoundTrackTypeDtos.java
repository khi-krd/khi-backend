package ak.dev.khi_backend.khi_app.dto.publishment.sound;

import lombok.*;

import java.io.Serializable;

/**
 * DTOs for the sound-type vocabulary admin endpoints.
 */
public class SoundTrackTypeDtos {

    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor
    public static class SoundTrackTypeRequest implements Serializable {
        private static final long serialVersionUID = 1L;
        private String nameCkb;
        private String nameKmr;
    }

    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor
    @Builder
    public static class SoundTrackTypeResponse implements Serializable {
        private static final long serialVersionUID = 1L;
        private Long id;
        private String nameCkb;
        private String nameKmr;
        private long trackCount;
    }
}
