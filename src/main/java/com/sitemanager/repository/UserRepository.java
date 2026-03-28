package com.sitemanager.repository;

import com.sitemanager.model.User;
import com.sitemanager.model.UserGroup;
import com.sitemanager.model.enums.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
    boolean existsByRole(UserRole role);
    List<User> findByRole(UserRole role);
    boolean existsByGroup(UserGroup group);
    List<User> findByApprovedFalseAndDeniedFalse();
}
