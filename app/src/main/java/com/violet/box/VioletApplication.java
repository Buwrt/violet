package com.violet.box;

import android.app.Application;

import com.google.android.material.color.DynamicColors;

/**
 * 宿主 Application。
 *
 * <p>V15 起同时承担「云析 YunX」的运行时装配：一个 APK 只能有一个 Application，
 * YunX 原本的初始化写在其自己的 YunXApp 里，融合后由这里调用 {@code YunXHost.install}。
 * 任何异常都不允许拖垮宿主的启动 —— 玩机工具本身必须始终可用。
 */
public class VioletApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        DynamicColors.applyToActivitiesIfAvailable(this);
        installYunX();
    }

    private void installYunX() {
        try {
            com.yunx.app.YunXHost.install(this);
        } catch (Throwable t) {
            android.util.Log.w("VioletBox", "云析装配失败，ROM 专区等功能不受影响", t);
        }
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        try {
            com.yunx.app.YunXHost.onTrimMemory(level);
        } catch (Throwable ignored) {
        }
    }
}
