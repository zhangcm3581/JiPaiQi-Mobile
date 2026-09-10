package com.jpq.mobile;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import java.io.IOException;

/** Two identity fields, drawn inside the floating control window. */
final class IdentitySettingsView extends LinearLayout {
    IdentitySettingsView(Context context,Runnable saved,Runnable dismiss) {
        super(context);
        setOrientation(VERTICAL);setPadding(dp(12),dp(10),dp(12),dp(8));
        setContentDescription("悬浮栏设置区");
        TextView heading=new TextView(context);heading.setText("设置");
        heading.setTextSize(13);heading.setTextColor(0xFFF3F7FB);heading.setTypeface(null,Typeface.BOLD);
        addView(heading,new LayoutParams(-1,dp(22)));
        var prefs=context.getSharedPreferences("hand_upload",0);
        EditText tenant=field("租户 ID","同组设备相同",prefs.getString("tenant_id",""),InputType.TYPE_CLASS_NUMBER);
        EditText client=field("客户端 ID","每台设备不同",prefs.getString("client_id",""),InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        LinearLayout actions=new LinearLayout(context);actions.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
        Button cancel=button("取消",false);cancel.setOnClickListener(v->dismiss.run());
        Button save=button("保存",true);save.setOnClickListener(v->{
            try {
                String tenantId=tenant.getText().toString().trim(),clientId=client.getText().toString().trim();
                RoundSocket.url(BuildConfig.SERVER_URL,tenantId,clientId);
                if(!prefs.edit().putString("tenant_id",tenantId).putString("client_id",clientId)
                        .remove("endpoint").remove("enabled").commit())throw new IOException("保存失败，请重试");
                saved.run();dismiss.run();
            } catch(Exception error) {Toast.makeText(context,error.getMessage(),Toast.LENGTH_LONG).show();}
        });
        actions.addView(cancel,new LayoutParams(dp(56),dp(32)));
        LayoutParams saveSize=new LayoutParams(dp(56),dp(32));saveSize.leftMargin=dp(6);
        actions.addView(save,saveSize);addView(actions,new LayoutParams(-1,dp(36)));
    }
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private EditText field(String title,String hint,String value,int inputType) {
        LinearLayout row=new LinearLayout(getContext());row.setGravity(Gravity.CENTER_VERTICAL);
        TextView label=new TextView(getContext());label.setText(title);label.setTextSize(11);label.setTextColor(0xFFB7CADD);
        row.addView(label,new LayoutParams(dp(64),-2));
        EditText edit=new EditText(getContext());edit.setId(View.generateViewId());label.setLabelFor(edit.getId());
        edit.setTextSize(14);edit.setTextColor(0xFFF3F7FB);edit.setHintTextColor(0xFF93ABBF);
        edit.setSingleLine(true);edit.setInputType(inputType);edit.setHint(hint);edit.setText(value);
        edit.setPadding(dp(9),0,dp(9),0);edit.setSelectAllOnFocus(true);
        edit.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        GradientDrawable input=new GradientDrawable();input.setColor(0x1820BFE0);input.setCornerRadius(dp(7));
        edit.setBackground(input);edit.setBackgroundTintList(null);
        row.addView(edit,new LayoutParams(0,dp(34),1));addView(row,new LayoutParams(-1,dp(42)));
        return edit;
    }
    private Button button(String title,boolean primary) {
        Button button=new Button(getContext());button.setText(title);button.setTextSize(12);button.setAllCaps(false);
        button.setTextColor(primary?0xFFF3F7FB:0xFFB7CADD);button.setPadding(0,0,0,0);
        button.setMinWidth(0);button.setMinimumWidth(0);button.setMinHeight(0);button.setMinimumHeight(0);
        GradientDrawable background=new GradientDrawable();background.setColor(primary?0xFF356586:0x00000000);background.setCornerRadius(dp(8));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x337AB9E3),background,null));
        return button;
    }
}
