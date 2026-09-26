package com.lumovault.app.data.remote.telegram

import com.lumovault.app.domain.telegram.TelegramAuthFailure
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * TDLib reports failures as machine tokens; these tests pin the token → meaning table, because the
 * user-facing sentences are written against the meaning and never against Telegram's own text.
 */
class TdErrorMapperTest {
    private fun kind(code: Int, message: String) = TdErrorMapper.from(code, message)

    @Test
    fun `bad number, bad code and expired code are three different asks`() {
        assertEquals(
            TelegramAuthFailure.Kind.InvalidPhoneNumber,
            kind(400, "PHONE_NUMBER_INVALID").kind,
        )
        assertEquals(TelegramAuthFailure.Kind.InvalidCode, kind(400, "AUTH_CODE_INVALID").kind)
        assertEquals(TelegramAuthFailure.Kind.CodeExpired, kind(400, "AUTH_CODE_EXPIRED").kind)
    }

    @Test
    fun `an expired code is not reported as a wrong code`() {
        // Order matters: both contain "CODE_", and telling someone they typed it wrong when it had
        // simply lapsed sends them in circles.
        assertEquals(TelegramAuthFailure.Kind.CodeExpired, kind(400, "AUTH_CODE_EXPIRED").kind)
    }

    @Test
    fun `wrong password is named as password`() {
        assertEquals(TelegramAuthFailure.Kind.PasswordIncorrect, kind(401, "PASSWORD_HASH_INVALID").kind)
    }

    @Test
    fun `flood wait keeps the retry window instead of dropping it`() {
        val failure = kind(420, "FLOOD_WAIT_312")

        assertEquals(TelegramAuthFailure.Kind.TooManyRequests, failure.kind)
        assertEquals(312, failure.retryAfterSeconds)
    }

    @Test
    fun `a dead session is distinguished from a mistyped credential`() {
        assertEquals(TelegramAuthFailure.Kind.SessionInvalid, kind(401, "SESSION_REVOKED").kind)
        assertEquals(TelegramAuthFailure.Kind.SessionInvalid, kind(401, "AUTH_KEY_UNREGISTERED").kind)
    }

    @Test
    fun `connectivity problems are named as connectivity`() {
        assertEquals(TelegramAuthFailure.Kind.NetworkUnavailable, kind(502, "Bad Gateway").kind)
        assertEquals(TelegramAuthFailure.Kind.NetworkUnavailable, kind(400, "NETWORK_ERROR").kind)
    }

    @Test
    fun `anything unmapped becomes unexpected rather than crashing`() {
        assertEquals(TelegramAuthFailure.Kind.Unexpected, kind(400, "SOMETHING_NEW").kind)
    }

    @Test
    fun `tokens are matched case-insensitively`() {
        assertEquals(TelegramAuthFailure.Kind.InvalidCode, kind(400, "auth_code_invalid").kind)
    }
}
