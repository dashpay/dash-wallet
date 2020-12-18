/*
 * Copyright 2020 Dash Core Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.schildbach.wallet.ui.dashpay.utils;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;

import java.math.BigInteger;

// base on:
// https://benhoyt.com/writings/duplicate-image-detection/
// https://www.hackerfactor.com/blog/index.php?/archives/529-Kind-of-Like-That.html
// https://github.com/tistaharahap/android-dhash/blob/master/src/com/bango/imagereco/Reco.java

public class DHash {

    private static final int HASH_SIZE = 8;

    public static BigInteger of(Bitmap srcBmp) {
        Bitmap resizedBmp = Bitmap.createScaledBitmap(srcBmp, HASH_SIZE + 1, HASH_SIZE + 1, false);
        if (resizedBmp != srcBmp) {
            srcBmp.recycle();
        }
        Bitmap resizedGrayscaleBmp = toGrayscale(resizedBmp);
        if (resizedGrayscaleBmp != resizedBmp) {
            resizedBmp.recycle();
        }

//        String fileName = "test1.png";
//        File file = new File(storageDir, fileName);
//        resizedGrayscaleBmp = BitmapFactory.decodeFile(file.getPath());

        String dHashH = getHorizontalDifferences(resizedGrayscaleBmp);
//        String dHashV = getVerticalDifferences(resizedGrayscaleBmp);

        return new BigInteger(dHashH, 2);
    }

    private static String getHorizontalDifferences(Bitmap bitmap) {
        StringBuilder dHashBuilder = new StringBuilder();
        for (int y = 0; y < bitmap.getHeight() - 1; y++) {
            for (int x = 0; x < bitmap.getWidth() - 1; x++) {
                int pixel = bitmap.getPixel(x, y);
                int nextPixel = bitmap.getPixel(x + 1, y);
                int difference = (pixel <= nextPixel) ? 1 : 0;
                dHashBuilder.append(difference);
            }
        }
        return dHashBuilder.toString();
    }

    private static String getVerticalDifferences(Bitmap bitmap) {
        StringBuilder dHashBuilder = new StringBuilder();
        for (int x = 0; x < bitmap.getHeight() - 1; x++) {
            for (int y = 0; y < bitmap.getWidth() - 1; y++) {
                int pixel = bitmap.getPixel(x, y);
                int nextPixel = bitmap.getPixel(x, y + 1);
                int difference = (pixel <= nextPixel) ? 1 : 0;
                dHashBuilder.append(difference);
            }
        }

        return dHashBuilder.toString();
    }

    public static Bitmap toGrayscale(Bitmap srcBmp) {
        final int height = srcBmp.getHeight();
        final int width = srcBmp.getWidth();

        final Bitmap grayscaleBmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        final Canvas c = new Canvas(grayscaleBmp);
        final Paint paint = new Paint();
        final ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(0);
        final ColorMatrixColorFilter f = new ColorMatrixColorFilter(cm);
        paint.setColorFilter(f);
        c.drawBitmap(srcBmp, 0, 0, paint);
        return grayscaleBmp;
    }

}