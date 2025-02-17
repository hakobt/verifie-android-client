package com.verifie.android.ui.widget;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.os.Build;
import android.util.AttributeSet;
import android.widget.LinearLayout;

import com.verifie.android.R;

public class FrameOverlay extends LinearLayout {
    private Bitmap windowFrame;
    private Context context;
    private RectF innerRectangle;
    private float coeficent = 1.54f;

    public FrameOverlay(Context context) {
        super(context);
        this.context = context;
    }

    public FrameOverlay(Context context, AttributeSet attrs) {
        super(context, attrs);
        this.context = context;
    }

    public FrameOverlay(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        this.context = context;
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public FrameOverlay(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        this.context = context;
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);

        if (windowFrame == null) {
            createWindowFrame();
        }
        canvas.drawBitmap(windowFrame, 0, 0, null);
    }

    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public boolean isClickable() {
        return false;
    }


    public void setCoeficent(float coeficent) {
        this.coeficent = coeficent;
        if (windowFrame != null) {
            createWindowFrame();
            invalidate();
        }
    }

    protected void createWindowFrame() {
        int viewHeight = getHeight();

        windowFrame = Bitmap.createBitmap(getWidth(), viewHeight, Bitmap.Config.ARGB_8888);
        Canvas osCanvas = new Canvas(windowFrame);

        RectF outerRectangle = new RectF(0, 0, getWidth(), viewHeight);

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.argb(150, 0, 0, 0));
        osCanvas.drawRect(outerRectangle, paint);

        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_OUT));

        int sizeInPixel = context.getResources().getDimensionPixelSize(R.dimen._12sdp);

        int center = viewHeight / 2;

        int left = sizeInPixel;
        int right = getWidth() - sizeInPixel;
        int width = right - left;
        int frameHeight = (int) (width / coeficent); // Passport's size (ISO/IEC 7810 ID-3) is 125mm × 88mm

        int top = center - (frameHeight / 2);
        int bottom = center + (frameHeight / 2);

        innerRectangle = new RectF(left, top, right, bottom);
        osCanvas.drawRect(innerRectangle, paint);

        paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStrokeWidth(5);
        paint.setColor(Color.WHITE);
        paint.setStyle(Paint.Style.STROKE);
        osCanvas.drawRoundRect(innerRectangle, 10, 10, paint);
    }

    public RectF getCropRecF() {
        return innerRectangle;
    }

    @Override
    public boolean isInEditMode() {
        return true;
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        windowFrame = null;
    }
}