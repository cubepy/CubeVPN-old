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

    val botHandle: String get() = "@$bot"
    val supportHandle: String get() = "@$support"
    val channelHandle: String get() = "@$channel"

    val botUrl: String get() = "https://t.me/$bot"
    val supportUrl: String get() = "https://t.me/$support"
    val channelUrl: String get() = "https://t.me/$channel"

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
}
