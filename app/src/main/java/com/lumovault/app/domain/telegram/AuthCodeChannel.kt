package com.lumovault.app.domain.telegram

/**
 * How Telegram is delivering the code, so the prompt can say "texted" or "calling" instead of
 * guessing. Names follow TDLib's `authenticationCodeType*` constructors; anything the app does not
 * name specifically arrives as [Unknown] rather than being forced into the nearest label.
 *
 * The translation from a TDLib code type to one of these lives in the data layer, with the TDLib
 * types — this enum is what the UI renders, and it names nothing from the client library.
 */
enum class AuthCodeChannel {
    Sms,
    Call,
    FlashCall,
    MissedCall,

    /** A delivery type newer or different than the ones LumoVault names. */
    Unknown,
}
