package com.jpq.mobile;

import android.graphics.Bitmap;
import org.json.*;
import org.opencv.android.*;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;
import org.opencv.imgcodecs.Imgcodecs;
import java.util.*;

/** Matches in the original sampling coordinate system, then resolves row ownership. */
public final class Vision implements AutoCloseable {
    public static final class Hit {
        public final String rank;public final Rect rect;public final double score;
        public Hit(String rank,Rect rect,double score){this.rank=rank;this.rect=rect;this.score=score;}
    }
    public static final class Reading {
        public String key,name,seat;public List<Hit> hits=new ArrayList<>();public List<Hit> unresolved=new ArrayList<>();public boolean ambiguous;
        public List<String> ranks(){List<String> a=new ArrayList<>();for(Hit h:hits)a.add(h.rank);return a;}
    }
    public static final class Frame {
        public final List<Reading> hands=new ArrayList<>();public final List<Reading> cards=new ArrayList<>();public boolean start,end;public final List<String> features=new ArrayList<>();
    }
    private final Pack pack;
    private final Map<String,Mat> targets=new HashMap<>();
    public Vision(Pack pack)throws Exception{
        this.pack=pack;if(!OpenCVLoader.initLocal())throw new IllegalStateException("OpenCV 初始化失败");
        try{for(JSONObject d:pack.detectors.values())if(d.getString("type").equals("template.ccoeff_normed.v1")){
            Mat image=Imgcodecs.imread(Pack.safeFile(pack.folder,pack.resources.get(d.getString("resource_id")).getString("file")).toString());
            try{Mat t=preprocess(image,d.getJSONObject("params").getString("preprocess"));MatOfDouble mean=new MatOfDouble(),sd=new MatOfDouble();Core.meanStdDev(t,mean,sd);double max=0;for(double x:sd.toArray())max=Math.max(max,x);mean.release();sd.release();if(max<1){t.release();throw new IllegalArgumentException("模板缺少图像特征");}targets.put(d.getString("id"),t);}finally{image.release();}
        }}catch(Exception e){close();throw e;}
    }
    private static Mat preprocess(Mat image,String mode){Mat out=new Mat();if(mode.equals("color"))image.copyTo(out);else{Imgproc.cvtColor(image,out,Imgproc.COLOR_BGR2GRAY);if(mode.equals("otsu"))Imgproc.threshold(out,out,0,255,Imgproc.THRESH_BINARY|Imgproc.THRESH_OTSU);}return out;}
    private Rect rect(JSONArray a)throws Exception{double[] v=pack.sourceRect(a);int x=Math.max(0,(int)Math.floor(v[0]+1e-6)),y=Math.max(0,(int)Math.floor(v[1]+1e-6));int right=Math.min(pack.width,(int)Math.ceil(v[0]+v[2]-1e-6)),bottom=Math.min(pack.height,(int)Math.ceil(v[1]+v[3]-1e-6));return new Rect(x,y,right-x,bottom-y);}
    private List<Hit> match(Mat image,JSONObject d,Rect area,String rank,int limit)throws Exception{
        Mat raw=image.submat(area),scene=preprocess(raw,d.getJSONObject("params").getString("preprocess")),scores=new Mat();raw.release();List<Hit> result=new ArrayList<>();Mat target=targets.get(d.getString("id"));
        try{if(target.cols()>scene.cols()||target.rows()>scene.rows())return result;
            Imgproc.matchTemplate(scene,target,scores,Imgproc.TM_CCOEFF_NORMED);double threshold=d.getJSONObject("params").getDouble("threshold");
            for(int i=0;i<limit;i++){Core.MinMaxLocResult mm=Core.minMaxLoc(scores);if(!Double.isFinite(mm.maxVal)||mm.maxVal<threshold)break;int x=(int)mm.maxLoc.x,y=(int)mm.maxLoc.y;result.add(new Hit(rank,new Rect(area.x+x,area.y+y,target.cols(),target.rows()),mm.maxVal));int rx=Math.max(1,target.cols()/2),ry=Math.max(1,target.rows()/2);Rect suppress=new Rect(Math.max(0,x-rx),Math.max(0,y-ry),Math.min(scores.cols(),x+rx+1)-Math.max(0,x-rx),Math.min(scores.rows(),y+ry+1)-Math.max(0,y-ry));Mat part=scores.submat(suppress);part.setTo(new Scalar(-1));part.release();}
            return result;
        }finally{scene.release();scores.release();}
    }
    public Frame analyze(Bitmap bitmap,double[] viewport)throws Exception{
        int x=(int)(viewport[0]*bitmap.getWidth()),y=(int)(viewport[1]*bitmap.getHeight()),w=(int)(viewport[2]*bitmap.getWidth()),h=(int)(viewport[3]*bitmap.getHeight());
        if(w<=0||h<=0||x<0||y<0||x+w>bitmap.getWidth()||y+h>bitmap.getHeight())throw new IllegalArgumentException("请校准游戏画面范围");
        double scale=Math.min(w/pack.content[2],h/pack.content[3]);
        if(Math.abs(w/(double)h-pack.content[2]/pack.content[3])/(pack.content[2]/pack.content[3])>.035)throw new IllegalArgumentException("画面比例不同，请在主页校准游戏范围");
        int cw=(int)Math.round(pack.content[2]*scale),ch=(int)Math.round(pack.content[3]*scale);x+=(w-cw)/2;y+=(h-ch)/2;
        Mat rgba=new Mat(),bgr=new Mat(),normalized=Mat.zeros(pack.height,pack.width,CvType.CV_8UC3);
        try{Utils.bitmapToMat(bitmap,rgba);Mat crop=rgba.submat(new Rect(x,y,cw,ch));Imgproc.cvtColor(crop,bgr,Imgproc.COLOR_RGBA2BGR);crop.release();Mat target=normalized.submat(new Rect((int)pack.content[0],(int)pack.content[1],(int)pack.content[2],(int)pack.content[3]));Imgproc.resize(bgr,target,target.size(),0,0,Imgproc.INTER_LINEAR);target.release();return analyzeSource(normalized);}finally{rgba.release();bgr.release();normalized.release();}
    }
    public Frame analyzeSource(Mat image)throws Exception{
        Frame frame=new Frame();Map<String,Boolean> found=new HashMap<>();JSONArray states=pack.json.getJSONArray("states");
        // End and start both evaluated to preserve the raw start edge for the end latch.
        for(int i=0;i<states.length();i++){JSONObject s=states.getJSONObject(i);boolean hit=false;JSONArray ids=s.getJSONArray("detector_ids");for(int j=0;j<ids.length();j++){String id=ids.getString(j);if(!found.containsKey(id))found.put(id,detectState(image,pack.detectors.get(id)));hit|=found.get(id);}if(hit){frame.features.add(s.getString("name"));if(s.getString("purpose").equals("round_end"))frame.end=true;if(s.getString("purpose").equals("new_round_candidate"))frame.start=true;}}
        for(JSONObject r:pack.regions.values()){
            JSONObject sem=r.getJSONObject("semantic");boolean hand=sem.optString("role").equals("hand")&&sem.optString("group").equals("me");if(!hand&&!sem.optString("role").equals("played"))continue;
            Reading reading=new Reading();reading.key=r.getString("id");reading.name=r.getString("name");reading.seat=sem.getString("seat_id");Rect area=rect(r.getJSONArray("rect"));
            for(JSONObject d:pack.detectors.values())if(d.getString("type").equals("template.ccoeff_normed.v1")){
                JSONObject res=pack.resources.get(d.getString("resource_id")),rs=res.getJSONObject("semantic");
                if(!rs.optString("role").equals(sem.optString("role"))||!rs.optString("group").equals(sem.optString("group")))continue;
                if(!rs.isNull("seat_id")&&!rs.optString("seat_id").equals(reading.seat))continue;
                if(!d.isNull("region_id")&&!d.getString("region_id").equals(r.getString("id")))continue;
                reading.hits.addAll(match(image,d,area,hand?rs.getString("suit")+":"+rs.getString("rank"):rs.getString("rank"),100));
            }
            reading.hits.sort(Comparator.comparingDouble((Hit a)->-a.score));List<Hit> clean=new ArrayList<>();for(Hit a:reading.hits)if(clean.stream().noneMatch(b->same(a,b)))clean.add(a);clean.sort(Comparator.comparingInt(a->a.rect.x));reading.hits=clean;if(hand)frame.hands.add(reading);else frame.cards.add(reading);
        }
        assign(frame.cards);return frame;
    }
    private boolean detectState(Mat image,JSONObject d)throws Exception{
        if(d.getString("type").equals("template.ccoeff_normed.v1")){Rect area=d.isNull("region_id")?new Rect((int)pack.content[0],(int)pack.content[1],(int)pack.content[2],(int)pack.content[3]):rect(pack.regions.get(d.getString("region_id")).getJSONArray("rect"));return !match(image,d,area,"",1).isEmpty();}
        JSONObject p=d.getJSONObject("params");JSONArray colors=p.getJSONArray("colors");boolean[] ok=new boolean[3];int enabled=0;Mat area=image.submat(rect(d.getJSONArray("rect"))),mask=new Mat();
        try{for(int i=0;i<3;i++){JSONObject c=colors.getJSONObject(i);if(!c.optBoolean("enabled",true))continue;enabled++;int rgb=Integer.parseInt(c.getString("hex").substring(1),16),tol=p.getInt("tolerance");int red=(rgb>>16)&255,green=(rgb>>8)&255,blue=rgb&255;Core.inRange(area,new Scalar(Math.max(0,blue-tol),Math.max(0,green-tol),Math.max(0,red-tol)),new Scalar(Math.min(255,blue+tol),Math.min(255,green+tol),Math.min(255,red+tol)),mask);ok[i]=Core.countNonZero(mask)/(double)area.total()>=p.getDouble("min_ratio");}
            if(enabled==3)return ok[0]&&(ok[1]||ok[2]);int n=0;for(boolean a:ok)if(a)n++;return n==enabled;
        }finally{area.release();mask.release();}
    }
    static boolean same(Hit a,Hit b){Rect r=a.rect,s=b.rect;double overlap=Math.max(0,Math.min(r.x+r.width,s.x+s.width)-Math.max(r.x,s.x))*Math.max(0,Math.min(r.y+r.height,s.y+s.height)-Math.max(r.y,s.y));return overlap/Math.min(r.area(),s.area())>.5;}
    private static List<Hit> fit(List<Hit> hits,double start,double step,int n,double tol){List<Hit> result=new ArrayList<>();for(int j=0;j<n;j++){double x=start+j*step;Hit best=null;for(Hit h:hits)if(!result.contains(h)&&Math.abs(h.rect.x-x)<=tol&&(best==null||Math.abs(h.rect.x-x)<Math.abs(best.rect.x-x)))best=h;if(best!=null)result.add(best);}return result;}
    private static double error(List<Hit> hits,double start,double step){double e=0;for(Hit h:hits){double nearest=start+Math.rint((h.rect.x-start)/step)*step;e+=Math.abs(h.rect.x-nearest);}return e;}
    private static final class Proposal{Reading owner;Hit hit;int strength;double error;Proposal(Reading r,Hit h,int s,double e){owner=r;hit=h;strength=s;error=e;}}
    private void assign(List<Reading> readings)throws Exception{
        List<Proposal> proposals=new ArrayList<>();
        for(Reading r:readings){JSONObject rule=pack.rows.optJSONObject(r.key);if(rule==null)continue;List<Hit> original=r.hits;r.hits=new ArrayList<>();double tol=rule.getDouble("tolerance"),step=rule.getDouble("step_x"),anchor=rule.getDouble("anchor_x"),origin=0;int cap=rule.getInt("capacity");JSONArray ys=rule.getJSONArray("row_y");boolean full=true;
            for(int line=0;line<ys.length();line++){double y=ys.getDouble(line);List<Hit> candidates=new ArrayList<>();for(Hit hit:original)if(Math.abs(hit.rect.y-y)<=tol)candidates.add(hit);if(candidates.isEmpty()){full=false;continue;}if(!full){r.unresolved.addAll(candidates);continue;}
                List<Hit> selected=new ArrayList<>();int nSelected=0;
                if(line==0){boolean complete=false;double bestError=Double.POSITIVE_INFINITY;int bestN=Integer.MAX_VALUE;double align=switch(rule.getString("align")){case "left"->0;case "right"->1;default->.5;};
                    for(int n=1;n<=cap;n++){double start=anchor-(n-1)*step*align;List<Hit> test=fit(candidates,start,step,n,tol);boolean all=test.size()==n;double err=error(test,start,step);if((all&&!complete)||(all==complete&&(test.size()>selected.size()||(test.size()==selected.size()&&(err<bestError||(err==bestError&&n<bestN)))))){selected=test;complete=all;bestError=err;bestN=n;origin=start;nSelected=n;}}
                }else{for(int j=0;j<cap;j++){List<Hit> slot=fit(candidates,origin+j*step,step,1,tol);if(slot.isEmpty())break;selected.add(slot.get(0));}nSelected=selected.size();}
                full=selected.size()==cap&&nSelected==cap;r.hits.addAll(selected);for(Hit hit:selected)proposals.add(new Proposal(r,hit,line==0?1:2,Math.abs(hit.rect.y-y)));for(Hit hit:candidates)if(!selected.contains(hit))r.unresolved.add(hit);
            }
        }
        Set<Proposal> rejected=new HashSet<>();for(int i=0;i<proposals.size();i++)for(int j=i+1;j<proposals.size();j++){Proposal a=proposals.get(i),b=proposals.get(j);if(a.owner==b.owner||!same(a.hit,b.hit))continue;if(a.strength!=b.strength)rejected.add(a.strength>b.strength?b:a);else if(Math.abs(a.error-b.error)>3)rejected.add(a.error<b.error?b:a);else{rejected.add(a);rejected.add(b);a.owner.ambiguous=true;b.owner.ambiguous=true;}}
        for(Proposal p:rejected)p.owner.hits.remove(p.hit);
        for(Reading r:readings)r.unresolved.removeIf(hit->proposals.stream().anyMatch(p->!rejected.contains(p)&&same(hit,p.hit)));
    }
    public void close(){for(Mat t:targets.values())t.release();targets.clear();}
}
