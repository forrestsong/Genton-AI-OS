package com.genton.brain.dmi.bridges

// 短信读取能力桥 - 占位实现
class SmsReadBridge(private val context: android.content.Context) : com.genton.brain.dmi.SystemCapabilityBridge {
    override fun execute(parameters: Map<String, Any>, callback: com.genton.brain.dmi.DmiExecutionCallback) {
        callback.onFailure(com.genton.brain.dmi.DmiBridge.RESULT_NOT_SUPPORTED, "Not implemented yet")
    }
    override fun validateParameters(parameters: Map<String, Any>): String? = null
    override fun checkPermissions(): String? = null
}

// 联系人创建能力桥 - 占位实现
class ContactsCreateBridge(private val context: android.content.Context) : com.genton.brain.dmi.SystemCapabilityBridge {
    override fun execute(parameters: Map<String, Any>, callback: com.genton.brain.dmi.DmiExecutionCallback) {
        callback.onFailure(com.genton.brain.dmi.DmiBridge.RESULT_NOT_SUPPORTED, "Not implemented yet")
    }
    override fun validateParameters(parameters: Map<String, Any>): String? = null
    override fun checkPermissions(): String? = null
}

// 联系人更新能力桥 - 占位实现
class ContactsUpdateBridge(private val context: android.content.Context) : com.genton.brain.dmi.SystemCapabilityBridge {
    override fun execute(parameters: Map<String, Any>, callback: com.genton.brain.dmi.DmiExecutionCallback) {
        callback.onFailure(com.genton.brain.dmi.DmiBridge.RESULT_NOT_SUPPORTED, "Not implemented yet")
    }
    override fun validateParameters(parameters: Map<String, Any>): String? = null
    override fun checkPermissions(): String? = null
}

// 日历更新能力桥 - 占位实现
class CalendarUpdateBridge(private val context: android.content.Context) : com.genton.brain.dmi.SystemCapabilityBridge {
    override fun execute(parameters: Map<String, Any>, callback: com.genton.brain.dmi.DmiExecutionCallback) {
        callback.onFailure(com.genton.brain.dmi.DmiBridge.RESULT_NOT_SUPPORTED, "Not implemented yet")
    }
    override fun validateParameters(parameters: Map<String, Any>): String? = null
    override fun checkPermissions(): String? = null
}

// 文件读取能力桥 - 占位实现
class FileReadBridge(private val context: android.content.Context) : com.genton.brain.dmi.SystemCapabilityBridge {
    override fun execute(parameters: Map<String, Any>, callback: com.genton.brain.dmi.DmiExecutionCallback) {
        callback.onFailure(com.genton.brain.dmi.DmiBridge.RESULT_NOT_SUPPORTED, "Not implemented yet")
    }
    override fun validateParameters(parameters: Map<String, Any>): String? = null
    override fun checkPermissions(): String? = null
}

// 文件写入能力桥 - 占位实现
class FileWriteBridge(private val context: android.content.Context) : com.genton.brain.dmi.SystemCapabilityBridge {
    override fun execute(parameters: Map<String, Any>, callback: com.genton.brain.dmi.DmiExecutionCallback) {
        callback.onFailure(com.genton.brain.dmi.DmiBridge.RESULT_NOT_SUPPORTED, "Not implemented yet")
    }
    override fun validateParameters(parameters: Map<String, Any>): String? = null
    override fun checkPermissions(): String? = null
}

// 设置读取能力桥 - 占位实现
class SettingsGetBridge(private val context: android.content.Context) : com.genton.brain.dmi.SystemCapabilityBridge {
    override fun execute(parameters: Map<String, Any>, callback: com.genton.brain.dmi.DmiExecutionCallback) {
        callback.onFailure(com.genton.brain.dmi.DmiBridge.RESULT_NOT_SUPPORTED, "Not implemented yet")
    }
    override fun validateParameters(parameters: Map<String, Any>): String? = null
    override fun checkPermissions(): String? = null
}

// 设置写入能力桥 - 占位实现
class SettingsSetBridge(private val context: android.content.Context) : com.genton.brain.dmi.SystemCapabilityBridge {
    override fun execute(parameters: Map<String, Any>, callback: com.genton.brain.dmi.DmiExecutionCallback) {
        callback.onFailure(com.genton.brain.dmi.DmiBridge.RESULT_NOT_SUPPORTED, "Not implemented yet")
    }
    override fun validateParameters(parameters: Map<String, Any>): String? = null
    override fun checkPermissions(): String? = null
}
