/* The MIT License (MIT)
 *
 *       Copyright (c) 2016 James K. Pringle
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.odk.collect.android.utilities;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Defines functions for rescaling an image
 *
 * Creator: James K. Pringle
 * E-mail: jpringle@jhu.edu
 * Created: 25 May 2016
 * Last modified: 25 May 2016
 */
public class ImageScaler {
    private static final int IMAGE_COMPRESSION = 60;

    public static void resize(String in, String out, int scaledWidth) {
        BitmapFactory.Options opts = getDimen(in);
        final int width = opts.outWidth;
        final int height = opts.outHeight;
        int inSampleSize = 1;

        if (width > scaledWidth || height > scaledWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;

            // Calculate the largest inSampleSize value that is a power of 2 and keeps
            // width larger than the requested height and width.
            while ( (halfWidth/inSampleSize) > scaledWidth &&
                    (halfHeight/inSampleSize) > scaledWidth ) {
                inSampleSize *= 2;
            }
        }
        opts.inSampleSize = inSampleSize;
        opts.inJustDecodeBounds = false;
        Bitmap bm = BitmapFactory.decodeFile(in, opts);
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(out);
            bm.compress(Bitmap.CompressFormat.JPEG, IMAGE_COMPRESSION, fos);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                if (out != null) {
                    fos.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

    }

    public static BitmapFactory.Options getDimen(String in) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(in, opts);
        return opts;
    }
}
