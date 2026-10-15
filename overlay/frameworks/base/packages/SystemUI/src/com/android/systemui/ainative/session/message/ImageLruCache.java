// AI Native OS - Image LRU Cache
package com.android.systemui.ainative.session.message;

import android.graphics.Bitmap;
import android.util.Log;
import android.util.LruCache;

/**
 * 图片 LRU 缓存：缓存附件缩略图避免重复加载（D2.1 增强）
 * 
 * 功能：
 * - LruCache 实现，基于内存大小限制（最大可用内存的 1/8）
 * - 按文件路径作为 key 缓存 Bitmap
 * - 避免长会话中重复解码图片导致的性能开销
 * 
 * 设计原则：
 * - 单例模式：全局共享一个缓存实例
 * - 内存安全：基于可用内存动态设置缓存大小
 * - 自动淘汰：LRU 策略，优先保留最近访问的图片
 * 
 * @since A2 (D2.1 增强)
 */
public class ImageLruCache {
    private static final String TAG = "ImageLruCache";
    
    private static ImageLruCache sInstance;
    
    private final LruCache<String, Bitmap> mCache;
    
    private ImageLruCache() {
        // 获取应用最大可用内存（单位：KB）
        final int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        
        // 使用最大内存的 1/8 作为缓存大小
        final int cacheSize = maxMemory / 8;
        
        mCache = new LruCache<String, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                // 返回 Bitmap 占用的 KB 数
                return bitmap.getByteCount() / 1024;
            }
        };
        
        Log.i(TAG, "ImageLruCache initialized with size: " + cacheSize + " KB");
    }
    
    /**
     * 获取单例实例
     */
    public static synchronized ImageLruCache getInstance() {
        if (sInstance == null) {
            sInstance = new ImageLruCache();
        }
        return sInstance;
    }
    
    /**
     * 从缓存获取 Bitmap
     */
    public Bitmap get(String key) {
        Bitmap bitmap = mCache.get(key);
        if (bitmap != null) {
            Log.d(TAG, "Cache hit: " + key);
        }
        return bitmap;
    }
    
    /**
     * 放入缓存
     */
    public void put(String key, Bitmap bitmap) {
        if (key == null || bitmap == null) {
            return;
        }
        mCache.put(key, bitmap);
        Log.d(TAG, "Cache put: " + key + " (" + (bitmap.getByteCount() / 1024) + " KB)");
    }
    
    /**
     * 清除指定 key
     */
    public void remove(String key) {
        mCache.remove(key);
    }
    
    /**
     * 清空缓存
     */
    public void clear() {
        mCache.evictAll();
        Log.i(TAG, "Cache cleared");
    }
    
    /**
     * 获取缓存使用率（0-100）
     */
    public int getUsagePercent() {
        return (int) ((float) mCache.size() / mCache.maxSize() * 100);
    }
}
