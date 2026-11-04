package ak.dev.khi_backend.khi_app.service.site;

import ak.dev.khi_backend.khi_app.model.site.Location;
import ak.dev.khi_backend.khi_app.repository.site.LocationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Seeds the four Kurdistan regions into an EMPTY location vocabulary. Runs
 * only while the table has no rows, so an option the editors deliberately
 * delete or rename is never brought back on the next boot.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LocationSeeder implements ApplicationRunner {

    private final LocationRepository repository;

    private record Seed(String nameCkb, String nameKmr) {}

    private static final List<Seed> DEFAULTS = List.of(
            new Seed("باشووری کوردستان", "Başûrê Kurdistanê"),
            new Seed("باکووری کوردستان", "Bakurê Kurdistanê"),
            new Seed("ڕۆژهەڵاتی کوردستان", "Rojhilatê Kurdistanê"),
            new Seed("ڕۆژئاوای کوردستان", "Rojavayê Kurdistanê"));

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (repository.count() > 0) {
            return;
        }
        List<Location> rows = new ArrayList<>();
        int order = 1;
        for (Seed seed : DEFAULTS) {
            rows.add(Location.builder()
                    .nameCkb(seed.nameCkb())
                    .nameKmr(seed.nameKmr())
                    .displayOrder(order++)
                    .build());
        }
        repository.saveAll(rows);
        log.info("Locations: seeded {} default region(s)", rows.size());
    }
}
