package com.genton.brain.event

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import com.genton.brain.BrainApplication
import com.genton.brain.intent.IntentReceiver

/**
 * 事件流监听器
 * 
 * 职责：
 * 1. 连接到 IAgentEventStreamService（A6.1 事件总线）
 * 2. 订阅系统事件流
 * 3. 根据触发规则主动启动 Agent 任务
 * 4. 转发事件到意图接收器
 */
class EventStreamListener(private val context: Context) {
    
    companion object {
        private const val TAG = "EventStreamListener"
        private const val EVENT_SERVICE_ACTION = "com.genton.agent.EVENT_STREAM_SERVICE"
        private const val EVENT_SERVICE_PACKAGE = "android"
    }
    
    @Volatile
    private var eventService: Any? = null // IAgentEventStreamService AIDL interface
    
    @Volatile
    private var isConnected = false
    
    private lateinit var intentReceiver: IntentReceiver
    
    // 事件触发规则表
    private val triggerRules = mutableListOf<EventTriggerRule>()
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.i(TAG, "Connected to Event Stream Service")
            // eventService = IAgentEventStreamService.Stub.asInterface(service)
            isConnected = true
            
            // 订阅事件
            subscribeToEvents()
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "Disconnected from Event Stream Service")
            eventService = null
            isConnected = false
            
            // 自动重连
            reconnect()
        }
    }
    
    init {
        val app = BrainApplication.getInstance()
        intentReceiver = app.getIntentReceiver()
    }
    
    /**
     * 启动监听
     */
    fun start() {
        Log.i(TAG, "Starting event stream listener...")
        
        // 加载触发规则
        loadTriggerRules()
        
        // 连接到事件服务
        connect()
    }
    
    /**
     * 停止监听
     */
    fun stop() {
        Log.i(TAG, "Stopping event stream listener...")
        
        try {
            if (isConnected) {
                // 取消订阅
                unsubscribeFromEvents()
                
                // 断开连接
                context.unbindService(serviceConnection)
                isConnected = false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop event stream listener", e)
        }
    }
    
    /**
     * 连接到事件服务
     */
    private fun connect() {
        try {
            val intent = Intent(EVENT_SERVICE_ACTION).apply {
                setPackage(EVENT_SERVICE_PACKAGE)
            }
            
            val bound = context.bindService(
                intent,
                serviceConnection,
                Context.BIND_AUTO_CREATE
            )
            
            if (!bound) {
                Log.e(TAG, "Failed to bind to Event Stream Service")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to Event Stream Service", e)
        }
    }
    
    /**
     * 重连
     */
    private fun reconnect() {
        Log.i(TAG, "Attempting to reconnect...")
        Thread.sleep(1000)
        connect()
    }
    
    /**
     * 订阅事件
     */
    private fun subscribeToEvents() {
        if (!isConnected) {
            Log.w(TAG, "Not connected to Event Stream Service")
            return
        }
        
        try {
            // 提取所有需要订阅的事件类型
            val eventTypes = triggerRules.map { it.eventType }.distinct()
            
            Log.i(TAG, "Subscribing to ${eventTypes.size} event types: $eventTypes")
            
            // TODO: 调用 AIDL 接口订阅
            // for (eventType in eventTypes) {
            //     eventService!!.subscribe(eventType, eventCallback)
            // }
            
            Log.i(TAG, "Event subscription completed")
            
        } catch (e: RemoteException) {
            Log.e(TAG, "Failed to subscribe to events", e)
        }
    }
    
    /**
     * 取消订阅
     */
    private fun unsubscribeFromEvents() {
        try {
            // TODO: 调用 AIDL 接口取消订阅
            // eventService!!.unsubscribe(eventCallback)
            
            Log.i(TAG, "Event unsubscription completed")
            
        } catch (e: RemoteException) {
            Log.e(TAG, "Failed to unsubscribe from events", e)
        }
    }
    
    /**
     * 事件回调（当事件发生时触发）
     */
    fun onEventReceived(eventType: String, eventData: Map<String, Any>) {
        Log.d(TAG, "Event received: type=$eventType")
        
        // 检查是否匹配触发规则
        val matchedRules = triggerRules.filter { rule ->
            rule.eventType == eventType && rule.matchesCondition(eventData)
        }
        
        if (matchedRules.isEmpty()) {
            Log.d(TAG, "No matching trigger rules for event: $eventType")
            return
        }
        
        Log.i(TAG, "Event matched ${matchedRules.size} trigger rules")
        
        // 触发意图接收
        for (rule in matchedRules) {
            try {
                val turnId = intentReceiver.onEventTriggered(
                    eventType = eventType,
                    eventData = eventData,
                    triggerRules = listOf(rule.ruleId)
                )
                
                Log.i(TAG, "Event triggered intent: turnId=$turnId, rule=${rule.ruleId}")
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to trigger intent for rule: ${rule.ruleId}", e)
            }
        }
    }
    
    /**
     * 加载触发规则
     */
    private fun loadTriggerRules() {
        Log.i(TAG, "Loading trigger rules...")
        
        // TODO: 从配置文件或数据库加载触发规则
        // 临时实现：硬编码一些示例规则
        
        triggerRules.clear()
        
        // 规则 1: 来电时提醒用户
        triggerRules.add(
            EventTriggerRule(
                ruleId = "rule_incoming_call",
                eventType = "genton.event.INCOMING_CALL",
                condition = { data ->
                    // 条件：来电号码不在通讯录中
                    val number = data["phone_number"] as? String
                    val inContacts = data["in_contacts"] as? Boolean ?: false
                    !inContacts && number != null
                },
                description = "Incoming call from unknown number"
            )
        )
        
        // 规则 2: 重要通知到达
        triggerRules.add(
            EventTriggerRule(
                ruleId = "rule_important_notification",
                eventType = "genton.event.NOTIFICATION_POSTED",
                condition = { data ->
                    // 条件：通知优先级为高
                    val priority = data["priority"] as? Int ?: 0
                    priority >= 1 // PRIORITY_HIGH
                },
                description = "Important notification posted"
            )
        )
        
        // 规则 3: 电量低于 15%
        triggerRules.add(
            EventTriggerRule(
                ruleId = "rule_low_battery",
                eventType = "genton.event.BATTERY_CHANGED",
                condition = { data ->
                    val level = data["battery_level"] as? Int ?: 100
                    level <= 15
                },
                description = "Battery level below 15%"
            )
        )
        
        Log.i(TAG, "Loaded ${triggerRules.size} trigger rules")
    }
    
    /**
     * 添加触发规则
     */
    fun addTriggerRule(rule: EventTriggerRule) {
        Log.i(TAG, "Adding trigger rule: ${rule.ruleId}")
        triggerRules.add(rule)
        
        // 如果已连接，立即订阅新事件类型
        if (isConnected && triggerRules.count { it.eventType == rule.eventType } == 1) {
            subscribeToEvents()
        }
    }
    
    /**
     * 移除触发规则
     */
    fun removeTriggerRule(ruleId: String): Boolean {
        Log.i(TAG, "Removing trigger rule: $ruleId")
        return triggerRules.removeIf { it.ruleId == ruleId }
    }
    
    /**
     * 事件触发规则
     */
    data class EventTriggerRule(
        val ruleId: String,
        val eventType: String,
        val condition: (Map<String, Any>) -> Boolean,
        val description: String
    ) {
        fun matchesCondition(eventData: Map<String, Any>): Boolean {
            return try {
                condition(eventData)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to evaluate trigger condition: $ruleId", e)
                false
            }
        }
    }
}
