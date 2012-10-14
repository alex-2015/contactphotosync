package com.oxplot.contactphotosync;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.View;

public class CropView extends View {

  public CropView(Context context, AttributeSet attrs) {
    super(context, attrs);
    TypedArray a = context.getTheme().obtainStyledAttributes(attrs,
        R.styleable.CropView, 0, 0);
    try {
      // mShowText = a.getBoolean(R.styleable.PieChart_showText, false);
    } finally {
      a.recycle();
    }
  }

}
