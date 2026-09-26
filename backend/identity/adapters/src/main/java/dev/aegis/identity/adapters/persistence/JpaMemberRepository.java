package dev.aegis.identity.adapters.persistence;

import dev.aegis.identity.domain.Appearance;
import dev.aegis.identity.domain.Member;
import dev.aegis.identity.domain.MemberKind;
import dev.aegis.identity.domain.MemberRepository;
import dev.aegis.identity.domain.Palette;
import dev.aegis.identity.domain.PasswordHash;
import dev.aegis.identity.domain.Username;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.ProductLocale;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Implements the domain repository with explicit mapping between aggregate and persistence entity. */
@Component
public class JpaMemberRepository implements MemberRepository {
    private final MemberJpaRepository jpa;

    JpaMemberRepository(MemberJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<Member> findById(MemberId id) {
        return jpa.findById(id.value()).map(JpaMemberRepository::toDomain);
    }

    @Override
    public Optional<Member> findByUsername(Username username) {
        return jpa.findByUsername(username.value()).map(JpaMemberRepository::toDomain);
    }

    @Override
    public List<Member> findAll() {
        return jpa.findAll().stream().map(JpaMemberRepository::toDomain).toList();
    }

    @Override
    public void save(Member member) {
        MemberEntity entity = jpa.findById(member.id().value()).orElse(null);
        if (entity == null) {
            jpa.save(new MemberEntity(member.id().value(), member.username().value(), member.displayName(),
                    member.passwordHash().value(), member.locale().name(), member.appearance().palette().name(),
                    member.appearance().effects(), member.isActive(), member.kind().name(), member.createdAt()));
            return;
        }
        entity.setDisplayName(member.displayName());
        entity.setPasswordHash(member.passwordHash().value());
        entity.setLocale(member.locale().name());
        entity.setPalette(member.appearance().palette().name());
        entity.setEffectsEnabled(member.appearance().effects());
        entity.setActive(member.isActive());
        entity.setKind(member.kind().name());
        jpa.save(entity);
    }

    private static Member toDomain(MemberEntity entity) {
        return Member.rehydrate(new MemberId(entity.getId()), new Username(entity.getUsername()),
                entity.getDisplayName(), new PasswordHash(entity.getPasswordHash()),
                ProductLocale.valueOf(entity.getLocale()),
                new Appearance(Palette.valueOf(entity.getPalette()), entity.isEffectsEnabled()), entity.isActive(),
                MemberKind.valueOf(entity.getKind()), entity.getCreatedAt());
    }
}
