package com.jpq.mobile;
import android.content.*;
import android.graphics.Color;
public final class Appearance {
    public int background=Color.rgb(241,239,232), rank=Color.rgb(37,34,29), number=Color.rgb(207,116,39), zero=Color.rgb(203,200,193), accent=Color.rgb(247,219,137), grid=Color.rgb(219,210,191);
    public float width=.35f,opacity=.96f;
    public void load(Context c){SharedPreferences p=c.getSharedPreferences("appearance",0);if(!p.getBoolean("compact_default_v1",false))p.edit().putFloat("width",.35f).putBoolean("compact_default_v1",true).apply();background=p.getInt("background",background);rank=p.getInt("rank",rank);number=p.getInt("number",number);zero=p.getInt("zero",zero);accent=p.getInt("accent",accent);grid=p.getInt("grid",grid);width=p.getFloat("width",width);opacity=p.getFloat("opacity",opacity);}
    public void save(Context c){c.getSharedPreferences("appearance",0).edit().putInt("background",background).putInt("rank",rank).putInt("number",number).putInt("zero",zero).putInt("accent",accent).putInt("grid",grid).putFloat("width",width).putFloat("opacity",opacity).apply();}
}
