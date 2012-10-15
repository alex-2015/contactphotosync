package com.oxplot.contactphotosync;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

public class CropView extends View {

  private static final int PADDING = 20;
  private static final int HANDLE_SIZE = 20;
  private static final int ACTIVE_TOUCH_RADIUS = 40;
  private static final int MIN_SCREEN_DIM = 50;
  private static final int CORNER_TL = 0;
  private static final int CORNER_TR = 1;
  private static final int CORNER_BL = 2;
  private static final int CORNER_BR = 3;
  private static final int CORNER_MID = 5;

  private Bitmap thumbnail;
  private float cropWRatio;
  private float cropHRatio;
  private int orgWidth;
  private int orgHeight;
  private RectF bound;
  private RectF transBound;
  private RectF transFullBound;
  private float[] tmpPoint;
  private Matrix thumbMatrix;
  private Matrix orgMatrix;
  private Matrix orgMatrixInverse;
  private Paint thumbPaint;
  private Paint handlePaint;
  private Paint framePaint;
  private Paint shadePaint;
  private Path shadePath;
  private Path shadePathIn;
  private boolean dragging;
  private int draggedCorner;
  private PointF dragOffset;
  private Path handlePath;
  private float minCropW;
  private float minCropH;
  private boolean enforceRatio;

  public CropView(Context context, AttributeSet attrs) {
    super(context, attrs);
    TypedArray a = context.getTheme().obtainStyledAttributes(attrs,
        R.styleable.CropView, 0, 0);
    try {
      // mShowText = a.getBoolean(R.styleable.PieChart_showText, false);
    } finally {
      a.recycle();
    }
    setBackgroundColor(Color.BLACK);
    orgMatrixInverse = new Matrix();
    orgMatrix = new Matrix();
    thumbMatrix = new Matrix();
    thumbPaint = new Paint();
    thumbPaint.setFilterBitmap(true);
    bound = new RectF();
    transBound = new RectF();
    tmpPoint = new float[2];
    transFullBound = new RectF();
    handlePaint = new Paint();
    handlePaint.setAntiAlias(true);
    handlePaint.setColor(Color.rgb(0, 204, 255));
    handlePaint.setStyle(Style.FILL);
    framePaint = new Paint();
    framePaint.setColor(Color.rgb(0, 204, 255));
    framePaint.setStyle(Style.STROKE);
    framePaint.setStrokeWidth(2);
    framePaint.setPathEffect(new DashPathEffect(new float[] { 5, 5 }, 0));
    shadePaint = new Paint();
    shadePaint.setColor(Color.argb(140, 0, 0, 0));
    shadePaint.setStyle(Style.FILL);
    shadePath = new Path();
    shadePathIn = new Path();
    dragging = false;
    dragOffset = new PointF();
    handlePath = new Path();
    handlePath.moveTo(-HANDLE_SIZE, 0);
    handlePath.lineTo(0, HANDLE_SIZE);
    handlePath.lineTo(HANDLE_SIZE, 0);
    handlePath.lineTo(0, -HANDLE_SIZE);
    handlePath.lineTo(-HANDLE_SIZE, 0);
  }

  public boolean isEnforceRatio() {
    return enforceRatio;
  }

  public void setEnforceRatio(boolean enforceRatio) {
    this.enforceRatio = enforceRatio;
    invalidateDims(getWidth(), getHeight());
  }

  public float getCropWRatio() {
    return cropWRatio;
  }

  public void setCropWRatio(float cropWRatio) {
    this.cropWRatio = cropWRatio;
  }

  public float getCropHRatio() {
    return cropHRatio;
  }

  public void setCropHRatio(float cropHRatio) {
    this.cropHRatio = cropHRatio;
  }

  private double getDist(double x1, double y1, double x2, double y2) {
    return Math.sqrt((x1 - x2) * (x1 - x2) + (y1 - y2) * (y1 - y2));
  }

  private void fixBound(int corner) {

    if (bound.left < 0) {
      if (corner == CORNER_MID)
        bound.right -= bound.left;
      bound.left = 0;
    }
    if (bound.top < 0) {
      if (corner == CORNER_MID)
        bound.bottom -= bound.top;
      bound.top = 0;
    }
    if (bound.right > orgWidth) {
      if (corner == CORNER_MID)
        bound.left += orgWidth - bound.right;
      bound.right = orgWidth;
    }
    if (bound.bottom > orgHeight) {
      if (corner == CORNER_MID)
        bound.top += orgHeight - bound.bottom;
      bound.bottom = orgHeight;
    }

    if (corner != CORNER_MID) {
      float fw = bound.right - bound.left;
      float fh = bound.bottom - bound.top;
      fw = fw < minCropW ? minCropW : fw;
      fh = fh < minCropH ? minCropH : fh;

      if ((fw * cropHRatio) / cropWRatio <= fh)
        fh = (fw * cropHRatio) / cropWRatio;
      else
        fw = (fh * cropWRatio) / cropHRatio;

      if (corner == CORNER_BR || corner == CORNER_TR)
        bound.right = bound.left + fw;
      else if (corner == CORNER_BL || corner == CORNER_TL)
        bound.left = bound.right - fw;

      if (corner == CORNER_TL || corner == CORNER_TR)
        bound.top = bound.bottom - fh;
      else if (corner == CORNER_BL || corner == CORNER_BR)
        bound.bottom = bound.top + fh;
    }

    transBound.left = bound.left;
    transBound.right = bound.right;
    transBound.top = bound.top;
    transBound.bottom = bound.bottom;
    orgMatrix.mapRect(transBound);

    shadePathIn.reset();
    shadePathIn.addRect(transBound, Path.Direction.CW);
    shadePath.reset();
    shadePath.addRect(transFullBound, Path.Direction.CW);
    shadePath.addPath(shadePathIn);
    shadePath.setFillType(Path.FillType.EVEN_ODD);

  }

  public void setCropBound(RectF bound) {
    this.bound.left = bound.left;
    this.bound.right = bound.right;
    this.bound.bottom = bound.bottom;
    this.bound.top = bound.top;
    fixBound(CORNER_BR);
    postInvalidate();
  }

  public RectF getCropBound() {
    return new RectF(bound);
  }

  public void setThumbnail(Bitmap thumbnail) {
    this.thumbnail = thumbnail;
    invalidateDims(getWidth(), getHeight());
  }

  public Bitmap getThumbnail() {
    return thumbnail;
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    canvas.save();

    canvas.concat(thumbMatrix);
    canvas.drawBitmap(thumbnail, 0, 0, thumbPaint);

    canvas.restore();

    canvas.drawPath(shadePath, shadePaint);
    canvas.drawRect(transBound, framePaint);

    if (!dragging) {

      canvas.save();
      canvas.translate(transBound.left, transBound.top);
      canvas.drawPath(handlePath, handlePaint);
      canvas.restore();

      canvas.save();
      canvas.translate(transBound.left, transBound.bottom);
      canvas.drawPath(handlePath, handlePaint);
      canvas.restore();

      canvas.save();
      canvas.translate(transBound.right, transBound.top);
      canvas.drawPath(handlePath, handlePaint);
      canvas.restore();

      canvas.save();
      canvas.translate(transBound.right, transBound.bottom);
      canvas.drawPath(handlePath, handlePaint);
      canvas.restore();
    }
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    float x = event.getX(), y = event.getY();
    if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
      float cx = 0, cy = 0;
      draggedCorner = -1;
      if (getDist(x, y, transBound.left, transBound.top) < ACTIVE_TOUCH_RADIUS) {
        draggedCorner = CORNER_TL;
        cx = transBound.left;
        cy = transBound.top;
      } else if (getDist(x, y, transBound.right, transBound.top) < ACTIVE_TOUCH_RADIUS) {
        draggedCorner = CORNER_TR;
        cx = transBound.right;
        cy = transBound.top;
      } else if (getDist(x, y, transBound.left, transBound.bottom) < ACTIVE_TOUCH_RADIUS) {
        draggedCorner = CORNER_BL;
        cx = transBound.left;
        cy = transBound.bottom;
      } else if (getDist(x, y, transBound.right, transBound.bottom) < ACTIVE_TOUCH_RADIUS) {
        draggedCorner = CORNER_BR;
        cx = transBound.right;
        cy = transBound.bottom;
      } else if (x >= transBound.left && x <= transBound.right
          && y >= transBound.top && y <= transBound.bottom) {
        draggedCorner = CORNER_MID;
        cx = transBound.left;
        cy = transBound.top;
      }
      if (draggedCorner != -1) {
        dragOffset.x = cx - x;
        dragOffset.y = cy - y;
        dragging = true;
        invalidate();
      }
    } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
      dragging = false;
      invalidate();
    } else if (dragging && event.getActionMasked() == MotionEvent.ACTION_MOVE) {
      tmpPoint[0] = x + dragOffset.x;
      tmpPoint[1] = y + dragOffset.y;
      orgMatrixInverse.mapPoints(tmpPoint);
      if (draggedCorner == CORNER_TL) {
        bound.left = tmpPoint[0];
        bound.top = tmpPoint[1];
      } else if (draggedCorner == CORNER_TR) {
        bound.right = tmpPoint[0];
        bound.top = tmpPoint[1];
      } else if (draggedCorner == CORNER_BL) {
        bound.left = tmpPoint[0];
        bound.bottom = tmpPoint[1];
      } else if (draggedCorner == CORNER_BR) {
        bound.right = tmpPoint[0];
        bound.bottom = tmpPoint[1];
      } else if (draggedCorner == CORNER_MID) {
        float w = bound.right - bound.left;
        float h = bound.bottom - bound.top;
        bound.left = tmpPoint[0];
        bound.top = tmpPoint[1];
        bound.right = bound.left + w;
        bound.bottom = bound.top + h;
      }
      fixBound(draggedCorner);
      invalidate();
    }
    return true;
  }

  public int getOrgWidth() {
    return orgWidth;
  }

  public void setOrgWidth(int orgWidth) {
    this.orgWidth = orgWidth;
    invalidateDims(getWidth(), getHeight());
  }

  public int getOrgHeight() {
    return orgHeight;
  }

  public void setOrgHeight(int orgHeight) {
    this.orgHeight = orgHeight;
    invalidateDims(getWidth(), getHeight());
  }

  private void invalidateDims(int w, int h) {

    if (thumbnail == null)
      return;

    int fw = w - PADDING * 2;
    int fh = h - PADDING * 2;
    float scale = 1;
    scale = (float) fw / thumbnail.getWidth();
    if (scale * thumbnail.getHeight() > fh)
      scale = (float) fh / thumbnail.getHeight();
    thumbMatrix.reset();
    thumbMatrix.preTranslate(
        (float) fw / 2 - (float) (scale * thumbnail.getWidth()) / 2 + PADDING,
        (float) fh / 2 - (float) (scale * thumbnail.getHeight()) / 2 + PADDING);
    thumbMatrix.preScale(scale, scale);

    scale = scale * thumbnail.getWidth() / orgWidth;
    orgMatrix.reset();
    orgMatrix.preTranslate((float) fw / 2 - (float) (scale * orgWidth) / 2
        + PADDING, (float) fh / 2 - (float) (scale * orgHeight) / 2 + PADDING);
    orgMatrix.preScale(scale, scale);
    orgMatrix.invert(orgMatrixInverse);

    transFullBound.left = 0;
    transFullBound.right = w;
    transFullBound.top = 0;
    transFullBound.bottom = h;

    if (enforceRatio) {
      if (cropWRatio > cropHRatio) {
        minCropH = MIN_SCREEN_DIM / scale;
        minCropW = (minCropH * cropWRatio) / cropHRatio;
      } else {
        minCropW = MIN_SCREEN_DIM / scale;
        minCropH = (minCropW * cropHRatio) / cropWRatio;
      }
    } else {
      minCropH = minCropW = MIN_SCREEN_DIM / scale;
    }

    fixBound(CORNER_BR);
  }

  @Override
  protected void onSizeChanged(int w, int h, int oldw, int oldh) {
    super.onSizeChanged(w, h, oldw, oldh);
    invalidateDims(w, h);
  }

}
