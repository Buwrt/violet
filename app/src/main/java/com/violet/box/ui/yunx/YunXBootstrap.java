package com.violet.box.ui.yunx;

import android.content.Context;
import android.content.Intent;

/**
 * 云析 YunX（AGPL-3.0）的**懒加载**入口。
 *
 * <p>为什么不在 Application.onCreate 里初始化：V15 试过，部分设备上一启动就卡死 ——
 * YunX 的装配包含 Room 数据库、隐藏 WebView、Android Keystore 自愈、代理恢复、
 * 全局崩溃处理器接管等一大串动作，任何一个在主线程卡住都会让主界面永远出不来。
 * 宿主是玩机工具，主界面必须始终可用，不能被一个附属模块拖死。
 *
 * <p>改成：用户点「玩机 → 云析」时才装配，且放到**后台线程**做，
 * 主线程只负责弹提示和跳转。失败就明确告诉用户，不影响其他功能。
 */
public final class YunXBootstrap {

    private static volatile boolean sInstalled = false;
    private static volatile boolean sInstalling = false;
    private static volatile Throwable sLastError = null;

    private YunXBootstrap() {
    }

    /**
     * 打开云析。首次调用会在后台线程装配后再跳转；已装配过则直接跳转。
     *
     * @param ctx        上下文
     * @param onProgress 可选回调，在主线程执行，用于显示"正在初始化"
     */
    public static void open(Context ctx, Runnable onProgress) {
        if (sInstalled) {
            launch(ctx);
            return;
        }
        if (sInstalling) {
            return;
        }
        sInstalling = true;
        if (onProgress != null) onProgress.run();

        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                com.yunx.app.YunXHost.install((android.app.Application) app);
                sInstalled = true;
            } catch (Throwable t) {
                sLastError = t;
                android.util.Log.w("VioletBox", "云析初始化失败", t);
            } finally {
                sInstalling = false;
            }
            android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
            main.post(() -> {
                if (sInstalled) {
                    launch(ctx);
                } else {
                    Throwable e = sLastError;
                    String msg = e == null ? "未知错误" : (e.getMessage() == null
                            ? e.getClass().getSimpleName() : e.getMessage());
                    android.widget.Toast.makeText(app,
                            "云析初始化失败：" + msg + "\nROM 专区等功能不受影响",
                            android.widget.Toast.LENGTH_LONG).show();
                }
            });
        }, "yunx-bootstrap").start();
    }

    private static void launch(Context ctx) {
        try {
            Intent it = new Intent();
            it.setClassName(ctx, "com.yunx.app.MainActivity");
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(it);
        } catch (Throwable t) {
            android.util.Log.w("VioletBox", "打开云析失败", t);
            android.widget.Toast.makeText(ctx, "云析未随本版构建："
                    + t.getClass().getSimpleName(), android.widget.Toast.LENGTH_LONG).show();
        }
    }

    /** 转发内存压力回调；未初始化时 YunXHost 内部自行判断。 */
    public static void onTrimMemory(int level) {
        if (!sInstalled) return;
        try {
            com.yunx.app.YunXHost.onTrimMemory(level);
        } catch (Throwable ignored) {
        }
    }

    public static boolean isReady() {
        return sInstalled;
    }
}
