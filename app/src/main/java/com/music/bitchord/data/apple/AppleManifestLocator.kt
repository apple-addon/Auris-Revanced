package com.music.bitchord.data.apple

import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.addon.AddonClient
import com.music.bitchord.data.sources.addon.AddonManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Finds the installed Apple Music manifest.
 *
 * Apple's catalogue is not read from inside the app: it is read from an addon
 * the listener installs, which holds the account, the developer token and the
 * storefront. So "is Apple available?" is not a question about the app's
 * capabilities — it is a question about what the listener has installed.
 *
 * The manifest is found by what it publishes — `catalogueProvider:
 * "apple-music"` — rather than by the addon's name or address. A listener's
 * own Apple Music server is an ordinary addon that happens to serve Apple's
 * catalogue, and it is recognised by its manifest like any other.
 *
 * The answer is cached for a short while, because it is asked on every screen
 * that draws Apple content and a manifest does not change often.
 */
object AppleManifestLocator {

    /** How long a found manifest is trusted before the addons are asked again. */
    private const val TTL_MS = 60_000L

    private val clients = ConcurrentHashMap<String, AddonClient>()

    @Volatile
    private var cachedBaseUrl: String? = null

    @Volatile
    private var cachedAt: Long = 0L

    /**
     * What the last answer was: true when a manifest was found, false when
     * the addons were asked and none of them was Apple's, and null when the
     * question has not been asked yet.
     */
    @Volatile
    private var lastInstalled: Boolean? = null

    /**
     * The base URL of the installed Apple Music manifest, if there is one.
     *
     * Null means the listener has not installed an Apple Music manifest, and
     * so the Apple axes cannot be satisfied. It is not an error: the app says
     * so rather than pretending.
     */
    suspend fun baseUrl(): String? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        cachedBaseUrl?.let { base ->
            if (now - cachedAt < TTL_MS) return@withContext base
        }
        val found = find()
        cachedBaseUrl = found
        cachedAt = now
        lastInstalled = found != null
        found
    }

    /** Whether an Apple Music manifest is installed. */
    suspend fun isInstalled(): Boolean = baseUrl() != null

    /**
     * Whether a config is the installed Apple Music manifest's own, as far as
     * the last answer goes.
     *
     * Null when the question has not been answered yet: an unknown answer is
     * not a "no", and the rule that keeps the app to Apple's catalogue must
     * not refuse a track because the app had not looked.
     */
    fun isAppleConfigCached(configId: String): Boolean? {
        val base = cachedBaseUrl ?: return null
        val config = SourceRegistry.configs.value.firstOrNull { it.baseUrl == base } ?: return null
        return config.id == configId
    }

    /**
     * Whether an Apple Music manifest is installed, as far as the last answer
     * goes.
     *
     * Null when the question has not been asked yet: an unknown answer is not
     * a "no", and a listener who has just installed the addon must not be
     * told their manifest is missing because the app had not looked yet.
     */
    fun isInstalledKnown(): Boolean? = lastInstalled

    /**
     * Whether [configId] is the installed Apple Music manifest's own id.
     *
     * The answer is what tells the resolver that a track's id is an Apple
     * catalogue id — and therefore that another addon may be asked for that
     * very id, rather than for the track by name.
     */
    suspend fun isAppleConfig(configId: String): Boolean {
        val base = baseUrl() ?: return false
        val config = SourceRegistry.configs.value.firstOrNull { it.id == configId }
            ?: return false
        return config.baseUrl == base
    }

    /**
     * The manifest itself, for the fields that say what the addon publishes.
     */
    suspend fun manifest(): Pair<String, AddonManifest>? {
        val base = baseUrl() ?: return null
        val manifest = client(base).manifest().getOrNull() ?: return null
        return base to manifest
    }

    /** Forgets the cached answer, so the next question asks the addons again. */
    fun invalidate() {
        cachedBaseUrl = null
        cachedAt = 0L
    }

    private suspend fun find(): String? {
        val configs = SourceRegistry.configs.value.filter { config ->
            config.kind == SourceKind.ADDON &&
                config.enabled &&
                config.baseUrl.isNotBlank()
        }
        for (config in configs) {
            val manifest = client(config.baseUrl).manifest().getOrNull() ?: continue
            if (AppleManifest.detect(manifest)) return config.baseUrl
        }
        return null
    }

    private fun client(baseUrl: String): AddonClient =
        clients.getOrPut(baseUrl) { AddonClient(baseUrl) }
}
