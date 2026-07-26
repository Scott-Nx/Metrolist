package com.metrolist.music.constants

import androidx.datastore.preferences.core.edit

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.metrolist.music.extensions.toEnum
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class SilenceTrimModePreferenceTest {
    /** Verifies selected trim mode survives closing and reopening preference storage. */
    @Test
    fun settingPersistsAcrossStoreRestarts() = runBlocking {
        val file = File.createTempFile("silence-trim-mode", ".preferences_pb").apply { delete() }
        try {
            val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val writeStore = PreferenceDataStoreFactory.create(scope = writeScope) { file }
            writeStore.edit { it[SilenceTrimModeKey] = SilenceTrimMode.EDGES.name }
            writeScope.cancel()

            val readScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val readStore = PreferenceDataStoreFactory.create(scope = readScope) { file }
            val persistedMode = readStore.data.first()[SilenceTrimModeKey].toEnum(SilenceTrimMode.ALL)
            readScope.cancel()

            assertEquals(SilenceTrimMode.EDGES, persistedMode)
        } finally {
            file.delete()
        }
    }
}
