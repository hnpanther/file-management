package com.hnp.filemanagement.identity.persistence;

/**
 * The account a locked or counted sign-in name belongs to, for the locked-sign-ins page (roadmap
 * 12.1): its id, its username as stored, whether it is enabled, and whether it holds ADMIN - the
 * id of that role, or {@code null}.
 */
public record SignInAccount(Integer id, String username, int enabled, Integer administratorRoleId) {

    public boolean administrator() {
        return administratorRoleId != null;
    }
}
