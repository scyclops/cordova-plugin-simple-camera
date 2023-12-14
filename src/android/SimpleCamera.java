
package com.trashnothing.cordova.camera;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.LOG;
import org.apache.cordova.PluginResult;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;


public class SimpleCamera extends CordovaPlugin {

    private static final int CAMERA_REQUEST_CODE = 1;

    private static final String PHOTO_FILE_PATH_KEY = "photoFilePath";

    private static final String LOG_TAG = "SimpleCamera";

    private static final String TIME_FORMAT = "yyyyMMdd_HHmmss";

    private String photoFilePath;
    private int quality; // [0-100]
    private int width; // pass -1 as width and/or height to not resize
    private int height;

    public CallbackContext callbackContext;

    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        this.callbackContext = callbackContext;

        if (action.equals("takePhoto")) {

            this.quality = args.getInt(0);
            this.width = args.getInt(1);
            this.height = args.getInt(2);

            this.takePhoto();

            PluginResult r = new PluginResult(PluginResult.Status.NO_RESULT);
            r.setKeepCallback(true);
            callbackContext.sendPluginResult(r);

            return true;

        } else if (action.equals("deletePhoto")) {
            String uriString = args.getString(0);

            if (uriString == null) {
                callbackContext.error("Error - empty string argument");
            } else {
                Uri uri = Uri.parse(uriString);
                if (uri == null) {
                    callbackContext.error("Error invalid uri");
                } else {
                    try {
                        (new File(uri.getPath())).delete();
                        callbackContext.success();
                    } catch (IllegalArgumentException e) {
                        // it may be that only file: Uri's will work
                        callbackContext.error("Error invalid uri: " + e.toString());
                    }
                }
            }
            return true;
        }

        return false;
    }

    private String getApplicationId() {
        return preferences.getString("applicationId", cordova.getContext().getPackageName());
    }

    private void takePhoto() {

        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);

        File photo = createPhotoFile("_photo");

        this.photoFilePath = photo.getAbsolutePath();
        
        Uri photoUri = FileProvider.getUriForFile(
            cordova.getActivity(),
            this.getApplicationId() + ".cordova.plugin.simple_camera.provider",
            photo
        );
        
        intent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri);

        if (intent.resolveActivity(this.cordova.getActivity().getPackageManager()) != null) {
            this.cordova.startActivityForResult(
                (CordovaPlugin) this,
                intent,
                CAMERA_REQUEST_CODE
            );
        } else {
            this.cameraError("Error no camera");
        }
    }

    private File createPhotoFile(String fileName) {
        File cache = cordova.getActivity().getCacheDir();
        cache.mkdirs();
        return new File(cache.getAbsolutePath(), fileName + ".jpg");
    }

    private void processPhoto() throws IOException {

        Bitmap bitmap = getResizedAndRotatedBitmap(this.photoFilePath);

        if (bitmap == null) {
            this.cameraError("Error creating bitmap");
            return;
        }

        Uri uri = Uri.fromFile(createPhotoFile(System.currentTimeMillis() + ""));

        // compress the photo
        OutputStream os = this.cordova.getActivity().getContentResolver().openOutputStream(uri);
        bitmap.compress(CompressFormat.JPEG, this.quality, os);
        os.close();

        this.callbackContext.success(uri.toString());

        bitmap.recycle();
        bitmap = null;

        (new File(this.photoFilePath)).delete();

        System.gc();
    }

    public void onActivityResult(int requestCode, int resultCode, Intent intent) {

        if (requestCode == CAMERA_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK) {
                try {
                    processPhoto();
                } catch (IOException e) {
                    e.printStackTrace();
                    cameraError("Error capturing image: " + e.getLocalizedMessage());
                }
            } else if (resultCode == Activity.RESULT_CANCELED) {
                cameraError("No Image Selected");
            } else {
                cameraError("Did not complete!");
            }
        } else {
            LOG.e(
                LOG_TAG, 
                "unhandled activity result - requestCode: " + requestCode +
                " - resultCode: " + resultCode +
                " - intent: " + intent.toString()
            );
        }
    }

    private int exifOrientationDegrees(int exifOrientation) {
        if (exifOrientation == ExifInterface.ORIENTATION_ROTATE_90) {
            return 90;
        } else if (exifOrientation == ExifInterface.ORIENTATION_ROTATE_180) {
            return 180;
        } else if (exifOrientation == ExifInterface.ORIENTATION_ROTATE_270) {
            return 270;
        } else {
            return 0;
        }
    }

    private void writeUncompressedImage(InputStream fis, Uri dest) throws FileNotFoundException,
            IOException {
        OutputStream os = null;
        try {
            os = this.cordova.getActivity().getContentResolver().openOutputStream(dest);
            byte[] buffer = new byte[4096];
            int len;
            while ((len = fis.read(buffer)) != -1) {
                os.write(buffer, 0, len);
            }
            os.flush();
        } finally {
            if (os != null) {
                try {
                    os.close();
                } catch (IOException e) {
                    LOG.d(LOG_TAG, "Exception closing output stream");
                }
            }
            if (fis != null) {
                try {
                    fis.close();
                } catch (IOException e) {
                    LOG.d(LOG_TAG, "Exception closing input stream");
                }
            }
        }
    }

    private Bitmap getResizedAndRotatedBitmap(String photoFilePath) throws IOException {
        // get exif rotation info
        int rotate = 0;
        try {
            ExifInterface exif = new ExifInterface(photoFilePath);
            rotate = exifOrientationDegrees(exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED));
        } catch (Exception e) {
            LOG.w(LOG_TAG, "Error reading exif data: " + e.toString());
        }

        // get photo width & height 
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        InputStream fileStream = null;
        try {
            fileStream = new FileInputStream(photoFilePath);
            BitmapFactory.decodeStream(fileStream, null, options);
        } finally {
            if (fileStream != null) {
                try {
                    fileStream.close();
                } catch (IOException e) {
                    LOG.d(LOG_TAG, "Exception closing file input stream 0");
                }
            }
        }
        if (options.outWidth == 0 || options.outHeight == 0) {
            return null;
        }

        // set target width & height based on orientation
        int rotatedWidth,
            rotatedHeight;

        boolean rotated = false;

        if (rotate == 90 || rotate == 270) {
            rotatedWidth = options.outHeight;
            rotatedHeight = options.outWidth;
            rotated = true;
        } else {
            rotatedWidth = options.outWidth;
            rotatedHeight = options.outHeight;
        }

        // determine the correct aspect ratio
        int[] widthHeight = calculateAspectRatio(rotatedWidth, rotatedHeight);

        // Load in the smallest bitmap possible that is closest to the size we want
        options.inJustDecodeBounds = false;
        options.inSampleSize = calculateSampleSize(rotatedWidth, rotatedHeight,  widthHeight[0], widthHeight[1]);
        Bitmap unscaledBitmap = null;
        try {
            fileStream = new FileInputStream(photoFilePath);
            unscaledBitmap = BitmapFactory.decodeStream(fileStream, null, options);
        } finally {
            if (fileStream != null) {
                try {
                    fileStream.close();
                } catch (IOException e) {
                    LOG.d(LOG_TAG, "Exception closing file input stream 1");
                }
            }
        }
        if (unscaledBitmap == null) {
            return null;
        }

        int scaledWidth = (!rotated) ? widthHeight[0] : widthHeight[1];
        int scaledHeight = (!rotated) ? widthHeight[1] : widthHeight[0];

        Bitmap scaledBitmap = Bitmap.createScaledBitmap(unscaledBitmap, scaledWidth, scaledHeight, true);
        if (scaledBitmap != unscaledBitmap) {
            unscaledBitmap.recycle();
            unscaledBitmap = null;
        }
        if (rotate != 0) {
            Matrix matrix = new Matrix();
            matrix.setRotate(rotate);
            try {
                scaledBitmap = Bitmap.createBitmap(scaledBitmap, 0, 0, scaledBitmap.getWidth(), scaledBitmap.getHeight(), matrix, true);
            } catch (OutOfMemoryError e) {
                // XXX: may return a photo that isn't rotated correctly and missing exif data
                //      but users can manually rotate it if they need to (assuming no vertical/horizontal flipping is needed)
            }
        }
        return scaledBitmap;
    }

    public int[] calculateAspectRatio(int origWidth, int origHeight) {
        int newWidth = this.width;
        int newHeight = this.height;

        // if no new width or height were specified return the original bitmap
        if (newWidth <= 0 && newHeight <= 0) {
            newWidth = origWidth;
            newHeight = origHeight;
        }
        // only the width was specified
        else if (newWidth > 0 && newHeight <= 0) {
            newHeight = (int)((double)(newWidth / (double)origWidth) * origHeight);
        }
        // only the height was specified
        else if (newWidth <= 0 && newHeight > 0) {
            newWidth = (int)((double)(newHeight / (double)origHeight) * origWidth);
        }
        // if the user specified both a positive width and height
        // (potentially different aspect ratio) then the width or height is
        // scaled so that the image fits while maintaining aspect ratio.
        // Alternatively, the specified width and height could have been
        // kept and Bitmap.SCALE_TO_FIT specified when scaling, but this
        // would result in whitespace in the new image.
        else {
            double newRatio = newWidth / (double) newHeight;
            double origRatio = origWidth / (double) origHeight;

            if (origRatio > newRatio) {
                newHeight = (newWidth * origHeight) / origWidth;
            } else if (origRatio < newRatio) {
                newWidth = (newHeight * origWidth) / origHeight;
            }
        }

        int[] retval = new int[2];
        retval[0] = newWidth;
        retval[1] = newHeight;
        return retval;
    }

    /**
     * Figure out what ratio we can load our image into memory at while still being bigger than
     * our desired width and height
     */
    public static int calculateSampleSize(int srcWidth, int srcHeight, int dstWidth, int dstHeight) {
        final float srcAspect = (float) srcWidth / (float) srcHeight;
        final float dstAspect = (float) dstWidth / (float) dstHeight;

        if (srcAspect > dstAspect) {
            return srcWidth / dstWidth;
        } else {
            return srcHeight / dstHeight;
        }
    }

    private void cameraError(String err) {
        this.callbackContext.error(err);
    }

    public Bundle onSaveInstanceState() {
        Bundle state = new Bundle();

        state.putInt("quality", this.quality);
        state.putInt("width", this.width);
        state.putInt("height", this.height);

        if (this.photoFilePath != null) {
            state.putString(PHOTO_FILE_PATH_KEY, this.photoFilePath);
        }

        return state;
    }

    public void onRestoreStateForActivityResult(Bundle state, CallbackContext callbackContext) {

        this.quality = state.getInt("quality");
        this.width = state.getInt("width");
        this.height = state.getInt("height");

        if (state.containsKey(PHOTO_FILE_PATH_KEY)) {
            this.photoFilePath = state.getString(PHOTO_FILE_PATH_KEY);
        }

        this.callbackContext = callbackContext;
    }
}
