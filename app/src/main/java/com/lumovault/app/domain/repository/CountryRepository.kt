package com.lumovault.app.domain.repository

import com.lumovault.app.domain.model.Country

interface CountryRepository {
    /** Every dialling region LumoVault can offer, sorted by display name. */
    val countries: List<Country>

    /**
     * A suggestion from the device locale, or null when there is nothing trustworthy to infer.
     * The user can always change it — no country is treated as the default.
     */
    fun suggestedForDevice(): Country?
}
