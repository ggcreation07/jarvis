package com.jarvis.app.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import com.jarvis.app.domain.model.ActionLogItem
import com.jarvis.app.domain.model.AgendaItem
import com.jarvis.app.domain.model.ArchivedThreadSummary
import com.jarvis.app.domain.model.ChatMessageItem
import com.jarvis.app.domain.model.FactItem
import com.jarvis.app.domain.model.PlaybookItem
import com.jarvis.app.domain.model.RoutineItem
import com.jarvis.app.domain.model.TaskRecordItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Entity(tableName = "facts")
data class FactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val text: String,
    val category: String = "personal",
    val createdAtMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "turns")
data class TurnEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val threadId: String = "current",
    val role: String,
    val content: String,
    val agentId: String = "auto",
    val attachmentLabel: String = "",
    val generatedImageKey: String = "",
    val qrText: String = "",
    val isError: Boolean = false,
    val timestampMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "archived_threads")
data class ArchivedThreadEntity(
    @PrimaryKey val id: String,
    val title: String,
    val preview: String,
    val messageCount: Int,
    val updatedAtMs: Long
)

@Entity(tableName = "routines")
data class RoutineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val prompt: String,
    val triggerTime: String = "",
    val days: String = "daily",
    val enabled: Boolean = true,
    val lastRunDate: String = ""
)

@Entity(tableName = "playbooks")
data class PlaybookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val steps: String,
    val triggers: String = "",
    val status: String = "draft",
    val updatedAtMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "agenda_items")
data class AgendaItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val dayKey: String,
    val timeLabel: String,
    val task: String,
    val source: String = "local",
    val completed: Boolean = false
)

@Entity(tableName = "action_logs")
data class ActionLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val toolName: String,
    val summary: String,
    val ok: Boolean,
    val timestampMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "task_records")
data class TaskRecordEntity(
    @PrimaryKey val id: String,
    val objective: String,
    val state: String,
    val agentId: String,
    val stepsCount: Int,
    val summary: String,
    val updatedAtMs: Long = System.currentTimeMillis()
)

@Dao
interface JarvisDao {
    @Query("SELECT * FROM facts ORDER BY createdAtMs DESC")
    fun observeFacts(): Flow<List<FactEntity>>

    @Query("SELECT * FROM facts ORDER BY createdAtMs DESC")
    suspend fun getFactsOnce(): List<FactEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFact(fact: FactEntity): Long

    @Query("DELETE FROM facts WHERE id = :id")
    suspend fun deleteFact(id: Long)

    @Query("DELETE FROM facts")
    suspend fun clearFacts()

    @Query("SELECT * FROM turns WHERE threadId = :threadId ORDER BY timestampMs ASC, id ASC")
    fun observeTurns(threadId: String = "current"): Flow<List<TurnEntity>>

    @Query("SELECT * FROM turns WHERE threadId = :threadId ORDER BY timestampMs ASC, id ASC")
    suspend fun getTurnsOnce(threadId: String = "current"): List<TurnEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTurn(turn: TurnEntity): Long

    @Query("UPDATE turns SET threadId = :targetThreadId WHERE threadId = 'current'")
    suspend fun moveCurrentTurnsToThread(targetThreadId: String)

    @Query("DELETE FROM turns WHERE threadId = :threadId")
    suspend fun deleteTurnsForThread(threadId: String)

    @Query("SELECT * FROM archived_threads ORDER BY updatedAtMs DESC")
    fun observeArchivedThreads(): Flow<List<ArchivedThreadEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertArchivedThread(thread: ArchivedThreadEntity)

    @Query("DELETE FROM archived_threads WHERE id = :threadId")
    suspend fun deleteArchivedThread(threadId: String)

    @Query("SELECT * FROM routines ORDER BY id ASC")
    fun observeRoutines(): Flow<List<RoutineEntity>>

    @Query("SELECT * FROM routines ORDER BY id ASC")
    suspend fun getRoutinesOnce(): List<RoutineEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRoutine(routine: RoutineEntity): Long

    @Update
    suspend fun updateRoutine(routine: RoutineEntity)

    @Query("DELETE FROM routines WHERE id = :id")
    suspend fun deleteRoutine(id: Long)

    @Query("SELECT * FROM playbooks ORDER BY updatedAtMs DESC")
    fun observePlaybooks(): Flow<List<PlaybookEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaybook(playbook: PlaybookEntity): Long

    @Update
    suspend fun updatePlaybook(playbook: PlaybookEntity)

    @Query("DELETE FROM playbooks WHERE id = :id")
    suspend fun deletePlaybook(id: Long)

    @Query("SELECT * FROM agenda_items WHERE dayKey = :dayKey ORDER BY timeLabel ASC, id ASC")
    fun observeAgendaForDay(dayKey: String): Flow<List<AgendaItemEntity>>

    @Query("SELECT * FROM agenda_items WHERE dayKey = :dayKey ORDER BY timeLabel ASC, id ASC")
    suspend fun getAgendaForDayOnce(dayKey: String): List<AgendaItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAgendaItem(item: AgendaItemEntity): Long

    @Update
    suspend fun updateAgendaItem(item: AgendaItemEntity)

    @Query("DELETE FROM agenda_items WHERE id = :id")
    suspend fun deleteAgendaItem(id: Long)

    @Query("DELETE FROM agenda_items WHERE dayKey = :dayKey AND source = 'local'")
    suspend fun clearLocalAgendaForDay(dayKey: String)

    @Query("SELECT * FROM action_logs ORDER BY timestampMs DESC LIMIT 50")
    fun observeActionLogs(): Flow<List<ActionLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertActionLog(item: ActionLogEntity): Long

    @Query("SELECT * FROM task_records ORDER BY updatedAtMs DESC LIMIT 40")
    fun observeTaskRecords(): Flow<List<TaskRecordEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTaskRecord(record: TaskRecordEntity)
}

@Database(
    entities = [
        FactEntity::class,
        TurnEntity::class,
        ArchivedThreadEntity::class,
        RoutineEntity::class,
        PlaybookEntity::class,
        AgendaItemEntity::class,
        ActionLogEntity::class,
        TaskRecordEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class JarvisDatabase : RoomDatabase() {
    abstract fun jarvisDao(): JarvisDao

    companion object {
        @Volatile
        private var INSTANCE: JarvisDatabase? = null

        fun getInstance(context: Context): JarvisDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    JarvisDatabase::class.java,
                    "jarvis_assistant.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}

class JarvisRepository(private val dao: JarvisDao) {
    val facts: Flow<List<FactItem>> = dao.observeFacts().map { list ->
        list.map { FactItem(it.id, it.text, it.category, it.createdAtMs) }
    }

    val currentTurns: Flow<List<ChatMessageItem>> = dao.observeTurns("current").map { list ->
        list.map {
            ChatMessageItem(
                id = it.id,
                threadId = it.threadId,
                role = it.role,
                content = it.content,
                agentId = it.agentId,
                timestampMs = it.timestampMs,
                attachmentLabel = it.attachmentLabel,
                generatedImageKey = it.generatedImageKey,
                qrText = it.qrText,
                isError = it.isError
            )
        }
    }

    val archivedThreads: Flow<List<ArchivedThreadSummary>> = dao.observeArchivedThreads().map { list ->
        list.map { ArchivedThreadSummary(it.id, it.title, it.preview, it.messageCount, it.updatedAtMs) }
    }

    val routines: Flow<List<RoutineItem>> = dao.observeRoutines().map { list ->
        list.map { RoutineItem(it.id, it.name, it.prompt, it.triggerTime, it.days, it.enabled, it.lastRunDate) }
    }

    val playbooks: Flow<List<PlaybookItem>> = dao.observePlaybooks().map { list ->
        list.map { PlaybookItem(it.id, it.name, it.steps, it.triggers, it.status, it.updatedAtMs) }
    }

    val actionLogs: Flow<List<ActionLogItem>> = dao.observeActionLogs().map { list ->
        list.map { ActionLogItem(it.id, it.toolName, it.summary, it.ok, it.timestampMs) }
    }

    val taskRecords: Flow<List<TaskRecordItem>> = dao.observeTaskRecords().map { list ->
        list.map { TaskRecordItem(it.id, it.objective, it.state, it.agentId, it.stepsCount, it.summary, it.updatedAtMs) }
    }

    fun observeAgendaForDay(dayKey: String): Flow<List<AgendaItem>> =
        dao.observeAgendaForDay(dayKey).map { list ->
            list.map { AgendaItem(it.id, it.dayKey, it.timeLabel, it.task, it.source, it.completed) }
        }

    suspend fun getFactsOnce(): List<FactItem> =
        dao.getFactsOnce().map { FactItem(it.id, it.text, it.category, it.createdAtMs) }

    suspend fun addFact(text: String, category: String = "personal"): Long {
        val clean = text.trim()
        if (clean.isEmpty()) return 0L
        val existing = dao.getFactsOnce()
        if (existing.any { it.text.equals(clean, ignoreCase = true) }) return 0L
        return dao.insertFact(FactEntity(text = clean, category = category))
    }

    suspend fun forgetFactById(id: Long) = dao.deleteFact(id)

    suspend fun forgetFactsMatching(query: String): Int {
        val clean = query.trim()
        if (clean.equals("everything", ignoreCase = true) || clean.equals("all", ignoreCase = true)) {
            val count = dao.getFactsOnce().size
            dao.clearFacts()
            return count
        }
        val matches = dao.getFactsOnce().filter { it.text.contains(clean, ignoreCase = true) }
        matches.forEach { dao.deleteFact(it.id) }
        return matches.size
    }

    suspend fun addTurn(item: ChatMessageItem): Long =
        dao.insertTurn(
            TurnEntity(
                threadId = item.threadId,
                role = item.role,
                content = item.content,
                agentId = item.agentId,
                attachmentLabel = item.attachmentLabel,
                generatedImageKey = item.generatedImageKey,
                qrText = item.qrText,
                isError = item.isError,
                timestampMs = item.timestampMs
            )
        )

    suspend fun getCurrentTurnsOnce(): List<ChatMessageItem> =
        dao.getTurnsOnce("current").map {
            ChatMessageItem(
                id = it.id,
                threadId = it.threadId,
                role = it.role,
                content = it.content,
                agentId = it.agentId,
                timestampMs = it.timestampMs,
                attachmentLabel = it.attachmentLabel,
                generatedImageKey = it.generatedImageKey,
                qrText = it.qrText,
                isError = it.isError
            )
        }

    suspend fun archiveAndClearCurrentChat() {
        val current = dao.getTurnsOnce("current")
        if (current.isNotEmpty()) {
            val threadId = "thread_${System.currentTimeMillis()}"
            val firstUser = current.firstOrNull { it.role == "user" }?.content ?: current.first().content
            val title = firstUser.replace(Regex("\\s+"), " ").take(48)
            val preview = current.last().content.replace(Regex("\\s+"), " ").take(88)
            dao.moveCurrentTurnsToThread(threadId)
            dao.insertArchivedThread(
                ArchivedThreadEntity(
                    id = threadId,
                    title = title,
                    preview = preview,
                    messageCount = current.size,
                    updatedAtMs = current.last().timestampMs
                )
            )
        }
    }

    suspend fun clearCurrentChatWithoutArchiving() {
        dao.deleteTurnsForThread("current")
    }

    suspend fun restoreArchivedThread(threadId: String) {
        archiveAndClearCurrentChat()
        val archivedTurns = dao.getTurnsOnce(threadId)
        archivedTurns.forEach { t ->
            dao.insertTurn(t.copy(id = 0L, threadId = "current"))
        }
        dao.deleteTurnsForThread(threadId)
        dao.deleteArchivedThread(threadId)
    }

    suspend fun deleteArchivedThread(threadId: String) {
        dao.deleteTurnsForThread(threadId)
        dao.deleteArchivedThread(threadId)
    }

    suspend fun getRoutinesOnce(): List<RoutineItem> =
        dao.getRoutinesOnce().map {
            RoutineItem(it.id, it.name, it.prompt, it.triggerTime, it.days, it.enabled, it.lastRunDate)
        }

    suspend fun upsertRoutine(item: RoutineItem): Long =
        dao.insertRoutine(
            RoutineEntity(
                id = item.id,
                name = item.name.trim(),
                prompt = item.prompt.trim(),
                triggerTime = item.triggerTime.trim(),
                days = item.days.ifBlank { "daily" },
                enabled = item.enabled,
                lastRunDate = item.lastRunDate
            )
        )

    suspend fun deleteRoutine(id: Long) = dao.deleteRoutine(id)

    suspend fun addPlaybook(name: String, steps: String, triggers: String = "", status: String = "draft"): Long =
        dao.insertPlaybook(
            PlaybookEntity(name = name.trim(), steps = steps.trim(), triggers = triggers.trim(), status = status)
        )

    suspend fun updatePlaybookStatus(item: PlaybookItem, newStatus: String) {
        dao.updatePlaybook(
            PlaybookEntity(
                id = item.id,
                name = item.name,
                steps = item.steps,
                triggers = item.triggers,
                status = newStatus,
                updatedAtMs = System.currentTimeMillis()
            )
        )
    }

    suspend fun deletePlaybook(id: Long) = dao.deletePlaybook(id)

    suspend fun getAgendaOnce(dayKey: String): List<AgendaItem> =
        dao.getAgendaForDayOnce(dayKey).map {
            AgendaItem(it.id, it.dayKey, it.timeLabel, it.task, it.source, it.completed)
        }

    suspend fun addAgendaItem(dayKey: String, timeLabel: String, task: String): Long =
        dao.insertAgendaItem(
            AgendaItemEntity(dayKey = dayKey, timeLabel = timeLabel.trim(), task = task.trim())
        )

    suspend fun updateAgendaItem(item: AgendaItem) {
        dao.updateAgendaItem(
            AgendaItemEntity(
                id = item.id,
                dayKey = item.dayKey,
                timeLabel = item.timeLabel,
                task = item.task,
                source = item.source,
                completed = item.completed
            )
        )
    }

    suspend fun deleteAgendaItem(id: Long) = dao.deleteAgendaItem(id)

    suspend fun clearAgendaDay(dayKey: String) = dao.clearLocalAgendaForDay(dayKey)

    suspend fun logAction(toolName: String, summary: String, ok: Boolean) {
        dao.insertActionLog(ActionLogEntity(toolName = toolName, summary = summary, ok = ok))
    }

    suspend fun upsertTaskRecord(record: TaskRecordItem) {
        dao.upsertTaskRecord(
            TaskRecordEntity(
                id = record.id,
                objective = record.objective,
                state = record.state,
                agentId = record.agentId,
                stepsCount = record.stepsCount,
                summary = record.summary,
                updatedAtMs = record.updatedAtMs
            )
        )
    }
}
