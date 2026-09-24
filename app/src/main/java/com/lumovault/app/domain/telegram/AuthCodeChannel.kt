package com.lumovault.app.domain.telegram

/**
 * How Telegram is delivering the code, so the prompt can say "texted" or "calling" instead of
 * guessing. Names follow TDLib's `authenticationCodeType*` constructors; anything the app does not
 * name specifically arrives as [Unknown] rather than being forced into the nearest label.
 */
enum class AuthCodeChannel {
    Sms,
    Call,
    FlashCall,
    MissedCall,

    /** A delivery type newer or different than the ones LumoVault names. */
    Unknown,
    ;

    companion object {
        fun fromTdType(type: String?): AuthCodeChannel = when (type) {
            "authenticationCodeTypeSms" -> Sms
            "authenticationCodeTypeCall" -> Call
            "authenticationCodeTypeFlashCall" -> FlashCall
            "authenticationCodeTypeMissedCall" -> MissedCall
            else -> Unknown
        }
    }
}
