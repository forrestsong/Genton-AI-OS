package com.genton.brain.lifecycle

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.genton.brain.BrainApplication

/**
 * 大脑启动自检服务
 * 
 * 职责：
 * 1. 系统服务连通性检查
 * 2. 权限完整性检查
 * 3. 模型文件可用性检查
 * 4. 运行时环境检查
 */
class BrainBootstrapService : Service() {
    
    companion object {
        private const val TAG = "BrainBootstrap"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "brain_bootstrap"
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "BrainBootstrapService created")
        
        // 创建前台通知（避免被系统杀死）
        createForegroundNotification()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "Starting bootstrap checks...")
        
        Thread {
            try {
                performBootstrapChecks()
                Log.i(TAG, "Bootstrap checks completed successfully")
                
                // 自检完成后停止前台服务
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                
            } catch (e: Exception) {
                Log.e(TAG, "Bootstrap checks failed", e)
                handleBootstrapFailure(e)
            }
        }.start()
        
        return START_STICKY
    }
    
    /**
     * 执行启动自检
     */
    private fun performBootstrapChecks() {
        val checks = listOf(
            BootstrapCheck("System Services", ::checkSystemServices),
            BootstrapCheck("Permissions", ::checkPermissions),
            BootstrapCheck("Model Files", ::checkModelFiles),
            BootstrapCheck("Runtime Environment", ::checkRuntimeEnvironment),
            BootstrapCheck("Agent Runtime Service", ::checkAgentRuntimeService)
        )
        
        for (check in checks) {
            Log.i(TAG, "Running check: ${check.name}...")
            check.run()
            Log.i(TAG, "✓ ${check.name} passed")
        }
    }
    
    /**
     * 检查系统服务连通性
     */
    private fun checkSystemServices() {
        val requiredServices = listOf(
            Context.ACTIVITY_SERVICE,
            Context.POWER_SERVICE,
            Context.NOTIFICATION_SERVICE
        )
        
        for (serviceName in requiredServices) {
            val service = getSystemService(serviceName)
            if (service == null) {
                throw BootstrapException("Required system service not available: $serviceName")
            }
        }
    }
    
    /**
     * 检查权限完整性
     */
    private fun checkPermissions() {
        val requiredPermissions = listOf(
            "com.genton.permission.MANAGE_AGENTS",
            "com.genton.permission.BIND_AGENT_RUNTIME",
            "com.genton.permission.READ_UI_TREE",
            "com.genton.permission.SUBMIT_ACTIONS",
            "com.genton.permission.ACCESS_AGENT_MEMORY"
        )
        
        for (permission in requiredPermissions) {
            val result = checkPermission(permission, android.os.Process.myPid(), android.os.Process.myUid())
            if (result != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Missing permission: $permission (this may be expected during initial setup)")
                // 注意：权限检查警告不应中断启动，因为某些权限可能在运行时授予
            }
        }
    }
    
    /**
     * 检查模型文件可用性
     */
    private fun checkModelFiles() {
        // 检查预置模型文件
        val modelPaths = listOf(
            "/system/genton/models/intent_classifier.tflite",
            "/system/genton/models/wake_word.tflite"
        )
        
        for (path in modelPaths) {
            val file = java.io.File(path)
            if (!file.exists()) {
                Log.w(TAG, "Model file not found: $path (may be optional)")
                // 模型文件缺失不中断启动，记录警告即可
            } else {
                Log.d(TAG, "Model file found: $path (${file.length()} bytes)")
            }
        }
    }
    
    /**
     * 检查运行时环境
     */
    private fun checkRuntimeEnvironment() {
        // 检查内存
        val runtime = Runtime.getRuntime()
        val maxMemory = runtime.maxMemory() / (1024 * 1024) // MB
        val totalMemory = runtime.totalMemory() / (1024 * 1024)
        val freeMemory = runtime.freeMemory() / (1024 * 1024)
        
        Log.i(TAG, "Memory: max=${maxMemory}MB, total=${totalMemory}MB, free=${freeMemory}MB")
        
        if (maxMemory < 128) {
            Log.w(TAG, "Low memory environment detected: ${maxMemory}MB")
        }
        
        // 检查存储空间
        val dataDir = applicationContext.dataDir
        val usableSpace = dataDir.usableSpace / (1024 * 1024) // MB
        
        Log.i(TAG, "Usable storage: ${usableSpace}MB")
        
        if (usableSpace < 100) {
            throw BootstrapException("Insufficient storage space: ${usableSpace}MB")
        }
        
        // 检查进程优先级
        val importance = android.os.Process.getThreadPriority(android.os.Process.myTid())
        Log.i(TAG, "Thread priority: $importance")
    }
    
    /**
     * 检查 Agent 运行时服务
     */
    private fun checkAgentRuntimeService() {
        try {
            val app = BrainApplication.getInstance()
            val runtimeClient = app.getRuntimeClient()
            
            // Ping 运行时服务
            val available = runtimeClient.ping()
            if (!available) {
                throw BootstrapException("Agent Runtime Service not responding")
            }
            
            Log.i(TAG, "Agent Runtime Service is available")
            
        } catch (e: Exception) {
            throw BootstrapException("Failed to connect to Agent Runtime Service", e)
        }
    }
    
    /**
     * 处理启动失败
     */
    private fun handleBootstrapFailure(error: Exception) {
        Log.e(TAG, "Bootstrap failed, attempting recovery...", error)
        
        // 记录启动失败
        getSharedPreferences("brain_state", MODE_PRIVATE)
            .edit()
            .putString("last_bootstrap_error", error.message)
            .putLong("last_bootstrap_error_time", System.currentTimeMillis())
            .apply()
        
        // TODO: 发送系统通知提示用户
        // TODO: 写入审计日志
        
        // 停止服务
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    
    /**
     * 创建前台通知
     */
    private fun createForegroundNotification() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Brain Bootstrap",
            NotificationManager.IMPORTANCE_LOW
        )
        
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
        
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("AI Native OS")
            .setContentText("Initializing Brain Service...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        
        startForeground(NOTIFICATION_ID, notification)
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    /**
     * 启动检查项
     */
    private data class BootstrapCheck(
        val name: String,
        val check: () -> Unit
    ) {
        fun run() = check()
    }
    
    /**
     * 启动异常
     */
    private class BootstrapException : Exception {
        constructor(message: String) : super(message)
        constructor(message: String, cause: Throwable) : super(message, cause)
    }
}
