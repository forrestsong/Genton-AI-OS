package com.genton.brain.dmi

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * 联系人查询能力桥
 * 
 * DMI: dmi.contacts.query
 * 功能：查询联系人
 * 参数：
 *   - query: String (可选) - 搜索关键词（姓名/电话）
 *   - limit: Int (可选，默认 10) - 返回结果数量上限
 */
class ContactsQueryBridge(private val context: Context) : SystemCapabilityBridge {
    
    companion object {
        private const val TAG = "ContactsQueryBridge"
        private const val DEFAULT_LIMIT = 10
        private const val MAX_LIMIT = 100
    }
    
    override fun execute(parameters: Map<String, Any>, callback: DmiExecutionCallback) {
        Log.i(TAG, "Executing contacts query")
        
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
            val query = parameters["query"] as? String
            val limit = (parameters["limit"] as? Int) ?: DEFAULT_LIMIT
            
            // 4. 查询联系人
            val contacts = queryContacts(query, limit.coerceAtMost(MAX_LIMIT))
            
            // 5. 返回结果
            val result = JSONObject().apply {
                put("success", true)
                put("count", contacts.length())
                put("contacts", contacts)
            }
            
            callback.onSuccess(result.toString())
            
            Log.i(TAG, "Contacts query completed: ${contacts.length()} results")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query contacts", e)
            callback.onFailure(
                DmiBridge.RESULT_EXECUTION_FAILED,
                "Failed to query contacts: ${e.message}"
            )
        }
    }
    
    /**
     * 查询联系人
     */
    private fun queryContacts(query: String?, limit: Int): JSONArray {
        val contacts = JSONArray()
        
        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME,
            ContactsContract.Contacts.HAS_PHONE_NUMBER
        )
        
        val selection = if (query != null) {
            "${ContactsContract.Contacts.DISPLAY_NAME} LIKE ?"
        } else {
            null
        }
        
        val selectionArgs = if (query != null) {
            arrayOf("%$query%")
        } else {
            null
        }
        
        val cursor = context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${ContactsContract.Contacts.DISPLAY_NAME} ASC LIMIT $limit"
        )
        
        cursor?.use {
            val idIndex = it.getColumnIndex(ContactsContract.Contacts._ID)
            val nameIndex = it.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
            val hasPhoneIndex = it.getColumnIndex(ContactsContract.Contacts.HAS_PHONE_NUMBER)
            
            while (it.moveToNext() && contacts.length() < limit) {
                val contactId = it.getString(idIndex)
                val name = it.getString(nameIndex)
                val hasPhone = it.getInt(hasPhoneIndex) > 0
                
                val contactJson = JSONObject().apply {
                    put("id", contactId)
                    put("name", name)
                    
                    // 查询电话号码
                    if (hasPhone) {
                        val phoneNumbers = queryPhoneNumbers(contactId)
                        put("phoneNumbers", phoneNumbers)
                    }
                }
                
                contacts.put(contactJson)
            }
        }
        
        return contacts
    }
    
    /**
     * 查询联系人电话号码
     */
    private fun queryPhoneNumbers(contactId: String): JSONArray {
        val phoneNumbers = JSONArray()
        
        val phoneCursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE
            ),
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
            arrayOf(contactId),
            null
        )
        
        phoneCursor?.use {
            val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val typeIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE)
            
            while (it.moveToNext()) {
                val number = it.getString(numberIndex)
                val type = it.getInt(typeIndex)
                
                val phoneJson = JSONObject().apply {
                    put("number", number)
                    put("type", getPhoneTypeLabel(type))
                }
                
                phoneNumbers.put(phoneJson)
            }
        }
        
        return phoneNumbers
    }
    
    /**
     * 获取电话类型标签
     */
    private fun getPhoneTypeLabel(type: Int): String {
        return when (type) {
            ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "mobile"
            ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "home"
            ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "work"
            else -> "other"
        }
    }
    
    override fun validateParameters(parameters: Map<String, Any>): String? {
        // query 和 limit 都是可选参数，无需强制验证
        
        // 验证 limit 范围
        val limit = parameters["limit"]
        if (limit != null && limit !is Int) {
            return "Invalid parameter: limit must be an integer"
        }
        
        if (limit is Int && limit <= 0) {
            return "Invalid parameter: limit must be greater than 0"
        }
        
        return null
    }
    
    override fun checkPermissions(): String? {
        val hasContactsPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        
        return if (hasContactsPermission) {
            null
        } else {
            "Missing permission: READ_CONTACTS"
        }
    }
}
