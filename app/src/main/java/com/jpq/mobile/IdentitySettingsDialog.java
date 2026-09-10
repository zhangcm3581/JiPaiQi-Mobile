package com.jpq.mobile;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.util.DisplayMetrics;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;

/** Fallback for an identity-settings intent before capture has started. */
final class IdentitySettingsDialog {
    private final Context context;
    private final Dialog dialog;
    private final LinearLayout content;
    private boolean dismissing;

    IdentitySettingsDialog(Context context, Rect anchor, Runnable saved, Runnable closed) {
        this.context=context;
        dialog=new Dialog(context,R.style.FloatingSettingsTheme);
        content=new IdentitySettingsView(dialog.getContext(),saved,this::dismiss);
        content.setFocusableInTouchMode(true);
        GradientDrawable background=new GradientDrawable();
        background.setColor(HandOverlay.BACKGROUND);background.setCornerRadius(dp(14));
        content.setBackground(background);
        dialog.setContentView(content);
        dialog.setCanceledOnTouchOutside(true);
        dialog.setOnDismissListener(ignored->{content.animate().cancel();closed.run();});
        Window window=dialog.getWindow();
        window.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        // Match the control bar's physical screen coordinates, including the status-bar area.
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                |WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        window.setWindowAnimations(0);
        window.getDecorView().setPadding(0,0,0,0);
        reposition(anchor);
        content.requestFocus();content.setAlpha(0);
        dialog.show();
        content.animate().alpha(1).setDuration(120).start();
    }

    private int dp(int n){return Math.round(n*context.getResources().getDisplayMetrics().density);}
    boolean isShowing(){return dialog.isShowing();}
    void reposition(Rect anchor){
        WindowManager wm=(WindowManager)context.getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics screen=new DisplayMetrics();wm.getDefaultDisplay().getRealMetrics(screen);
        int width=Math.min(dp(240),screen.widthPixels-dp(24));
        content.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(screen.heightPixels,View.MeasureSpec.AT_MOST));
        int height=content.getMeasuredHeight(),gap=dp(8);
        int x=anchor==null?(screen.widthPixels-width)/2:anchor.right-width;
        int y=anchor==null?(screen.heightPixels-height)/2:anchor.top-height-gap;
        if(anchor!=null&&y<dp(28))y=anchor.bottom+gap;
        WindowManager.LayoutParams p=dialog.getWindow().getAttributes();
        p.gravity=Gravity.TOP|Gravity.LEFT;p.width=width;p.height=height;
        p.x=Math.max(dp(12),Math.min(x,screen.widthPixels-width-dp(12)));
        p.y=Math.max(dp(28),Math.min(y,screen.heightPixels-height-dp(24)));
        dialog.getWindow().setAttributes(p);
    }
    void dismiss(){
        if(dismissing||!dialog.isShowing())return;dismissing=true;
        ((InputMethodManager)context.getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(content.getWindowToken(),0);
        content.animate().alpha(0).setDuration(100).withEndAction(dialog::dismiss).start();
    }
    void close(){content.animate().cancel();dialog.dismiss();}
}
