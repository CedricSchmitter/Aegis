package dev.aegis.identity.application;

import dev.aegis.identity.domain.Member;
import dev.aegis.identity.domain.MemberRepository;
import dev.aegis.identity.domain.Username;
import dev.aegis.shared.MemberId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Test double for application tests; the real adapter is verified against PostgreSQL in the bootstrap module. */
public class InMemoryMemberRepository implements MemberRepository {
    private final Map<MemberId, Member> store = new LinkedHashMap<>();

    @Override
    public Optional<Member> findById(MemberId id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public Optional<Member> findByUsername(Username username) {
        return store.values().stream().filter(member -> member.username().equals(username)).findFirst();
    }

    @Override
    public List<Member> findAll() {
        return new ArrayList<>(store.values());
    }

    @Override
    public void save(Member member) {
        store.put(member.id(), member);
    }
}
