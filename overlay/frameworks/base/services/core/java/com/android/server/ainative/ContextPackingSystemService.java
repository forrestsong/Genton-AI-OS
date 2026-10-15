// ContextPackingSystemService.java
// C3.3 上下文打包系统服务：集成到 system_server
package com.android.server.ainative;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Slog;

import com.ainative.semantics.extraction.EntityExtractionPipeline;
import com.ainative.semantics.extraction.EntityChange;
import com.ainative.semantics.extraction.IEntityChangeListener;
import com.ainative.semantics.packing.*;
import com.ainative.semantics.ontology.EntityId;

/**
 * 上下文打包系统服务
 * 
 * 职责：
 * 1. 提供 packContext API（供大脑/Runtime 调用）
 * 2. 监听实体变更，自动失效缓存
 * 3. 定期清理过期缓存
 * 4. 与 ACB checkpoint 联动（保存/加载 packId）
 */
public class ContextPackingSystemService {
    private static final String TAG = "ContextPackingSystemService";
    
    private final Context mContext;
    private final ContextPackingService mPackingService;
    private final EntityExtractionService mEntityService;
    
    // 后台线程
    private HandlerThread mBackgroundThread;
    private Handler mBackgroundHandler;
    
    // 缓存清理间隔（5 分钟）
    private static final long CACHE_CLEANUP_INTERVAL_MS = 5 * 60 * 1000L;
    
    public ContextPackingSystemService(
            Context context,
            EntityExtractionService entityService) {
        mContext = context;
        mEntityService = entityService;
        
        // 创建打包服务
        EntityExtractionPipeline pipeline = entityService.getPipeline();
        mPackingService = new ContextPackingService(context, pipeline);
        
        Slog.i(TAG, "ContextPackingSystemService created");
    }
    
    /**
     * 系统启动时调用
     */
    public void onStart() {
        Slog.i(TAG, "Starting ContextPackingSystemService");
        
        // 启动后台线程
        mBackgroundThread = new HandlerThread("ContextPackingThread");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
        
        // 注册实体变更监听器
        mEntityService.registerChangeListener(new EntityChangeListenerImpl());
        
        // 启动定期缓存清理
        scheduleCache Cleanup();
        
        Slog.i(TAG, "ContextPackingSystemService started");
    }
    
    /**
     * 系统关闭时调用
     */
    public void onStop() {
        Slog.i(TAG, "Stopping ContextPackingSystemService");
        
        if (mBackgroundThread != null) {
            mBackgroundThread.quitSafely();
            try {
                mBackgroundThread.join(5000);
            } catch (InterruptedException e) {
                Slog.w(TAG, "Interrupted while stopping background thread", e);
            }
        }
        
        Slog.i(TAG, "ContextPackingSystemService stopped");
    }
    
    /**
     * 打包上下文（主入口）
     * 
     * @param request 打包请求
     * @return ContextPack
     */
    public ContextPack packContext(ContextPackRequest request) {
        long startTime = System.currentTimeMillis();
        
        try {
            ContextPack pack = mPackingService.packContext(request);
            
            long elapsed = System.currentTimeMillis() - startTime;
            Slog.d(TAG, "packContext completed: packId=" + pack.getPackId() + 
                    ", entities=" + pack.getEntities().size() + 
                    ", elapsed=" + elapsed + "ms");
            
            return pack;
            
        } catch (Exception e) {
            Slog.e(TAG, "Error in packContext", e);
            throw e;
        }
    }
    
    /**
     * 加载已打包的上下文（用于 checkpoint 恢复）
     * 
     * @param packId pack ID
     * @return ContextPack，不存在返回 null
     */
    public ContextPack loadContextPack(long packId) {
        try {
            ContextPack pack = mPackingService.loadContextPack(packId);
            
            if (pack != null) {
                Slog.d(TAG, "loadContextPack: packId=" + packId + 
                        ", entities=" + pack.getEntities().size());
            } else {
                Slog.w(TAG, "loadContextPack: pack not found, packId=" + packId);
            }
            
            return pack;
            
        } catch (Exception e) {
            Slog.e(TAG, "Error loading context pack: packId=" + packId, e);
            return null;
        }
    }
    
    /**
     * 失效缓存（手动触发）
     * 
     * @param entityId 实体 ID
     */
    public void invalidateCache(EntityId entityId) {
        mBackgroundHandler.post(() -> {
            try {
                mPackingService.invalidateCache(entityId);
            } catch (Exception e) {
                Slog.e(TAG, "Error invalidating cache", e);
            }
        });
    }
    
    /**
     * 定期缓存清理
     */
    private void scheduleCacheCleanup() {
        mBackgroundHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    mPackingService.cleanupExpiredCache();
                } catch (Exception e) {
                    Slog.e(TAG, "Error cleaning up cache", e);
                }
                
                // 递归调度下一次清理
                mBackgroundHandler.postDelayed(this, CACHE_CLEANUP_INTERVAL_MS);
            }
        }, CACHE_CLEANUP_INTERVAL_MS);
    }
    
    /**
     * 实体变更监听器实现
     */
    private class EntityChangeListenerImpl implements IEntityChangeListener {
        @Override
        public void onEntityChanged(EntityChange change) {
            // 实体变更 → 失效相关的 pack 缓存
            mBackgroundHandler.post(() -> {
                try {
                    mPackingService.invalidateCache(change.getEntityId());
                    Slog.d(TAG, "Cache invalidated due to entity change: " + 
                            change.getEntityId().getId());
                } catch (Exception e) {
                    Slog.e(TAG, "Error handling entity change", e);
                }
            });
        }
    }
}
