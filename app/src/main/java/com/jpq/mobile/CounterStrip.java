package com.jpq.mobile;
import android.content.Context;
import android.graphics.*;
import android.view.*;
import java.util.*;

/** Two rows, matching the supplied reference. Left cell is the automatic-recording toggle. */
public final class CounterStrip extends View {
    public final Appearance style;
    private final Path check=new Path();
    public CounterStrip(Context c){this(c,new Appearance());}
    @Override public boolean performClick(){return super.performClick();}
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<String> ranks=List.of("2","A","K","Q","J","10","9","8","7","6","5","4","3");
    private Map<String,Integer> remaining=new HashMap<>();
    private boolean pulse;
    public void pulse(boolean value){if(pulse!=value){pulse=value;invalidate();}}
    public boolean automatic=true,waiting=false;public int reviewCount=0;
    public CounterStrip(Context c,Appearance style){super(c);this.style=style;setContentDescription("记牌器，左侧开关暂停或继续，长按查看记录，拖动调整位置");}
    public void data(List<String> order,Map<String,Integer> counts,boolean automatic,boolean waiting,int reviews){if(this.ranks.equals(order)&&remaining.equals(counts)&&this.automatic==automatic&&this.waiting==waiting&&reviewCount==reviews)return;this.ranks=List.copyOf(order);remaining=new LinkedHashMap<>(counts);this.automatic=automatic;this.waiting=waiting;reviewCount=reviews;invalidate();}
    @Override protected void onMeasure(int ws,int hs){int width=MeasureSpec.getSize(ws);setMeasuredDimension(width,resolveSize(Math.round(width/6.5f),hs));}
    private void text(Canvas c,String value,float x,float y,float size,int color,boolean bold){paint.setColor(color);paint.setTextSize(size);paint.setTypeface(bold?Typeface.create("sans-serif-medium",Typeface.NORMAL):Typeface.create("sans-serif",Typeface.NORMAL));paint.setTextAlign(Paint.Align.CENTER);Paint.FontMetrics fm=paint.getFontMetrics();c.drawText(value,x,y-(fm.ascent+fm.descent)/2,paint);}
    @Override protected void onDraw(Canvas c){super.onDraw(c);float w=getWidth(),h=getHeight(),cell=w/(ranks.size()+1f);c.drawColor(style.background);paint.setColor(style.accent);c.drawRect(0,0,cell,h,paint);
        for(int i=0;i<ranks.size();i++){float x=cell*(i+1.5f);String rank=ranks.get(i);text(c,rank,x,h*.255f,Math.min(cell*.68f,h*.38f),style.rank,false);Integer count=remaining.get(rank);String value=waiting?"—":String.valueOf(count==null?0:count);text(c,value,x,h*.755f,Math.min(cell*.69f,h*.38f),count!=null&&count==0?style.zero:style.number,false);}
        paint.setColor(style.grid);paint.setStrokeWidth(Math.max(1,w/900));for(int i=1;i<=ranks.size();i++)c.drawLine(cell*i,0,cell*i,h,paint);c.drawLine(cell,h*.5f,w,h*.5f,paint);
        text(c,automatic?"自动":"暂停",cell*.5f,h*.16f,Math.min(cell*.39f,h*.25f),style.rank,true);text(c,"记牌",cell*.5f,h*.37f,Math.min(cell*.39f,h*.25f),style.rank,true);
        float box=Math.min(cell*.6f,h*.32f),left=(cell-box)/2,top=h*.62f;paint.setColor(automatic?Color.rgb(163,117,61):style.zero);c.drawRoundRect(left,top,left+box,top+box,2,2,paint);
        if(automatic){paint.setColor(Color.rgb(255,243,183));paint.setStrokeWidth(box*.12f);paint.setStyle(Paint.Style.STROKE);check.reset();check.moveTo(left+box*.18f,top+box*.5f);check.lineTo(left+box*.4f,top+box*.72f);check.lineTo(left+box*.82f,top+box*.24f);c.drawPath(check,paint);paint.setStyle(Paint.Style.FILL);}
        if(automatic){paint.setColor(pulse?style.rank:style.accent);c.drawCircle(cell-4,4,2,paint);}
        if(reviewCount>0){paint.setColor(style.number);c.drawCircle(w-5,5,4,paint);}
    }
}
