// EntitySourceAdapters.kt
// C3.2 数据源适配器：从各个来源抽取实体
package com.genton.semantics.extraction

import android.content.Context
import android.provider.ContactsContract
import android.provider.CalendarContract
import android.util.Log
import com.genton.semantics.ontology.*
import org.json.JSONObject

/**
 * 联系人同步适配器
 * 
 * 从系统联系人提供者同步联系人实体
 */
class ContactSyncAdapter(
    private val context: Context,
    private val pipeline: EntityExtractionPipeline
) {
    companion object {
        private const val TAG = "ContactSyncAdapter"
    }
    
    /**
     * 同步所有联系人
     */
    fun syncAll(): Int {
        var count = 0
        val cursor = context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.DISPLAY_NAME,
                ContactsContract.Contacts.HAS_PHONE_NUMBER
            ),
            null,
            null,
            null
        )
        
        cursor?.use {
            while (it.moveToNext()) {
                val contactId = it.getLong(0)
                val displayName = it.getString(1) ?: continue
                val hasPhone = it.getInt(2) > 0
                
                // 提取联系人详细信息
                val phone = if (hasPhone) getPhoneNumber(contactId) else null
                val email = getEmail(contactId)
                
                // 构建属性
                val attributes = JSONObject().apply {
                    phone?.let { put("phone", it) }
                    email?.let { put("email", it) }
                    put("contactId", contactId)
                }.toString()
                
                // 抽取实体
                val request = EntityExtractionRequest(
                    type = EntityType.PERSON,
                    displayName = displayName,
                    attributes = attributes,
                    provenance = "contact_sync_$contactId",
                    confidence = 0.95f  // 系统联系人高置信度
                )
                
                val result = pipeline.extractEntity(request)
                if (result.success) {
                    count++
                }
            }
        }
        
        Log.i(TAG, "Synced $count contacts")
        return count
    }
    
    private fun getPhoneNumber(contactId: Long): String? {
        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
            arrayOf(contactId.toString()),
            null
        )
        
        return cursor?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }
    
    private fun getEmail(contactId: Long): String? {
        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS),
            "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?",
            arrayOf(contactId.toString()),
            null
        )
        
        return cursor?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }
}

/**
 * 日历事件适配器
 * 
 * 从系统日历提供者抽取事件实体
 */
class CalendarEventAdapter(
    private val context: Context,
    private val pipeline: EntityExtractionPipeline
) {
    companion object {
        private const val TAG = "CalendarEventAdapter"
    }
    
    /**
     * 同步未来事件（从现在开始 30 天内）
     */
    fun syncUpcomingEvents(): Int {
        var count = 0
        val now = System.currentTimeMillis()
        val thirtyDaysLater = now + 30L * 24 * 60 * 60 * 1000
        
        val cursor = context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            arrayOf(
                CalendarContract.Events._ID,
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.DTEND,
                CalendarContract.Events.EVENT_LOCATION,
                CalendarContract.Events.DESCRIPTION
            ),
            "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?",
            arrayOf(now.toString(), thirtyDaysLater.toString()),
            "${CalendarContract.Events.DTSTART} ASC"
        )
        
        cursor?.use {
            while (it.moveToNext()) {
                val eventId = it.getLong(0)
                val title = it.getString(1) ?: "Untitled Event"
                val startTime = it.getLong(2)
                val endTime = it.getLong(3)
                val location = it.getString(4)
                val description = it.getString(5)
                
                // 构建属性
                val attributes = JSONObject().apply {
                    put("eventId", eventId)
                    put("startTime", startTime)
                    put("endTime", endTime)
                    location?.let { put("location", it) }
                    description?.let { put("description", it) }
                }.toString()
                
                // 抽取实体
                val request = EntityExtractionRequest(
                    type = EntityType.EVENT,
                    displayName = title,
                    attributes = attributes,
                    provenance = "calendar_event_$eventId",
                    confidence = 0.9f
                )
                
                val result = pipeline.extractEntity(request)
                if (result.success) {
                    count++
                    
                    // 如果事件有地点，抽取地点实体
                    if (!location.isNullOrBlank()) {
                        extractLocationEntity(location, "calendar_event_${eventId}_location")
                    }
                }
            }
        }
        
        Log.i(TAG, "Synced $count calendar events")
        return count
    }
    
    private fun extractLocationEntity(location: String, provenance: String) {
        val attributes = JSONObject().apply {
            put("address", location)
        }.toString()
        
        val request = EntityExtractionRequest(
            type = EntityType.LOCATION,
            displayName = location,
            attributes = attributes,
            provenance = provenance,
            confidence = 0.8f
        )
        
        pipeline.extractEntity(request)
    }
}

/**
 * 对话实体抽取适配器
 * 
 * 从对话轮次中抽取提及的实体
 */
class ConversationEntityExtractor(
    private val pipeline: EntityExtractionPipeline
) {
    companion object {
        private const val TAG = "ConversationEntityExtractor"
    }
    
    /**
     * 从对话文本中抽取实体
     * 
     * @param conversationTurnId 对话轮次 ID
     * @param text 对话文本
     * @param extractedEntities NLU 提取的实体列表（包含类型、名称、置信度）
     */
    fun extractFromConversation(
        conversationTurnId: String,
        text: String,
        extractedEntities: List<NLUEntity>
    ): List<Entity> {
        val results = mutableListOf<Entity>()
        
        for (nluEntity in extractedEntities) {
            // 将 NLU 实体类型映射到本体类型
            val entityType = mapNLUTypeToOntologyType(nluEntity.type) ?: continue
            
            // 构建属性
            val attributes = JSONObject().apply {
                put("mentionText", nluEntity.text)
                put("mentionOffset", nluEntity.offset)
                put("conversationContext", text.take(200))  // 保留上下文（截断）
                nluEntity.properties.forEach { (key, value) ->
                    put(key, value)
                }
            }.toString()
            
            // 抽取实体
            val request = EntityExtractionRequest(
                type = entityType,
                displayName = nluEntity.text,
                attributes = attributes,
                provenance = "conversation_turn_$conversationTurnId",
                confidence = nluEntity.confidence
            )
            
            val result = pipeline.extractEntity(request)
            if (result.success && result.entity != null) {
                results.add(result.entity)
            }
        }
        
        Log.d(TAG, "Extracted ${results.size} entities from conversation turn $conversationTurnId")
        return results
    }
    
    /**
     * 映射 NLU 实体类型到本体实体类型
     */
    private fun mapNLUTypeToOntologyType(nluType: String): EntityType? {
        return when (nluType.lowercase()) {
            "person", "人名" -> EntityType.PERSON
            "organization", "org", "公司", "机构" -> EntityType.ORGANIZATION
            "location", "地点", "地址" -> EntityType.LOCATION
            "event", "事件", "活动" -> EntityType.EVENT
            "task", "任务", "待办" -> EntityType.TASK
            "document", "文档", "文件" -> EntityType.DOCUMENT
            "app", "应用" -> EntityType.APPLICATION
            else -> {
                Log.w(TAG, "Unknown NLU entity type: $nluType")
                null
            }
        }
    }
}

/**
 * NLU 提取的实体（输入）
 */
data class NLUEntity(
    val type: String,           // NLU 实体类型
    val text: String,           // 实体文本
    val offset: Int,            // 在原文中的偏移
    val confidence: Float,      // 置信度
    val properties: Map<String, Any> = emptyMap()  // 附加属性
)

/**
 * 通知实体抽取适配器
 * 
 * 从通知中抽取相关实体（发送方、应用等）
 */
class NotificationEntityExtractor(
    private val pipeline: EntityExtractionPipeline
) {
    companion object {
        private const val TAG = "NotificationEntityExtractor"
    }
    
    /**
     * 从通知中抽取实体
     */
    fun extractFromNotification(
        notificationId: String,
        packageName: String,
        appName: String,
        title: String?,
        text: String?
    ): List<Entity> {
        val results = mutableListOf<Entity>()
        
        // 1. 抽取应用实体
        val appAttributes = JSONObject().apply {
            put("packageName", packageName)
            put("appName", appName)
        }.toString()
        
        val appRequest = EntityExtractionRequest(
            type = EntityType.APPLICATION,
            displayName = appName,
            attributes = appAttributes,
            provenance = "notification_$notificationId",
            confidence = 0.95f
        )
        
        val appResult = pipeline.extractEntity(appRequest)
        if (appResult.success && appResult.entity != null) {
            results.add(appResult.entity)
        }
        
        // 2. TODO: 从通知文本中提取其他实体（人名、地点等）
        // 这里需要集成 NLU 服务来解析通知文本
        
        Log.d(TAG, "Extracted ${results.size} entities from notification $notificationId")
        return results
    }
}

/**
 * 用户输入实体抽取适配器
 * 
 * 从用户显式创建的实体（如"记住张三的电话是..."）
 */
class UserInputEntityExtractor(
    private val pipeline: EntityExtractionPipeline
) {
    companion object {
        private const val TAG = "UserInputEntityExtractor"
    }
    
    /**
     * 从用户输入创建实体
     * 
     * 这是最高置信度的来源，因为是用户显式创建
     */
    fun createFromUserInput(
        intentId: String,
        type: EntityType,
        displayName: String,
        attributes: Map<String, Any>
    ): Entity? {
        val attributesJson = JSONObject(attributes).toString()
        
        val request = EntityExtractionRequest(
            type = type,
            displayName = displayName,
            attributes = attributesJson,
            provenance = "user_input_$intentId",
            confidence = 0.98f  // 用户输入最高置信度
        )
        
        val result = pipeline.extractEntity(request)
        return if (result.success) result.entity else null
    }
}
