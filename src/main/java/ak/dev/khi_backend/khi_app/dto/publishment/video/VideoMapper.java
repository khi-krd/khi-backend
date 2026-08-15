package ak.dev.khi_backend.khi_app.dto.publishment.video;


import ak.dev.khi_backend.khi_app.model.publishment.video.Video;
import ak.dev.khi_backend.khi_app.model.publishment.video.VideoClipItem;
import ak.dev.khi_backend.khi_app.model.publishment.video.VideoContent;
import ak.dev.khi_backend.khi_app.model.publishment.video.VideoLog;
import ak.dev.khi_backend.khi_app.model.publishment.video.VideoCastMember;
import ak.dev.khi_backend.khi_app.model.publishment.video.VideoHighlightClip;
import ak.dev.khi_backend.khi_app.model.publishment.video.VideoSourceFile;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * VideoMapper — Static mapper between Video entity and VideoDTO.
 *
 * toEntity()     → DTO → new Video entity (used on CREATE)
 * updateEntity() → apply DTO fields onto an existing Video entity (used on UPDATE)
 * toDTO()        → Video entity → response DTO
 * toLogDTO()     → VideoLog entity → VideoLogDTO
 */
public final class VideoMapper {

    private VideoMapper() {}

    // =========================================================================
    //  DTO → ENTITY  (CREATE)
    // =========================================================================

    /**
     * Build a new Video entity from a VideoDTO.
     *
     * NOTE: The following are NOT set here — they are handled by VideoService:
     *   - coverUrl      (may come from multipart upload)
     *   - sourceUrl / sourceExternalUrl / sourceEmbedUrl  (upload logic)
     *   - videoClipItems  (built and attached in service)
     *   - topic           (resolved by service from topicId FK)
     *   - albumOfMemories (enforced by service based on videoType rule)
     */
    public static Video toEntity(VideoDTO dto) {
        if (dto == null) return null;

        return Video.builder()
                .videoType(dto.getVideoType())
                // coverUrl, topic, albumOfMemories, source, clips → set by service
                .contentLanguages(safeSet(dto.getContentLanguages()))
                .ckbContent(toContentEntity(dto.getCkbContent()))
                .kmrContent(toContentEntity(dto.getKmrContent()))
                .fileFormat(trimOrNull(dto.getFileFormat()))
                .durationSeconds(dto.getDurationSeconds())
                .publishmentDate(dto.getPublishmentDate())
                .resolution(trimOrNull(dto.getResolution()))
                .fileSizeMb(dto.getFileSizeMb())
                .tagsCkb(safeSet(dto.getTagsCkb()))
                .tagsKmr(safeSet(dto.getTagsKmr()))
                .keywordsCkb(safeSet(dto.getKeywordsCkb()))
                .keywordsKmr(safeSet(dto.getKeywordsKmr()))
                .castMembers(toCastEntities(dto.getCastMembers()))
                .highlightClips(toHighlightEntities(dto.getHighlightClips()))
                .build();
    }

    // =========================================================================
    //  DTO → ENTITY  (UPDATE)
    // =========================================================================

    /**
     * Apply updatable fields from a VideoDTO onto an existing Video entity.
     *
     * Rules:
     *  - A null field in the DTO means "do not change that field".
     *  - Collections: if the DTO collection is non-null, it replaces the existing one.
     *  - coverUrl, sourceUrls, topic, albumOfMemories, videoClipItems
     *    are all handled by VideoService (not here).
     */
    public static void updateEntity(Video video, VideoDTO dto) {
        if (dto == null) return;

        // videoType: allow changing type (service enforces albumOfMemories rule after)
        if (dto.getVideoType() != null) {
            video.setVideoType(dto.getVideoType());
        }

        // Bilingual content
        if (dto.getCkbContent() != null) {
            video.setCkbContent(toContentEntity(dto.getCkbContent()));
        }
        if (dto.getKmrContent() != null) {
            video.setKmrContent(toContentEntity(dto.getKmrContent()));
        }

        // Languages
        if (dto.getContentLanguages() != null) {
            video.setContentLanguages(safeSet(dto.getContentLanguages()));
        }

        // Common metadata
        if (dto.getFileFormat() != null) {
            video.setFileFormat(trimOrNull(dto.getFileFormat()));
        }
        if (dto.getDurationSeconds() != null) {
            video.setDurationSeconds(dto.getDurationSeconds());
        }
        if (dto.getPublishmentDate() != null) {
            video.setPublishmentDate(dto.getPublishmentDate());
        }
        if (dto.getResolution() != null) {
            video.setResolution(trimOrNull(dto.getResolution()));
        }
        if (dto.getFileSizeMb() != null) {
            video.setFileSizeMb(dto.getFileSizeMb());
        }

        // Tags & Keywords — replace if provided
        if (dto.getTagsCkb() != null) {
            video.setTagsCkb(safeSet(dto.getTagsCkb()));
        }
        if (dto.getTagsKmr() != null) {
            video.setTagsKmr(safeSet(dto.getTagsKmr()));
        }
        if (dto.getKeywordsCkb() != null) {
            video.setKeywordsCkb(safeSet(dto.getKeywordsCkb()));
        }
        if (dto.getKeywordsKmr() != null) {
            video.setKeywordsKmr(safeSet(dto.getKeywordsKmr()));
        }
        if (dto.getCastMembers() != null) {
            video.setCastMembers(toCastEntities(dto.getCastMembers()));
        }
        if (dto.getHighlightClips() != null) {
            video.setHighlightClips(toHighlightEntities(dto.getHighlightClips()));
        }
    }

    // =========================================================================
    //  ENTITY → DTO  (RESPONSE)
    // =========================================================================

    /**
     * Map a Video entity to a VideoDTO for the response body.
     * All fields are populated, including topic names and clip items.
     */
    public static VideoDTO toDTO(Video video) {
        if (video == null) return null;

        VideoDTO dto = VideoDTO.builder()
                .id(video.getId())
                .ckbCoverUrl(video.getCkbCoverUrl())
                .kmrCoverUrl(video.getKmrCoverUrl())
                .hoverCoverUrl(video.getHoverCoverUrl())
                .featureImageUrl(video.getFeatureImageUrl())
                .videoType(video.getVideoType())
                .albumOfMemories(video.isAlbumOfMemories())
                // topic
                .topicId(video.getTopic() != null ? video.getTopic().getId() : null)
                .topicNameCkb(video.getTopic() != null ? video.getTopic().getNameCkb() : null)
                .topicNameKmr(video.getTopic() != null ? video.getTopic().getNameKmr() : null)
                // content languages
                .contentLanguages(video.getContentLanguages() != null
                        ? new LinkedHashSet<>(video.getContentLanguages()) : null)
                // bilingual content
                .ckbContent(toContentDTO(video.getCkbContent()))
                .kmrContent(toContentDTO(video.getKmrContent()))
                // single video source (FILM type)
                .sourceUrl(video.getSourceUrl())
                .sourceExternalUrl(video.getSourceExternalUrl())
                .sourceEmbedUrl(video.getSourceEmbedUrl())
                // common metadata
                .fileFormat(video.getFileFormat())
                .durationSeconds(video.getDurationSeconds())
                .publishmentDate(video.getPublishmentDate())
                .resolution(video.getResolution())
                .fileSizeMb(video.getFileSizeMb())
                // tags & keywords
                .tagsCkb(video.getTagsCkb() != null ? new LinkedHashSet<>(video.getTagsCkb()) : null)
                .tagsKmr(video.getTagsKmr() != null ? new LinkedHashSet<>(video.getTagsKmr()) : null)
                .keywordsCkb(video.getKeywordsCkb() != null ? new LinkedHashSet<>(video.getKeywordsCkb()) : null)
                .keywordsKmr(video.getKeywordsKmr() != null ? new LinkedHashSet<>(video.getKeywordsKmr()) : null)
                .castMembers(video.getCastMembers() == null ? null : video.getCastMembers().stream()
                        .map(VideoMapper::toCastDTO).toList())
                .highlightClips(video.getHighlightClips() == null ? null : video.getHighlightClips().stream()
                        .map(VideoMapper::toHighlightDTO).toList())
                // timestamps
                .createdAt(video.getCreatedAt())
                .updatedAt(video.getUpdatedAt())
                .build();

        // Clip items (VIDEO_CLIP type only)
        if (video.getVideoClipItems() != null && !video.getVideoClipItems().isEmpty()) {
            dto.setVideoClipItems(
                    video.getVideoClipItems().stream()
                            .map(VideoMapper::toClipItemDTO)
                            .collect(Collectors.toList())
            );
        }

        // Video sources (FILM type). Fall back to the legacy single-source columns
        // so films created before this field existed still return a sources array.
        if (video.getVideoSources() != null && !video.getVideoSources().isEmpty()) {
            dto.setVideoSources(
                    video.getVideoSources().stream()
                            .map(VideoMapper::toSourceDTO)
                            .collect(Collectors.toList())
            );
        } else if (!isBlank(video.getSourceUrl())
                || !isBlank(video.getSourceExternalUrl())
                || !isBlank(video.getSourceEmbedUrl())) {
            dto.setVideoSources(List.of(VideoDTO.VideoSourceDTO.builder()
                    .url(video.getSourceUrl())
                    .externalUrl(video.getSourceExternalUrl())
                    .embedUrl(video.getSourceEmbedUrl())
                    .main(true)
                    .build()));
        }

        return dto;
    }

    private static VideoDTO.VideoSourceDTO toSourceDTO(VideoSourceFile s) {
        if (s == null) return null;
        return VideoDTO.VideoSourceDTO.builder()
                .url(s.getUrl())
                .externalUrl(s.getExternalUrl())
                .embedUrl(s.getEmbedUrl())
                .main(s.isMain())
                .label(s.getLabel())
                .durationSeconds(s.getDurationSeconds())
                .build();
    }

    private static List<VideoCastMember> toCastEntities(List<VideoDTO.CastMemberDTO> values) {
        if (values == null) return new ArrayList<>();
        return values.stream().map(value -> VideoCastMember.builder()
                .nameCkb(trimOrNull(value.getNameCkb()))
                .nameKmr(trimOrNull(value.getNameKmr()))
                .roleCkb(trimOrNull(value.getRoleCkb()))
                .roleKmr(trimOrNull(value.getRoleKmr()))
                .imageUrl(trimOrNull(value.getImageUrl())).build())
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private static List<VideoHighlightClip> toHighlightEntities(
            List<VideoDTO.HighlightClipDTO> values) {
        if (values == null) return new ArrayList<>();
        return values.stream().map(value -> VideoHighlightClip.builder()
                .titleCkb(trimOrNull(value.getTitleCkb()))
                .titleKmr(trimOrNull(value.getTitleKmr()))
                .url(trimOrNull(value.getUrl()))
                .embedUrl(trimOrNull(value.getEmbedUrl()))
                .durationSeconds(value.getDurationSeconds()).build())
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private static VideoDTO.CastMemberDTO toCastDTO(VideoCastMember value) {
        return VideoDTO.CastMemberDTO.builder()
                .nameCkb(value.getNameCkb()).nameKmr(value.getNameKmr())
                .roleCkb(value.getRoleCkb()).roleKmr(value.getRoleKmr())
                .imageUrl(value.getImageUrl()).build();
    }

    private static VideoDTO.HighlightClipDTO toHighlightDTO(VideoHighlightClip value) {
        return VideoDTO.HighlightClipDTO.builder()
                .titleCkb(value.getTitleCkb()).titleKmr(value.getTitleKmr())
                .url(value.getUrl()).embedUrl(value.getEmbedUrl())
                .durationSeconds(value.getDurationSeconds()).build();
    }

    // =========================================================================
    //  LOG ENTITY → LOG DTO
    // =========================================================================

    public static VideoLogDTO toLogDTO(VideoLog log) {
        if (log == null) return null;
        return VideoLogDTO.builder()
                .id(log.getId())
                .videoId(log.getVideoId())
                .videoTitle(log.getVideoTitle())
                .action(log.getAction())
                .details(log.getDetails())
                .performedBy(log.getPerformedBy())
                .timestamp(log.getTimestamp())
                .build();
    }

    // =========================================================================
    //  PRIVATE — inner mappers
    // =========================================================================

    private static VideoContent toContentEntity(VideoDTO.VideoContentDTO dto) {
        if (dto == null) return null;
        // If everything is blank, return null (no content set for this language)
        if (isBlank(dto.getTitle()) && isBlank(dto.getDescription())
                && isBlank(dto.getLocation()) && isBlank(dto.getDirector())
                && isBlank(dto.getProducer())) {
            return null;
        }
        return VideoContent.builder()
                .title(trimOrNull(dto.getTitle()))
                .description(trimOrNull(dto.getDescription()))
                .location(trimOrNull(dto.getLocation()))
                .director(trimOrNull(dto.getDirector()))
                .producer(trimOrNull(dto.getProducer()))
                .build();
    }

    private static VideoDTO.VideoContentDTO toContentDTO(VideoContent content) {
        if (content == null) return null;
        return VideoDTO.VideoContentDTO.builder()
                .title(content.getTitle())
                .description(content.getDescription())
                .location(content.getLocation())
                .director(content.getDirector())
                .producer(content.getProducer())
                .build();
    }

    private static VideoDTO.VideoClipItemDTO toClipItemDTO(VideoClipItem item) {
        if (item == null) return null;
        return VideoDTO.VideoClipItemDTO.builder()
                .id(item.getId())
                .url(item.getUrl())
                .externalUrl(item.getExternalUrl())
                .embedUrl(item.getEmbedUrl())
                .clipNumber(item.getClipNumber())
                .durationSeconds(item.getDurationSeconds())
                .resolution(item.getResolution())
                .fileFormat(item.getFileFormat())
                .fileSizeMb(item.getFileSizeMb())
                .titleCkb(item.getTitleCkb())
                .titleKmr(item.getTitleKmr())
                .descriptionCkb(item.getDescriptionCkb())
                .descriptionKmr(item.getDescriptionKmr())
                .build();
    }

    // =========================================================================
    //  PRIVATE — utils
    // =========================================================================

    private static <T> Set<T> safeSet(Set<T> s) {
        return s == null ? new LinkedHashSet<>() : new LinkedHashSet<>(s);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String trimOrNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
