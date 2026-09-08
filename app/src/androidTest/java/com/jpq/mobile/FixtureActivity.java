package com.jpq.mobile;
import android.app.Activity;
import android.os.Bundle;
import android.graphics.*;
import android.view.*;
import android.content.Intent;
import java.io.*;
/** Only in the test APK. Replays user-provided fixtures through real screen capture. */
public class FixtureActivity extends Activity {
    Bitmap image;
    @Override public void onCreate(Bundle b){super.onCreate(b);getWindow().getDecorView().setSystemUiVisibility(5894);show();}
    @Override public void onNewIntent(Intent i){super.onNewIntent(i);setIntent(i);show();}
    private void show(){String name=getIntent().getStringExtra("fixture");if(!java.util.Set.of("overlap","settlement","two_rows","three_aces").contains(name)){finish();return;}try(InputStream in=getAssets().open(name+".png")){if(image!=null)image.recycle();image=BitmapFactory.decodeStream(in);}catch(Exception e){finish();return;}setContentView(new View(this){@Override protected void onDraw(Canvas c){Paint p=new Paint(Paint.FILTER_BITMAP_FLAG);c.drawBitmap(image,null,new Rect(0,0,getWidth(),getHeight()),p);}});}
    @Override protected void onDestroy(){if(image!=null)image.recycle();super.onDestroy();}
}
