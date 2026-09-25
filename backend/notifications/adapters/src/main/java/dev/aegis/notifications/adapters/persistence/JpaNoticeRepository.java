package dev.aegis.notifications.adapters.persistence;

import dev.aegis.notifications.domain.Notice;
import dev.aegis.notifications.domain.NoticeId;
import dev.aegis.notifications.domain.NoticeKind;
import dev.aegis.notifications.domain.NoticeLink;
import dev.aegis.notifications.domain.NoticeRepository;
import dev.aegis.shared.MemberId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Domain repository over JPA with JSON-encoded parameters. */
@Component
public class JpaNoticeRepository implements NoticeRepository {
    private static final TypeReference<Map<String, String>> PARAMS = new TypeReference<>() {
    };

    private final NoticeJpaRepository jpa;
    private final ObjectMapper mapper;

    JpaNoticeRepository(NoticeJpaRepository jpa, ObjectMapper mapper) {
        this.jpa = jpa;
        this.mapper = mapper;
    }

    @Override
    public Optional<Notice> findById(NoticeId id) {
        return jpa.findById(id.value()).map(this::toDomain);
    }

    @Override
    public List<Notice> findByRecipient(MemberId recipient, Set<NoticeKind> kinds, boolean unreadOnly) {
        List<String> names = names(kinds);
        List<NoticeEntity> rows = unreadOnly
                ? jpa.findByRecipientIdAndKindInAndReadAtIsNullOrderByCreatedAtDesc(recipient.value(), names)
                : jpa.findByRecipientIdAndKindInOrderByCreatedAtDesc(recipient.value(), names);
        return rows.stream().map(this::toDomain).toList();
    }

    @Override
    public Optional<Notice> findByDedupKey(MemberId recipient, String dedupKey) {
        return jpa.findFirstByRecipientIdAndDedupKey(recipient.value(), dedupKey).map(this::toDomain);
    }

    @Override
    public long countUnread(MemberId recipient, Set<NoticeKind> kinds) {
        return jpa.countByRecipientIdAndKindInAndReadAtIsNull(recipient.value(), names(kinds));
    }

    private static List<String> names(Set<NoticeKind> kinds) {
        return kinds.stream().map(NoticeKind::name).toList();
    }

    @Override
    public void save(Notice notice) {
        NoticeEntity existing = jpa.findById(notice.id().value()).orElse(null);
        if (existing != null) {
            existing.setReadAt(notice.readAt().orElse(null));
            jpa.save(existing);
            return;
        }
        jpa.save(new NoticeEntity(notice.id().value(), notice.recipient().value(), notice.kind().name(),
                notice.dedupKey(), mapper.writeValueAsString(new LinkedHashMap<>(notice.params())),
                notice.link().map(l -> l.type().name()).orElse(null), notice.link().map(NoticeLink::id).orElse(null),
                notice.createdAt(), notice.readAt().orElse(null)));
    }

    @Override
    public boolean saveIfAbsent(Notice notice) {
        return jpa.insertIfAbsent(notice.id().value(), notice.recipient().value(), notice.kind().name(),
                notice.dedupKey(), mapper.writeValueAsString(new LinkedHashMap<>(notice.params())),
                notice.link().map(l -> l.type().name()).orElse(null),
                notice.link().map(NoticeLink::id).orElse(null), notice.createdAt()) == 1;
    }

    private Notice toDomain(NoticeEntity e) {
        Map<String, String> params = mapper.readValue(e.getParamsJson(), PARAMS);
        NoticeLink link = e.getLinkType() == null ? null
                : new NoticeLink(NoticeLink.LinkType.valueOf(e.getLinkType()), e.getLinkId());
        return Notice.rehydrate(new NoticeId(e.getId()), new MemberId(e.getRecipientId()),
                NoticeKind.valueOf(e.getKind()), e.getDedupKey(), params, link, e.getCreatedAt(), e.getReadAt());
    }
}
