package com.ainative.brain.lifecycle

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ainative.brain.BrainApplication

/**
 * 系统启动完成接收器
 */
class BootCompletedReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "BootCompletedReceiver"
    }
    
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) {
            return
        }
        
        val action = intent.action
        Log.i(TAG, "Received broadcast: $action")
        
        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                onBootCompleted(context)
            }
        }
    }
    
    private fun onBootCompleted(context: Context) {
        Log.i(TAG, "System boot completed, initializing Brain...")
        
        try {
            // 启动 Bootstrap 服务
            val intent = Intent(context, BrainBootstrapService::class.java)
            context.startForegroundService(intent)
            
            Log.i(TAG, "Brain initialization started")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Brain on boot", e)
        }
    }
}

/**
 * 用户解锁接收器
 */
class UserUnlockedReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "UserUnlockedReceiver"
    }
    
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) {
            return
        }
        
        Log.i(TAG, "User unlocked, checking Brain status...")
        
        try {
            val app = BrainApplication.getInstance()
            if (!app.isBootstrapped()) {
                Log.i(TAG, "Brain not bootstrapped, starting initialization...")
                
                val bootstrapIntent = Intent(context, BrainBootstrapService::class.java)
                context.startForegroundService(bootstrapIntent)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle user unlock", e)
        }
    }
}
