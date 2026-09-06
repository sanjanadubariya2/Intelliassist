package com.intelliassist.backend.repository;

import com.intelliassist.backend.entity.User;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {

    /** Email is stored lowercased, so callers must normalise before querying. */
    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);
}
