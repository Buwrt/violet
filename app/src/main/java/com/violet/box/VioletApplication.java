package com.violet.box;

import android.app.Application;

import com.google.android.material.color.DynamicColors;

/**
 * 宿主 Application。
 *
 * <p>【V15.1 重要改动】这里**不再**初始化云析 YunX。
 *
 * <p>V15 把 YunX 的运行时装配放在 Application.onCreate 里，结果部分设备上一启动就卡死 /
 * 崩溃 —— 宿主体量小，突然多出 275 个 Kotlin 文件的初始化（Room、WebView、Keystore、
 * 代理恢复、崩溃处理器），任何一步卡住都会让主界面永远出不来。
 *
 * <p>改成懒加载：云析只在用户真正点进「玩机 → 云析」时才初始化，
 * 见 {@link com.violet.box.ui.yunx.YunXBootstrap}。主界面不再依赖 YunX 的任何代码路径，
 * 即使云析初始化失败，ROM 专区 / 模块下载中心 / 环境检测等照常可用。
 */
public class VioletApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        DynamicColors.applyToActivitiesIfAvailable(this);
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        try {
            com.violet.box.ui.yunx.YunXBootstrap.onTrimMemory(level);
        } catch (Throwable ignored) {
            // 云析还没初始化过，忽略
        }
    }
}
