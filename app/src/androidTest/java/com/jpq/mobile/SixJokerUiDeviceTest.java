package com.jpq.mobile;
import android.content.Context;
import android.graphics.*;
import android.view.View;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.json.*;
import static org.junit.Assert.*;

/** Local rendering only: no socket, no collector, no game input. */
public class SixJokerUiDeviceTest {
 @Test public void settingsAndJokerGridRender()throws Exception{render("six_jokers");}
 @Test public void twoJokerGridRender()throws Exception{render("two_jokers");}
 private void render(String mode)throws Exception{
  Context ctx=InstrumentationRegistry.getInstrumentation().getTargetContext();
  JSONObject p=new JSONObject().put("remaining_count",13).put("mode",mode);
  JSONArray cards=new JSONArray().put(new JSONObject().put("suit","j").put("rank","big").put("count",mode.equals("two_jokers")?1:3)).put(new JSONObject().put("suit","j").put("rank","small").put("count",mode.equals("two_jokers")?1:3));
  for(int i=0;i<(mode.equals("two_jokers")?11:7);i++)cards.put(new JSONObject().put("suit","h").put("rank",HandSnapshots.RANKS.get(i)).put("count",1));
  HandReply reply=RoundSession.serverResult(p.put("cards",cards),1);
  InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
   HandOverlay overlay=new HandOverlay(ctx,new HandOverlay.Actions(){public void toggle(){}public void settings(){}public void help(){}public void exit(){}});
   try{
    overlay.update(true,reply,"本地显示测试",true);
    java.lang.reflect.Field f=HandOverlay.class.getDeclaredField("grid");f.setAccessible(true);View grid=(View)f.get(overlay);
    assertEquals(View.VISIBLE,grid.getVisibility());save(ctx,grid,mode+"-grid.png",560,270);
    IdentitySettingsView settings=new IdentitySettingsView(ctx,()->{},()->{});
    for(int i=0;i<settings.getChildCount();i++)if(settings.getChildAt(i) instanceof android.widget.Button){
      android.widget.Button b=(android.widget.Button)settings.getChildAt(i);
      for(int n=0;n<3&&!b.getText().toString().contains(mode.equals("two_jokers")?"2王":"6王");n++)b.performClick();
      assertTrue(b.getText().toString().contains(mode.equals("two_jokers")?"2王":"6王"));
    }
    save(ctx,settings,mode+"-settings.png",600,600);
   }catch(Exception e){throw new RuntimeException(e);}finally{overlay.close();}
  });
 }
 private void save(Context ctx,View view,String name,int w,int h)throws Exception{
  view.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.AT_MOST));
  if(view.getMeasuredHeight()==0)view.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));
  view.layout(0,0,w,view.getMeasuredHeight());Bitmap b=Bitmap.createBitmap(w,view.getHeight(),Bitmap.Config.ARGB_8888);Canvas c=new Canvas(b);c.drawColor(0xFF18354D);view.draw(c);
  try(java.io.FileOutputStream out=ctx.openFileOutput(name,0)){b.compress(Bitmap.CompressFormat.PNG,100,out);}b.recycle();
 }
}
