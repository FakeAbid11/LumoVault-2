package com.lumovault.app.domain.telegram

/** How Telegram is delivering the code, so the prompt can say "texted" instead of guessing. */
enum class AuthCodeChannel {
    Sms,
    Call,
    Email,
    FlashCall,
    MissedCall,

    /** TDLib reported a channel this build does not name. */
    Unknown,
    ;

    companion object {
        /** Maps an `authCodeType*` `@type` string; unknown values stay [Unknown] rather than throwing. */
        fun fromTdType(type: String?): AuthCodeChannel = when (type) {
            "authCodeTypeSms" -> Sms
            "authCodeTypeCall" -> Call
            "authCodeTypeEmailCode", "authCodeTypeEmail" -> Email
            "authCodeTypeFlashCall" -> FlashCall
            "authCodeTypeMissedCall" -> MissedCall
            else -> Unknown
        }
    }
}
