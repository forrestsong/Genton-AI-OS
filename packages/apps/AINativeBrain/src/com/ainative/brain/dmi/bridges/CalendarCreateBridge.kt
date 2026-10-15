package com.ainative.brain.dmi

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.Calendar

/**
 * 日历创建能力桥
 * 
 * DMI: dmi.calendar.create
 * 功能：创建日历事件
 * 参数：
 *   - title: String (必填) - 事件标题
 *   - startTime: Long (必填) - 开始时间（Unix 时间戳，毫秒）
 *   - endTime: Long (必填) - 结束时间（Unix 时间戳，毫秒）
 *   - location: String (可选) - 地点
 *   - description: String (可选) - 描述
 *   - calendarId: Long (可选，默认使用主日历) - 日历 ID
 */
class CalendarCreateBridge(private val context: Context) : SystemCapabilityBridge {
    
    companion object {
        private const val TAG = "CalendarCreateBridge"
    }
    
    override fun execute(parameters: Map<String, Any>, callback: DmiExecutionCallback) {
        Log.i(TAG, "Executing calendar create")
        
        // 1. 验证参数
        val validationError = validateParameters(parameters)
        if (validationError != null) {
            callback.onFailure(DmiBridge.RESULT_INVALID_PARAMETERS, validationError)
            return
        }
        
        // 2. 检查权限
        val permissionError = checkPermissions()
        if (permissionError != null) {
            callback.onFailure(DmiBridge.RESULT_PERMISSION_DENIED, permissionError)
            return
        }
        
        try {
            // 3. 提取参数
            val title = parameters["title"] as String
            val startTime = (parameters["startTime"] as Number).toLong()
            val endTime = (parameters["endTime"] as Number).toLong()
            val location = parameters["location"] as? String
            val description = parameters["description"] as? String
            val calendarId = (parameters["calendarId"] as? Number)?.toLong() 
                ?: getPrimaryCalendarId()
            
            // 4. 构建 ContentValues
            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DTSTART, startTime)
                put(CalendarContract.Events.DTEND, endTime)
                put(CalendarContract.Events.EVENT_TIMEZONE, Calendar.getInstance().timeZone.id)
                
                if (location != null) {
                    put(CalendarContract.Events.EVENT_LOCATION, location)
                }
                
                if (description != null) {
                    put(CalendarContract.Events.DESCRIPTION, description)
                }
            }
            
            // 5. 插入日历事件
            val uri = context.contentResolver.insert(
                CalendarContract.Events.CONTENT_URI,
                values
            )
            
            if (uri == null) {
                callback.onFailure(
                    DmiBridge.RESULT_EXECUTION_FAILED,
                    "Failed to create calendar event"
                )
                return
            }
            
            // 6. 返回成功结果
            val eventId = uri.lastPathSegment
            val result = JSONObject().apply {
                put("success", true)
                put("eventId", eventId)
                put("title", title)
                put("startTime", startTime)
                put("endTime", endTime)
            }
            
            callback.onSuccess(result.toString())
            
            Log.i(TAG, "Calendar event created: $eventId")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create calendar event", e)
            callback.onFailure(
                DmiBridge.RESULT_EXECUTION_FAILED,
                "Failed to create calendar event: ${e.message}"
            )
        }
    }
    
    /**
     * 获取主日历 ID
     */
    private fun getPrimaryCalendarId(): Long {
        val projection = arrayOf(CalendarContract.Calendars._ID)
        val cursor = context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            "${CalendarContract.Calendars.IS_PRIMARY} = 1",
            null,
            null
        )
        
        cursor?.use {
            if (it.moveToFirst()) {
                val idIndex = it.getColumnIndex(CalendarContract.Calendars._ID)
                return it.getLong(idIndex)
            }
        }
        
        // 如果没有主日历，返回第一个可用的日历
        val fallbackCursor = context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            null,
            null,
            "${CalendarContract.Calendars._ID} ASC LIMIT 1"
        )
        
        fallbackCursor?.use {
            if (it.moveToFirst()) {
                val idIndex = it.getColumnIndex(CalendarContract.Calendars._ID)
                return it.getLong(idIndex)
            }
        }
        
        throw IllegalStateException("No calendar available")
    }
    
    override fun validateParameters(parameters: Map<String, Any>): String? {
        // 检查 title
        if (!parameters.containsKey("title")) {
            return "Missing required parameter: title"
        }
        if (parameters["title"] !is String) {
            return "Invalid parameter: title must be a string"
        }
        
        // 检查 startTime
        if (!parameters.containsKey("startTime")) {
            return "Missing required parameter: startTime"
        }
        val startTime = parameters["startTime"]
        if (startTime !is Number) {
            return "Invalid parameter: startTime must be a number"
        }
        
        // 检查 endTime
        if (!parameters.containsKey("endTime")) {
            return "Missing required parameter: endTime"
        }
        val endTime = parameters["endTime"]
        if (endTime !is Number) {
            return "Invalid parameter: endTime must be a number"
        }
        
        // 验证时间逻辑
        if (startTime.toLong() >= endTime.toLong()) {
            return "Invalid time range: startTime must be before endTime"
        }
        
        return null
    }
    
    override fun checkPermissions(): String? {
        val hasCalendarPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
        
        return if (hasCalendarPermission) {
            null
        } else {
            "Missing permission: WRITE_CALENDAR"
        }
    }
}
