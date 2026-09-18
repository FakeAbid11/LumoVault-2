package com.lumovault.lumovault.core.auth

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Telegram sign-in contract.
 *
 * Ported from lib/core/auth/auth_service.dart. The load-bearing member is
 * [hasResolvedAuth]: "not signed in" is only a safe conclusion once the auth
 * state has actually been read from TDLib. Before that it means "still
 * connecting", and drawing a Sign-In button can start a redundant second
 * login for a session that is about to be restored.
 */
interface AuthService {
    val currentState: AuthState
    val state: StateFlow<AuthState>
    val hasResolvedAuth: Boolean

    suspend fun initialize()
    suspend fun sendCode(phoneNumber: String): AuthResult
    suspend fun verifyCode(code: String): AuthResult
    suspend fun submitPassword(password: String): AuthResult
    suspend fun logout()
    fun dispose()
}

/**
 * Sign-in progress. Distinct from [AuthResult], which is the outcome of one
 * operation; this is the standing state the UI renders.
 */
enum class AuthState {
    unauthenticated,
    codeSent,
    passwordRequired,
    authenticated,
    error,
    loading,
}

/** The outcome of a single [AuthService] call. */
sealed interface AuthResult {
    data object Success : AuthResult
    data class CodeSent(val phoneNumber: String) : AuthResult
    data object PasswordRequired : AuthResult
    data class Error(val message: String, val code: String? = null) : AuthResult
}
