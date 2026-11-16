package com.nuvio.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.engine.NuvioEngine
import com.nuvio.engine.NuvioEngineConfig
import com.nuvio.engine.NuvioUploadMode
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NuvioEngineNativeSmokeTest {
    @Test
    fun loadsPinnedNativeEngineAndCreatesAndroidTrustStore() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.noBackupFilesDir, "engine-smoke-${UUID.randomUUID()}")
        assertTrue(directory.mkdirs())

        try {
            val data = File(directory, "data")
            val engine = NuvioEngine.create(
                NuvioEngineConfig(
                    dataDirectory = data,
                    cacheDirectory = File(directory, "cache"),
                    memoryCacheCapacityBytes = 4 * 1024 * 1024,
                    diskCacheCapacityBytes = 0,
                    uploadMode = NuvioUploadMode.Disabled,
                    streamInactivityTimeoutMilliseconds = 0,
                    warmTorrentTimeoutMilliseconds = 0,
                ),
            )
            try {
                assertEquals("0.1.6", NuvioEngine.version)
                assertTrue(NuvioEngine.protocolBackendVersion.startsWith("2.0.12"))
                val certificates = File(data, "tls/android-ca.pem")
                assertTrue(certificates.isFile)
                assertTrue(certificates.length() > 0)
            } finally {
                engine.close()
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
