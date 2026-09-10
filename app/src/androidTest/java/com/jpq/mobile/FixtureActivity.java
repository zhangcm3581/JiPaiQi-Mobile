package com.jpq.mobile;
import android.app.Activity;
import android.os.Bundle;
import android.graphics.*;
import android.view.*;
import android.content.Intent;
import java.io.*;
/** Stable replay surface: changing a fixture must not create a synthetic black window. */
public class FixtureActivity extends Activity {
    Bitmap image;View canvas;
    final android.content.BroadcastReceiver frames=new android.content.BroadcastReceiver(){public void onReceive(android.content.Context c,Intent i){setIntent(i);show();}};
    // The no-flags registration below is restricted to API <33, in the test APK only.
    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override public void onCreate(Bundle b){super.onCreate(b);getWindow().getDecorView().setSystemUiVisibility(5894);
        canvas=new View(this){@Override protected void onDraw(Canvas c){if(image!=null)c.drawBitmap(image,null,new Rect(0,0,getWidth(),getHeight()),new Paint(Paint.FILTER_BITMAP_FLAG));}};
        setContentView(canvas);show();
        if(android.os.Build.VERSION.SDK_INT>=33)registerReceiver(frames,new android.content.IntentFilter("com.jpq.mobile.test.FRAME"),RECEIVER_EXPORTED);
        else registerReceiver(frames,new android.content.IntentFilter("com.jpq.mobile.test.FRAME"));
    }
    @Override public void onNewIntent(Intent i){super.onNewIntent(i);setIntent(i);show();}
    private void show(){String name=getIntent().getStringExtra("fixture");if(!java.util.Set.of("overlap","settlement","two_rows","three_aces","shisanshui/predeal","shisanshui/yellow_k","shisanshui/missing_12","shisanshui/extra_14","shisanshui/arranging","shisanshui/confirm_ready","shisanshui/comparing","shisanshui/end_right","shisanshui/end_hall").contains(name)){finish();return;}
        try(InputStream in=getAssets().open(name+".png")){Bitmap next=BitmapFactory.decodeStream(in),old=image;image=next;canvas.invalidate();if(old!=null)canvas.postDelayed(old::recycle,500);}catch(Exception e){finish();}}
    @Override protected void onDestroy(){unregisterReceiver(frames);if(image!=null)image.recycle();super.onDestroy();}
}
