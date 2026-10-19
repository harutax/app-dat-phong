package datphong.vn.repository;

import datphong.vn.exception.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    boolean existsByEmail(String email);

    Optional<User> findByEmail(String email);

    @Query("select distinct u from User u left join fetch u.roles r left join fetch r.permissions where u.id = :id")
    Optional<User> findWithAuthorities(@Param("id") Long id);

    @Query("select distinct u from User u left join fetch u.roles r left join fetch r.permissions where u.email = :email")
    Optional<User> findWithAuthoritiesByEmail(@Param("email") String email);

    @Query(value = "select u from User u where (:q is null or lower(u.email) like lower(concat('%', cast(:q as string), '%')) or lower(u.fullName) like lower(concat('%', cast(:q as string), '%')))",
            countQuery = "select count(u) from User u where (:q is null or lower(u.email) like lower(concat('%', cast(:q as string), '%')) or lower(u.fullName) like lower(concat('%', cast(:q as string), '%')))")
    Page<User> search(@Param("q") String q, Pageable pageable);

    @Query(value = "select count(*) from user_roles ur join roles r on r.id = ur.role_id join users u on u.id = ur.user_id where r.code = 'ADMIN' and u.status = 'ACTIVE'", nativeQuery = true)
    long countActiveAdmins();
}
