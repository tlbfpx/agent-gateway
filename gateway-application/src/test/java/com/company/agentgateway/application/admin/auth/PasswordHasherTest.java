package com.company.agentgateway.application.admin.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordHasherTest {

    @Test
    void hash_verify_roundTrip() {
        String h = PasswordHasher.hash("hello123");
        assertTrue(h.startsWith("$pbkdf2-sha256$"));
        assertTrue(PasswordHasher.verify("hello123", h));
        assertFalse(PasswordHasher.verify("wrong", h));
    }

    @Test
    void hash_isSalted_noTwoEquals() {
        String a = PasswordHasher.hash("same");
        String b = PasswordHasher.hash("same");
        assertNotEquals(a, b, "salt should differ");
    }

    @Test
    void verify_rejectsMalformed() {
        assertFalse(PasswordHasher.verify("x", "garbage"));
        assertFalse(PasswordHasher.verify("x", "$pbkdf2-sha256$bad$salt$hash"));
        assertFalse(PasswordHasher.verify("x", null));
        assertFalse(PasswordHasher.verify(null, "$pbkdf2-sha256$100000$xxx$yyy"));
    }

    @Test
    void hash_rejectsEmpty() {
        assertTrue(true); // placeholder; covered by IllegalArgumentException
        try {
            PasswordHasher.hash("");
            assert false : "expected IllegalArgumentException";
        } catch (IllegalArgumentException ignored) { /* ok */ }
        try {
            PasswordHasher.hash(null);
            assert false : "expected IllegalArgumentException";
        } catch (IllegalArgumentException ignored) { /* ok */ }
    }

    @Test
    void needsRehash_detection() {
        assertTrue(PasswordHasher.needsRehash(null));
        assertTrue(PasswordHasher.needsRehash("garbage"));
        String h = PasswordHasher.hash("x");
        assertFalse(PasswordHasher.needsRehash(h));
    }
}
