package de.schildbach.wallet.ui;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.net.Uri;

import com.bumptech.glide.load.MultiTransformation;
import com.bumptech.glide.load.Transformation;
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool;
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation;
import com.bumptech.glide.load.resource.bitmap.CircleCrop;
import com.bumptech.glide.util.Util;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public class ProfilePictureTransformation extends BitmapTransformation {

    private static final Logger log = LoggerFactory.getLogger(ProfilePictureTransformation.class);

    private static final String ID = ProfilePictureTransformation.class.getCanonicalName();
    private static final byte[] ID_BYTES = ID.getBytes(StandardCharsets.UTF_8);

    private static final float TARGET_WIDTH = 300f;
    private static final float TARGET_HEIGHT = 300f;

    private final RectF zoomedRect;

    public static Transformation<Bitmap> create(String profilePicUrl) {
        Uri uri = Uri.parse(profilePicUrl);
        String zoomedRectParam = uri.getQueryParameter("dashpay-profile-pic-zoom");
        String[] zoomedRectStr;
        if (zoomedRectParam != null && (zoomedRectStr = zoomedRectParam.split(",")).length == 4) {
            RectF zoomedRect = new RectF(
                    Float.parseFloat(zoomedRectStr[0]), Float.parseFloat(zoomedRectStr[1]),
                    Float.parseFloat(zoomedRectStr[2]), Float.parseFloat(zoomedRectStr[3]));
            return create(zoomedRect);
        } else {
            return new CircleCrop();
        }
    }

    public static MultiTransformation<Bitmap> create(RectF zoomedRect) {
        return new MultiTransformation<>(new ProfilePictureTransformation(zoomedRect), new CircleCrop());
    }

    public ProfilePictureTransformation(RectF zoomedRect) {
        this.zoomedRect = zoomedRect;
    }

    @Override
    protected Bitmap transform(@NotNull BitmapPool pool, @NotNull Bitmap originalBitmap, int outWidth, int outHeight) {
        int resultWidth = Math.round(originalBitmap.getWidth() * (zoomedRect.right - zoomedRect.left));
        int resultHeight = Math.round(originalBitmap.getHeight() * (zoomedRect.bottom - zoomedRect.top));

        float zoomX = TARGET_WIDTH / resultWidth;
        float zoomY = TARGET_HEIGHT / resultHeight;
        log.info("zoomX: {}, zoomY: {}", zoomX, zoomY);
        Matrix matrix = new Matrix();
        matrix.setScale(zoomX, zoomY);

        int x = Math.round(zoomedRect.left * originalBitmap.getWidth());
        int y = Math.round(zoomedRect.top * originalBitmap.getHeight());
        Bitmap resultBitmap = Bitmap.createBitmap(originalBitmap, x, y, resultWidth, resultHeight, matrix, true);
        log.info("originalBitmap: {}x{}, resultBitmap: {}x{}, transform: [{}, {}]",
                originalBitmap.getWidth(), originalBitmap.getHeight(),
                resultBitmap.getWidth(), resultBitmap.getHeight(),
                x, y);

        return resultBitmap;
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof ProfilePictureTransformation) {
            ProfilePictureTransformation obj = (ProfilePictureTransformation) o;
            return (obj.zoomedRect.equals(this.zoomedRect));
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Util.hashCode(ID.hashCode(), zoomedRect.hashCode());
    }

    @Override
    public void updateDiskCacheKey(MessageDigest messageDigest) {
        messageDigest.update(ID_BYTES);
        messageDigest.update(ByteBuffer.allocate(4).putFloat(zoomedRect.left).array());
        messageDigest.update(ByteBuffer.allocate(4).putFloat(zoomedRect.top).array());
        messageDigest.update(ByteBuffer.allocate(4).putFloat(zoomedRect.right).array());
        messageDigest.update(ByteBuffer.allocate(4).putFloat(zoomedRect.bottom).array());
    }
}