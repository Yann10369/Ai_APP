package com.ai_photo.auth;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 用户会话（token / userId / username）。
 *
 * 持久化到 SharedPreferences，进程重启后仍能保持登录态。
 */
public final class Session {

    private static final String PREFS = "ai_photo_session";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_USER_ID = "userId";
    private static final String KEY_USERNAME = "username";

    private static String sToken;
    private static long sUserId;
    private static String sUsername;

    private Session() { }

    public static void init(Context ctx) {
        SharedPreferences sp = ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        sToken = sp.getString(KEY_TOKEN, null);
        sUserId = sp.getLong(KEY_USER_ID, 0);
        sUsername = sp.getString(KEY_USERNAME, null);
    }

    public static void save(Context ctx, String token, long userId, String username) {
        sToken = token;
        sUserId = userId;
        sUsername = username;
        SharedPreferences sp = ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        sp.edit()
                .putString(KEY_TOKEN, token)
                .putLong(KEY_USER_ID, userId)
                .putString(KEY_USERNAME, username)
                .apply();
    }

    public static void clear(Context ctx) {
        sToken = null;
        sUserId = 0;
        sUsername = null;
        SharedPreferences sp = ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        sp.edit().clear().apply();
    }

    public static String getToken() {
        return sToken;
    }

    public static long getUserId() {
        return sUserId;
    }

    public static String getUsername() {
        return sUsername;
    }

    public static boolean isLoggedIn() {
        return sToken != null && !sToken.isEmpty();
    }
}