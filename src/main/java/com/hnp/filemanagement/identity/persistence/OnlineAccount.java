package com.hnp.filemanagement.identity.persistence;

/**
 * The account a signed-in session belongs to, for the page of who is signed in (2.16.0): its id, its
 * username and name as stored, whether it is enabled, and whether it holds ADMIN - the id of that role,
 * or {@code null}.
 */
public record OnlineAccount(Integer id, String username, String firstName, String lastName, int enabled,
                            Integer administratorRoleId) {

    public boolean administrator() {
        return administratorRoleId != null;
    }
}
