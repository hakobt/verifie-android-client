package com.verifie.android.ui.widget;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.hardware.Camera.PreviewCallback;
import android.os.Build;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.widget.RelativeLayout;

import java.io.IOException;
import java.util.List;

/**
 * This class assumes the parent layout is RelativeLayout.LayoutParams.
 */
public final class CameraPreview extends SurfaceView implements SurfaceHolder.Callback {

    private static boolean DEBUGGING = true;

    private static final String LOG_TAG = "CameraPreviewSample";
    private static final String CAMERA_PARAM_ORIENTATION = "orientation";
    private static final String CAMERA_PARAM_LANDSCAPE = "landscape";
    private static final String CAMERA_PARAM_PORTRAIT = "portrait";
    protected Activity mActivity;
    private SurfaceHolder mHolder;
    protected Camera mCamera;
    protected List<Camera.Size> mPreviewSizeList;
    protected List<Camera.Size> mPictureSizeList;
    protected Camera.Size mPreviewSize;
    protected Camera.Size mPictureSize;
    private int mSurfaceChangedCallDepth = 0;
    private int mCameraId;
    private LayoutMode mLayoutMode;
    private int mCenterPosX = -1;
    private int mCenterPosY;

    PreviewReadyCallback mPreviewReadyCallback = null;

    public static enum LayoutMode {
        FitToParent, // Scale to the size that no side is larger than the parent
        NoBlank // Scale to the size that no side is smaller than the parent
    }

    public interface PreviewReadyCallback {
        void onPreviewReady();
    }

    /**
     * State flag: true when surface's layout size is set and surfaceChanged()
     * process has not been completed.
     */
    protected boolean mSurfaceConfiguring = false;

    public CameraPreview(Activity activity, int cameraId, LayoutMode mode) {
        super(activity); // Always necessary
        mActivity = activity;
        mLayoutMode = mode;
        mHolder = getHolder();
        mHolder.addCallback(this);
        mHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);

        mCameraId = 0;

        Camera.CameraInfo cameraInfo = new Camera.CameraInfo();
        for (int camIdx = 0; camIdx < Camera.getNumberOfCameras(); ++camIdx) {
            Camera.getCameraInfo(camIdx, cameraInfo);
            if (cameraId == 1) {
                if (cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                    mCameraId = camIdx;
                    break;
                }
            } else {
                if (cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                    mCameraId = camIdx;
                    break;
                }
            }
        }

        mCamera = Camera.open(mCameraId);

        Camera.Parameters cameraParams = mCamera.getParameters();
        try {
            cameraParams.set("mode", "m");
            cameraParams.set("aperture", "28"); //can be 28 32 35 40 45 50 56 63 71 80 on default zoom
            cameraParams.set("shutter-speed", 1 / 500); // depends on camera, eg. 1 means longest
            cameraParams.set("iso", 200);
        } catch (Throwable throwable) {
            throwable.printStackTrace();
        }
        mPreviewSizeList = cameraParams.getSupportedPreviewSizes();
        mPictureSizeList = cameraParams.getSupportedPictureSizes();
        cameraParams.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
    }

    public boolean safeCameraOpen(int id) {
        boolean qOpened = false;

        try {
            releaseCameraAndPreview();
            mCamera = Camera.open(id);
            qOpened = (mCamera != null);
        } catch (Exception e) {
            Log.e("camera failed", "failed to open Camera");
            e.printStackTrace();
        }

        return qOpened;
    }

    private void releaseCameraAndPreview() {
        if (mCamera != null) {
            mCamera.release();
            mCamera = null;
        }
    }


    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        try {
            mCamera.setPreviewDisplay(mHolder);
        } catch (IOException e) {
            mCamera.release();
            mCamera = null;
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        mSurfaceChangedCallDepth++;
        doSurfaceChanged(width, height);
        mSurfaceChangedCallDepth--;
    }

    private void doSurfaceChanged(int width, int height) {
        mCamera.stopPreview();

        Camera.Parameters cameraParams = mCamera.getParameters();
        cameraParams.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
        boolean portrait = isPortrait();

        // The code in this if-statement is prevented from executed again when surfaceChanged is
        // called again due to the change of the layout size in this if-statement.
        if (!mSurfaceConfiguring) {
            Camera.Size previewSize = determinePreviewSize(portrait, width, height);
            Camera.Size pictureSize = determinePictureSize(previewSize);
            if (DEBUGGING) {
                Log.v(LOG_TAG, "Desired Preview Size - w: " + width + ", h: " + height);
            }
            mPreviewSize = previewSize;
            mPictureSize = pictureSize;
            mSurfaceConfiguring = adjustSurfaceLayoutSize(previewSize, portrait, width, height);
            // Continue executing this method if this method is called recursively.
            // Recursive call of surfaceChanged is very special case, which is a path from
            // the catch clause at the end of this method.
            // The later part of this method should be executed as well in the recursive
            // invocation of this method, because the layout change made in this recursive
            // call will not trigger another invocation of this method.
            if (mSurfaceConfiguring && (mSurfaceChangedCallDepth <= 1)) {
                return;
            }
        }

        configureCameraParameters(cameraParams, portrait);
        mSurfaceConfiguring = false;

        try {
            mCamera.startPreview();
        } catch (Exception e) {
            Log.w(LOG_TAG, "Failed to start preview: " + e.getMessage());

            // Remove failed size
            mPreviewSizeList.remove(mPreviewSize);
            mPreviewSize = null;

            // Reconfigure
            if (mPreviewSizeList.size() > 0) { // prevent infinite loop
                surfaceChanged(null, 0, width, height);
            } else {
                Log.w(LOG_TAG, "Gave up starting preview");
            }
        }

        if (null != mPreviewReadyCallback) {
            mPreviewReadyCallback.onPreviewReady();
        }
    }

    /**
     * @param portrait
     * @param reqWidth  must be the value of the parameter passed in surfaceChanged
     * @param reqHeight must be the value of the parameter passed in surfaceChanged
     * @return Camera.Size object that is an element of the list returned from Camera.Parameters.getSupportedPreviewSizes.
     */
    protected Camera.Size determinePreviewSize(boolean portrait, int reqWidth, int reqHeight) {
        // Meaning of width and height is switched for preview when portrait,
        // while it is the same as user's view for surface and metrics.
        // That is, width must always be larger than height for setPreviewSize.
        int reqPreviewWidth; // requested width in terms of camera hardware
        int reqPreviewHeight; // requested height in terms of camera hardware
        if (portrait) {
            reqPreviewWidth = reqHeight;
            reqPreviewHeight = reqWidth;
        } else {
            reqPreviewWidth = reqWidth;
            reqPreviewHeight = reqHeight;
        }

        if (DEBUGGING) {
            Log.v(LOG_TAG, "Listing all supported preview sizes");
            for (Camera.Size size : mPreviewSizeList) {
                Log.v(LOG_TAG, "  w: " + size.width + ", h: " + size.height);
            }
            Log.v(LOG_TAG, "Listing all supported picture sizes");
            for (Camera.Size size : mPictureSizeList) {
                Log.v(LOG_TAG, "  w: " + size.width + ", h: " + size.height);
            }
        }

        // Adjust surface size with the closest aspect-ratio
        float reqRatio = ((float) reqPreviewWidth) / reqPreviewHeight;
        float curRatio, deltaRatio;
        float deltaRatioMin = Float.MAX_VALUE;
        Camera.Size retSize = null;
        for (Camera.Size size : mPreviewSizeList) {
            curRatio = ((float) size.width) / size.height;
            deltaRatio = Math.abs(reqRatio - curRatio);
            if (deltaRatio < deltaRatioMin) {
                deltaRatioMin = deltaRatio;
                retSize = size;
            }
        }

        return retSize;
    }

    protected Camera.Size determinePictureSize(Camera.Size previewSize) {
        Camera.Size retSize = null;
        for (Camera.Size size : mPictureSizeList) {
            if (size.equals(previewSize)) {
                return size;
            }
        }

        if (DEBUGGING) {
            Log.v(LOG_TAG, "Same picture size not found.");
        }

        // if the preview size is not supported as a picture size
        float reqRatio = ((float) previewSize.width) / previewSize.height;
        float curRatio, deltaRatio;
        float deltaRatioMin = Float.MAX_VALUE;
        for (Camera.Size size : mPictureSizeList) {
            curRatio = ((float) size.width) / size.height;
            deltaRatio = Math.abs(reqRatio - curRatio);
            if (deltaRatio < deltaRatioMin) {
                deltaRatioMin = deltaRatio;
                retSize = size;
            }
        }

        return retSize;
    }

    protected boolean adjustSurfaceLayoutSize(Camera.Size previewSize, boolean portrait, int availableWidth, int availableHeight) {
        float tmpLayoutHeight, tmpLayoutWidth;

        if (portrait) {
            tmpLayoutHeight = previewSize.width;
            tmpLayoutWidth = previewSize.height;
        } else {
            tmpLayoutHeight = previewSize.height;
            tmpLayoutWidth = previewSize.width;
        }

        float factH, factW, fact;
        factH = availableHeight / tmpLayoutHeight;
        factW = availableWidth / tmpLayoutWidth;
        if (mLayoutMode == LayoutMode.FitToParent) {
            // Select smaller factor, because the surface cannot be set to the size larger than display metrics.
            if (factH < factW) {
                fact = factH;
            } else {
                fact = factW;
            }
        } else {
            if (factH < factW) {
                fact = factW;
            } else {
                fact = factH;
            }
        }

        final int layoutHeight = (int) (tmpLayoutHeight * fact);
        final int layoutWidth = (int) (tmpLayoutWidth * fact);

        if (DEBUGGING) {
            Log.v(LOG_TAG, "Preview Layout Size - w: " + layoutWidth + ", h: " + layoutHeight);
            Log.v(LOG_TAG, "Scale factor: " + fact);
        }

        boolean layoutChanged;

        if ((layoutWidth != this.getWidth()) || (layoutHeight != this.getHeight())) {
            ViewGroup holderParent = ((ViewGroup) getParent().getParent());

            int holderParentWidth = holderParent.getWidth();
            int holderParentHeight = holderParent.getHeight();

            ViewGroup.MarginLayoutParams parentParams = ((ViewGroup.MarginLayoutParams) ((ViewGroup) getParent()).getLayoutParams());

            int marginLeft = Math.abs((holderParentWidth - layoutWidth) / 2);
            int marginTop = Math.abs((holderParentHeight - layoutHeight) / 2);

            parentParams.width = layoutWidth;
            parentParams.height = layoutHeight;
            parentParams.topMargin = -marginTop;
            parentParams.leftMargin = -marginLeft;

            ((ViewGroup) getParent()).setLayoutParams(parentParams);
            ((ViewGroup) getParent()).post(new Runnable() {
                @Override
                public void run() {
                    RelativeLayout.LayoutParams layoutParams = new RelativeLayout.LayoutParams(layoutWidth, layoutHeight);

                    layoutParams.height = layoutHeight;
                    layoutParams.width = layoutWidth;

                    if (mCenterPosX >= 0) {
                        layoutParams.topMargin = mCenterPosY - (layoutHeight / 2);
                        layoutParams.leftMargin = mCenterPosX - (layoutWidth / 2);
                    }
                    setLayoutParams(layoutParams); // this will trigger another surfaceChanged invocation.


                }
            });
            layoutChanged = true;
        } else {
            layoutChanged = false;
        }

        return layoutChanged;
    }

    /**
     * @param x X coordinate of center position on the screen. Set to negative value to unset.
     * @param y Y coordinate of center position on the screen.
     */
    public void setCenterPosition(int x, int y) {
        mCenterPosX = x;
        mCenterPosY = y;
    }

    protected void configureCameraParameters(Camera.Parameters cameraParams, boolean portrait) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.FROYO) { // for 2.1 and before
            if (portrait) {
                cameraParams.set(CAMERA_PARAM_ORIENTATION, CAMERA_PARAM_PORTRAIT);
            } else {
                cameraParams.set(CAMERA_PARAM_ORIENTATION, CAMERA_PARAM_LANDSCAPE);
            }
        } else { // for 2.2 and later
            int angle;
            Display display = mActivity.getWindowManager().getDefaultDisplay();
            switch (display.getRotation()) {
                case Surface.ROTATION_0: // This is display orientation
                    angle = 90; // This is camera orientation
                    break;
                case Surface.ROTATION_90:
                    angle = 0;
                    break;
                case Surface.ROTATION_180:
                    angle = 270;
                    break;
                case Surface.ROTATION_270:
                    angle = 180;
                    break;
                default:
                    angle = 90;
                    break;
            }
            Log.v(LOG_TAG, "angle: " + angle);
            mCamera.setDisplayOrientation(angle);
        }

        cameraParams.setPreviewSize(mPreviewSize.width, mPreviewSize.height);
        cameraParams.setPictureSize(mPictureSize.width, mPictureSize.height);
        cameraParams.setPreviewFormat(getPreviewFormat());

        List<String> focusModes = cameraParams.getSupportedFocusModes();
        if (focusModes != null && focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
            cameraParams.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
        }

        if (DEBUGGING) {
            Log.v(LOG_TAG, "Preview Actual Size - w: " + mPreviewSize.width + ", h: " + mPreviewSize.height);
            Log.v(LOG_TAG, "Picture Actual Size - w: " + mPictureSize.width + ", h: " + mPictureSize.height);
        }

        mCamera.setParameters(cameraParams);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        stop();
    }

    public void stop() {
        if (null == mCamera) {
            return;
        }

        mCamera.setPreviewCallback(null);
        mCamera.stopPreview();
        mCamera.release();
        mCamera = null;
    }

    public boolean isPortrait() {
        return (mActivity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT);
    }

    public void setOneShotPreviewCallback(PreviewCallback callback) {
        if (null == mCamera) {
            return;
        }
        mCamera.setOneShotPreviewCallback(callback);
    }

    public void setPreviewCallback(PreviewCallback callback) {
        if (null == mCamera) {
            return;
        }
        mCamera.setPreviewCallback(callback);
    }

    public Camera.Size getPreviewSize() {
        return mPreviewSize;
    }

    public void setOnPreviewReady(PreviewReadyCallback cb) {
        mPreviewReadyCallback = cb;
    }

    private int getPreviewFormat() {
        if (Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.startsWith("unknown")
                || Build.MODEL.contains("google_sdk")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for x86")
                || Build.MANUFACTURER.contains("Genymotion")
                || (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"))
                || "google_sdk".equals(Build.PRODUCT)) {
            return ImageFormat.YV12;
        } else {
            return ImageFormat.NV21;
        }
    }
}