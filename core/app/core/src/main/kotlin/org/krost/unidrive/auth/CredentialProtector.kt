package org.krost.unidrive.auth

/** Protects serialized credentials before they are persisted by [CredentialStore]. */
public interface CredentialProtector {
    /** Return an opaque on-disk reference after securely storing [plaintext]. */
    public fun protect(id: String, plaintext: String): String

    /** Decode a stored value; [CredentialData.protected] is false for legacy plaintext. */
    public fun decode(id: String, stored: String): CredentialData

    /** Remove the external protected value associated with [id]. */
    public fun delete(id: String)

    /** Remove a newly written reference when its file transaction did not commit. */
    public fun discard(id: String, reference: String) = Unit
}

public data class CredentialData(
    val plaintext: String,
    val protected: Boolean,
)

/** A protection backend failure must never be treated as a missing credential. */
public class CredentialProtectionException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)
