package ak.dev.khi_backend.khi_app.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Configuration;

/**
 * CacheConfig — switches on the {@code @Cacheable} / {@code @CacheEvict} annotations
 * that were already spread across the service layer but had no effect until now.
 *
 * ─── What this turns on ───────────────────────────────────────────────────────
 *
 *  Cache names in use (all with {@code @CacheEvict(allEntries = true)} on their
 *  create / update / delete paths, so writes never leave stale reads behind):
 *
 *    news              — NewsService
 *    projects          — ProjectService
 *    soundTracks       — SoundTrackService
 *    imageCollections  — ImageCollectionService
 *    services          — ServiceService, SiteContentService
 *
 * ─── Backing store ────────────────────────────────────────────────────────────
 *
 *  Redis, configured entirely in {@code application.yaml}:
 *
 *    spring.cache.type              = redis
 *    spring.cache.redis.time-to-live = 600000ms (10 minutes)
 *    spring.cache.redis.key-prefix   = "khi:"
 *    spring.cache.redis.cache-null-values = false
 *
 *  This class deliberately does NOT define a {@code RedisCacheConfiguration} bean —
 *  doing so would replace the property-derived configuration wholesale and silently
 *  drop the TTL and key prefix above. Tune caching in the YAML, not here.
 *
 * ─── ⚠ Serialization contract — read before adding a new @Cacheable ────────────
 *
 *  Spring Boot's Redis cache serializes values with <b>JDK serialization</b>.
 *  Every type reachable from a cached method's return value must therefore
 *  implement {@link java.io.Serializable}, or the first cache write throws
 *  {@code SerializationFailedException} at runtime.
 *
 *  The five cached DTO graphs already satisfy this:
 *
 *    NewsDto                      (+ CategoryDto, SubCategoryDto, LanguageContentDto, BilingualSet)
 *    ProjectResponse              (+ ProjectContentBlockDto)
 *    ServiceDTOs.ServiceResponse  (+ ServiceContentResponse)
 *    SoundTrackDtos.Response      (+ LanguageContentDto, BilingualSet, FileResponse,
 *                                    BrochureResponse, AttachmentResponse)
 *    ImageCollectionDTO.Response  (+ LanguageContentDto, BilingualSet, ImageItemDto)
 *
 *  plus {@code MediaItem}, which was already Serializable, and the enums / java.time
 *  types, which are Serializable by definition. {@code PageImpl} — the wrapper every
 *  cached search returns — is Serializable too.
 *
 *  Each of those classes pins {@code serialVersionUID = 1L} on purpose: without it the
 *  JVM derives the ID from the class structure, and merely <em>adding a field</em> would
 *  make every entry written before the deploy fail to read back with
 *  {@code InvalidClassException} until the 10-minute TTL flushed it.
 *
 *  {@code CacheSerializationTests} enforces this contract — it round-trips each cached
 *  page shape through {@code ObjectOutputStream} and fails the build if a new
 *  non-Serializable field creeps into any of these graphs.
 *
 * ─── ⚠ Redis is now on the request path ───────────────────────────────────────
 *
 *  Before this class existed the app started and served traffic whether or not Redis
 *  was reachable, because Lettuce connects lazily and nothing ever asked it for a
 *  value. With caching active, an unreachable Redis surfaces on every cached read.
 *  Make sure {@code REDIS_HOST} / {@code REDIS_PORT} / {@code REDIS_PASSWORD} are set
 *  in every environment this is deployed to.
 */
@Configuration
@EnableCaching
public class CacheConfig {
}
