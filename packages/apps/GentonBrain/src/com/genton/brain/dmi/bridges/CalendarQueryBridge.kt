package com.genton.brain.dmi

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * 日历查询能力桥
 * 
 * DMI: dmi.calendar.query
 * 功能：查询日历事件
 * 参数：
 *   - startTime: Long (可选) - 查询开始时间（Unix 时间戳，毫秒）
 *   - endTime: Long (可选) - 查询结束时间（Unix 时间戳，毫秒）
 *   - keyword: String (可选) - 关键词搜索（标题/地点）
 *   - limit: Int (可选，默认 10) - 返回结果数量上限
 */
class CalendarQueryBridge(private val context: Context) : SystemCapabilityBridge {
    
    companion object {
        private const val TAG = "CalendarQueryBridge"
        private const val DEFAULT_LIMIT = 10
        private const val MAX_LIMIT = 100
    }
    
    override fun execute(parameters: Map<String, Any>, callback: DmiExecutionCallback) {
        Log.i(TAG, "Executing calendar query")
        
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
            val startTime = (parameters["startTime"] as? Number)?.toLong()
            val endTime = (parameters["endTime"] as? Number)?.toLong()
            val keyword = parameters["keyword"] as? String
            val limit = (parameters["limit"] as? Int) ?: DEFAULT_LIMIT
            
            // 4. 查询日历事件
            val events = queryEvents(startTime, endTime, keyword, limit.coerceAtMost(MAX_LIMIT))
            
            // 5. 返回结果
            val result = JSONObject().apply {
                put("success", true)
                put("count", events.length())
                put("events", events)
            }
            
            callback.onSuccess(result.toString())
            
            Log.i(TAG, "Calendar query completed: ${events.length()} results")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query calendar", e)
            callback.onFailure(
                DmiBridge.RESULT_EXECUTION_FAILED,
                "Failed to query calendar: ${e.message}"
            )
        }
    }
    
    /**
     * 查询日历事件
     */
    private fun queryEvents(
        startTime: Long?,
        endTime: Long?,
        keyword: String?,
        limit: Int
    ): JSONArray {
        val events = JSONArray()
        
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.DESCRIPTION
        )
        
        // 构建查询条件
        val selectionParts = mutableListOf<String>()
        val selectionArgs = mutableListOf<String>()
        
        if (startTime != null) {
            selectionParts.add("${CalendarContract.Events.DTSTART} >= ?")
            selectionArgs.add(startTime.toString())
        }
        
        if (endTime != null) {
            selectionParts.add("${CalendarContract.Events.DTEND} <= ?")
            selectionArgs.add(endTime.toString())
        }
        
        if (keyword != null) {
            selectionParts.add("(${CalendarContract.Events.TITLE} LIKE ? OR ${CalendarContract.Events.EVENT_LOCATION} LIKE ?)")
            selectionArgs.add("%$keyword%")
            selectionArgs.add("%$keyword%")
        }
        
        val selection = if (selectionParts.isNotEmpty()) {
            selectionParts.joinToString(" AND ")
        } else {
            null
        }
        
        val cursor = context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            projection,
            selection,
            if (selectionArgs.isNotEmpty()) selectionArgs.toTypedArray() else null,
            "${CalendarContract.Events.DTSTART} ASC LIMIT $limit"
        )
        
        cursor?.use {
            val idIndex = it.getColumnIndex(CalendarContract.Events._ID)
            val titleIndex = it.getColumnIndex(CalendarContract.Events.TITLE)
            val startIndex = it.getColumnIndex(CalendarContract.Events.DTSTART)
            val endIndex = it.getColumnIndex(CalendarContract.Events.DTEND)
            val locationIndex = it.getColumnIndex(CalendarContract.Events.EVENT_LOCATION)
            val descIndex = it.getColumnIndex(CalendarContract.Events.DESCRIPTION)
            
            while (it.moveToNext() && events.length() < limit) {
                val eventJson = JSONObject().apply {
                    put("id", it.getLong(idIndex))
                    put("title", it.getString(titleIndex))
                    put("startTime", it.getLong(startIndex))
                    put("endTime", it.getLong(endIndex))
                    
                    val location = it.getString(locationIndex)
                    if (!location.isNullOrEmpty()) {
                        put("location", location)
                    }
                    
                    val description = it.getString(descIndex)
                    if (!description.isNullOrEmpty()) {
                        put("description", description)
                    }
                }
                
                events.put(eventJson)
            }
        }
        
        return events
    }
    
    override fun validateParameters(parameters: Map<String, Any>): String? {
        // 所有参数都是可选的
        
        // 如果同时指定了 startTime 和 endTime，验证时间逻辑
        val startTime = parameters["startTime"] as? Number
        val endTime = parameters["endTime"] as? Number
        
        if (startTime != null && endTime != null) {
            if (startTime.toLong() >= endTime.toLong()) {
                return "Invalid time range: startTime must be before endTime"
            }
        }
        
        return null
    }
    
    override fun checkPermissions(): String? {
        val hasCalendarPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
        
        return if (hasCalendarPermission) {
            null
        } else {
            "Missing permission: READ_CALENDAR"
        }
    }
}
