package com.wb.signalpro.photos

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wb.signalpro.data.model.MediaId
import com.wb.signalpro.data.model.MediaItem
import com.wb.signalpro.data.model.MediaType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore

@RunWith(AndroidJUnit4::class)
class PairingRecoveryInstrumentedTest {
    @Test fun wrappingKeyCreationCannotRaceWithParallelCredentialReads(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val endpoint = "http://127.0.0.1:8765"
        val store = SignalPairingStore(context)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            store.save(endpoint, "synthetic-concurrent-token")
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("signal-gallery-token-v1") }
            (1..4).map { pool.submit { repeat(20) { assertNull(store.read()) } } }.forEach { it.get() }
            assertFalse("reading missing credentials cannot replace their wrapping key",
                KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias("signal-gallery-token-v1"))
            (1..4).map { pool.submit { repeat(20) {
                store.save(endpoint, "synthetic-concurrent-token")
                assertEquals("synthetic-concurrent-token", store.read()!!.deviceToken)
            } } }.forEach { it.get() }
        } finally {
            pool.shutdownNow()
            GallerySyncEngine(context).use { it.pair(endpoint, "synthetic-test-enrollment-secret", "Android Emulator") }
        }
        Unit
    }

    @Test fun invalidatedKeyCanRecoverStableIdentityAndCenterSwitchCannotReuseOldProofs(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val endpoint = "http://127.0.0.1:8765"
        val enrollment = "synthetic-test-enrollment-secret"
        suspend fun pair(url: String) {
            GallerySyncEngine(context).use { it.pair(url, enrollment, "Android Emulator") }
        }
        pair(endpoint)
        val client = SignalMediaClient(context)
        val identity = client.deviceId
        val item = MediaItem(MediaId("pairing-recovery:1"), 1L, "content://synthetic/pairing/1", MediaType.PHOTO,
            1L, 1L, 1L, "image/jpeg", 32, 32, null, "Synthetic/", "pairing-recovery.jpg")
        val mediaId = client.register(item)
        // Simulate restoring encrypted settings without the original Keystore key.
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("signal-gallery-token-v1") }
        assertFalse(client.paired)
        pair(endpoint)
        assertTrue(client.paired)
        assertEquals(identity, client.deviceId)
        assertEquals(mediaId, client.register(item))
        val oldMedia = client.catalog(0).items.first { it.id == mediaId }
        GallerySyncStore(context).use { store ->
            store.ingest(listOf(oldMedia), 99999L)
            store.saveLocal("synthetic-old", mediaId, verified = true)
            store.saveSourceVersion("synthetic-old", "old-center-version")
        }
        val preview = File(context.filesDir, "gallery-previews/$mediaId.jpg")
        preview.parentFile!!.mkdirs(); preview.writeText("old synthetic preview")
        val original = File(context.filesDir, "gallery-originals/$mediaId")
        original.parentFile!!.mkdirs(); original.writeText("old synthetic original cache")
        context.getSharedPreferences("gallery-remote-failures", 0).edit()
            .putStringSet("ids", setOf("original:$mediaId")).commit()
        try {
            // The alternate loopback authority is an independent namespace to the client.
            pair("http://localhost:8765")
            GallerySyncStore(context).use { store ->
                assertEquals(0L, store.cursor())
                assertTrue(store.allCatalog().isEmpty())
                assertNull(store.local("synthetic-old"))
                assertNull(store.sourceVersion("synthetic-old"))
            }
            assertFalse(preview.exists()); assertFalse(original.exists())
            assertTrue(context.getSharedPreferences("gallery-remote-failures", 0).getStringSet("ids", emptySet())!!.isEmpty())
            assertTrue(runCatching { client.fetchOriginal(oldMedia) { _, _ -> } }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
            assertTrue(runCatching { client.fetchPreview(oldMedia) }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
            assertEquals(identity, client.deviceId)
        } finally { pair(endpoint) }
        Unit
    }
}
