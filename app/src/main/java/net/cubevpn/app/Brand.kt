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
