package net.cubevpn.app

/**
 * Everything that differs between one reseller's build of this app and another's.
 *
 * All of it arrives from `secrets.properties` at build time (see build.gradle.kts) and falls
 * back to CubeVPN's own values, so a plain checkout still builds the CubeVPN app unchanged.
 *
 * User-facing text does NOT reference any of this directly. Copy in [Strings] carries the
 * placeholders `{app}`, `{bot}`, `{support}` and `{channel}` instead, and [Strings.get] runs
 * [apply] over the result — that way a sentence stays grammatical in both languages no matter
 * whose brand is dropped into it, and adding a brand never means re-translating anything.
 */
object Brand {

    val appName: String = BuildConfig.BRAND_APP_NAME.trim().ifEmpty { "CubeVPN" }

    /** Telegram usernames are stored bare; the `@` and the t.me URL are derived below. */
    val bot: String = handle(BuildConfig.BRAND_BOT, "cubevvpn_bot")
    val support: String = handle(BuildConfig.BRAND_SUPPORT, "cube_sup")
    val channel: String = handle(BuildConfig.BRAND_CHANNEL, "cube_vpnn")

    /** Blank for any brand that doesn't take TON donations — the card hides itself then. */
    val tonWallet: String = BuildConfig.BRAND_TON_WALLET.trim()

    /**
     * Whether this build asks anyone for money on its own behalf.
     *
     * The donation screen belongs to whoever built the app, and a reseller's copy must not
     * carry it: their users would be looking at a stranger's card number, or — worse, and what
     * actually shipped once — at a placeholder row of zeros above a plea written in someone
     * else's voice. Rather than a flag that can disagree with reality, this asks whether there
     * is any account to donate to at all, which for a reseller build there never is.
     */
    val donationsEnabled: Boolean =
        BuildConfig.DONATION_CARD_NUMBER.isNotBlank() || tonWallet.isNotEmpty()

    /**
     * The name split for the wordmark: what comes before the accented tail, and the tail.
     *
     * The header has always read "Cube" in the text colour and "VPN" in the accent, which was
     * written as two literals — so every reseller's app announced itself as CubeVPN at the top
     * of its own home screen. The same treatment is what their name deserves, so the seam is
     * found rather than hard-coded: the last word when the name has spaces ("OG VPN"), a
     * trailing "VPN" when it does not ("CubeVPN"), and otherwise nothing, because a name with
     * no natural seam looks worse cut than whole.
     */
    val nameHead: String get() = nameSplit.first
    val nameAccentTail: String get() = nameSplit.second

    val botHandle: String get() = "@$bot"
    val supportHandle: String get() = "@$support"
    val channelHandle: String get() = "@$channel"

    val botUrl: String get() = "https://t.me/$bot"
    val supportUrl: String get() = "https://t.me/$support"
    val channelUrl: String get() = "https://t.me/$channel"

    /**
     * Accounts exist only when the build points at a panel that can run them.
     *
     * A reseller who sells through their own system rather than ours gets the app with no
     * API_BASE_URL, and for them the sign-in screen is not merely useless but wrong: the login
     * code is sent by the panel's bot, so there is nothing on the other end to send it. Rather
     * than carry a second flag that can disagree with the URL, the URL itself is the answer.
     */
    val accountsEnabled: Boolean = BuildConfig.API_BASE_URL.isNotBlank()

    /**
     * When a promotional build stops working, or 0 for a build that never does.
     *
     * The date is read as the end of that day in the device's own zone, which is the reading a
     * reseller means by "seven days": a build stamped with the 9th works all through the 9th.
     */
    val expiresAt: Long = parseExpiry(BuildConfig.BRAND_EXPIRES_AT)

    val hasExpired: Boolean get() = expiresAt > 0L && System.currentTimeMillis() > expiresAt

    /**
     * Which brand this build is, for the shared platform API.
     *
     * A reseller who never bought a bot from us still has a bot of their own, and that is the
     * one their customers know. They hand over its token, we send the one-time codes through
     * it, and this key is how the platform knows whose bot to send them through. It is an
     * identifier, not a credential: it says which brand is calling, and every request it
     * accompanies is still authorized on its own.
     */
    val key: String = BuildConfig.BRAND_KEY.trim()

    /**
     * The colour this brand chose, or null to leave the choice to the person using the app.
     *
     * A reseller picks one of the six by name. Null is CubeVPN's own build and any brand that
     * did not care, where the accent stays a setting like it always was — but where a brand did
     * choose, the picker disappears, because a colour the user can change is not a brand colour.
     *
     * An unrecognised name reads as null rather than failing the build: a typo in a brand file
     * should cost that brand its colour, not its app.
     */
    val accent: AccentTheme? = BuildConfig.BRAND_ACCENT.trim().uppercase().let { name ->
        if (name.isEmpty()) null else AccentTheme.entries.firstOrNull { it.name == name }
    }

    /** Substitutes the brand placeholders. Cheap enough to sit in every string lookup. */
    fun apply(text: String): String {
        if (!text.contains('{')) return text
        return text
            .replace("{app}", appName)
            .replace("{bot}", botHandle)
            .replace("{support}", supportHandle)
            .replace("{channel}", channelHandle)
    }

    private fun handle(configured: String, fallback: String): String =
        configured.trim().removePrefix("@").ifEmpty { fallback }

    /** Computed once: the name cannot change while the process is running. */
    private val nameSplit: Pair<String, String> = splitName(appName)

    private fun splitName(name: String): Pair<String, String> {
        val trimmed = name.trim()
        // The space stays with the head so the two runs still read as one name when drawn.
        val space = trimmed.lastIndexOf(' ')
        if (space > 0) return trimmed.substring(0, space + 1) to trimmed.substring(space + 1)
        if (trimmed.length > 3 && trimmed.takeLast(3).equals("vpn", ignoreCase = true)) {
            return trimmed.dropLast(3) to trimmed.takeLast(3)
        }
        return trimmed to ""
    }

    /**
     * "yyyy-MM-dd" → the last millisecond of that day, or 0 for blank or malformed input.
     *
     * Falling back to "never expires" on a value we cannot read is the only safe direction: a
     * typo in a brand file must not brick an app that is already on people's phones.
     */
    private fun parseExpiry(raw: String): Long {
        val value = raw.trim()
        if (value.isEmpty()) return 0L
        val parts = value.split("-")
        if (parts.size != 3) return 0L
        val year = parts[0].toIntOrNull() ?: return 0L
        val month = parts[1].toIntOrNull() ?: return 0L
        val day = parts[2].toIntOrNull() ?: return 0L
        return runCatching {
            java.util.Calendar.getInstance().apply {
                clear()
                set(year, month - 1, day, 23, 59, 59)
                set(java.util.Calendar.MILLISECOND, 999)
            }.timeInMillis
        }.getOrDefault(0L)
    }
}
