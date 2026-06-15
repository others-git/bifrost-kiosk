package live.theundead.bifrost.kiosk.voice

/**
 * Pure decision of **where** [VoskSpeechEngine] should load its acoustic model
 * from, given what's present on disk. Android-free so it can be unit-tested
 * (see `ModelResolverTest`) — the engine does the actual file IO.
 *
 * Precedence, highest first:
 *  1. [EXTERNAL] — a model the user pushed to the app's external dir
 *     (`…/Android/data/<pkg>/files/model-en-us`). "Bring your own model": lets the
 *     slim (model-less) APK gain voice, and lets a bundled APK be overridden,
 *     **without a rebuild**. Chosen only when it must be (re)mirrored into
 *     internal storage — i.e. there's no internal copy yet, or the external copy
 *     is newer (a fresh push).
 *  2. [INTERNAL] — the unpacked model already cached in internal storage (from a
 *     prior asset unpack or external mirror). Internal storage is a real
 *     filesystem, so Vosk can mmap it; external (FUSE) is mirrored here first.
 *  3. [ASSET] — the model bundled into the APK's assets (the "bundled" build).
 *  4. [NONE] — nothing available; the engine stays idle (graceful degradation).
 */
enum class ModelSource { EXTERNAL, INTERNAL, ASSET, NONE }

object ModelResolver {
    /**
     * @param externalPresent a non-empty external (pushed) model dir exists
     * @param externalMtime    last-modified of that dir (millis); ignored if absent
     * @param internalPresent  a non-empty internal unpacked model dir exists
     * @param internalMtime    last-modified of that dir (millis); ignored if absent
     * @param assetPresent     a model is bundled in the APK assets
     */
    fun resolve(
        externalPresent: Boolean,
        externalMtime: Long,
        internalPresent: Boolean,
        internalMtime: Long,
        assetPresent: Boolean,
    ): ModelSource = when {
        externalPresent && (!internalPresent || externalMtime > internalMtime) -> ModelSource.EXTERNAL
        internalPresent -> ModelSource.INTERNAL
        assetPresent -> ModelSource.ASSET
        else -> ModelSource.NONE
    }
}
