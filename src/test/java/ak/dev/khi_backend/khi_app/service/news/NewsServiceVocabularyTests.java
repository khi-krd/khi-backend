package ak.dev.khi_backend.khi_app.service.news;

import ak.dev.khi_backend.khi_app.dto.news.NewsDto;
import ak.dev.khi_backend.khi_app.model.news.News;
import ak.dev.khi_backend.khi_app.model.news.NewsCategory;
import ak.dev.khi_backend.khi_app.repository.news.NewsAuditLogRepository;
import ak.dev.khi_backend.khi_app.repository.news.NewsCategoryRepository;
import ak.dev.khi_backend.khi_app.repository.news.NewsRepository;
import ak.dev.khi_backend.khi_app.repository.news.NewsSubCategoryRepository;
import ak.dev.khi_backend.khi_app.service.S3Service;
import ak.dev.khi_backend.khi_app.service.media.TiptapHtmlProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression test for the bilingual vocabulary mirror bug: a news save that
 * supplies only the CKB category name must leave the stored KMR translation
 * untouched instead of copying CKB into it.
 */
@ExtendWith(MockitoExtension.class)
class NewsServiceVocabularyTests {

    @Mock private NewsRepository newsRepository;
    @Mock private NewsCategoryRepository newsCategoryRepository;
    @Mock private NewsSubCategoryRepository newsSubCategoryRepository;
    @Mock private NewsAuditLogRepository newsAuditLogRepository;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private TiptapHtmlProcessor tiptapHtmlProcessor;
    @Mock private S3Service s3Service;

    @InjectMocks
    private NewsService newsService;

    @BeforeEach
    void executeTransactionsInline() {
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            TransactionCallback<Object> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactionTemplate).execute(any());
    }

    @Test
    void updateWithCkbOnlyCategoryKeepsStoredKmrName() {
        News news = News.builder().build();
        news.setId(7L);
        when(newsRepository.findByIdWithGraph(7L)).thenReturn(Optional.of(news));
        when(newsRepository.save(any(News.class))).thenAnswer(inv -> inv.getArgument(0));

        NewsCategory existing = NewsCategory.builder()
                .nameCkb("هەواڵ")
                .nameKmr("Nûçe")
                .build();
        when(newsCategoryRepository.findByNameCkb("هەواڵ"))
                .thenReturn(Optional.of(existing));

        NewsDto payload = new NewsDto();
        payload.setCategory(NewsDto.CategoryDto.builder()
                .ckbName("هەواڵ")
                .kmrName(null)
                .build());

        NewsDto result = newsService.updateNews(7L, payload);

        // The vocabulary's KMR translation must survive a CKB-only payload.
        assertEquals("Nûçe", existing.getNameKmr());
        verify(newsCategoryRepository, never()).save(any());
        assertEquals("Nûçe", result.getCategory().getKmrName());
    }

    @Test
    void ckbOnlyCategoryCreatedWithEmptyKmrNotMirror() {
        News news = News.builder().build();
        news.setId(9L);
        when(newsRepository.findByIdWithGraph(9L)).thenReturn(Optional.of(news));
        when(newsRepository.save(any(News.class))).thenAnswer(inv -> inv.getArgument(0));
        when(newsCategoryRepository.findByNameCkb("وەرزش"))
                .thenReturn(Optional.empty());
        when(newsCategoryRepository.save(any(NewsCategory.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        NewsDto payload = new NewsDto();
        payload.setCategory(NewsDto.CategoryDto.builder()
                .ckbName("وەرزش")
                .build());

        newsService.updateNews(9L, payload);

        org.mockito.ArgumentCaptor<NewsCategory> captor =
                org.mockito.ArgumentCaptor.forClass(NewsCategory.class);
        verify(newsCategoryRepository).save(captor.capture());
        // Blank KMR must stay empty — never mirrored from the CKB name.
        assertEquals("", captor.getValue().getNameKmr());
        assertEquals("وەرزش", captor.getValue().getNameCkb());
    }
}
