package com.inputleaf.android.storage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import com.inputleaf.android.network.CachedServerAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AppPreferencesTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `verified addresses for two Mac hostnames survive restart and update independently`() = runBlocking {
        val a = CachedServerAddress("192.168.1.10", "ab".repeat(32))
        val b = CachedServerAddress("192.168.1.68", "cd".repeat(32))
        withPreferences { prefs, _ ->
            prefs.saveFingerprint("mac-a.local", a.fingerprint)
            prefs.saveFingerprint("mac-b.local", b.fingerprint)
            prefs.saveCachedAddress("mac-a.local", a)
            prefs.saveCachedAddress("mac-b.local", b)
        }
        withPreferences { prefs, _ ->
            assertThat(prefs.cachedAddressFor("mac-a.local").first()).isEqualTo(a)
            assertThat(prefs.cachedAddressFor("mac-b.local").first()).isEqualTo(b)
            prefs.saveCachedAddress("mac-a.local", a.copy(address = "10.0.0.20"))
        }
        withPreferences { prefs, _ ->
            assertThat(prefs.cachedAddressFor("mac-a.local").first()?.address).isEqualTo("10.0.0.20")
            assertThat(prefs.cachedAddressFor("mac-b.local").first()).isEqualTo(b)
            prefs.removeFingerprint("mac-a.local")
            assertThat(prefs.cachedAddressFor("mac-a.local").first()).isNull()
            prefs.saveFingerprint("mac-a.local", a.fingerprint)
            assertThat(prefs.cachedAddressFor("mac-a.local").first()).isNull()
        }
    }

    @Test fun `address cache cannot introduce or change certificate trust`() = runBlocking {
        val cached = CachedServerAddress("192.168.1.10", "ab".repeat(32))
        withPreferences { prefs, _ ->
            prefs.saveCachedAddress("mac.local", cached)
            assertThat(prefs.cachedAddressFor("mac.local").first()).isNull()
            assertThat(prefs.fingerprintFor("mac.local").first()).isNull()
            prefs.saveFingerprint("mac.local", "cd".repeat(32))
            prefs.saveCachedAddress("mac.local", cached)
            assertThat(prefs.cachedAddressFor("mac.local").first()).isNull()
            prefs.saveCachedAddress("mac.local", cached.copy(fingerprint = "cd".repeat(32)))
            prefs.saveFingerprint("mac.local", "ef".repeat(32))
            assertThat(prefs.cachedAddressFor("mac.local").first()).isNull()
        }
    }

    @Test fun `corrupt address cache is ignored and repaired on the next verified save`() = runBlocking {
        val cached = CachedServerAddress("192.168.1.10", "ab".repeat(32))
        withPreferences { prefs, store ->
            prefs.saveFingerprint("mac.local", cached.fingerprint)
            store.edit { it[stringPreferencesKey("verified_server_addresses")] = "not json" }
            assertThat(prefs.cachedAddressFor("mac.local").first()).isNull()
            prefs.saveCachedAddress("mac.local", cached)
            assertThat(prefs.cachedAddressFor("mac.local").first()).isEqualTo(cached)
            prefs.saveCachedAddress("other.local", cached.copy(address = "untrusted.local"))
            assertThat(prefs.cachedAddressFor("other.local").first()).isNull()
        }
    }

    @Test fun `manual servers survive closing and reopening the preference store without auto connect`() = runBlocking {
        withPreferences { prefs, _ ->
            prefs.saveAutoConnect(false)
            prefs.saveServer(" 192.168.1.10 ")
            prefs.saveServer("macbook-b.local")
            prefs.saveServer("192.168.1.10")
            prefs.saveServer(" ")
        }
        withPreferences { prefs, _ ->
            assertThat(prefs.savedServers.first()).containsExactly("192.168.1.10", "macbook-b.local")
            assertThat(prefs.autoConnect.first()).isFalse()
            assertThat(prefs.lastServerIp.first()).isNull()
        }
    }

    @Test fun `connecting or favoriting remembers a server and removing its star does not forget it`() = runBlocking {
        withPreferences { prefs, _ ->
            prefs.saveLastServer("192.168.1.10")
            prefs.saveLastServer("192.168.1.68")
            prefs.toggleFavoriteServer("192.168.1.20")
            prefs.toggleFavoriteServer("192.168.1.20")
        }
        withPreferences { prefs, _ ->
            assertThat(prefs.savedServers.first()).containsExactly("192.168.1.10", "192.168.1.68", "192.168.1.20")
            assertThat(prefs.favoriteServers.first()).isEmpty()
            assertThat(prefs.lastServerIp.first()).isEqualTo("192.168.1.68")
        }
    }

    @Test fun `legacy favorites last server and connection records are recovered and retained after subsequent edits`() = runBlocking {
        withPreferences { prefs, store ->
            store.edit {
                it[stringPreferencesKey("last_server_ip")] = "192.168.1.10"
                it[stringPreferencesKey("favorite_servers")] = "192.168.1.68\n\n"
                it[stringPreferencesKey("tls_fingerprints")] = "old-mac.local:${"ab".repeat(32)}\nmalformed"
                it[stringPreferencesKey("server_transport_modes")] = "plain-mac.local:plain"
            }
            assertThat(prefs.savedServers.first())
                .containsExactly("192.168.1.10", "192.168.1.68", "old-mac.local", "plain-mac.local")
            prefs.saveServer("new-mac.local")
            prefs.saveLastServer("192.168.1.30")
            prefs.toggleFavoriteServer("192.168.1.68")
            prefs.removeFingerprint("old-mac.local")
            prefs.clearTransport("plain-mac.local")
        }
        withPreferences { prefs, _ ->
            assertThat(prefs.savedServers.first())
                .containsExactly("192.168.1.10", "192.168.1.68", "old-mac.local", "plain-mac.local", "new-mac.local", "192.168.1.30")
            assertThat(prefs.favoriteServers.first()).isEmpty()
            assertThat(prefs.fingerprintFor("old-mac.local").first()).isNull()
            assertThat(prefs.transportFor("plain-mac.local").first()).isNull()
        }
    }

    @Test fun `forgetting legacy certificate trust keeps the server address`() = runBlocking {
        withPreferences { prefs, store ->
            store.edit { it[stringPreferencesKey("tls_fingerprints")] = "mac.local:${"ab".repeat(32)}" }
            prefs.removeFingerprint("mac.local")
        }
        withPreferences { prefs, _ ->
            assertThat(prefs.savedServers.first()).containsExactly("mac.local")
            assertThat(prefs.fingerprintFor("mac.local").first()).isNull()
        }
    }

    private suspend fun withPreferences(block: suspend (AppPreferences, DataStore<Preferences>) -> Unit) {
        val job = SupervisorJob()
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) {
            File(folder.root, "servers.preferences_pb")
        }
        try {
            block(AppPreferences(store), store)
        } finally {
            // The next instance must reopen the actual on-disk preferences, not a cached flow.
            job.cancelAndJoin()
        }
    }

    @Test
    fun `getDefaultScreenName sanitizes model name properly`() {
        assertThat(AppPreferences.getDefaultScreenName("Pixel 7 Pro")).isEqualTo("pixel-7-pro")
        assertThat(AppPreferences.getDefaultScreenName("SM-G991B")).isEqualTo("sm-g991b")
        assertThat(AppPreferences.getDefaultScreenName("")).isEqualTo("android-phone")
        assertThat(AppPreferences.getDefaultScreenName("Special @#$ Name")).isEqualTo("special--name")
        assertThat(AppPreferences.getDefaultScreenName(null)).isEqualTo("android-phone")
    }
}
