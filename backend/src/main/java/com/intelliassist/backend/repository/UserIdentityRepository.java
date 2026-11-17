package com.intelliassist.backend.repository;

import com.intelliassist.backend.entity.IdentityProvider;
import com.intelliassist.backend.entity.UserIdentity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserIdentityRepository extends JpaRepository<UserIdentity, UUID> {

    /** The user is fetched with it: signing in always needs both. */
    @EntityGraph(attributePaths = "user")
    Optional<UserIdentity> findByProviderAndSubject(IdentityProvider provider, String subject);
}
