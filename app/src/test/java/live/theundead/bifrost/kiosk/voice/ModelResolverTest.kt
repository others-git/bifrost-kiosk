package live.theundead.bifrost.kiosk.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelResolverTest {

    private fun resolve(
        externalPresent: Boolean = false,
        externalMtime: Long = 0L,
        internalPresent: Boolean = false,
        internalMtime: Long = 0L,
        assetPresent: Boolean = false,
    ) = ModelResolver.resolve(
        externalPresent, externalMtime, internalPresent, internalMtime, assetPresent,
    )

    @Test
    fun nothingPresent_isIdle() {
        assertEquals(ModelSource.NONE, resolve())
    }

    @Test
    fun onlyBundledAsset_usesAsset() {
        assertEquals(ModelSource.ASSET, resolve(assetPresent = true))
    }

    @Test
    fun internalCacheBeatsBundledAsset() {
        assertEquals(ModelSource.INTERNAL, resolve(internalPresent = true, assetPresent = true))
    }

    @Test
    fun pushedModel_withNoInternalCache_usesExternal() {
        assertEquals(ModelSource.EXTERNAL, resolve(externalPresent = true, externalMtime = 100))
    }

    @Test
    fun freshlyPushedModel_newerThanCache_reMirrorsFromExternal() {
        assertEquals(
            ModelSource.EXTERNAL,
            resolve(
                externalPresent = true, externalMtime = 200,
                internalPresent = true, internalMtime = 100,
            ),
        )
    }

    @Test
    fun alreadyMirroredModel_cacheNotOlder_usesInternal() {
        // After a mirror the internal copy is >= the external mtime, so we must NOT
        // recopy the (tens of MB) model on every boot.
        assertEquals(
            ModelSource.INTERNAL,
            resolve(
                externalPresent = true, externalMtime = 100,
                internalPresent = true, internalMtime = 100,
            ),
        )
    }

    @Test
    fun pushedModel_overridesBundledAsset_evenWithoutCache() {
        // BYO on a *bundled* APK: a pushed model wins over the baked-in one.
        assertEquals(
            ModelSource.EXTERNAL,
            resolve(externalPresent = true, externalMtime = 50, assetPresent = true),
        )
    }
}
