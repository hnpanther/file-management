package com.hnp.filemanagement.identity.domain;


/**
 * A user as the pages and the services pass it around (issue 29: one mapper per feature, in place
 * of {@code ModelConverterUtil}).
 *
 * <p>Reads the user's own columns only - no association - so any loaded {@link User} will do.
 * The password is never copied: the DTO carries a mask, so a form re-rendered from it cannot send
 * the hash back, and no log of the DTO can show it (issue 92).
 */
public final class UserMapper {

    /** What the DTO carries in place of the password. */
    public static final String PASSWORD_MASK = "**********";

    private UserMapper() {
    }

    public static UserDTO toDto(User user) {
        UserDTO dto = new UserDTO();
        dto.setId(user.getId());
        dto.setUsername(user.getUsername());
        dto.setPersonelCode(user.getPersonelCode());
        dto.setNationalCode(user.getNationalCode());
        dto.setPhoneNumber(user.getPhoneNumber());
        dto.setEmail(user.getEmail());
        dto.setPassword(PASSWORD_MASK);
        dto.setFirstName(user.getFirstName());
        dto.setLastName(user.getLastName());
        dto.setEnabled(user.getEnabled());
        dto.setState(user.getState());
        dto.setLoginType(user.getLoginType());
        return dto;
    }
}
