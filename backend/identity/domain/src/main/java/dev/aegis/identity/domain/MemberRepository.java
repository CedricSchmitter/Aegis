package dev.aegis.identity.domain;

import dev.aegis.shared.MemberId;
import java.util.List;
import java.util.Optional;

/** Aggregate persistence contract for members. */
public interface MemberRepository {
    Optional<Member> findById(MemberId id);

    Optional<Member> findByUsername(Username username);

    List<Member> findAll();

    void save(Member member);
}
