package ak.dev.khi_backend.khi_app.dto.news;

import lombok.*;

import java.io.Serializable;
import java.util.List;

/**
 * DTOs for the news taxonomy admin endpoints — categories carry their
 * sub-categories plus per-bucket usage counts so the dashboard can warn
 * before deleting something that is still in use.
 */
public class NewsTaxonomyDtos {

    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor
    public static class CategoryRequest implements Serializable {
        private static final long serialVersionUID = 1L;
        private String nameCkb;
        private String nameKmr;
    }

    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor
    public static class SubCategoryRequest implements Serializable {
        private static final long serialVersionUID = 1L;
        private String nameCkb;
        private String nameKmr;
        private Long categoryId;
        /**
         * Alternative to {@code categoryId}: resolve (or auto-create) the
         * parent by name — used by the picker's inline-create, which only
         * knows the selected category's name. Mirrors the auto-create the
         * news save path already performs.
         */
        private String categoryCkb;
        private String categoryKmr;
    }

    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor
    @Builder
    public static class SubCategoryResponse implements Serializable {
        private static final long serialVersionUID = 1L;
        private Long id;
        private String nameCkb;
        private String nameKmr;
        private Long categoryId;
        private long newsCount;
    }

    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor
    @Builder
    public static class CategoryResponse implements Serializable {
        private static final long serialVersionUID = 1L;
        private Long id;
        private String nameCkb;
        private String nameKmr;
        private long newsCount;
        private List<SubCategoryResponse> subCategories;
    }
}
