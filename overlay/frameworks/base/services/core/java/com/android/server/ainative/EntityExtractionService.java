// EntityExtractionService.java
// C3.2 实体抽取服务：系统服务实现
package com.android.server.ainative;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Slog;

import com.ainative.semantics.extraction.*;
import com.ainative.semantics.ontology.*;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 实体抽取服务
 * 
 * 系统服务实现，集成实体抽取管线和数据源适配器
 * 
 * 职责：
 * 1. 启动时同步联系人和日历实体
 * 2. 监听系统事件（联系人变更、日历变更、对话轮次）
 * 3. 触发实体抽取
 * 4. 广播实体变更（供上下文打包服务消费）
 */
public class EntityExtractionService {
    private static final String TAG = "EntityExtractionService";
    
    private final Context mContext;
    private final EntityExtractionPipeline mPipeline;
    
    // 数据源适配器
    private ContactSyncAdapter mContactAdapter;
    private CalendarEventAdapter mCalendarAdapter;
    private ConversationEntityExtractor mConversationExtractor;
    private NotificationEntityExtractor mNotificationExtractor;
    private UserInputEntityExtractor mUserInputExtractor;
    
    // 后台线程
    private HandlerThread mBackgroundThread;
    private Handler mBackgroundHandler;
    
    // 初始化完成标志
    private volatile boolean mInitialized = false;
    private final CountDownLatch mInitLatch = new CountDownLatch(1);
    
    public EntityExtractionService(Context context) {
        mContext = context;
        mPipeline = new EntityExtractionPipeline(context);
        
        Slog.i(TAG, "EntityExtractionService created");
    }
    
    /**
     * 系统启动时调用
     */
    public void onStart() {
        Slog.i(TAG, "Starting EntityExtractionService");
        
        // 启动后台线程
        mBackgroundThread = new HandlerThread("EntityExtractionThread");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
        
        // 初始化数据源适配器
        initializeAdapters();
        
        // 后台执行初始同步
        mBackgroundHandler.post(this::performInitialSync);
        
        Slog.i(TAG, "EntityExtractionService started");
    }
    
    /**
     * 系统关闭时调用
     */
    public void onStop() {
        Slog.i(TAG, "Stopping EntityExtractionService");
        
        if (mBackgroundThread != null) {
            mBackgroundThread.quitSafely();
            try {
                mBackgroundThread.join(5000);
            } catch (InterruptedException e) {
                Slog.w(TAG, "Interrupted while stopping background thread", e);
            }
        }
        
        Slog.i(TAG, "EntityExtractionService stopped");
    }
    
    /**
     * 初始化数据源适配器
     */
    private void initializeAdapters() {
        mContactAdapter = new ContactSyncAdapter(mContext, mPipeline);
        mCalendarAdapter = new CalendarEventAdapter(mContext, mPipeline);
        mConversationExtractor = new ConversationEntityExtractor(mPipeline);
        mNotificationExtractor = new NotificationEntityExtractor(mPipeline);
        mUserInputExtractor = new UserInputEntityExtractor(mPipeline);
        
        Slog.d(TAG, "Data source adapters initialized");
    }
    
    /**
     * 执行初始同步
     */
    private void performInitialSync() {
        long startTime = System.currentTimeMillis();
        Slog.i(TAG, "Starting initial entity sync");
        
        try {
            // 1. 同步联系人
            int contactCount = mContactAdapter.syncAll();
            Slog.i(TAG, "Synced " + contactCount + " contacts");
            
            // 2. 同步日历事件
            int eventCount = mCalendarAdapter.syncUpcomingEvents();
            Slog.i(TAG, "Synced " + eventCount + " calendar events");
            
            mInitialized = true;
            mInitLatch.countDown();
            
            long elapsedTime = System.currentTimeMillis() - startTime;
            Slog.i(TAG, "Initial entity sync completed in " + elapsedTime + "ms");
            
        } catch (Exception e) {
            Slog.e(TAG, "Error during initial sync", e);
            mInitLatch.countDown();
        }
    }
    
    /**
     * 等待初始化完成
     */
    public boolean waitForInitialization(long timeoutMs) {
        try {
            return mInitLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Slog.w(TAG, "Interrupted while waiting for initialization", e);
            return false;
        }
    }
    
    /**
     * 是否已初始化
     */
    public boolean isInitialized() {
        return mInitialized;
    }
    
    /**
     * 从对话中抽取实体
     * 
     * @param conversationTurnId 对话轮次 ID
     * @param text 对话文本
     * @param nluEntities NLU 提取的实体列表
     */
    public void extractFromConversation(
            String conversationTurnId,
            String text,
            List<NLUEntity> nluEntities) {
        
        mBackgroundHandler.post(() -> {
            try {
                List<Entity> entities = mConversationExtractor.extractFromConversation(
                    conversationTurnId,
                    text,
                    nluEntities
                );
                
                Slog.d(TAG, "Extracted " + entities.size() + " entities from conversation " + conversationTurnId);
                
            } catch (Exception e) {
                Slog.e(TAG, "Error extracting entities from conversation", e);
            }
        });
    }
    
    /**
     * 从通知中抽取实体
     */
    public void extractFromNotification(
            String notificationId,
            String packageName,
            String appName,
            String title,
            String text) {
        
        mBackgroundHandler.post(() -> {
            try {
                List<Entity> entities = mNotificationExtractor.extractFromNotification(
                    notificationId,
                    packageName,
                    appName,
                    title,
                    text
                );
                
                Slog.d(TAG, "Extracted " + entities.size() + " entities from notification " + notificationId);
                
            } catch (Exception e) {
                Slog.e(TAG, "Error extracting entities from notification", e);
            }
        });
    }
    
    /**
     * 从用户输入创建实体
     */
    public Entity createFromUserInput(
            String intentId,
            EntityType type,
            String displayName,
            java.util.Map<String, Object> attributes) {
        
        // 用户输入需要同步返回
        try {
            Entity entity = mUserInputExtractor.createFromUserInput(
                intentId,
                type,
                displayName,
                attributes
            );
            
            if (entity != null) {
                Slog.i(TAG, "Created entity from user input: " + entity.getEntityId().getId());
            }
            
            return entity;
            
        } catch (Exception e) {
            Slog.e(TAG, "Error creating entity from user input", e);
            return null;
        }
    }
    
    /**
     * 注册实体变更监听器
     */
    public void registerChangeListener(IEntityChangeListener listener) {
        mPipeline.registerChangeListener(listener);
    }
    
    /**
     * 注销实体变更监听器
     */
    public void unregisterChangeListener(IEntityChangeListener listener) {
        mPipeline.unregisterChangeListener(listener);
    }
    
    /**
     * 获取实体抽取管线（供其他服务使用）
     */
    public EntityExtractionPipeline getPipeline() {
        return mPipeline;
    }
    
    /**
     * 手动触发联系人同步
     */
    public void syncContacts() {
        mBackgroundHandler.post(() -> {
            try {
                int count = mContactAdapter.syncAll();
                Slog.i(TAG, "Manual contact sync completed: " + count + " contacts");
            } catch (Exception e) {
                Slog.e(TAG, "Error during manual contact sync", e);
            }
        });
    }
    
    /**
     * 手动触发日历同步
     */
    public void syncCalendarEvents() {
        mBackgroundHandler.post(() -> {
            try {
                int count = mCalendarAdapter.syncUpcomingEvents();
                Slog.i(TAG, "Manual calendar sync completed: " + count + " events");
            } catch (Exception e) {
                Slog.e(TAG, "Error during manual calendar sync", e);
            }
        });
    }
}
