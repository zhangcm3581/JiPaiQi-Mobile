package com.jpq.mobile;
import android.content.*;
import android.graphics.*;
import android.view.*;
import android.util.DisplayMetrics;
import java.util.*;

/** Two draggable windows: edge controls and an independently hideable server-only grid. */
public final class HandOverlay implements AutoCloseable {
    public interface Actions {void toggle();void settings();void help();void exit();}
    private final Context context;private final WindowManager wm;private final Actions actions;
    private final Controls controls;private final Grid grid;
    private final WindowManager.LayoutParams menuParams,panelParams;
    private boolean expanded=true,panelVisible=true,running;private boolean closed;
    private boolean menuDocked=true,panelDocked=true;
    private final float density;private HandReply reply;private String message="点击启动，等待新局";
    private volatile java.util.List<Rect> bounds=List.of();
    public HandOverlay(Context context,Actions actions){this.context=context;this.actions=actions;wm=(WindowManager)context.getSystemService(Context.WINDOW_SERVICE);density=context.getResources().getDisplayMetrics().density;
        controls=new Controls();grid=new Grid();menuParams=params();panelParams=params();panelParams.flags|=WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        size();wm.addView(grid,panelParams);wm.addView(controls,menuParams);drag(controls,menuParams,true);drag(grid,panelParams,false);updateBounds();
    }
    private WindowManager.LayoutParams params(){var p=new WindowManager.LayoutParams(1,1,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);p.gravity=Gravity.TOP|Gravity.LEFT;return p;}
    private int dp(float x){return Math.round(x*density);}
    private DisplayMetrics screen(){DisplayMetrics d=new DisplayMetrics();wm.getDefaultDisplay().getRealMetrics(d);return d;}
    private void clamp(WindowManager.LayoutParams p,DisplayMetrics d){p.x=Math.max(0,Math.min(p.x,d.widthPixels-p.width));p.y=Math.max(0,Math.min(p.y,d.heightPixels-p.height));}
    private void size(){DisplayMetrics d=screen();menuParams.width=expanded?Math.min(dp(360),Math.round(d.widthPixels*.30f)):Math.max(dp(48),Math.round(d.widthPixels*.045f));menuParams.height=expanded?Math.round(menuParams.width*.25f):Math.round(menuParams.width*1.2f);
        panelParams.width=Math.min(dp(400),Math.round(d.widthPixels*.27f));panelParams.height=Math.round(panelParams.width*.46f);
        if(menuDocked){menuParams.x=d.widthPixels-menuParams.width;menuParams.y=(d.heightPixels-menuParams.height)/2;}
        if(panelDocked){panelParams.x=2;panelParams.y=2;}clamp(menuParams,d);clamp(panelParams,d);
    }
    public void resize(){if(closed)return;size();wm.updateViewLayout(controls,menuParams);if(panelVisible)wm.updateViewLayout(grid,panelParams);updateBounds();}
    public void update(boolean active,HandReply data,String status){if(closed)return;running=active;reply=data;message=status;controls.invalidate();grid.invalidate();}
    public List<Rect> bounds(){return bounds;}
    private void updateBounds(){List<Rect> b=new ArrayList<>();b.add(new Rect(menuParams.x,menuParams.y,menuParams.x+menuParams.width,menuParams.y+menuParams.height));if(panelVisible)b.add(new Rect(panelParams.x,panelParams.y,panelParams.x+panelParams.width,panelParams.y+panelParams.height));bounds=List.copyOf(b);}
    private void collapse(){expanded=!expanded;menuDocked=false;resize();}
    private void panel(){panelVisible=!panelVisible;if(panelVisible)wm.addView(grid,panelParams);else wm.removeView(grid);updateBounds();}
    private void drag(View view,WindowManager.LayoutParams p,boolean menu){view.setOnTouchListener(new View.OnTouchListener(){float x,y;int px,py;boolean moved;long start;
        public boolean onTouch(View v,MotionEvent e){switch(e.getActionMasked()){
            case MotionEvent.ACTION_DOWN:x=e.getRawX();y=e.getRawY();px=p.x;py=p.y;moved=false;start=android.os.SystemClock.elapsedRealtime();return true;
            case MotionEvent.ACTION_MOVE:float dx=e.getRawX()-x,dy=e.getRawY()-y;if(Math.abs(dx)+Math.abs(dy)>dp(8))moved=true;if(moved){if(menu)menuDocked=false;else panelDocked=false;p.x=px+(int)dx;p.y=py+(int)dy;clamp(p,screen());wm.updateViewLayout(view,p);updateBounds();}return true;
            case MotionEvent.ACTION_UP:if(!moved){view.performClick();if(menu){if(!expanded){collapse();return true;}int slot=Math.min(4,(int)(e.getX()*5/view.getWidth()));switch(slot){case 0:actions.toggle();break;case 1:actions.settings();break;case 2:actions.help();break;case 3:actions.exit();break;case 4:if(android.os.SystemClock.elapsedRealtime()-start>500)panel();else collapse();break;}}else panel();}return true;
            default:return true;
        }} });}
    private final class Controls extends View {
        final Paint paint=new Paint(3);Controls(){super(context);setContentDescription("悬浮控制栏：启动、设置、说明、退出、收起；长按收起可切换结果面板");}
        @Override public boolean performClick(){super.performClick();return true;}
        @Override protected void onDraw(Canvas c){super.onDraw(c);paint.setColor(0xDB142D46);c.drawRoundRect(0,0,getWidth(),getHeight(),dp(14),dp(14),paint);paint.setTextAlign(Paint.Align.CENTER);
            if(!expanded){paint.setColor(Color.WHITE);paint.setTextSize(Math.min(dp(25),getWidth()*.9f));c.drawText("‹",getWidth()/2f,getHeight()*.55f,paint);paint.setTextSize(Math.min(dp(10),getWidth()*.42f));c.drawText("展开",getWidth()/2f,getHeight()*.82f,paint);return;}
            String[] icons={running?"Ⅱ":"▶","⚙","i","↪","›"},labels={running?"暂停":"启动","设置","说明","退出","收起"};float unit=getWidth()/5f;
            for(int i=0;i<5;i++){float cx=unit*(i+.5f),cy=getHeight()*.36f,r=Math.min(unit*.34f,getHeight()*.27f);paint.setColor(i==0&&running?0xFFB99D53:0xAA102132);c.drawCircle(cx,cy,r,paint);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1));paint.setColor(0xFF8194A7);c.drawCircle(cx,cy,r,paint);paint.setStyle(Paint.Style.FILL);paint.setColor(Color.WHITE);paint.setTextSize(r*1.25f);c.drawText(icons[i],cx,cy+r*.45f,paint);paint.setTextSize(Math.min(dp(14),unit*.25f));c.drawText(labels[i],cx,getHeight()*.87f,paint);}
            paint.setColor(running?0xFF66D5AE:0xFFABB4BF);float pulse=(android.os.SystemClock.elapsedRealtime()/500)%2==0?dp(2):dp(1);c.drawCircle(dp(7),dp(7),pulse,paint);
        }
    }
    private final class Grid extends View {
        final Paint p=new Paint(3);Grid(){super(context);setContentDescription("服务器返回的花色点数面板，点击隐藏，拖动移动");}
        @Override public boolean performClick(){super.performClick();return true;}
        @Override protected void onDraw(Canvas c){super.onDraw(c);float footer=getHeight()*.1f,header=0,row=(getHeight()-footer-header)/8f,col=getWidth()/14f;
            c.drawColor(0xFFF5F8FE);p.setTextAlign(Paint.Align.CENTER);p.setColor(0xFF647B98);p.setTextSize(Math.min(col*.68f,header*.85f));
            for(int y=0;y<8;y++){if(y%2==1){p.setColor(0xFFE3EDFA);c.drawRect(0,header+y*row,getWidth(),header+(y+1)*row,p);}p.setColor(y/2==1||y/2==3?0xFFE32636:0xFF39434B);p.setTextSize(row*.92f);c.drawText(new String[]{"♠","♥","♣","♦"}[y/2],col*.5f,header+(y+.83f)*row,p);
                for(int x=0;x<13;x++)if(reply!=null&&reply.occupied(y,x)){p.setTextSize(Math.min(row*.85f,col*.9f));c.drawText(HandSnapshots.RANKS.get(x),col*(x+1.5f),header+(y+.8f)*row,p);}}
            p.setColor(0xFF4387D9);p.setStrokeWidth(Math.max(1,dp(.6f)));for(int x=0;x<=14;x++)c.drawLine(x*col,header,x*col,getHeight()-footer,p);for(int y=0;y<=8;y++)c.drawLine(0,header+y*row,getWidth(),header+y*row,p);
            p.setColor(0xFF6D7B8D);p.setTextAlign(Paint.Align.LEFT);p.setTextSize(footer*.72f);String label=message;while(p.measureText(label)>getWidth()-4&&label.length()>1)label=label.substring(0,label.length()-1);c.drawText(label,2,getHeight()-footer*.2f,p);
        }
    }
    public void togglePanel(){panel();}
    @Override public void close(){closed=true;wm.removeView(controls);if(panelVisible)wm.removeView(grid);bounds=List.of();}
}
