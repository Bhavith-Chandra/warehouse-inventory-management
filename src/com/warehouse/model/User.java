package com.warehouse.model;

import java.io.Serializable;

/**
 * Represents an authenticated warehouse-staff user.
 *
 * Two roles are supported:
 *  - ADMIN: may create/delete products and adjust prices
 *  - STAFF: may add/remove stock but not create or delete products
 *
 * The {@code passwordHash} is never sent to the client; the client only
 * ever receives sanitised User objects with the hash field cleared.
 */
public class User implements Serializable {

    private static final long serialVersionUID = 1L;

    public enum Role { ADMIN, STAFF }

    private int id;
    private String username;
    private String passwordHash;
    private Role role;

    public User() {
    }

    public User(int id, String username, String passwordHash, Role role) {
        this.id = id;
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }

    /**
     * @return a copy of this user safe to send across the network
     *         (the password hash is stripped).
     */
    public User sanitised() {
        return new User(id, username, null, role);
    }

    @Override
    public String toString() {
        return username + " (" + role + ")";
    }
}
