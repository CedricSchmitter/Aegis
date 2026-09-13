package dev.aegis.identity.adapters.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data access to the members table; stays inside the adapter. */
interface MemberJpaRepository extends JpaRepository<MemberEntity, UUID> {
    Optional<MemberEntity> findByUsername(String username);
}
