package ak.dev.khi_backend.khi_app.service.publishment.writing;

import ak.dev.khi_backend.khi_app.dto.publishment.writing.WritingDtos.BookGenreRequest;
import ak.dev.khi_backend.khi_app.dto.publishment.writing.WritingDtos.BookGenreResponse;
import ak.dev.khi_backend.khi_app.model.publishment.writing.BookGenre;
import ak.dev.khi_backend.khi_app.repository.publishment.writing.BookGenreRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookGenreServiceTests {

    @Mock private BookGenreRepository bookGenreRepository;

    @InjectMocks
    private BookGenreService bookGenreService;

    private BookGenre poetry() {
        return BookGenre.builder()
                .id(1L).slug("POETRY").nameCkb("شیعر").nameKmr("Şîir")
                .displayOrder(0).active(true)
                .build();
    }

    @Test
    void getGenresAttachesBookCountsAndUsesTheRightQuery() {
        when(bookGenreRepository.findAllByActiveTrueOrderByDisplayOrderAsc())
                .thenReturn(List.of(poetry()));
        when(bookGenreRepository.countBooksPerGenre())
                .thenReturn(List.<Object[]>of(new Object[]{1L, 42L}));

        List<BookGenreResponse> result = bookGenreService.getGenres(false);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getSlug()).isEqualTo("POETRY");
        assertThat(result.get(0).getBookCount()).isEqualTo(42L);
        verify(bookGenreRepository, never()).findAllByOrderByDisplayOrderAsc();
    }

    @Test
    void createNormalisesSlugAndTrimsNames() {
        when(bookGenreRepository.saveAndFlush(any(BookGenre.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        BookGenreResponse response = bookGenreService.createGenre(
                BookGenreRequest.builder()
                        .slug("  memoir ")
                        .nameCkb("  یادەوەری ")
                        .nameKmr("   ")
                        .build());

        assertThat(response.getSlug()).isEqualTo("MEMOIR");
        assertThat(response.getNameCkb()).isEqualTo("یادەوەری");
        assertThat(response.getNameKmr()).isNull();
        assertThat(response.getDisplayOrder()).isZero();
        assertThat(response.getActive()).isTrue();
        assertThat(response.getBookCount()).isZero();
    }

    @Test
    void createRejectsBadSlugAndMissingNames() {
        assertThatThrownBy(() -> bookGenreService.createGenre(
                BookGenreRequest.builder().slug("bad slug!").nameCkb("x").build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("slug");

        assertThatThrownBy(() -> bookGenreService.createGenre(
                BookGenreRequest.builder().slug("POETRY").nameCkb(" ").nameKmr("").build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nameCkb");

        verify(bookGenreRepository, never()).saveAndFlush(any());
    }

    @Test
    void updateRejectsSlugChangeWhileBooksUseTheGenre() {
        when(bookGenreRepository.findById(1L)).thenReturn(Optional.of(poetry()));
        when(bookGenreRepository.countBooks(1L)).thenReturn(3L);

        assertThatThrownBy(() -> bookGenreService.updateGenre(1L,
                BookGenreRequest.builder().slug("VERSE").nameCkb("شیعر").build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Slug cannot change while books use this genre");
        verify(bookGenreRepository, never()).saveAndFlush(any());
    }

    @Test
    void updateAllowsRenameWithSameSlugAndSlugChangeWhenUnused() {
        when(bookGenreRepository.findById(1L)).thenReturn(Optional.of(poetry()));
        when(bookGenreRepository.saveAndFlush(any(BookGenre.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        when(bookGenreRepository.countBooks(1L)).thenReturn(5L);
        BookGenreResponse renamed = bookGenreService.updateGenre(1L,
                BookGenreRequest.builder().slug("poetry").nameCkb("شیعر و هۆنراوە").build());
        assertThat(renamed.getSlug()).isEqualTo("POETRY");
        assertThat(renamed.getNameCkb()).isEqualTo("شیعر و هۆنراوە");
        assertThat(renamed.getBookCount()).isEqualTo(5L);

        when(bookGenreRepository.countBooks(1L)).thenReturn(0L);
        BookGenreResponse reslugged = bookGenreService.updateGenre(1L,
                BookGenreRequest.builder().slug("VERSE").nameCkb("شیعر").build());
        assertThat(reslugged.getSlug()).isEqualTo("VERSE");
    }

    @Test
    void deleteDetachesLinksBeforeDeletingTheRow() {
        when(bookGenreRepository.existsById(1L)).thenReturn(true);

        bookGenreService.deleteGenre(1L);

        InOrder inOrder = Mockito.inOrder(bookGenreRepository);
        inOrder.verify(bookGenreRepository).detachFromBooks(1L);
        inOrder.verify(bookGenreRepository).deleteById(1L);
    }

    @Test
    void deleteAndUpdateOnUnknownIdThrowNotFound() {
        when(bookGenreRepository.existsById(9L)).thenReturn(false);
        assertThatThrownBy(() -> bookGenreService.deleteGenre(9L))
                .hasMessage("Book genre not found: 9");

        when(bookGenreRepository.findById(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> bookGenreService.updateGenre(9L,
                BookGenreRequest.builder().slug("X").nameCkb("x").build()))
                .hasMessage("Book genre not found: 9");
    }

    @Test
    void resolvePrefersIdsAndFallsBackToLegacyEnumCodesWithAliases() {
        BookGenre politics = BookGenre.builder().id(10L).slug("POLITICS").nameCkb("سیاسەت").build();
        when(bookGenreRepository.findById(10L)).thenReturn(Optional.of(politics));

        Set<BookGenre> byId = bookGenreService.resolve(
                List.of(10L),
                Set.of(ak.dev.khi_backend.khi_app.enums.publishment.BookGenre.POETRY));
        assertThat(byId).containsExactly(politics);
        verify(bookGenreRepository, never()).findBySlug(any());

        // Legacy alias POLITICAL normalises to POLITICS before the slug lookup.
        when(bookGenreRepository.findBySlug("POLITICS")).thenReturn(Optional.of(politics));
        Set<BookGenre> byLegacy = bookGenreService.resolve(null,
                Set.of(ak.dev.khi_backend.khi_app.enums.publishment.BookGenre.POLITICAL));
        assertThat(byLegacy).containsExactly(politics);

        assertThat(bookGenreService.resolve(null, null)).isEmpty();
    }

    @Test
    void resolveRejectsUnknownIdAndUnknownSlug() {
        when(bookGenreRepository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> bookGenreService.resolve(List.of(99L), null))
                .hasMessage("Unknown genre id: 99");

        when(bookGenreRepository.findBySlug("POETRY")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> bookGenreService.resolve(null,
                Set.of(ak.dev.khi_backend.khi_app.enums.publishment.BookGenre.POETRY)))
                .hasMessage("Unknown genre slug: POETRY");
    }
}
