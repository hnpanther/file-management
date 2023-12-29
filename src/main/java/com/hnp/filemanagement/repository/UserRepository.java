package com.hnp.filemanagement.repository;

import com.hnp.filemanagement.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Integer> {


    boolean existsByUsernameOrPersonelCodeOrNationalCodeOrPhoneNumber(String username, Integer personelCode,
                                                                      String nationalCode, String phoneNumber);

    Optional<User> findByIdOrUsername(int id, String username);
}
