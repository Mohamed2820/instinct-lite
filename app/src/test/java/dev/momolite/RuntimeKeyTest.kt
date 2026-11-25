package dev.momolite

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Runs ApiSettings on a (simulated) Android runtime. The Keystore is not available in Robolectric, so a plain in-memory prefs file stands in for the encrypted one. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RuntimeKeyTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    @Before fun setUp() { ApiSettings.prefsOverride = ctx.getSharedPreferences("test_plain", Context.MODE_PRIVATE).also { it.edit().clear().commit() } }
    @After fun tearDown() { ApiSettings.prefsOverride = null }

    private val a = "https://api.one.example/v1"
    private val b = "https://api.two.example/v1"

    @Test fun keyKeptForSameEndpointWhenBlank() {
        assertTrue(ApiSettings.save(ctx, ProviderType.OPENAI_COMPATIBLE, "k-1", "m", "", a))
        assertTrue(ApiSettings.save(ctx, ProviderType.OPENAI_COMPATIBLE, "", "m2", "", "$a/"))
        assertEquals("k-1", ApiSettings.config(ctx)!!.apiKey)
        assertEquals("m2", ApiSettings.config(ctx)!!.model)
    }

    @Test fun keyDroppedAndProviderDisabledWhenEndpointChanges() {
        ApiSettings.save(ctx, ProviderType.OPENAI_COMPATIBLE, "k-1", "m", "", a)
        ApiSettings.setEnabled(ctx, true)
        ApiSettings.save(ctx, ProviderType.OPENAI_COMPATIBLE, "", "m", "", b)
        assertFalse(ApiSettings.hasKey(ctx))
        assertFalse(ApiSettings.isEnabled(ctx))
        assertEquals("", ApiSettings.config(ctx)!!.apiKey)
    }

    @Test fun keyDroppedWhenTypeChanges() {
        ApiSettings.save(ctx, ProviderType.OPENAI_COMPATIBLE, "k-1", "m", "", a)
        ApiSettings.save(ctx, ProviderType.CUSTOM, "", "m", "", a)
        assertFalse(ApiSettings.hasKey(ctx))
    }

    @Test fun newKeyAcceptedForNewEndpoint() {
        ApiSettings.save(ctx, ProviderType.OPENAI_COMPATIBLE, "k-1", "m", "", a)
        ApiSettings.save(ctx, ProviderType.OPENAI_COMPATIBLE, "k-2", "m", "", b)
        assertEquals("k-2", ApiSettings.config(ctx)!!.apiKey)
    }

    @Test fun cloudflareKeyBoundToAccount() {
        ApiSettings.save(ctx, ProviderType.CLOUDFLARE, "k-cf", "m", "acc1", "")
        assertEquals("k-cf", ApiSettings.keyForTest(ctx, ProviderType.CLOUDFLARE, "", "acc1", ""))
        assertEquals("", ApiSettings.keyForTest(ctx, ProviderType.CLOUDFLARE, "", "acc2", ""))
    }

    @Test fun testConnectionNeverUsesStoredKeyForOtherAddress() {
        ApiSettings.save(ctx, ProviderType.OPENAI_COMPATIBLE, "k-1", "m", "", a)
        assertEquals("", ApiSettings.keyForTest(ctx, ProviderType.OPENAI_COMPATIBLE, "", "", "https://evil.example/v1"))
        assertEquals("k-1", ApiSettings.keyForTest(ctx, ProviderType.OPENAI_COMPATIBLE, "", "", a))
        assertEquals("typed", ApiSettings.keyForTest(ctx, ProviderType.OPENAI_COMPATIBLE, " typed ", "", "https://x.example/v1"))
    }

    @Test fun legacyKeyWithoutDestinationIsNotReused() {
        ApiSettings.prefsOverride!!.edit().putString("api_key", "old").putString("address", a).putString("type", "OPENAI_COMPATIBLE").commit()
        assertEquals("", ApiSettings.keyForTest(ctx, ProviderType.OPENAI_COMPATIBLE, "", "", a))
    }

    @Test fun clearRemovesEverything() {
        ApiSettings.save(ctx, ProviderType.OPENAI_COMPATIBLE, "k-1", "m", "", a)
        ApiSettings.setEnabled(ctx, true)
        ApiSettings.clear(ctx)
        assertFalse(ApiSettings.hasKey(ctx)); assertFalse(ApiSettings.isEnabled(ctx))
        assertEquals("", ApiSettings.config(ctx)!!.address)
    }

    @Test fun concurrentSavesStayConsistent() {
        val ts = (1..8).map { i -> Thread { repeat(25) { ApiSettings.save(ctx, ProviderType.OPENAI_COMPATIBLE, "k-$i", "m$i", "", "https://h$i.example/v1") } } }
        ts.forEach { it.start() }; ts.forEach { it.join() }
        val c = ApiSettings.config(ctx)!!
        val i = c.apiKey.removePrefix("k-")
        assertEquals("https://h$i.example/v1", c.address) // key and address always belong together
        assertEquals("m$i", c.model)
    }
}
