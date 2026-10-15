package com.genton.learn

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import org.json.JSONObject

/**
 * Learn 建议持久化数据库
 * 
 * 表结构：learn_suggestions
 * - suggestion_id: TEXT PRIMARY KEY (learn.notify.<template>-<hash>)
 * - template_id: TEXT (sender-frequent-p1/category-digest/...)
 * - domain: TEXT (notification/call_gate)
 * - state: TEXT (SUGGESTION/ACTIVE/DISCARDED)
 * - rule_json: TEXT (PolicyRule JSON)
 * - title: TEXT
 * - description: TEXT
 * - confidence: REAL
 * - evidence_count: INTEGER
 * - created_at_ms: INTEGER
 * - activated_at_ms: INTEGER (nullable)
 * - discarded_at_ms: INTEGER (nullable)
 * - cool_down_until_ms: INTEGER (nullable)
 * - hit_count: INTEGER
 * - last_hit_at_ms: INTEGER (nullable)
 * - user_rule_id: TEXT (nullable)
 * - discard_reason: TEXT (nullable)
 * 
 * 索引：
 * - idx_state: (state)
 * - idx_template: (template_id, state)
 * - idx_created: (created_at_ms DESC)
 */
class LearnSuggestionDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
    
    companion object {
        private const val TAG = "LearnSuggestionDB"
        private const val DATABASE_NAME = "learn_suggestions.db"
        private const val DATABASE_VERSION = 1
        
        private const val TABLE_NAME = "learn_suggestions"
        
        // 列名
        private const val COL_SUGGESTION_ID = "suggestion_id"
        private const val COL_TEMPLATE_ID = "template_id"
        private const val COL_DOMAIN = "domain"
        private const val COL_STATE = "state"
        private const val COL_RULE_JSON = "rule_json"
        private const val COL_TITLE = "title"
        private const val COL_DESCRIPTION = "description"
        private const val COL_CONFIDENCE = "confidence"
        private const val COL_EVIDENCE_COUNT = "evidence_count"
        private const val COL_CREATED_AT_MS = "created_at_ms"
        private const val COL_ACTIVATED_AT_MS = "activated_at_ms"
        private const val COL_DISCARDED_AT_MS = "discarded_at_ms"
        private const val COL_COOL_DOWN_UNTIL_MS = "cool_down_until_ms"
        private const val COL_HIT_COUNT = "hit_count"
        private const val COL_LAST_HIT_AT_MS = "last_hit_at_ms"
        private const val COL_USER_RULE_ID = "user_rule_id"
        private const val COL_DISCARD_REASON = "discard_reason"
        
        @Volatile
        private var instance: LearnSuggestionDatabase? = null
        
        fun getInstance(context: Context): LearnSuggestionDatabase {
            return instance ?: synchronized(this) {
                instance ?: LearnSuggestionDatabase(context.applicationContext).also { instance = it }
            }
        }
    }
    
    override fun onCreate(db: SQLiteDatabase) {
        // 创建主表
        val createTable = """
            CREATE TABLE $TABLE_NAME (
                $COL_SUGGESTION_ID TEXT PRIMARY KEY,
                $COL_TEMPLATE_ID TEXT NOT NULL,
                $COL_DOMAIN TEXT NOT NULL,
                $COL_STATE TEXT NOT NULL,
                $COL_RULE_JSON TEXT NOT NULL,
                $COL_TITLE TEXT NOT NULL,
                $COL_DESCRIPTION TEXT NOT NULL,
                $COL_CONFIDENCE REAL NOT NULL,
                $COL_EVIDENCE_COUNT INTEGER NOT NULL,
                $COL_CREATED_AT_MS INTEGER NOT NULL,
                $COL_ACTIVATED_AT_MS INTEGER,
                $COL_DISCARDED_AT_MS INTEGER,
                $COL_COOL_DOWN_UNTIL_MS INTEGER,
                $COL_HIT_COUNT INTEGER NOT NULL DEFAULT 0,
                $COL_LAST_HIT_AT_MS INTEGER,
                $COL_USER_RULE_ID TEXT,
                $COL_DISCARD_REASON TEXT
            )
        """.trimIndent()
        db.execSQL(createTable)
        
        // 创建索引
        db.execSQL("CREATE INDEX idx_state ON $TABLE_NAME($COL_STATE)")
        db.execSQL("CREATE INDEX idx_template ON $TABLE_NAME($COL_TEMPLATE_ID, $COL_STATE)")
        db.execSQL("CREATE INDEX idx_created ON $TABLE_NAME($COL_CREATED_AT_MS DESC)")
        
        Log.i(TAG, "Database created with version $DATABASE_VERSION")
    }
    
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        Log.w(TAG, "Upgrading database from version $oldVersion to $newVersion")
        // 暂时简单处理：删除重建（生产环境需要迁移逻辑）
        db.execSQL("DROP TABLE IF EXISTS $TABLE_NAME")
        onCreate(db)
    }
    
    // ========== 写操作 ==========
    
    /**
     * 插入新建议
     */
    fun insertSuggestion(record: LearnSuggestionRecord): Boolean {
        val db = writableDatabase
        val values = recordToContentValues(record)
        
        return try {
            val rowId = db.insert(TABLE_NAME, null, values)
            if (rowId > 0) {
                Log.d(TAG, "Inserted suggestion: ${record.suggestionId}")
                true
            } else {
                Log.e(TAG, "Failed to insert suggestion: ${record.suggestionId}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error inserting suggestion: ${record.suggestionId}", e)
            false
        }
    }
    
    /**
     * 更新建议状态
     */
    fun updateSuggestion(record: LearnSuggestionRecord): Boolean {
        val db = writableDatabase
        val values = recordToContentValues(record)
        
        return try {
            val rowsAffected = db.update(
                TABLE_NAME,
                values,
                "$COL_SUGGESTION_ID = ?",
                arrayOf(record.suggestionId)
            )
            if (rowsAffected > 0) {
                Log.d(TAG, "Updated suggestion: ${record.suggestionId}, state=${record.state}")
                true
            } else {
                Log.w(TAG, "No rows updated for suggestion: ${record.suggestionId}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating suggestion: ${record.suggestionId}", e)
            false
        }
    }
    
    /**
     * 删除建议（慎用，一般只更新状态）
     */
    fun deleteSuggestion(suggestionId: String): Boolean {
        val db = writableDatabase
        
        return try {
            val rowsAffected = db.delete(
                TABLE_NAME,
                "$COL_SUGGESTION_ID = ?",
                arrayOf(suggestionId)
            )
            if (rowsAffected > 0) {
                Log.d(TAG, "Deleted suggestion: $suggestionId")
                true
            } else {
                Log.w(TAG, "No rows deleted for suggestion: $suggestionId")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting suggestion: $suggestionId", e)
            false
        }
    }
    
    /**
     * 清理过期建议（仅删除 DISCARDED 且超过30天的记录）
     */
    fun cleanupExpiredSuggestions(retentionDays: Int = 30): Int {
        val db = writableDatabase
        val cutoffMs = System.currentTimeMillis() - (retentionDays * 24 * 60 * 60 * 1000L)
        
        return try {
            val rowsDeleted = db.delete(
                TABLE_NAME,
                "$COL_STATE = ? AND $COL_DISCARDED_AT_MS < ?",
                arrayOf(LearnSuggestionState.DISCARDED.name, cutoffMs.toString())
            )
            Log.i(TAG, "Cleaned up $rowsDeleted expired suggestions (older than $retentionDays days)")
            rowsDeleted
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up expired suggestions", e)
            0
        }
    }
    
    // ========== 读操作 ==========
    
    /**
     * 根据 ID 查询建议
     */
    fun getSuggestionById(suggestionId: String): LearnSuggestionRecord? {
        val db = readableDatabase
        
        return try {
            db.query(
                TABLE_NAME,
                null,
                "$COL_SUGGESTION_ID = ?",
                arrayOf(suggestionId),
                null,
                null,
                null
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    cursorToRecord(cursor)
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying suggestion: $suggestionId", e)
            null
        }
    }
    
    /**
     * 查询指定状态的所有建议
     */
    fun getSuggestionsByState(state: LearnSuggestionState): List<LearnSuggestionRecord> {
        val db = readableDatabase
        val results = mutableListOf<LearnSuggestionRecord>()
        
        try {
            db.query(
                TABLE_NAME,
                null,
                "$COL_STATE = ?",
                arrayOf(state.name),
                null,
                null,
                "$COL_CREATED_AT_MS DESC"
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    cursorToRecord(cursor)?.let { results.add(it) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying suggestions by state: $state", e)
        }
        
        return results
    }
    
    /**
     * 查询所有待处理建议（SUGGESTION 状态）
     */
    fun getPendingSuggestions(): List<LearnSuggestionRecord> {
        return getSuggestionsByState(LearnSuggestionState.SUGGESTION)
    }
    
    /**
     * 查询所有激活的建议（ACTIVE 状态）
     */
    fun getActiveSuggestions(): List<LearnSuggestionRecord> {
        return getSuggestionsByState(LearnSuggestionState.ACTIVE)
    }
    
    /**
     * 查询冷静期内的建议（ACTIVE + coolDownUntilMs > now）
     */
    fun getSuggestionsInCoolDown(): List<LearnSuggestionRecord> {
        val db = readableDatabase
        val results = mutableListOf<LearnSuggestionRecord>()
        val nowMs = System.currentTimeMillis()
        
        try {
            db.query(
                TABLE_NAME,
                null,
                "$COL_STATE = ? AND $COL_COOL_DOWN_UNTIL_MS > ?",
                arrayOf(LearnSuggestionState.ACTIVE.name, nowMs.toString()),
                null,
                null,
                "$COL_COOL_DOWN_UNTIL_MS ASC"
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    cursorToRecord(cursor)?.let { results.add(it) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying suggestions in cool down", e)
        }
        
        return results
    }
    
    /**
     * 查询指定模板的最近丢弃时间（用于抑制窗判断）
     */
    fun getLastDiscardedTimeForTemplate(templateId: String): Long? {
        val db = readableDatabase
        
        return try {
            db.query(
                TABLE_NAME,
                arrayOf(COL_DISCARDED_AT_MS),
                "$COL_TEMPLATE_ID = ? AND $COL_STATE = ?",
                arrayOf(templateId, LearnSuggestionState.DISCARDED.name),
                null,
                null,
                "$COL_DISCARDED_AT_MS DESC",
                "1"
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getLong(0)
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying last discarded time for template: $templateId", e)
            null
        }
    }
    
    /**
     * 统计各状态的建议数量
     */
    fun getStateStatistics(): Map<LearnSuggestionState, Int> {
        val db = readableDatabase
        val stats = mutableMapOf<LearnSuggestionState, Int>()
        
        try {
            db.rawQuery(
                "SELECT $COL_STATE, COUNT(*) FROM $TABLE_NAME GROUP BY $COL_STATE",
                null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val stateName = cursor.getString(0)
                    val count = cursor.getInt(1)
                    try {
                        val state = LearnSuggestionState.valueOf(stateName)
                        stats[state] = count
                    } catch (e: IllegalArgumentException) {
                        Log.w(TAG, "Unknown state: $stateName")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying state statistics", e)
        }
        
        return stats
    }
    
    // ========== 辅助方法 ==========
    
    private fun recordToContentValues(record: LearnSuggestionRecord): ContentValues {
        return ContentValues().apply {
            put(COL_SUGGESTION_ID, record.suggestionId)
            put(COL_TEMPLATE_ID, record.templateId)
            put(COL_DOMAIN, record.domain)
            put(COL_STATE, record.state.name)
            put(COL_RULE_JSON, record.ruleJson)
            put(COL_TITLE, record.title)
            put(COL_DESCRIPTION, record.description)
            put(COL_CONFIDENCE, record.confidence)
            put(COL_EVIDENCE_COUNT, record.evidenceCount)
            put(COL_CREATED_AT_MS, record.createdAtMs)
            put(COL_ACTIVATED_AT_MS, record.activatedAtMs)
            put(COL_DISCARDED_AT_MS, record.discardedAtMs)
            put(COL_COOL_DOWN_UNTIL_MS, record.coolDownUntilMs)
            put(COL_HIT_COUNT, record.hitCount)
            put(COL_LAST_HIT_AT_MS, record.lastHitAtMs)
            put(COL_USER_RULE_ID, record.userRuleId)
            put(COL_DISCARD_REASON, record.discardReason)
        }
    }
    
    private fun cursorToRecord(cursor: Cursor): LearnSuggestionRecord? {
        return try {
            LearnSuggestionRecord(
                suggestionId = cursor.getString(cursor.getColumnIndexOrThrow(COL_SUGGESTION_ID)),
                templateId = cursor.getString(cursor.getColumnIndexOrThrow(COL_TEMPLATE_ID)),
                domain = cursor.getString(cursor.getColumnIndexOrThrow(COL_DOMAIN)),
                state = LearnSuggestionState.valueOf(cursor.getString(cursor.getColumnIndexOrThrow(COL_STATE))),
                ruleJson = cursor.getString(cursor.getColumnIndexOrThrow(COL_RULE_JSON)),
                title = cursor.getString(cursor.getColumnIndexOrThrow(COL_TITLE)),
                description = cursor.getString(cursor.getColumnIndexOrThrow(COL_DESCRIPTION)),
                confidence = cursor.getFloat(cursor.getColumnIndexOrThrow(COL_CONFIDENCE)),
                evidenceCount = cursor.getInt(cursor.getColumnIndexOrThrow(COL_EVIDENCE_COUNT)),
                createdAtMs = cursor.getLong(cursor.getColumnIndexOrThrow(COL_CREATED_AT_MS)),
                activatedAtMs = cursor.getLongOrNull(cursor.getColumnIndexOrThrow(COL_ACTIVATED_AT_MS)),
                discardedAtMs = cursor.getLongOrNull(cursor.getColumnIndexOrThrow(COL_DISCARDED_AT_MS)),
                coolDownUntilMs = cursor.getLongOrNull(cursor.getColumnIndexOrThrow(COL_COOL_DOWN_UNTIL_MS)),
                hitCount = cursor.getInt(cursor.getColumnIndexOrThrow(COL_HIT_COUNT)),
                lastHitAtMs = cursor.getLongOrNull(cursor.getColumnIndexOrThrow(COL_LAST_HIT_AT_MS)),
                userRuleId = cursor.getStringOrNull(cursor.getColumnIndexOrThrow(COL_USER_RULE_ID)),
                discardReason = cursor.getStringOrNull(cursor.getColumnIndexOrThrow(COL_DISCARD_REASON))
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error converting cursor to record", e)
            null
        }
    }
    
    private fun Cursor.getLongOrNull(columnIndex: Int): Long? {
        return if (isNull(columnIndex)) null else getLong(columnIndex)
    }
    
    private fun Cursor.getStringOrNull(columnIndex: Int): String? {
        return if (isNull(columnIndex)) null else getString(columnIndex)
    }
}
