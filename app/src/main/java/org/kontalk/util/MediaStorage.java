/*
 * Kontalk Android client
 * Copyright (C) 2015 Kontalk Devteam <devteam@kontalk.org>

 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.kontalk.util;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLConnection;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.support.v4.app.Fragment;
import android.util.Log;
import android.webkit.MimeTypeMap;

import org.kontalk.Kontalk;


/**
 * Media storage utilities.
 * @author Daniele Ricci
 */
public abstract class MediaStorage {
    private static final String TAG = Kontalk.TAG;

    public static final File MEDIA_ROOT = new File(Environment.getExternalStorageDirectory(), "Kontalk");

    public static final String UNKNOWN_FILENAME = "unknown_file.bin";

    private static final File DCIM_ROOT = new File(Environment
        .getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
        "Kontalk");
    private static final File PICTURES_ROOT = new File(Environment
        .getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
        "Kontalk");
    private static final File AUDIO_ROOT = new File(Environment
        .getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
        "Kontalk");

    private static final DateFormat sDateFormat =
        new SimpleDateFormat("yyyyMMdd_HHmmssSSS", Locale.US);

    private static final int THUMBNAIL_WIDTH = 256;
    private static final int THUMBNAIL_HEIGHT = 256;
    public static final String THUMBNAIL_MIME = "image/png";
    public static final String THUMBNAIL_MIME_NETWORK = "image/jpeg";
    public static final int THUMBNAIL_MIME_COMPRESSION = 60;

    public static final String COMPRESS_MIME = "image/jpeg";
    private static final String COMPRESS_FILENAME_FORMAT = "compress_%d.jpg";
    private static final int COMPRESSION_QUALITY = 85;

    public static boolean isExternalStorageAvailable() {
        return Environment.getExternalStorageState()
            .equals(Environment.MEDIA_MOUNTED);
    }

    /** Writes a media to the internal cache. */
    public static File writeInternalMedia(Context context, String filename, byte[] contents) throws IOException {
        File file = new File(context.getCacheDir(), filename);
        FileOutputStream fout = new FileOutputStream(file);
        fout.write(contents);
        fout.close();
        return file;
    }

    private static BitmapFactory.Options processOptions(BitmapFactory.Options options,
            int scaleWidth, int scaleHeight) {
        int w = options.outWidth;
        int h = options.outHeight;
        // error :(
        if (w < 0 || h < 0) return null;

        if (w > scaleWidth)
            options.inSampleSize = (w / scaleWidth);
        else if (h > scaleHeight)
            options.inSampleSize = (h / scaleHeight);

        options.inJustDecodeBounds = false;
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        return options;
    }

    /** Generates {@link BitmapFactory.Options} for the given {@link InputStream}. */
    public static BitmapFactory.Options preloadBitmap(InputStream in, int scaleWidth, int scaleHeight) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeStream(in, null, options);

        return processOptions(options, scaleWidth, scaleHeight);
    }

    /** Writes a thumbnail of a media to the internal cache. */
    public static File cacheThumbnail(Context context, Uri media, String filename, boolean forNetwork) throws IOException {
        File file = new File(context.getCacheDir(), filename);
        cacheThumbnail(context, media, file, forNetwork);
        return file;
    }

    /** Writes a thumbnail of a media to a {@link File}. */
    public static void cacheThumbnail(Context context, Uri media, File destination, boolean forNetwork) throws IOException {
        FileOutputStream fout = new FileOutputStream(destination);
        cacheThumbnail(context, media, fout, forNetwork);
        fout.close();
    }

    private static void cacheThumbnail(Context context, Uri media, FileOutputStream fout, boolean forNetwork) throws IOException {
        ContentResolver cr = context.getContentResolver();
        InputStream in = cr.openInputStream(media);
        BitmapFactory.Options options = preloadBitmap(in, THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT);
        in.close();

        // open again
        in = cr.openInputStream(media);
        Bitmap bitmap = BitmapFactory.decodeStream(in, null, options);
        in.close();

        Bitmap thumbnail = ThumbnailUtils
            .extractThumbnail(bitmap, THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT);
        bitmap.recycle();

        thumbnail = bitmapOrientation(context, media, thumbnail);

        // write down to file
        thumbnail.compress(forNetwork ? Bitmap.CompressFormat.JPEG :
            Bitmap.CompressFormat.PNG,
            forNetwork ? THUMBNAIL_MIME_COMPRESSION : 0, fout);
        thumbnail.recycle();
    }

    public static Bitmap bitmapOrientation(Context context, Uri media, Bitmap bitmap) {
        // check if we have to (and can) rotate the thumbnail
        try {
            Cursor cursor = context.getContentResolver().query(media,
                new String[] { MediaStore.Images.ImageColumns.ORIENTATION }, null, null, null);

            if (cursor != null) {
                cursor.moveToFirst();
                int orientation = cursor.getInt(0);
                cursor.close();

                if (orientation != 0) {
                    Matrix m = new Matrix();
                    m.postRotate(orientation);

                    Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), m, true);
                    // createBitmap might return the input bitmap which we don't want to recycle
                    if (rotated != bitmap)
                        bitmap.recycle();
                    bitmap = rotated;

                }
            }
        }
        catch (Exception e) {
            Log.w(TAG, "unable to check for rotation data", e);
        }

        return bitmap;
    }

    public static File writeMedia(String filename, InputStream source) throws IOException {
        MEDIA_ROOT.mkdirs();
        File f = new File(MEDIA_ROOT, filename);
        FileOutputStream fout = new FileOutputStream(f);
        byte[] buffer = new byte[1024];
        int len;
        while ((len = source.read(buffer)) != -1)
            fout.write(buffer, 0, len);
        fout.close();
        return f;
    }

    public static File writeMedia(String filename, byte[] contents) throws IOException {
        MEDIA_ROOT.mkdirs();
        File f = new File(MEDIA_ROOT, filename);
        FileOutputStream fout = new FileOutputStream(f);
        fout.write(contents);
        fout.close();
        return f;
    }

    public static long getLength(Context context, Uri media) throws IOException {
        AssetFileDescriptor stat = null;
        try {
            stat = context.getContentResolver().openAssetFileDescriptor(media, "r");
            return stat.getLength();
        }
        finally {
            try {
                stat.close();
            }
            catch (Exception e) {
                // ignored
            }
        }
    }

    /** Creates a temporary JPEG file. */
    public static File getOutgoingImageFile() throws IOException {
        return getOutgoingImageFile(new Date());
    }

    private static File getOutgoingImageFile(Date date) throws IOException {
        createMedia(DCIM_ROOT);
        String timeStamp = sDateFormat.format(date);
        File f = new File(DCIM_ROOT, "IMG_" + timeStamp + ".jpg");
        f.createNewFile();
        return f;
    }

    /** Creates a file object for an incoming image file. */
    public static File getIncomingImageFile(Date date, String extension) {
        createMedia(PICTURES_ROOT);
        String timeStamp = sDateFormat.format(date);
        return new File(PICTURES_ROOT, "IMG_" + timeStamp + "." + extension);
    }

    /** Creates a temporary 3gp file. */
    public static File getOutgoingAudioFile() throws IOException {
        return getOutgoingAudioFile(new Date());
    }

    private static File getOutgoingAudioFile(Date date) throws IOException {
        createNoMedia(AUDIO_ROOT);
        String timeStamp = sDateFormat.format(date);
        File f = new File(AUDIO_ROOT, "record_" + timeStamp + ".3gp");
        f.createNewFile();
        return f;
    }

    /** Creates a file object for an incoming audio file. */
    public static File getIncomingAudioFile(Date date, String extension) {
        createNoMedia(AUDIO_ROOT);
        String timeStamp = sDateFormat.format(date);
        return new File(AUDIO_ROOT, "audio_" + timeStamp + "." + extension);
    }

    /** Ensures that the given path exists. */
    private static boolean createMedia(File path) {
        return path.isDirectory() || path.mkdirs();
    }

    /** Ensures that the given path exists and a .nomedia file exists. */
    private static boolean createNoMedia(File path) {
        try {
            if (createMedia(path)) {
                File nomedia = new File(path, ".nomedia");
                return nomedia.isFile() || nomedia.createNewFile();
            }
            return false;
        }
        catch (Exception e) {
            return false;
        }
    }

    /** Guesses the MIME type of an {@link Uri}. */
    public static String getType(Context context, Uri uri) {
        // try Android detection
        String mime = context.getContentResolver().getType(uri);

        // the following methods actually use the same underlying implementation
        // (libcore.net.MimeUtils), but that could change in the future so no
        // hurt in trying them all just in case.
        // Lowercasing the filename seems to help in detecting the correct MIME.

        if (mime == null)
            // try WebKit detection
            mime = MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(MimeTypeMap
                    .getFileExtensionFromUrl(uri.toString()).toLowerCase());
        if (mime == null)
            // try Java detection
            mime = URLConnection.guessContentTypeFromName(uri.toString().toLowerCase());
        return mime;
    }

    public static File resizeImage(Context context, Uri uri, long msgId, int maxSize)
        throws FileNotFoundException {
        return resizeImage(context, uri, msgId, maxSize, maxSize, COMPRESSION_QUALITY);
    }

    public static File resizeImage(Context context, Uri uri, long msgId, int maxWidth, int maxHeight, int quality)
        throws FileNotFoundException {

        final int MAX_IMAGE_SIZE = 1200000; // 1.2MP

        ContentResolver cr = context.getContentResolver();

        // compute optimal image scale size
        int scale = 1;
        InputStream in = cr.openInputStream(uri);

        try {
            // decode image size
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(in, null, o);
            in.close();

            // calculate optimal image scale size
            while ((o.outWidth * o.outHeight) * (1 / Math.pow(scale, 2)) > MAX_IMAGE_SIZE)
                scale++;

            Log.d(TAG, "scale = " + scale + ", orig-width: " + o.outWidth + ", orig-height: " + o.outHeight);
        }
        catch (IOException e) {
            Log.d(TAG, "unable to calculate optimal scale size, using original image");
        }
        finally {
            try {
                in.close();
            }
            catch (Exception e) {
                // ignored
            }
        }

        // open image again for the actual scaling
        Bitmap bitmap = null;

        try {
            in = cr.openInputStream(uri);
            BitmapFactory.Options o = new BitmapFactory.Options();

            if (scale > 1) {
                o.inSampleSize = scale - 1;
            }

            bitmap = BitmapFactory.decodeStream(in, null, o);
        }
        finally {
            try {
                in.close();
            }
            catch (Exception e) {
                // ignored
            }
        }

        if (bitmap == null) {
            return null;
        }
        float photoW = bitmap.getWidth();
        float photoH = bitmap.getHeight();
        if (photoW == 0 || photoH == 0) {
            return null;
        }
        float scaleFactor = Math.max(photoW / maxWidth, photoH / maxHeight);
        int w = (int) (photoW / scaleFactor);
        int h = (int) (photoH / scaleFactor);
        if (h == 0 || w == 0) {
            return null;
        }

        Bitmap scaledBitmap;
        try {
            scaledBitmap = Bitmap.createScaledBitmap(bitmap, w, h, true);
        }
        finally {
            bitmap.recycle();
        }

        // check for rotation data
        scaledBitmap = bitmapOrientation(context, uri, scaledBitmap);

        String filename = String.format(COMPRESS_FILENAME_FORMAT, msgId);
        final File compressedFile = new File(context.getCacheDir(), filename);

        FileOutputStream stream = null;

        try {
            stream = new FileOutputStream(compressedFile);
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream);

            return compressedFile;
        }
        finally {
            try {
                stream.close();
            }
            catch (Exception e) {
                // ignored
            }
        }
    }

    /**
     * Returns true if the running platform is using SAF, therefore we'll need
     * to persist permissions when asking for media files.
     */
    public static boolean isStorageAccessFrameworkAvailable() {
        return android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT;
    }

    @TargetApi(Build.VERSION_CODES.KITKAT)
    public static void requestPersistablePermissions(Context context, Uri uri) {
        context.getContentResolver().takePersistableUriPermission(uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }

    public static void scanFile(Context context, File file, String mime) {
        MediaScannerConnection.scanFile(context.getApplicationContext(),
            new String[] { file.getPath() },
            new String[] { mime }, null);
    }

    @TargetApi(Build.VERSION_CODES.KITKAT)
    public static void createFile(Fragment fragment, String mimeType, String fileName, int requestCode) {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);

        // Filter to only show results that can be "opened", such as
        // a file (as opposed to a list of contacts or timezones).
        intent.addCategory(Intent.CATEGORY_OPENABLE);

        // Create a file with the requested MIME type.
        intent.setType(mimeType);
        intent.putExtra(Intent.EXTRA_TITLE, fileName);
        fragment.startActivityForResult(intent, requestCode);
    }


    @TargetApi(Build.VERSION_CODES.KITKAT)
    public static void openFile(Activity activity, String mimeType, String fileName, int requestCode) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);

        // Filter to only show results that can be "opened", such as
        // a file (as opposed to a list of contacts or timezones).
        intent.addCategory(Intent.CATEGORY_OPENABLE);

        // Create a file with the requested MIME type.
        intent.setType(mimeType);
        intent.putExtra(Intent.EXTRA_TITLE, fileName);
        activity.startActivityForResult(intent, requestCode);
    }

}
