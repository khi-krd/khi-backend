package ak.dev.khi_backend.khi_app.service.publishment.writing;

import ak.dev.khi_backend.khi_app.repository.publishment.writing.WritingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * One-shot normalizer that brings existing series rows under the flat
 * primary-root model at startup. {@link WritingService#refreshSeries} is
 * idempotent, so re-running it on every boot is harmless: each pass re-elects
 * the root, flattens legacy parent chains (A → B → C becomes every child
 * pointing at A), renumbers volumes contiguously from 1, re-derives the series
 * name from the root's title, and clears the stray series key the old
 * {@code @PrePersist} left on standalone books.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WritingSeriesMigrator implements ApplicationRunner {

    private final WritingRepository writingRepository;
    private final WritingService    writingService;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<String> seriesIds = writingRepository.findDistinctSeriesIds();
        if (seriesIds.isEmpty()) return;
        for (String seriesId : seriesIds) {
            try {
                writingService.refreshSeries(seriesId);
            } catch (RuntimeException ex) {
                log.warn("Series migration skipped for '{}': {}", seriesId, ex.getMessage());
            }
        }
        log.info("Series migration: normalized {} series key(s)", seriesIds.size());
    }
}
