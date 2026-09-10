package com.jpq.mobile;
import android.animation.*;
import android.content.*;
import android.graphics.*;
import android.view.*;
import android.util.DisplayMetrics;
import android.widget.FrameLayout;
import android.view.animation.PathInterpolator;
import android.view.inputmethod.InputMethodManager;
import java.util.*;

/** Draggable edge controls and a fixed upper-left server-only grid. */
public final class HandOverlay implements AutoCloseable {
    public interface Actions {void toggle();void settings();void help();void exit();}
    private final Context context;private final WindowManager wm;private final Actions actions;
    static final int BACKGROUND=0xED142D46;
    private final Controls controls;private final ControlWindow controlWindow;private final Grid grid;
    private IdentitySettingsView settingsView;
    private SettingsWindow settingsWindow;
    private WindowManager.LayoutParams settingsParams;
    private ValueAnimator settingsAnimator;
    private int settingsHeight;
    private float settingsProgress;
    private boolean settingsAbove,settingsClosing;
    private Runnable settingsClosed,afterSettings;
    private final WindowManager.LayoutParams menuParams,panelParams;
    private boolean expanded=true,panelVisible=false,running;private boolean closed;
    private boolean menuDocked=true,rightDocked=false;
    private final float density;private HandReply reply;private String message="点击启动，等待新局";
    private volatile java.util.List<Rect> bounds=List.of();
    private List<Rect> recognitionAreas=List.of();
    public HandOverlay(Context context,Actions actions){this.context=context;this.actions=actions;wm=(WindowManager)context.getSystemService(Context.WINDOW_SERVICE);density=context.getResources().getDisplayMetrics().density;
        controls=new Controls();controlWindow=new ControlWindow();controlWindow.addView(controls);grid=new Grid();menuParams=params();panelParams=params();panelParams.flags|=WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        size();layoutControls();wm.addView(controlWindow,menuParams);grid.setVisibility(View.GONE);wm.addView(grid,panelParams);drag(controls,menuParams,true);drag(grid,panelParams,false);updateBounds();
    }
    private WindowManager.LayoutParams params(){var p=new WindowManager.LayoutParams(1,1,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);p.gravity=Gravity.TOP|Gravity.LEFT;return p;}
    private int dp(float x){return Math.round(x*density);}
    private DisplayMetrics screen(){DisplayMetrics d=new DisplayMetrics();wm.getDefaultDisplay().getRealMetrics(d);return d;}
    private void clamp(WindowManager.LayoutParams p,DisplayMetrics d){p.x=Math.max(0,Math.min(p.x,d.widthPixels-p.width));p.y=Math.max(0,Math.min(p.y,d.heightPixels-p.height));}
    private void size(){DisplayMetrics d=screen();menuParams.width=expanded?Math.min(dp(240),d.widthPixels-dp(16)):Math.max(dp(48),Math.round(d.widthPixels*.045f));menuParams.height=expanded?dp(48):Math.round(menuParams.width*1.2f);
        panelParams.width=Math.min(dp(400),Math.round(d.widthPixels*.27f));panelParams.height=Math.round(panelParams.width*.46f);
        if(menuDocked){menuParams.x=d.widthPixels-menuParams.width;menuParams.y=(d.heightPixels-menuParams.height)/2;}
        if(!menuDocked&&rightDocked)menuParams.x=d.widthPixels-menuParams.width;
        panelParams.x=2;panelParams.y=2;clamp(menuParams,d);clamp(panelParams,d);
    }
    public void setRecognitionAreas(List<Rect> areas){
        recognitionAreas=areas.stream().map(Rect::new).toList();
        if(!ensurePanelSafe())setPanelVisible(false);if(panelVisible)wm.updateViewLayout(grid,panelParams);updateBounds();
    }
    private boolean panelSafe(int x,int y){
        Rect candidate=new Rect(x,y,x+panelParams.width,y+panelParams.height);
        for(Rect area:recognitionAreas)if(Rect.intersects(candidate,area))return false;
        return true;
    }
    private boolean ensurePanelSafe(){
        panelParams.x=2;panelParams.y=2;
        return panelSafe(panelParams.x,panelParams.y);
    }
    public void resize(){
        if(closed)return;
        size();
        layoutControls();
        if(settingsView!=null){positionSettings();wm.updateViewLayout(settingsWindow,settingsParams);}
        wm.updateViewLayout(controlWindow,menuParams);
        if(!ensurePanelSafe())setPanelVisible(false);if(panelVisible)wm.updateViewLayout(grid,panelParams);updateBounds();
    }
    private void layoutControls(){controls.setLayoutParams(new FrameLayout.LayoutParams(-1,menuParams.height));}
    private void positionSettings(){
        DisplayMetrics d=screen();
        settingsView.measure(View.MeasureSpec.makeMeasureSpec(menuParams.width,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(d.heightPixels-dp(48),View.MeasureSpec.AT_MOST));
        settingsHeight=settingsView.getMeasuredHeight();
        settingsAbove=menuParams.y>=settingsHeight+dp(24);
        settingsParams.width=menuParams.width;settingsParams.height=settingsHeight;
        settingsParams.x=menuParams.x;
        settingsParams.y=settingsAbove?menuParams.y-settingsHeight:menuParams.y+menuParams.height;
        applySettingsProgress();
    }
    public boolean hasSettings(){return settingsView!=null;}
    public void showSettings(Runnable saved,Runnable closedCallback){
        if(closed)return;
        if(settingsView!=null){dismissSettings();return;}
        expand();settingsClosed=closedCallback;settingsClosing=false;settingsProgress=0;
        settingsView=new IdentitySettingsView(context,saved,this::dismissSettings);
        // Resizing/moving the toolbar's native surface invokes Android's move animation.
        // Keep that surface fixed; only the adjacent form has an input-capable window.
        settingsWindow=new SettingsWindow();settingsWindow.addView(settingsView,new FrameLayout.LayoutParams(-1,-1));
        settingsParams=params();settingsParams.flags&=~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        settingsParams.flags|=WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        settingsParams.softInputMode=WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN|WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
        settingsParams.windowAnimations=0;
        positionSettings();wm.addView(settingsWindow,settingsParams);settingsWindow.requestFocus();
        animateSettings(1,200);
    }
    public void dismissSettings(){
        if(settingsView==null||settingsClosing)return;
        settingsClosing=true;
        ((InputMethodManager)context.getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(settingsWindow.getWindowToken(),0);
        animateSettings(0,160);
    }
    private void animateSettings(float target,long duration){
        if(settingsAnimator!=null){settingsAnimator.removeAllListeners();settingsAnimator.cancel();}
        settingsAnimator=ValueAnimator.ofFloat(settingsProgress,target);
        settingsAnimator.setDuration(duration);settingsAnimator.setInterpolator(new PathInterpolator(.2f,0,0,1));
        settingsAnimator.addUpdateListener(value->{settingsProgress=(float)value.getAnimatedValue();applySettingsProgress();});
        settingsAnimator.addListener(new AnimatorListenerAdapter(){@Override public void onAnimationEnd(Animator animation){
            settingsAnimator=null;if(target==0)finishSettings();
        }});settingsAnimator.start();
    }
    private void applySettingsProgress(){
        if(settingsView!=null){settingsView.setAlpha(settingsProgress);settingsView.setTranslationY((settingsAbove?1:-1)*settingsHeight*(1-settingsProgress));}
        controlWindow.invalidate();if(settingsWindow!=null)settingsWindow.invalidate();
    }
    private void finishSettings(){
        if(settingsView==null)return;
        wm.removeView(settingsWindow);settingsWindow=null;settingsParams=null;settingsView=null;
        settingsHeight=0;settingsProgress=0;controlWindow.invalidate();
        Runnable callback=settingsClosed,after=afterSettings;settingsClosed=null;afterSettings=null;settingsClosing=false;
        if(callback!=null)callback.run();if(after!=null)after.run();
    }
    public void update(boolean active,HandReply data,String status){if(closed)return;phaseVisible=active;if(running!=active){running=active;setPanelVisible(active);}else if(!active)setPanelVisible(false);reply=data;message=status;controls.invalidate();grid.invalidate();}
    private boolean phaseVisible;
    public void update(boolean active,HandReply data,String status,boolean visible){
        if(closed)return;running=active;reply=data;message=status;
        boolean wanted=active&&visible;
        if(wanted!=phaseVisible){phaseVisible=wanted;setPanelVisible(wanted);}
        if(!wanted)setPanelVisible(false);
        controls.invalidate();grid.invalidate();
    }
    public List<Rect> bounds(){return bounds;}
    private void updateBounds(){List<Rect> b=new ArrayList<>();if(controlWindow.getVisibility()==View.VISIBLE)b.add(new Rect(menuParams.x,menuParams.y,menuParams.x+menuParams.width,menuParams.y+menuParams.height));if(panelVisible)b.add(new Rect(panelParams.x,panelParams.y,panelParams.x+panelParams.width,panelParams.y+panelParams.height));bounds=List.copyOf(b);}
    public void expand(){if(closed)return;expanded=true;resize();controls.invalidate();}
    public void setControlsVisible(boolean visible){if(closed)return;controlWindow.setVisibility(visible?View.VISIBLE:View.GONE);if(!visible)setPanelVisible(false);updateBounds();}
    private void collapse(){if(settingsView!=null){afterSettings=this::collapse;dismissSettings();return;}expanded=!expanded;menuDocked=false;rightDocked=true;resize();}
    private void setPanelVisible(boolean visible){if(closed||panelVisible==visible)return;if(visible&&!ensurePanelSafe())return;panelVisible=visible;if(visible)wm.updateViewLayout(grid,panelParams);grid.setVisibility(visible?View.VISIBLE:View.GONE);updateBounds();}
    private void panel(){if(running&&phaseVisible)setPanelVisible(!panelVisible);}
    private void drag(View view,WindowManager.LayoutParams p,boolean menu){view.setOnTouchListener(new View.OnTouchListener(){float x,y;int px,py;boolean moved;long start;
        public boolean onTouch(View v,MotionEvent e){switch(e.getActionMasked()){
            case MotionEvent.ACTION_DOWN:x=e.getRawX();y=e.getRawY();px=p.x;py=p.y;moved=false;start=android.os.SystemClock.elapsedRealtime();return true;
            case MotionEvent.ACTION_MOVE:float dx=e.getRawX()-x,dy=e.getRawY()-y;if(Math.abs(dx)+Math.abs(dy)>dp(8))moved=true;if(moved&&menu){menuDocked=false;rightDocked=false;p.x=px+(int)dx;p.y=py+(int)dy;clamp(p,screen());wm.updateViewLayout(menu?controlWindow:view,p);if(menu&&settingsView!=null){positionSettings();wm.updateViewLayout(settingsWindow,settingsParams);}updateBounds();}return true;
            case MotionEvent.ACTION_UP:if(moved&&menu&&!expanded){rightDocked=true;resize();}if(!moved){view.performClick();if(menu){if(!expanded){collapse();return true;}int slot=Math.min(4,(int)(e.getX()*5/view.getWidth()));switch(slot){case 0:if(settingsView!=null){afterSettings=actions::toggle;dismissSettings();}else actions.toggle();break;case 1:actions.settings();break;case 2:if(settingsView!=null){afterSettings=actions::help;dismissSettings();}else actions.help();break;case 3:actions.exit();break;case 4:if(android.os.SystemClock.elapsedRealtime()-start>500)panel();else collapse();break;}}else panel();}return true;
            default:return true;
        }} });}
    /** Shared drawing keeps the two fixed surfaces visually joined, including during reveal. */
    private abstract class JoinedWindow extends FrameLayout {
        final Paint background=new Paint(3);
        final Path outline=new Path();
        final RectF visible=new RectF();
        final float[] radii=new float[8];
        JoinedWindow(){super(context);setWillNotDraw(false);}
        void drawBackground(Canvas canvas,float top,float bottom,boolean joinTop,boolean joinBottom){
            float radius=dp(14),joinRadius=radius*(1-settingsProgress);
            Arrays.fill(radii,radius);
            if(joinTop)Arrays.fill(radii,0,4,joinRadius);
            if(joinBottom)Arrays.fill(radii,4,8,joinRadius);
            visible.set(0,top,getWidth(),bottom);outline.reset();outline.addRoundRect(visible,radii,Path.Direction.CW);
            background.setColor(BACKGROUND);canvas.drawPath(outline,background);
        }
        @Override protected void dispatchDraw(Canvas canvas){int save=canvas.save();canvas.clipPath(outline);super.dispatchDraw(canvas);canvas.restoreToCount(save);}
    }
    private final class ControlWindow extends JoinedWindow {
        @Override protected void onDraw(Canvas canvas){
            drawBackground(canvas,0,getHeight(),settingsView!=null&&settingsAbove,settingsView!=null&&!settingsAbove);
            if(settingsView!=null){
                background.setColor(0xFF456179);background.setAlpha(Math.round(100*settingsProgress));
                float y=settingsAbove?0:getHeight()-dp(.5f);canvas.drawRect(dp(12),y,getWidth()-dp(12),y+dp(.5f),background);
            }
        }
    }
    private final class SettingsWindow extends JoinedWindow {
        SettingsWindow(){setFocusableInTouchMode(true);setDescendantFocusability(FOCUS_BEFORE_DESCENDANTS);}
        @Override protected void onDraw(Canvas canvas){
            float top=settingsAbove?getHeight()*(1-settingsProgress):0;
            float bottom=settingsAbove?getHeight():getHeight()*settingsProgress;
            drawBackground(canvas,top,bottom,!settingsAbove,settingsAbove);
        }
        @Override public boolean dispatchKeyEvent(KeyEvent event){
            if(event.getKeyCode()==KeyEvent.KEYCODE_BACK){if(event.getAction()==KeyEvent.ACTION_UP)dismissSettings();return true;}
            return super.dispatchKeyEvent(event);
        }
    }
    private final class Controls extends View {
        final Paint paint=new Paint(3);Controls(){super(context);setContentDescription("悬浮控制栏：启动、设置、说明、退出、收起；长按收起可切换结果面板");}
        @Override public boolean performClick(){super.performClick();return true;}
        @Override protected void onDraw(Canvas c){super.onDraw(c);paint.setTextAlign(Paint.Align.CENTER);
            if(!expanded){paint.setColor(Color.WHITE);paint.setTextSize(Math.min(dp(25),getWidth()*.9f));c.drawText("‹",getWidth()/2f,getHeight()*.55f,paint);paint.setTextSize(Math.min(dp(10),getWidth()*.42f));c.drawText("展开",getWidth()/2f,getHeight()*.82f,paint);return;}
            String[] icons={running?"Ⅱ":"▶","⚙","i","↪","›"},labels={running?"暂停":"启动","设置","说明","退出","收起"};float unit=getWidth()/5f;
            for(int i=0;i<5;i++){float cx=unit*(i+.5f),cy=getHeight()*.36f,r=Math.min(unit*.34f,getHeight()*.27f);paint.setColor(i==0&&running?0xFFB99D53:0xAA102132);c.drawCircle(cx,cy,r,paint);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1));paint.setColor(0xFF8194A7);c.drawCircle(cx,cy,r,paint);paint.setStyle(Paint.Style.FILL);paint.setColor(Color.WHITE);paint.setTextSize(r*1.25f);c.drawText(icons[i],cx,cy+r*.45f,paint);paint.setTextSize(Math.min(dp(14),unit*.25f));c.drawText(labels[i],cx,getHeight()*.87f,paint);}
            paint.setColor(running?0xFF66D5AE:0xFFABB4BF);float pulse=(android.os.SystemClock.elapsedRealtime()/500)%2==0?dp(2):dp(1);c.drawCircle(dp(7),dp(7),pulse,paint);
        }
    }
    private final class Grid extends View {
        final Paint p=new Paint(3);Grid(){super(context);setContentDescription("服务器返回的花色点数面板，固定左上角，点击隐藏");}
        @Override public boolean performClick(){super.performClick();return true;}
        @Override protected void onDraw(Canvas c){super.onDraw(c);float footer=getHeight()*.1f,header=0,row=(getHeight()-footer-header)/8f,col=getWidth()/14f;
            c.drawColor(0xFFF5F8FE);p.setTextAlign(Paint.Align.CENTER);p.setColor(0xFF647B98);p.setTextSize(Math.min(col*.68f,header*.85f));
            for(int y=0;y<8;y++){if(y%2==1){p.setColor(0xFFE3EDFA);c.drawRect(0,header+y*row,getWidth(),header+(y+1)*row,p);}p.setColor(y/2==1||y/2==3?0xFFE32636:0xFF39434B);p.setTextSize(row*.92f);c.drawText(new String[]{"♠","♥","♣","♦"}[y/2],col*.5f,header+(y+.83f)*row,p);
                for(int x=0;x<13;x++)if(reply!=null&&reply.occupied(y,x)){p.setTextSize(Math.min(row*.85f,col*.9f));c.drawText(HandSnapshots.RANKS.get(x),col*(x+1.5f),header+(y+.8f)*row,p);}}
            p.setColor(0xFF4387D9);p.setStrokeWidth(Math.max(1,dp(.6f)));for(int x=0;x<=14;x++)c.drawLine(x*col,header,x*col,getHeight()-footer,p);for(int y=0;y<=8;y++)c.drawLine(0,header+y*row,getWidth(),header+y*row,p);
            p.setColor(0xFF6D7B8D);p.setTextAlign(Paint.Align.LEFT);p.setTextSize(footer*.72f);String label=reply==null?message:reply.totalCount+" 张 · "+message;while(p.measureText(label)>getWidth()-4&&label.length()>1)label=label.substring(0,label.length()-1);c.drawText(label,2,getHeight()-footer*.2f,p);
        }
    }
    public void togglePanel(){panel();}
    @Override public void close(){if(closed)return;closed=true;if(settingsAnimator!=null){settingsAnimator.removeAllListeners();settingsAnimator.cancel();settingsAnimator=null;}
        if(settingsClosed!=null){settingsClosed.run();settingsClosed=null;}if(settingsWindow!=null){wm.removeView(settingsWindow);settingsWindow=null;}settingsView=null;wm.removeView(controlWindow);wm.removeView(grid);bounds=List.of();}
}
