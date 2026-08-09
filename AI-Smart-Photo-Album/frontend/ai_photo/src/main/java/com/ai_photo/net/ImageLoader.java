package com.ai_photo.net;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 图片加载（项目唯一入口）：
 *
 *   Adapter → ImageLoader.load(iv, url)
 *              ├─ ApiClient.getBytes(url)   ← 网络层（项目唯一的 HTTP 入口）
 *              ├─ decodeBytes(bytes)       ← 格式解码（私有，仅本类用）
 *              ├─ LruCache 命中 / 写入
 *              └─ main.post → ImageView.setImageBitmap
 *
 * 设计取舍：本类是图片加载唯一对外 API；解码是私有步骤，不外泄成独立类。
 * 未来加视频/GIF/PDF 时，新增各自 Loader（VideoLoader.loadSurfaceView...），互不耦合。
 */
public final class ImageLoader {

    private static final int MAX_CACHE_SIZE  = 24;
    private static final int DECODE_MAX_EDGE = 512;

    private static final ExecutorService IO = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static final LruCache<String, Bitmap> CACHE = new LruCache<>(MAX_CACHE_SIZE);

    private ImageLoader() { }

    /**
     * 异步加载 url 指向的图片并显示在 view 上。
     * - url 空 → 清空 view
     * - url 命中缓存 → 立即 set
     * - 否则：ApiClient.getBytes → decodeBytes → 缓存 → main.post → set（带 tag 校验防错位）
     */
    public static void load(final ImageView view, final String url) {
        if (view == null) return;
        view.setTag(url == null ? "" : url);
        if (url == null || url.isEmpty()) {
            view.setImageDrawable(null);
            return;
        }

        Bitmap cached = CACHE.get(url);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }

        final String absolute = ApiClient.absoluteUrl(url);
        IO.execute(() -> {
            byte[] bytes = ApiClient.getBytes(absolute);
            Bitmap bmp   = decodeBytes(bytes, DECODE_MAX_EDGE);
            if (bmp != null) CACHE.put(url, bmp);

            final Bitmap finalBmp = bmp;
            MAIN.post(() -> {
                if (url.equals(view.getTag()) && finalBmp != null) {
                    view.setImageBitmap(finalBmp);
                }
            });
        });
    }

    /**
     * 私有：JPEG/PNG/WebP 字节 → Bitmap，自动按 maxEdge 采样避免 OOM。
     * 失败返回 null。
     */
    @Nullable
    private static Bitmap decodeBytes(@Nullable byte[] data, int maxEdge) {
        if (data == null || data.length == 0) return null;
        if (maxEdge <= 0) maxEdge = 1024;

        // 1) 第一次 decode：只读 bounds
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

        int sample = 1;
        int longer = Math.max(bounds.outWidth, bounds.outHeight);
        while (longer / sample > maxEdge) sample *= 2;

        // 2) 第二次 decode：真正解码
        BitmapFactory.Options real = new BitmapFactory.Options();
        real.inSampleSize = Math.max(1, sample);
        real.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeByteArray(data, 0, data.length, real);
    }

    /** 清空 LRU 缓存（调试/登出时用） */
    public static void clearCache() {
        CACHE.evictAll();
    }
}