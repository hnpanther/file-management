package com.hnp.filemanagement.repository;

import com.hnp.filemanagement.entity.User;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Integer> {


    boolean existsByUsernameOrPersonelCodeOrNationalCodeOrPhoneNumber(String username, Integer personelCode,
                                                                      String nationalCode, String phoneNumber);

    Optional<User> findByIdOrUsername(int id, String username);

    Optional<User> findByUsername(String username);



    @Query("""
    SELECT u FROM User u
    WHERE (
    (((:searchNumber) IS NULL) OR u.id = (:searchNumber) OR u.personelCode = (:searchNumber))
    AND
    (((:search) IS NULL) OR u.username LIKE CONCAT('%', (:search), '%') OR CONCAT(u.firstName, ' ', u.lastName) LIKE CONCAT('%', (:search), '%'))
    )
    """)
    List<User> findByAllParameterAndPagination(Integer searchNumber, String search,
                                               Pageable pageable);

    @Query("""
    SELECT COUNT(u) FROM User u
    WHERE (
    (((:searchNumber) IS NULL) OR u.id = (:searchNumber) OR u.personelCode = (:searchNumber))
    AND
    (((:search) IS NULL) OR u.username LIKE CONCAT('%', (:search), '%') OR CONCAT(u.firstName, ' ', u.lastName) LIKE CONCAT('%', (:search), '%'))
    )
    """)
    int countByAllParameterAndPagination(Integer searchNumber, String search);
}
