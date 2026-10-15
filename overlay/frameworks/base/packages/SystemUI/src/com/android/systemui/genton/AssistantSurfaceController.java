// AI Native OS - Assistant Surface Controller
package com.android.systemui.genton;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import com.android.systemui.genton.session.SessionViewController;
import com.android.systemui.genton.launcher.LauncherViewController;
import com.android.systemui.genton.accessibility.AnimationAccessibilityManager;

/**
 * 双模式容器控制器：管理 Assistant Surface 和 Launcher3 同容器呈现
 * 
 * 核心职责：
 * - 双模式优先级切换（< 1帧）
 * - 模式状态机管理
 * - 容器生命周期协调
 * 
 * 状态机：LAUNCHER（桌面模式）↔ ASSISTANT（助理模式）
 */
public class AssistantSurfaceController {
    private static final String TAG = "AssistantSurfaceController";
    
    // 模式枚举
    public enum Mode {
        LAUNCHER,   // 桌面模式（Launcher3）
        ASSISTANT   // 助理模式（Assistant Surface）
    }
    
    // 状态机状态
    private enum State {
        IDLE,           // 空闲
        TRANSITIONING,  // 切换中
        ACTIVE          // 活跃
    }
    
    private final Context mContext;
    private final Handler mHandler;
    private final WindowManager mWindowManager;
    
    // 容器
    private FrameLayout mRootContainer;
    private ViewGroup mLauncherContainer;
    private ViewGroup mAssistantContainer;
    
    // 控制器
    private LauncherViewController mLauncherController;
    private SessionViewController mSessionController;
    
    // D2.3：胶囊系统
    private SuggestionCapsuleManager mCapsuleManager;
    private SuggestionCapsuleContainer mCapsuleContainer;
    private AssistantUiService mUiService;
    private AnimationAccessibilityManager mAnimationManager;
    
    // 状态
    private Mode mCurrentMode = Mode.LAUNCHER;
    private State mState = State.IDLE;
    private boolean mIsInitialized = false;
    
    // 切换监听器
    private OnModeChangeListener mModeChangeListener;
    
    public interface OnModeChangeListener {
        void onModeChanged(Mode oldMode, Mode newMode);
        void onModeChanging(Mode fromMode, Mode toMode);
    }
    
    public AssistantSurfaceController(Context context) {
        mContext = context;
        mHandler = new Handler(Looper.getMainLooper());
        mWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        mAnimationManager = new AnimationAccessibilityManager(context);
    }
    
    /**
     * 初始化双模式容器
     */
    public void initialize() {
        if (mIsInitialized) {
            Log.w(TAG, "Already initialized");
            return;
        }
        
        // 创建根容器
        mRootContainer = new FrameLayout(mContext);
        mRootContainer.setLayoutParams(new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ));
        
        // 创建 Launcher 容器
        mLauncherContainer = new FrameLayout(mContext);
        mLauncherContainer.setLayoutParams(new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ));
        mLauncherContainer.setVisibility(View.VISIBLE);
        
        // 创建 Assistant 容器
        mAssistantContainer = new FrameLayout(mContext);
        mAssistantContainer.setLayoutParams(new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ));
        mAssistantContainer.setVisibility(View.GONE);
        
        // 添加到根容器（Launcher 在底层，Assistant 在上层）
        mRootContainer.addView(mLauncherContainer);
        mRootContainer.addView(mAssistantContainer);
        
        // 初始化子控制器
        mLauncherController = new LauncherViewController(mContext, mLauncherContainer);
        mSessionController = new SessionViewController(mContext, mAssistantContainer);
        
        mLauncherController.initialize();
        mSessionController.initialize();
        
        mIsInitialized = true;
        mState = State.IDLE;
        
        Log.i(TAG, "Dual-mode container initialized");
    }
    
    /**
     * 获取根容器视图
     */
    public View getRootView() {
        return mRootContainer;
    }
    
    /**
     * 切换模式（< 1帧性能要求）
     * 
     * @param targetMode 目标模式
     * @param animated 是否动画
     */
    public void switchMode(Mode targetMode, boolean animated) {
        if (!mIsInitialized) {
            Log.e(TAG, "Not initialized");
            return;
        }
        
        if (mCurrentMode == targetMode) {
            Log.d(TAG, "Already in mode: " + targetMode);
            return;
        }
        
        if (mState == State.TRANSITIONING) {
            Log.w(TAG, "Transition in progress, ignoring");
            return;
        }
        
        Mode oldMode = mCurrentMode;
        mState = State.TRANSITIONING;
        
        // 通知切换开始
        if (mModeChangeListener != null) {
            mModeChangeListener.onModeChanging(oldMode, targetMode);
        }
        
        Log.i(TAG, "Switching mode: " + oldMode + " -> " + targetMode);
        
        if (animated) {
            performAnimatedSwitch(oldMode, targetMode);
        } else {
            performImmediateSwitch(oldMode, targetMode);
        }
    }
    
    /**
     * 立即切换（无动画，< 1帧）
     */
    private void performImmediateSwitch(Mode fromMode, Mode toMode) {
        // 直接切换可见性（< 1帧保证）
        if (toMode == Mode.ASSISTANT) {
            mLauncherContainer.setVisibility(View.GONE);
            mAssistantContainer.setVisibility(View.VISIBLE);
            mSessionController.onBecomeActive();
            mLauncherController.onBecomeInactive();
        } else {
            mAssistantContainer.setVisibility(View.GONE);
            mLauncherContainer.setVisibility(View.VISIBLE);
            mLauncherController.onBecomeActive();
            mSessionController.onBecomeInactive();
        }
        
        mCurrentMode = toMode;
        mState = State.ACTIVE;
        
        // 通知切换完成
        if (mModeChangeListener != null) {
            mModeChangeListener.onModeChanged(fromMode, toMode);
        }
        
        Log.i(TAG, "Mode switched immediately: " + toMode);
    }
    
    /**
     * 动画切换（带动效）
     */
    private void performAnimatedSwitch(Mode fromMode, Mode toMode) {
        ViewGroup fromContainer = (fromMode == Mode.LAUNCHER) ? mLauncherContainer : mAssistantContainer;
        ViewGroup toContainer = (toMode == Mode.LAUNCHER) ? mLauncherContainer : mAssistantContainer;
        
        // 准备目标容器
        toContainer.setVisibility(View.VISIBLE);
        toContainer.setAlpha(0f);
        
        // 淡入淡出动画（使用AnimationAccessibilityManager确保<150ms并支持无障碍）
        final long ANIMATION_DURATION = mAnimationManager.getActualDuration(
            AnimationAccessibilityManager.TRANSITION_DURATION);
        
        toContainer.animate()
            .alpha(1f)
            .setDuration(ANIMATION_DURATION)
            .withEndAction(() -> {
                // 动画完成
                fromContainer.setVisibility(View.GONE);
                fromContainer.setAlpha(1f);
                
                // 通知控制器状态变化
                if (toMode == Mode.ASSISTANT) {
                    mSessionController.onBecomeActive();
                    mLauncherController.onBecomeInactive();
                } else {
                    mLauncherController.onBecomeActive();
                    mSessionController.onBecomeInactive();
                }
                
                mCurrentMode = toMode;
                mState = State.ACTIVE;
                
                // 通知切换完成
                if (mModeChangeListener != null) {
                    mModeChangeListener.onModeChanged(fromMode, toMode);
                }
                
                // D1.3：切换到 LAUNCHER 时，保持后台任务状态（SessionServiceConnector 不解绑）
                // 胶囊由 BackgroundAssistantManager 在后台继续显示
                
                Log.i(TAG, "Mode switched with animation: " + toMode);
            })
            .start();
        
        fromContainer.animate()
            .alpha(0f)
            .setDuration(ANIMATION_DURATION)
            .start();
    }
    
    /**
     * 获取当前模式
     */
    public Mode getCurrentMode() {
        return mCurrentMode;
    }
    
    /**
     * 是否在切换中
     */
    public boolean isTransitioning() {
        return mState == State.TRANSITIONING;
    }
    
    /**
     * 设置模式切换监听器
     */
    public void setOnModeChangeListener(OnModeChangeListener listener) {
        mModeChangeListener = listener;
    }
    
    /**
     * 获取会话控制器
     */
    public SessionViewController getSessionController() {
        return mSessionController;
    }
    
    /**
     * 获取Launcher控制器
     */
    public LauncherViewController getLauncherController() {
        return mLauncherController;
    }
    
    /**
     * D3.2：初始化活性指示器系统
     */
    private void initializeIndicatorSystem() {
        // 1. 创建指示器管理器
        mIndicatorManager = new ActivityIndicatorManager();
        
        // 2. 创建指示器容器视图
        mIndicatorContainer = new ActivityIndicatorContainer(mContext);
        FrameLayout.LayoutParams indicatorParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        );
        indicatorParams.gravity = android.view.Gravity.TOP | android.view.Gravity.END;
        indicatorParams.topMargin = dpToPx(16);
        indicatorParams.rightMargin = dpToPx(16);
        mRootContainer.addView(mIndicatorContainer, indicatorParams);
        
        // 3. 设置显示回调
        mIndicatorManager.setDisplayCallback((kind, active, agentCount, agentIds) -> {
            mIndicatorContainer.updateChip(kind, active, agentCount);
        });
        
        // 4. 设置点击回调（展开详情列表）
        mIndicatorContainer.setOnChipClickListener(kind -> {
            // TODO: 展开活性详情列表（显示具体 Agent）
            Log.i(TAG, "Indicator chip clicked: kind=" + kind);
            
            // 暂时打印活跃 Agent 列表
            java.util.List<String> agents = mIndicatorManager.getActiveAgents(kind);
            Log.d(TAG, "Active agents: " + agents);
        });
        
        // 5. 创建控制器（连接审计服务）
        mIndicatorController = new ActivityIndicatorController(mContext, mIndicatorManager);
        
        // 6. 设置活性指示器回调（连接到 UI 服务）
        if (mUiService != null) {
            mUiService.setActivityIndicatorCallback((kind, active) -> {
                // 从 UI 服务路由到指示器管理器（用于测试或手动控制）
                Log.d(TAG, "Activity indicator from UI service: kind=" + kind + 
                      ", active=" + active);
            });
        }
        
        Log.i(TAG, "Indicator system initialized (D3.2)");
    }
    
    /**
     * D3.2：连接审计服务（供 SystemServer 调用）
     */
    public void connectAuditService(AgentAuditService auditService) {
        if (mIndicatorController != null) {
            mIndicatorController.initialize(auditService);
            Log.i(TAG, "Audit service connected to indicator controller");
        }
    }
    
    /**
     * D2.3：获取胶囊管理器
     */
    public SuggestionCapsuleManager getCapsuleManager() {
        return mCapsuleManager;
    }
    
    /**
     * D3.2：获取指示器管理器
     */
    public ActivityIndicatorManager getIndicatorManager() {
        return mIndicatorManager;
    }
    
    /**
     * 销毁
     */
    public void destroy() {
        if (!mIsInitialized) {
            return;
        }
        
        if (mSessionController != null) {
            mSessionController.destroy();
        }
        
        if (mLauncherController != null) {
            mLauncherController.destroy();
        }
        
        // D2.3：销毁胶囊系统
        if (mCapsuleManager != null) {
            mCapsuleManager.destroy();
        }
        
        if (mUiService != null) {
            mUiService.destroy();
        }
        
        // D3.2：销毁活性指示器系统
        if (mIndicatorController != null) {
            mIndicatorController.destroy();
        }
        
        if (mIndicatorManager != null) {
            mIndicatorManager.destroy();
        }
        
        mModeChangeListener = null;
        mIsInitialized = false;
        
        Log.i(TAG, "Controller destroyed");
    }
}
