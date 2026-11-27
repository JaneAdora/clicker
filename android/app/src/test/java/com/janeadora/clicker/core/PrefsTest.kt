package com.janeadora.clicker.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class PrefsTest {
    private val dir: File = Files.createTempDirectory("prefs-test").toFile()
    private val file = File(dir, "settings.preferences_pb")

    @After fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun store(scope: CoroutineScope) =
        createSettingsStore(scope) { file }

    @Test fun defaults_match() = runBlocking {
        val job = SupervisorJob()
        val s = Prefs(store(CoroutineScope(Dispatchers.IO + job))).settings.first()
        assertEquals(Settings(), s)
        assertEquals(ThemeMode.System, s.theme)
        assertEquals(DpadStyle.Buttons, s.dpad)
        assertEquals(true, s.haptics)
        assertEquals(true, s.volumeKeys)
        assertEquals(false, s.keepScreenOn)
        assertEquals(false, s.localNetworkDenied)
        job.cancelAndJoin()
    }

    @Test fun update_survives_a_new_prefs_instance() = runBlocking {
        val first = SupervisorJob()
        Prefs(store(CoroutineScope(Dispatchers.IO + first))).update { it.copy(dpad = DpadStyle.Swipe) }
        first.cancelAndJoin()

        val second: Job = SupervisorJob()
        val reread = Prefs(store(CoroutineScope(Dispatchers.IO + second))).settings.first()
        assertEquals(Settings(dpad = DpadStyle.Swipe), reread)
        second.cancelAndJoin()
    }

    @Test fun every_field_round_trips() = runBlocking {
        val all = Settings(
            theme = ThemeMode.Dark,
            dpad = DpadStyle.Swipe,
            haptics = false,
            volumeKeys = false,
            keepScreenOn = true,
            localNetworkDenied = true,
        )
        val first = SupervisorJob()
        Prefs(store(CoroutineScope(Dispatchers.IO + first))).update { all }
        first.cancelAndJoin()

        val second = SupervisorJob()
        assertEquals(all, Prefs(store(CoroutineScope(Dispatchers.IO + second))).settings.first())
        second.cancelAndJoin()
    }

    @Test fun corrupt_file_does_not_block_writes() = runBlocking {
        file.writeBytes(byteArrayOf(0x7f, 0x00, 0x13, 0x37, 0x42, 0x00, 0x7f))
        val job = SupervisorJob()
        val prefs = Prefs(store(CoroutineScope(Dispatchers.IO + job)))
        prefs.update { it.copy(theme = ThemeMode.Dark) }
        assertEquals(Settings(theme = ThemeMode.Dark), prefs.settings.first())
        job.cancelAndJoin()
    }
}
