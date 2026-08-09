package com.ai_photo;

import android.app.Application;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.ai_photo.auth.Session;
import com.ai_photo.net.ApiClient;

/**
 * App 启动入口：初始化 Session，加载持久化 token。
 * 注入 ApiClient 的全局 401 监听器：任意接口收到 401 时清 Session 并跳 LoginActivity。
 */
public class App extends Application {

    private static final String TAG = "AiPhoto.App";
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate() {
        super.onCreate();
        Session.init(this);

        // 全局未捕获异常：把堆栈打到 logcat，便于排查"闪退/进程被杀"类问题
        Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
            Log.e(TAG, "[FATAL] thread=" + thread.getName()
                    + " exClass=" + ex.getClass().getName()
                    + " msg=" + ex.getMessage(), ex);
            // 调默认 handler，避免吞掉
            Thread.UncaughtExceptionHandler def =
                    Thread.getDefaultUncaughtExceptionHandler();
            if (def != null) def.uncaughtException(thread, ex);
        });

        // 全局 401 处理：清本地 session + 跳登录页
        ApiClient.setOnUnauthorizedListener((httpCode, rawBody) -> {
            Log.w(TAG, "[401] clearing session and jumping to LoginActivity");
            // Session.clear 必须在有 Context 的线程执行，且要异步跳页避免阻塞 HTTP 线程
            main.post(() -> {
                Session.clear(App.this);
                // 用 NEW_TASK + CLEAR_TASK 保证栈里其它 Activity 全部清掉
                Intent i = new Intent(App.this, LoginActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(i);
            });
        });
    }
}