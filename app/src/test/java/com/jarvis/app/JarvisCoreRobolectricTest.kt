package com.jarvis.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jarvis.app.data.local.JarvisDatabase
import com.jarvis.app.data.local.JarvisRepository
import com.jarvis.app.domain.brain.DirectActionMatch
import com.jarvis.app.domain.brain.DirectCommandMatcher
import com.jarvis.app.domain.model.ChatMessageItem
import com.jarvis.app.domain.model.HandPose
import com.jarvis.app.domain.model.RoutineItem
import com.jarvis.app.platform.GestureLatch
import com.jarvis.app.platform.WakeOnLan
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class JarvisCoreRobolectricTest {

    @Test
    fun `app name matches JARVIS`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("JARVIS", context.getString(R.string.app_name))
    }

    @Test
    fun `direct command matcher parses timers, looks, and device controls`() {
        val timer = DirectCommandMatcher.match("set a 5 minute timer") as? DirectActionMatch.ToolCall
        assertNotNull(timer)
        assertEquals("set_timer", timer?.toolName)
        assertEquals("300", timer?.args?.get("seconds"))

        val ironMan = DirectCommandMatcher.match("switch to iron man look") as? DirectActionMatch.ToolCall
        assertEquals("customize_screen", ironMan?.toolName)
        assertEquals("ironman", ironMan?.args?.get("value"))

        val torch = DirectCommandMatcher.match("turn on flashlight") as? DirectActionMatch.ToolCall
        assertEquals("device_control", torch?.toolName)
        assertEquals("flashlight_on", torch?.args?.get("action"))

        val app = DirectCommandMatcher.match("open Spotify") as? DirectActionMatch.ToolCall
        assertEquals("open_application", app?.toolName)
        assertEquals("Spotify", app?.args?.get("name"))

        assertNull(DirectCommandMatcher.match("Write a three paragraph essay about orbital mechanics."))
    }

    @Test
    fun `gesture latch requires neutral release and hold duration`() {
        val latch = GestureLatch(holdMs = 500L, releaseMs = 300L)
        assertNull(latch.update(HandPose.UNKNOWN, 100L))
        assertNull(latch.update(HandPose.UNKNOWN, 450L))
        assertNull(latch.update(HandPose.OPEN_PALM, 500L))
        assertNull(latch.update(HandPose.OPEN_PALM, 700L))
        assertNull(latch.update(HandPose.OPEN_PALM, 900L))
        val triggered = latch.update(HandPose.OPEN_PALM, 1050L)
        assertEquals(HandPose.OPEN_PALM, triggered)
        // Repeat hold does not double-fire before neutral release
        assertNull(latch.update(HandPose.OPEN_PALM, 1200L))
    }

    @Test
    fun `wake on lan normalizes valid macs and rejects invalid`() {
        assertEquals("aa:bb:cc:11:22:33", WakeOnLan.normMac("AA-BB-CC-11-22-33"))
        assertEquals("", WakeOnLan.normMac("00:00:00:00:00:00"))
        assertEquals("", WakeOnLan.normMac("invalid"))
    }

    @Test
    fun `room repository persists facts, routines, agenda, and archived chats`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, JarvisDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val repo = JarvisRepository(db.jarvisDao())
            repo.addFact("Prefers dark HUD mode")
            repo.addFact("Prefers dark HUD mode") // deduplicated
            assertEquals(1, repo.getFactsOnce().size)

            repo.upsertRoutine(RoutineItem(name = "Morning", prompt = "Brief me", triggerTime = "08:00"))
            assertEquals("Morning", repo.getRoutinesOnce().first().name)

            repo.addAgendaItem("2026-11-20", "09:30", "Standup")
            assertEquals(1, repo.getAgendaOnce("2026-11-20").size)

            repo.addTurn(ChatMessageItem(role = "user", content = "Hello Jarvis"))
            repo.addTurn(ChatMessageItem(role = "assistant", content = "Good morning."))
            repo.archiveAndClearCurrentChat()
            assertTrue(repo.getCurrentTurnsOnce().isEmpty())
        } finally {
            db.close()
        }
    }
}
