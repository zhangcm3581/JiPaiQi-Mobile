package com.jpq.mobile;

import android.content.Context;
import android.graphics.BitmapFactory;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Versioned manifest is the only runtime contract. Imports are staged and validated. */
public final class Pack {
    public final JSONObject json;
    public final File folder;
    public final Map<String, JSONObject> regions=new LinkedHashMap<>(), resources=new LinkedHashMap<>(), detectors=new LinkedHashMap<>();
    public final LinkedHashMap<String,Integer> deck=new LinkedHashMap<>();
    public final Map<String,Integer> hands=new LinkedHashMap<>();
    public final int width,height;
    public final double[] content;
    public final List<String> order=new ArrayList<>();
    public final String name;
    public final JSONObject timing,guard,rows;
    private static final Set<String> CAPABILITIES=Set.of("cards.row_ownership.v1","color.base_and_any_ink.v1","color.selected.v2","recognition.round_guard.v1","recognition.timing.v1","state.priority.v1","template.ccoeff_normed.v1");

    public Pack(File dir) throws Exception {
        folder=dir;
        File manifest=new File(dir,"manifest.json");
        if(manifest.length()>2_000_000)throw new IOException("配置文件过大");
        json=new JSONObject(new String(Files.readAllBytes(manifest.toPath()),StandardCharsets.UTF_8));
        require(json.getString("schema").equals("jpq.mobile-adaptation/1.0"),"不支持此配置版本");
        JSONArray required=json.getJSONArray("requires");
        for(int i=0;i<required.length();i++)require(CAPABILITIES.contains(required.getString(i)),"尚不支持："+required.getString(i));
        JSONObject layout=json.getJSONObject("layout");
        require(layout.getString("transform").equals("uniform_fit") && layout.getString("coordinate_space").equals("normalized_content"),"画面坐标类型不支持");
        width=layout.getJSONArray("base_size").getInt(0);height=layout.getJSONArray("base_size").getInt(1);
        require(width>0&&height>0&&width<=4096&&height<=4096,"采集尺寸无效");
        content=numbers(layout.getJSONArray("content_rect"));
        require(content[0]>=0&&content[1]>=0&&content[2]>0&&content[3]>0&&content[0]+content[2]<=width&&content[1]+content[3]<=height,"有效画面无效");
        index(json.getJSONArray("regions"),regions);index(json.getJSONArray("resources"),resources);index(json.getJSONArray("detectors"),detectors);
        JSONObject rules=json.getJSONObject("rules");JSONArray cards=rules.getJSONArray("cards");
        for(int i=0;i<cards.length();i++){JSONObject c=cards.getJSONObject(i);String id=c.getString("id");int count=c.getInt("count");require(!deck.containsKey(id)&&count>=0&&count<=100&&!id.isBlank(),"牌库配置无效");deck.put(id,count);}
        require(!deck.isEmpty(),"缺少实际牌库");
        for(String id:"2 A K Q J 10 9 8 7 6 5 4 3".split(" "))if(deck.containsKey(id))order.add(id);
        for(String id:deck.keySet())if(!order.contains(id))order.add(id);
        require(order.size()<=24,"悬浮条最多支持 24 种牌");
        JSONArray seats=json.getJSONArray("seats"),initial=rules.getJSONArray("initial_hand_counts");
        require(initial.length()==seats.length()&&seats.length()==rules.getInt("player_count"),"缺少初始手牌数量");
        int total=0;
        for(int i=0;i<seats.length();i++){String id=seats.getJSONObject(i).getString("id");int count=initial.getInt(i);require(!hands.containsKey(id)&&count>0&&count<=100,"座位配置无效");hands.put(id,count);total+=count;}
        require(total+rules.getInt("bottom_count")+rules.getInt("undealt_count")==deck.values().stream().mapToInt(Integer::intValue).sum(),"总牌数与发牌数不符");
        for(JSONObject region:regions.values()){normalized(region.getJSONArray("rect"));JSONObject sem=region.getJSONObject("semantic");if(sem.optString("role").equals("played"))require(hands.containsKey(sem.optString("seat_id")),"出牌区缺少座位关联");}
        for(JSONObject res:resources.values()){
            File image=safeFile(dir,res.getString("file"));require(image.isFile()&&image.length()<8_000_000,"模板文件缺失或过大");
            BitmapFactory.Options opts=new BitmapFactory.Options();opts.inJustDecodeBounds=true;BitmapFactory.decodeFile(image.toString(),opts);
            require(opts.outWidth>1&&opts.outHeight>1&&opts.outWidth<=2048&&opts.outHeight<=2048,"模板图片无效");
            JSONObject sem=res.getJSONObject("semantic");if(sem.optString("role").equals("played"))require(deck.containsKey(sem.optString("rank")),"模板牌点不在牌库中");
        }
        for(JSONObject d:detectors.values()){
            String type=d.getString("type");require(CAPABILITIES.contains(type),"检测器不支持");JSONObject params=d.getJSONObject("params");
            if(type.equals("template.ccoeff_normed.v1")){
                require(resources.containsKey(d.getString("resource_id")),"模板引用不存在");
                require(Set.of("gray","color","otsu").contains(params.getString("preprocess")),"预处理不支持");
                double threshold=params.getDouble("threshold");require(Double.isFinite(threshold)&&threshold>=.1&&threshold<=1,"匹配阈值无效");
                require(d.isNull("region_id")||regions.containsKey(d.getString("region_id")),"搜索区域不存在");
            }else if(type.startsWith("color.")){
                normalized(d.getJSONArray("rect"));JSONArray colors=params.getJSONArray("colors");int enabled=0;
                require(colors.length()==3,"颜色特征数量无效");
                for(int i=0;i<3;i++){JSONObject c=colors.getJSONObject(i);if(c.optBoolean("enabled",true)){enabled++;require(c.getString("hex").matches("#[0-9a-fA-F]{6}"),"取色值无效");}}
                require(enabled>=2&&params.getDouble("min_ratio")>=0&&params.getDouble("min_ratio")<=1&&params.getInt("tolerance")>=0&&params.getInt("tolerance")<=255,"颜色参数无效");
            }else throw new IOException("未知检测器类型");
        }
        JSONArray states=json.getJSONArray("states");boolean hasEnd=false,hasStart=false;
        for(int i=0;i<states.length();i++){JSONObject s=states.getJSONObject(i);require(s.getString("combine").equals("any"),"状态组合不支持");String purpose=s.getString("purpose");hasEnd|=purpose.equals("round_end");hasStart|=purpose.equals("new_round_candidate");JSONArray ids=s.getJSONArray("detector_ids");require(ids.length()>0,"空状态特征");for(int j=0;j<ids.length();j++)require(detectors.containsKey(ids.getString(j)),"状态检测器不存在");}
        require(hasEnd&&hasStart,"自动记牌需要开局和结束特征");
        require(json.getJSONObject("state_resolution").getString("type").equals("state.priority.v1"),"状态优先级不支持");
        timing=json.getJSONObject("recognition_timing");guard=json.getJSONObject("round_guard");
        require(timing.getString("type").equals("recognition.timing.v1")&&guard.getString("type").equals("recognition.round_guard.v1"),"识别规则版本不支持");
        require(timing.getInt("confirm_samples")>=1&&timing.getInt("confirm_samples")<=30,"确认次数无效");
        bounded(timing,"sample_interval_ms",0,10000);bounded(timing,"empty_hold_ms",0,10000);bounded(guard,"settlement_hold_ms",1,10000);bounded(guard,"restart_clear_ms",1,10000);bounded(guard,"restart_confirm_samples",2,30);
        JSONObject ext=json.optJSONObject("extensions");JSONObject rule=ext==null?null:ext.optJSONObject("jpq.card_layout");
        rows=rule==null?new JSONObject():rule.getJSONObject("regions");
        if(rule!=null){require(rule.getString("type").equals("cards.row_ownership.v1")&&rule.getString("coordinate_space").equals("source_pixels")&&rule.getJSONArray("base_size").getInt(0)==width&&rule.getJSONArray("base_size").getInt(1)==height,"牌行规则尺寸不符");
            for(Iterator<String> it=rows.keys();it.hasNext();){String id=it.next();require(regions.containsKey(id),"牌行区域不存在");JSONObject r=rows.getJSONObject(id);double step=r.getDouble("step_x"),tol=r.getDouble("tolerance"),anchor=r.getDouble("anchor_x");require(step>0&&tol>0&&tol<step/2&&anchor>=0&&anchor<width,"牌行间距无效");bounded(r,"capacity",1,30);require(Set.of("left","right","center").contains(r.getString("align")),"牌行对齐无效");JSONArray ys=r.getJSONArray("row_y");require(ys.length()>0&&ys.length()<=4,"牌行数无效");double last=-1e6;for(int i=0;i<ys.length();i++){double y=ys.getDouble(i);require(Double.isFinite(y)&&y>=0&&y<height&&y-last>2*tol,"牌行坐标无效");last=y;}}}
        name=json.getJSONObject("package").getString("name");
    }
    static void bounded(JSONObject o,String k,double a,double b)throws Exception{double v=o.getDouble(k);require(Double.isFinite(v)&&v>=a&&v<=b,"规则参数无效："+k);}
    static void require(boolean ok,String message)throws IOException{if(!ok)throw new IOException(message);}
    static void index(JSONArray array,Map<String,JSONObject> into)throws Exception{require(array.length()<=256,"配置条目过多");for(int i=0;i<array.length();i++){JSONObject o=array.getJSONObject(i);String id=o.getString("id");require(!into.containsKey(id)&&!id.isBlank(),"重复或空 ID");into.put(id,o);}}
    static double[] numbers(JSONArray a)throws Exception{require(a.length()==4,"矩形格式错误");double[] v=new double[4];for(int i=0;i<4;i++){v[i]=a.getDouble(i);require(Double.isFinite(v[i]),"坐标无效");}return v;}
    static void normalized(JSONArray a)throws Exception{double[] r=numbers(a);require(r[0]>=0&&r[1]>=0&&r[2]>0&&r[3]>0&&r[0]+r[2]<=1.000001&&r[1]+r[3]<=1.000001,"区域越界");}
    public double[] sourceRect(JSONArray a)throws Exception{double[] r=numbers(a);return new double[]{content[0]+r[0]*content[2],content[1]+r[1]*content[3],r[2]*content[2],r[3]*content[3]};}
    static File safeFile(File root,String path)throws Exception{File f=new File(root,path);require(!path.contains("\\")&&!path.startsWith("/")&&f.getCanonicalPath().startsWith(root.getCanonicalPath()+File.separator),"配置包路径不安全");return f;}
    static void delete(File f){if(f.isDirectory()){File[] cs=f.listFiles();if(cs!=null)for(File c:cs)delete(c);}f.delete();}
    public static Pack current(Context c)throws Exception{File dir=new File(c.getFilesDir(),"pack");if(!dir.isDirectory())try(InputStream in=c.getAssets().open("default-pack.zip")){return install(c,in);}return new Pack(dir);}
    public static synchronized Pack install(Context c,InputStream in)throws Exception{
        File stage=new File(c.getFilesDir(),"pack-stage-"+UUID.randomUUID());require(stage.mkdirs(),"无法创建配置目录");
        try{
            long total=0;int count=0;Set<String> seen=new HashSet<>();
            try(ZipInputStream zip=new ZipInputStream(in)){ZipEntry e;byte[] buffer=new byte[8192];while((e=zip.getNextEntry())!=null){require(++count<=256,"ZIP 文件过多");File f=safeFile(stage,e.getName());if(e.isDirectory()){require(f.isDirectory()||f.mkdirs(),"创建目录失败");continue;}require(seen.add(f.getCanonicalPath()),"ZIP 含重复文件");require(f.getParentFile().isDirectory()||f.getParentFile().mkdirs(),"创建目录失败");try(OutputStream out=new FileOutputStream(f)){int n;while((n=zip.read(buffer))!=-1){total+=n;require(total<=50_000_000,"配置包解压超过 50MB");out.write(buffer,0,n);}}}}
            File root=stage;if(!new File(root,"manifest.json").isFile()){File[] ds=stage.listFiles(f->f.isDirectory()&&new File(f,"manifest.json").isFile());require(ds!=null&&ds.length==1,"找不到唯一的 manifest.json");root=ds[0];}
            new Pack(root);
            File dest=new File(c.getFilesDir(),"pack"),old=new File(c.getFilesDir(),"pack-backup");delete(old);
            boolean existed=dest.exists();if(existed)require(dest.renameTo(old),"无法备份旧配置");
            if(!root.renameTo(dest)){if(existed)old.renameTo(dest);throw new IOException("保存配置失败");}
            delete(old);return new Pack(dest);
        }finally{delete(stage);}
    }
}
