package com.jpq.mobile;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private final ExecutorService io=Executors.newSingleThreadExecutor();private final Handler ui=new Handler(Looper.getMainLooper());
    private Pack pack;private Appearance appearance;private CounterStrip preview;private TextView status,packLabel,stats;private Button start,stop;
    private boolean loading=true,pendingStart=false;private String savedReport="";
    private final Runnable poll=new Runnable(){public void run(){refresh();ui.postDelayed(this,500);}};
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView text(String s,int size,int color){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(color);v.setPadding(0,dp(4),0,dp(4));return v;}
    private Button button(String title,Runnable action){Button b=new Button(this);b.setText(title);b.setAllCaps(false);b.setTextSize(15);b.setOnClickListener(v->action.run());return b;}
    private LinearLayout box(){LinearLayout b=new LinearLayout(this);b.setOrientation(LinearLayout.VERTICAL);b.setPadding(dp(18),dp(14),dp(18),dp(14));GradientDrawable bg=new GradientDrawable();bg.setColor(Color.WHITE);bg.setCornerRadius(dp(18));bg.setStroke(dp(1),0xFFE6E0D4);b.setBackground(bg);return b;}
    private void addBox(LinearLayout parent,View child){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(14);parent.addView(child,p);}
    @Override public void onCreate(Bundle saved){super.onCreate(saved);appearance=new Appearance();appearance.load(this);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(0xFFF8F5EE);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(22),dp(22),dp(22),dp(24));scroll.addView(root);setContentView(scroll);
        root.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(dp(22),dp(14)+insets.getSystemWindowInsetTop(),dp(22),dp(20)+insets.getSystemWindowInsetBottom());return insets;});
        TextView title=text("记牌器",30,0xFF302C24);title.setTypeface(null,android.graphics.Typeface.BOLD);root.addView(title);root.addView(text("看清每张牌，记住每次出牌",14,0xFF887B67));
        LinearLayout live=box();live.addView(text("悬浮条预览",13,0xFF887B67));preview=new CounterStrip(this,appearance);live.addView(preview,new LinearLayout.LayoutParams(-1,-2));
        live.addView(text("黑色牌点 · 橙色余量 · 灰色归零\n左侧切换自动记牌，拖动调整位置，点牌面查看记录。",12,0xFF887B67));
        status=text("正在载入配置…",15,0xFF62553F);live.addView(status);stats=text("",12,0xFF887B67);live.addView(stats);addBox(root,live);
        LinearLayout config=box();config.addView(text("游戏配置",18,0xFF302C24));packLabel=text("正在检查内置配置包",14,0xFF62553F);config.addView(packLabel);
        config.addView(button("导入新的 ZIP 配置包",this::importPack));config.addView(text("配置来自 JPQ Tools。所有截图仅在本机处理，不上传。",12,0xFF887B67));addBox(root,config);
        LinearLayout controls=box();start=button("开始记牌",this::begin);start.setTextColor(Color.WHITE);start.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFC97832));controls.addView(start);
        stop=button("停止识别",()->stopService(new Intent(this,CaptureService.class)));controls.addView(stop);
        controls.addView(text("请在发牌前开启。余量 = 整副牌 − 各家已确认出牌，包含你尚未打出的手牌。中途开启无法补回此前出牌。",13,0xFF887B67));addBox(root,controls);
        LinearLayout tools=box();tools.addView(text("显示与记录",18,0xFF302C24));tools.addView(button("样式与颜色",this::appearanceDialog));tools.addView(button("游戏画面范围",this::viewportDialog));tools.addView(button("出牌记录 / 待核对",this::history));tools.addView(button("导出本次记录",this::exportReport));tools.addView(button("撤销上一笔已计入出牌",()->{CaptureService s=CaptureService.current;if(s!=null)s.command("undo",0);else toast("请先开始记牌");}));
        tools.addView(button("手动确认新局",()->{CaptureService s=CaptureService.current;if(s==null){toast("请先开始记牌");return;}new AlertDialog.Builder(this).setTitle("确认刚刚发牌？").setMessage("这会重置本局余量。只在新一局开始时使用。").setNegativeButton("取消",null).setPositiveButton("确认新局",(d,w)->s.command("new",0)).show();}));addBox(root,tools);
        root.addView(text("结算特征命中后锁定。临近结算的候选先保留核对，不会直接扣牌。",12,0xFF887B67));
        io.execute(()->{try{Pack loaded=Pack.current(this);ui.post(()->{pack=loaded;loading=false;refresh();});}catch(Exception e){ui.post(()->{loading=false;setText(status,"配置载入失败："+e.getMessage());toast(e.getMessage());});}});
    }
    private void setText(TextView v,String text){if(!v.getText().toString().equals(text))v.setText(text);}
    private void refresh(){if(isDestroyed())return;CaptureService s=CaptureService.current;start.setEnabled(!loading&&pack!=null&&s==null);stop.setEnabled(s!=null);if(pack==null)return;
        try{setText(packLabel,pack.name+"  ·  "+pack.json.getJSONObject("package").getString("version")+"\n"+pack.hands.size()+" 人 · "+pack.deck.values().stream().mapToInt(Integer::intValue).sum()+" 张牌 · "+pack.resources.size()+" 个模板");}catch(Exception ignored){}
        if(s==null){String error=getSharedPreferences("capture",0).getString("last_error","");setText(status,error.isEmpty()?"准备就绪 · 等待开启":error);setText(stats,"默认采样 "+pack.timing.optInt("sample_interval_ms")+"ms · 结算保护 "+pack.guard.optInt("settlement_hold_ms")/1000f+"s");preview.data(pack.order,pack.deck,true,false,0);}
        else{setText(status,s.status);setText(stats,s.frameMillis>0?"单帧处理 "+s.frameMillis+"ms · "+(s.automatic?"自动记牌":"已暂停"):"正在连接屏幕采集");if(s.ledger!=null)preview.data(pack.order,s.ledger.remaining(),s.automatic,s.ledger.phase==Ledger.Phase.WAITING,s.ledger.reviews());}
    }
    @Override protected void onResume(){super.onResume();ui.post(poll);if(pendingStart&&Settings.canDrawOverlays(this)){pendingStart=false;requestCapture();}}
    @Override protected void onPause(){super.onPause();ui.removeCallbacks(poll);}
    @Override protected void onDestroy(){super.onDestroy();ui.removeCallbacks(poll);io.shutdown();}
    private void toast(String message){Toast.makeText(this,message==null?"操作失败":message,Toast.LENGTH_LONG).show();}
    private void begin(){if(pack==null)return;if(!Settings.canDrawOverlays(this)){pendingStart=true;new AlertDialog.Builder(this).setTitle("允许显示悬浮条").setMessage("请在系统设置中允许记牌器显示在其他应用上层，然后返回。").setPositiveButton("去设置",(d,w)->startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName())))).setNegativeButton("取消",(d,w)->pendingStart=false).show();return;}requestCapture();}
    private void requestCapture(){if(CaptureService.current!=null)return;if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},80);}else projectionPrompt();}
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){super.onRequestPermissionsResult(code,permissions,results);if(code==80)projectionPrompt();}
    private void projectionPrompt(){MediaProjectionManager m=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);Intent intent=Build.VERSION.SDK_INT>=34?m.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay()):m.createScreenCaptureIntent();startActivityForResult(intent,81);}
    private void importPack(){if(CaptureService.current!=null){toast("请先停止识别，再更换配置");return;}Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/zip","application/x-zip-compressed","application/octet-stream"});startActivityForResult(i,82);}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null)return;
        if(request==81){getSharedPreferences("capture",0).edit().remove("last_error").apply();Intent service=new Intent(this,CaptureService.class).putExtra("projection",data).putExtra("result",result);startForegroundService(service);toast("已开启，切换到横屏游戏即可");}
        if(request==82){if(CaptureService.current!=null){toast("识别中不能更换配置");return;}loading=true;refresh();io.execute(()->{try(InputStream in=getContentResolver().openInputStream(data.getData())){Pack next=Pack.install(this,in);ui.post(()->{pack=next;loading=false;getSharedPreferences("capture",0).edit().remove("last_error").apply();refresh();toast("配置已导入");});}catch(Exception e){ui.post(()->{loading=false;refresh();toast("导入失败，保留旧配置："+e.getMessage());});}});}
        if(request==83){io.execute(()->{try(OutputStream out=getContentResolver().openOutputStream(data.getData())){out.write(savedReport.getBytes(StandardCharsets.UTF_8));ui.post(()->toast("记录已导出"));}catch(Exception e){ui.post(()->toast(e.getMessage()));}});}
    }
    private JSONObject report(){CaptureService s=CaptureService.current;if(s!=null)return s.report();try{return new JSONObject(new String(java.nio.file.Files.readAllBytes(new File(getFilesDir(),"session.json").toPath()),StandardCharsets.UTF_8));}catch(Exception e){return new JSONObject();}}
    private void history(){JSONObject report=report();JSONArray events=report.optJSONArray("events");LinearLayout list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);list.setPadding(dp(16),dp(10),dp(16),dp(10));
        if(events==null||events.length()==0)list.addView(text("还没有出牌记录。识别到的牌组会先等待结算保护，再计入余量。",15,0xFF62553F));
        if(events!=null)for(int i=events.length()-1;i>=Math.max(0,events.length()-150);i--)try{
            JSONObject e=events.getJSONObject(i);String state=e.getString("status");String label=switch(state){case "COUNTED"->"已计入";case "PENDING"->"观察中";case "REVIEW"->"待核对";default->"已忽略";};JSONArray cards=e.getJSONArray("cards");List<String> labels=new ArrayList<>();for(int j=0;j<cards.length();j++)labels.add(cards.getString(j));
            TextView row=text("第 "+e.getInt("round")+" 局 · "+e.getString("name")+" · "+label+"\n"+String.join(" ",labels)+"\n"+e.getString("reason"),14,state.equals("REVIEW")?0xFFB76521:0xFF62553F);row.setPadding(dp(8),dp(12),dp(8),dp(12));list.addView(row);
            if(state.equals("REVIEW")){row.setOnClickListener(v->{CaptureService s=CaptureService.current;if(s==null||s.ledger==null||e.optInt("round")!=s.ledger.round){toast("历史局仅供查看，不影响当前余量");return;}new AlertDialog.Builder(this).setTitle("核对这笔出牌").setMessage("只有确认这是实际出牌，才计入余量。结算时展示的剩余牌请忽略。").setNeutralButton("取消",null).setNegativeButton("忽略",(d,w)->s.command("dismiss",e.optLong("id"))).setPositiveButton("确实出牌",(d,w)->{s.command("confirm",e.optLong("id"));toast("已提交核对；超过牌库上限的记录仍保留待核对");}).show();});}
        }catch(Exception ignored){}
        ScrollView scroll=new ScrollView(this);scroll.addView(list);new AlertDialog.Builder(this).setTitle("出牌记录 · 最近 150 笔").setView(scroll).setPositiveButton("关闭",null).show();
    }
    private void exportReport(){try{savedReport=report().toString(2);if(!report().has("events")){toast("还没有可导出的记录");return;}startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"记牌记录.json"),83);}catch(Exception e){toast(e.getMessage());}}
    private void appearanceDialog(){
        LinearLayout fields=new LinearLayout(this);fields.setOrientation(LinearLayout.VERTICAL);fields.setPadding(dp(20),dp(8),dp(20),dp(8));String[] names={"底色","牌点颜色","余量颜色","归零颜色","开关区颜色","网格颜色"};int[] colors={appearance.background,appearance.rank,appearance.number,appearance.zero,appearance.accent,appearance.grid};EditText[] edits=new EditText[6];
        for(int i=0;i<6;i++){fields.addView(text(names[i],13,0xFF62553F));EditText e=new EditText(this);e.setSingleLine();e.setText(String.format("#%06X",colors[i]&0xffffff));fields.addView(e);edits[i]=e;}
        TextView widthLabel=text("悬浮条宽度 "+Math.round(appearance.width*100)+"%",14,0xFF62553F);fields.addView(widthLabel);SeekBar width=new SeekBar(this);width.setMax(73);width.setProgress(Math.round(appearance.width*100)-25);fields.addView(width);width.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar s,int p,boolean user){widthLabel.setText("悬浮条宽度 "+(25+p)+"%");}public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){}});
        TextView opacityLabel=text("不透明度 "+Math.round(appearance.opacity*100)+"%",14,0xFF62553F);fields.addView(opacityLabel);SeekBar opacity=new SeekBar(this);opacity.setMax(50);opacity.setProgress(Math.round(appearance.opacity*100)-50);fields.addView(opacity);opacity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar s,int p,boolean user){opacityLabel.setText("不透明度 "+(50+p)+"%");}public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){}});
        ScrollView scroll=new ScrollView(this);scroll.addView(fields);AlertDialog d=new AlertDialog.Builder(this).setTitle("样式与颜色").setView(scroll).setNegativeButton("取消",null).setNeutralButton("恢复参考配色",null).setPositiveButton("保存",null).create();d.setOnShowListener(v->{d.getButton(-3).setOnClickListener(b->{Appearance a=new Appearance();a.save(this);appearance.load(this);applyAppearance();d.dismiss();});d.getButton(-1).setOnClickListener(b->{try{int[] cs=new int[6];for(int i=0;i<6;i++){String hex=edits[i].getText().toString().trim();if(!hex.matches("#[0-9a-fA-F]{6}"))throw new IllegalArgumentException("请输入 # 开头的六位颜色值");cs[i]=Color.parseColor(hex);}appearance.background=cs[0];appearance.rank=cs[1];appearance.number=cs[2];appearance.zero=cs[3];appearance.accent=cs[4];appearance.grid=cs[5];appearance.width=(width.getProgress()+25)/100f;appearance.opacity=(opacity.getProgress()+50)/100f;appearance.save(this);applyAppearance();d.dismiss();}catch(Exception e){toast(e.getMessage());}});});d.show();
    }
    private void applyAppearance(){preview.invalidate();CaptureService s=CaptureService.current;if(s!=null)s.refreshAppearance();}
    private void viewportDialog(){if(CaptureService.current!=null){toast("请先停止识别，再校准画面");return;}LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(20),dp(8),dp(20),dp(8));box.addView(text("默认整个横屏画面。只有游戏周围有黑边时才裁剪；界面排布变化应重新采集适配包。填写占屏幕的百分比。",14,0xFF62553F));String[] keys={"x","y","w","h"},labels={"左侧位置 %","顶部位置 %","有效宽度 %","有效高度 %"};EditText[] es=new EditText[4];SharedPreferences p=getSharedPreferences("capture",0);
        for(int i=0;i<4;i++){box.addView(text(labels[i],13,0xFF62553F));es[i]=new EditText(this);es[i].setSingleLine();es[i].setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);es[i].setText(String.valueOf(p.getFloat(keys[i],i<2?0:1)*100));box.addView(es[i]);}
        AlertDialog d=new AlertDialog.Builder(this).setTitle("游戏画面范围").setView(box).setNegativeButton("取消",null).setPositiveButton("保存",null).create();d.setOnShowListener(v->d.getButton(-1).setOnClickListener(b->{try{float[] a=new float[4];for(int i=0;i<4;i++)a[i]=Float.parseFloat(es[i].getText().toString())/100;if(a[0]<0||a[1]<0||a[2]<=0||a[3]<=0||a[0]+a[2]>1.00001||a[1]+a[3]>1.00001)throw new IllegalArgumentException();SharedPreferences.Editor e=p.edit();for(int i=0;i<4;i++)e.putFloat(keys[i],a[i]);e.apply();d.dismiss();}catch(Exception error){toast("范围必须位于屏幕内");}}));d.show();
    }
}
