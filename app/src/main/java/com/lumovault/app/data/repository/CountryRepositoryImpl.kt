package com.lumovault.app.data.repository

import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.lumovault.app.domain.model.Country
import com.lumovault.app.domain.repository.CountryRepository
import java.util.Locale

/**
 * Builds the country list from two sources that are already on the device: the JDK's ISO 3166
 * region codes for names, and libphonenumber's metadata for calling codes.
 *
 * This deliberately avoids a bundled JSON/CSV of countries. A hand-maintained table of ~240 rows is
 * the kind of data that quietly rots, and it would have to agree with the parser that normalizes
 * the number anyway — so both come from the same library. Flags are derived from the ISO code, so
 * there are no image assets either.
 */
class CountryRepositoryImpl : CountryRepository {
    private val phoneUtil: PhoneNumberUtil by lazy { PhoneNumberUtil.getInstance() }

    override val countries: List<Country> by lazy {
        Locale.getISOCountries()
            .mapNotNull { iso2 ->
                val callingCode = phoneUtil.getCountryCodeForRegion(iso2)
                if (callingCode <= 0) return@mapNotNull null
                Country(
                    iso2 = iso2,
                    callingCode = callingCode,
                    name = Locale.Builder().setRegion(iso2).build().getDisplayName(Locale.ENGLISH),
                )
            }
            .sortedBy { country -> country.name }
    }

    /**
     * Device region when it is a real one, otherwise null so the selector opens unselected rather
     * than assuming the user is somewhere they are not.
     */
    override fun suggestedForDevice(): Country? {
        val regionCode = Locale.getDefault().country
        if (regionCode.isBlank() || regionCode == "ZZ") return null
        return countries.firstOrNull { it.iso2 == regionCode }
    }
}
