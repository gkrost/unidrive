package org.krost.unidrive.internxt

import org.krost.unidrive.internxt.model.InternxtCredentials

/**
 * Convenience entrypoint for non-CLI consumers (Android, future tools)
 * that need to run the Internxt login flow once without owning an
 * AuthService instance.
 *
 * Builds a temporary AuthService, initialises it, runs authenticate(...),
 * persists credentials at [config.tokenPath]/credentials.json, and returns
 * them. The temporary AuthService is closed before this returns.
 *
 * Thread safety: each call constructs its own AuthService. Safe to invoke
 * concurrently for distinct profile token paths. Concurrent invocations
 * against the SAME tokenPath race on credential persistence — don't.
 *
 * See docs/superpowers/specs/2026-05-13-internxt-android-auth-flow-design.md
 * for the exception contract.
 */
suspend fun authenticateInternxt(
    config: InternxtConfig,
    email: String,
    password: String,
    tfaCode: String? = null,
): InternxtCredentials =
    AuthService(config).use { svc ->
        svc.initialize()
        svc.authenticate(email, password, tfaCode)
    }
