package com.saj.medicalvitals;

import android.app.Activity;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final String PREFS = "medical_vitals_store";
    private static final String RECORDS_KEY = "records";
    private static final String DOWNLOAD_FOLDER = Environment.DIRECTORY_DOWNLOADS + "/MedicalVitals";

    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.US);
    private final List<JSONObject> records = new ArrayList<>();

    private EditText date, time, pulse, spo2, systolic, diastolic, preMeal, postMeal, randomSugar, notes;
    private EditText rangeAmount, fromDate, toDate;
    private Spinner rangePreset, rangeUnit;
    private TextView history, count;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(15, 118, 110));
        loadRecords();
        setContentView(buildUi());
        setCurrentDateTime();
        applyPreset();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = vertical();
        root.setPadding(dp(16), dp(18), dp(16), dp(28));
        scroll.addView(root);

        TextView title = text("Medical Vitals Tracker", 27, true);
        title.setTextColor(Color.rgb(15, 118, 110));
        root.addView(title);
        root.addView(text("Your readings remain saved after closing the app. Export the selected date range as PDF or CSV.", 14, false));

        root.addView(section("Add reading"));
        LinearLayout row1 = horizontal();
        date = field("Date (YYYY-MM-DD)", false); time = field("Time (HH:MM)", false);
        row1.addView(date, weight()); row1.addView(time, weight()); root.addView(row1);

        LinearLayout row2 = horizontal();
        pulse = field("Pulse bpm", true); spo2 = field("SpO₂ %", true);
        row2.addView(pulse, weight()); row2.addView(spo2, weight()); root.addView(row2);

        LinearLayout row3 = horizontal();
        systolic = field("BP upper", true); diastolic = field("BP lower", true);
        row3.addView(systolic, weight()); row3.addView(diastolic, weight()); root.addView(row3);

        preMeal = field("Pre-meal sugar mg/dL", true);
        postMeal = field("Post-meal sugar mg/dL", true);
        randomSugar = field("Random sugar mg/dL", true);
        notes = field("Notes", false);
        root.addView(preMeal); root.addView(postMeal); root.addView(randomSugar); root.addView(notes);

        LinearLayout saveRow = horizontal();
        Button save = button("Save reading", true);
        Button now = button("Use current time", false);
        save.setOnClickListener(v -> saveRecord());
        now.setOnClickListener(v -> setCurrentDateTime());
        saveRow.addView(save, weight()); saveRow.addView(now, weight()); root.addView(saveRow);

        root.addView(section("Filter and download"));
        rangePreset = spinner(new String[]{"All data", "Today", "Last 7 days", "Last 30 days", "Last N weeks/months", "Custom dates"});
        root.addView(label("Date range")); root.addView(rangePreset);
        rangePreset.setOnItemSelectedListener(new SimpleItemSelected(() -> applyPreset()));

        LinearLayout rangeRow = horizontal();
        rangeAmount = field("N", true); rangeAmount.setText("2");
        rangeUnit = spinner(new String[]{"Weeks", "Months"});
        rangeRow.addView(rangeAmount, weight()); rangeRow.addView(rangeUnit, weight()); root.addView(rangeRow);
        rangeAmount.setOnFocusChangeListener((v, hasFocus) -> { if (!hasFocus) applyPreset(); });
        rangeUnit.setOnItemSelectedListener(new SimpleItemSelected(() -> applyPreset()));

        LinearLayout datesRow = horizontal();
        fromDate = field("From YYYY-MM-DD", false); toDate = field("To YYYY-MM-DD", false);
        datesRow.addView(fromDate, weight()); datesRow.addView(toDate, weight()); root.addView(datesRow);
        Button apply = button("Apply filter", false); apply.setOnClickListener(v -> renderHistory()); root.addView(apply);

        LinearLayout exportRow = horizontal();
        Button pdf = button("Download PDF", true); Button csv = button("Download CSV", false);
        pdf.setOnClickListener(v -> exportPdf()); csv.setOnClickListener(v -> exportCsv());
        exportRow.addView(pdf, weight()); exportRow.addView(csv, weight()); root.addView(exportRow);

        root.addView(section("Saved history"));
        count = text("0 readings", 14, true); root.addView(count);
        history = text("", 14, false);
        history.setTextIsSelectable(true);
        history.setPadding(dp(12), dp(10), dp(12), dp(10));
        history.setBackgroundColor(Color.rgb(240, 248, 247));
        root.addView(history, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button clear = button("Delete all saved readings", false);
        clear.setTextColor(Color.rgb(180, 35, 24));
        clear.setOnClickListener(v -> {
            if (records.isEmpty()) return;
            records.clear(); saveRecords(); renderHistory(); toast("All readings deleted");
        });
        root.addView(clear);

        root.addView(text("Alert limits copied from your sheet: pre-meal or random sugar below 60; any sugar above 350; pulse below 50; systolic BP below 90 or above 180. This app is a personal log, not medical advice.", 12, false));
        return scroll;
    }

    private void saveRecord() {
        if (date.getText().toString().trim().isEmpty() || time.getText().toString().trim().isEmpty()) {
            toast("Date and time are required"); return;
        }
        if (allReadingsBlank()) { toast("Enter at least one medical reading"); return; }
        try {
            JSONObject r = new JSONObject();
            r.put("id", System.currentTimeMillis());
            r.put("date", date.getText().toString().trim());
            r.put("time", time.getText().toString().trim());
            putNumber(r, "pulse", pulse); putNumber(r, "spo2", spo2);
            putNumber(r, "systolic", systolic); putNumber(r, "diastolic", diastolic);
            putNumber(r, "preMeal", preMeal); putNumber(r, "postMeal", postMeal);
            putNumber(r, "randomSugar", randomSugar);
            r.put("notes", notes.getText().toString().trim());
            records.add(r); saveRecords(); clearReadingFields(); setCurrentDateTime(); renderHistory(); toast("Reading saved");
        } catch (Exception e) { toast("Could not save reading"); }
    }

    private boolean allReadingsBlank() {
        return blank(pulse) && blank(spo2) && blank(systolic) && blank(diastolic) && blank(preMeal) && blank(postMeal) && blank(randomSugar);
    }

    private void putNumber(JSONObject o, String key, EditText input) throws Exception {
        String value = input.getText().toString().trim();
        if (value.isEmpty()) o.put(key, JSONObject.NULL); else o.put(key, Double.parseDouble(value));
    }

    private void applyPreset() {
        if (rangePreset == null) return;
        int selected = rangePreset.getSelectedItemPosition();
        boolean customN = selected == 4;
        boolean customDates = selected == 5;
        rangeAmount.setVisibility(customN ? View.VISIBLE : View.GONE);
        rangeUnit.setVisibility(customN ? View.VISIBLE : View.GONE);
        fromDate.setEnabled(customDates || selected == 0);
        toDate.setEnabled(customDates || selected == 0);

        Calendar today = Calendar.getInstance();
        Calendar from = (Calendar) today.clone();
        if (selected == 0) {
            fromDate.setText(""); toDate.setText("");
        } else if (selected == 1) {
            fromDate.setText(dateFormat.format(today.getTime())); toDate.setText(dateFormat.format(today.getTime()));
        } else if (selected == 2) {
            from.add(Calendar.DAY_OF_MONTH, -6); setRange(from, today);
        } else if (selected == 3) {
            from.add(Calendar.DAY_OF_MONTH, -29); setRange(from, today);
        } else if (selected == 4) {
            int n = safeInt(rangeAmount.getText().toString(), 1);
            if (rangeUnit.getSelectedItemPosition() == 1) from.add(Calendar.MONTH, -n); else from.add(Calendar.WEEK_OF_YEAR, -n);
            setRange(from, today);
        }
        renderHistory();
    }

    private void setRange(Calendar from, Calendar to) {
        fromDate.setText(dateFormat.format(from.getTime()));
        toDate.setText(dateFormat.format(to.getTime()));
    }

    private List<JSONObject> filtered() {
        String from = fromDate.getText().toString().trim();
        String to = toDate.getText().toString().trim();
        List<JSONObject> result = new ArrayList<>();
        for (JSONObject r : records) {
            String d = r.optString("date", "");
            if (!from.isEmpty() && d.compareTo(from) < 0) continue;
            if (!to.isEmpty() && d.compareTo(to) > 0) continue;
            result.add(r);
        }
        Collections.sort(result, (a, b) -> (b.optString("date") + b.optString("time")).compareTo(a.optString("date") + a.optString("time")));
        return result;
    }

    private void renderHistory() {
        if (history == null) return;
        List<JSONObject> list = filtered();
        count.setText(list.size() + (list.size() == 1 ? " reading" : " readings"));
        if (list.isEmpty()) { history.setText("No readings in this date range."); return; }
        StringBuilder text = new StringBuilder();
        for (JSONObject r : list) {
            text.append(r.optString("date")).append("  ").append(r.optString("time")).append("\n")
                    .append("Pulse/SpO₂: ").append(value(r,"pulse")).append(" / ").append(value(r,"spo2")).append("\n")
                    .append("BP: ").append(value(r,"systolic")).append(" / ").append(value(r,"diastolic")).append("\n")
                    .append("Sugar pre/post/random: ").append(value(r,"preMeal")).append(" / ").append(value(r,"postMeal")).append(" / ").append(value(r,"randomSugar")).append("\n");
            String note = r.optString("notes", ""); if (!note.isEmpty()) text.append("Notes: ").append(note).append("\n");
            String alert = alerts(r); if (!alert.isEmpty()) text.append("⚠ ").append(alert).append("\n");
            text.append("\n");
        }
        history.setText(text.toString());
    }

    private String alerts(JSONObject r) {
        List<String> a = new ArrayList<>();
        double p = r.optDouble("pulse", Double.NaN), sys = r.optDouble("systolic", Double.NaN);
        double pre = r.optDouble("preMeal", Double.NaN), post = r.optDouble("postMeal", Double.NaN), random = r.optDouble("randomSugar", Double.NaN);
        if (!Double.isNaN(pre) && pre < 60) a.add("pre-meal sugar below 60");
        if (!Double.isNaN(random) && random < 60) a.add("random sugar below 60");
        if ((!Double.isNaN(pre) && pre > 350) || (!Double.isNaN(post) && post > 350) || (!Double.isNaN(random) && random > 350)) a.add("sugar above 350");
        if (!Double.isNaN(p) && p < 50) a.add("pulse below 50");
        if (!Double.isNaN(sys) && (sys < 90 || sys > 180)) a.add("systolic BP outside 90–180");
        return String.join("; ", a);
    }

    private void exportCsv() {
        List<JSONObject> list = filtered();
        if (list.isEmpty()) { toast("No readings in selected range"); return; }
        StringBuilder csv = new StringBuilder("Date,Time,Pulse,SpO2,Systolic,Diastolic,PreMeal,PostMeal,Random,Notes,Alerts\n");
        for (JSONObject r : list) {
            csv.append(q(r.optString("date"))).append(',').append(q(r.optString("time"))).append(',')
                    .append(q(value(r,"pulse"))).append(',').append(q(value(r,"spo2"))).append(',')
                    .append(q(value(r,"systolic"))).append(',').append(q(value(r,"diastolic"))).append(',')
                    .append(q(value(r,"preMeal"))).append(',').append(q(value(r,"postMeal"))).append(',')
                    .append(q(value(r,"randomSugar"))).append(',').append(q(r.optString("notes"))).append(',').append(q(alerts(r))).append('\n');
        }
        writeFile("medical-vitals-" + dateFormat.format(new Date()) + ".csv", "text/csv", csv.toString().getBytes());
    }

    private void exportPdf() {
        List<JSONObject> list = filtered();
        if (list.isEmpty()) { toast("No readings in selected range"); return; }
        PdfDocument doc = new PdfDocument();
        Paint titlePaint = paint(18, true), bodyPaint = paint(9, false), alertPaint = paint(9, true); alertPaint.setColor(Color.rgb(180,35,24));
        int pageNo = 1, y = 0; PdfDocument.Page page = null; Canvas canvas = null;
        try {
            for (JSONObject r : list) {
                if (page == null || y > 540) {
                    if (page != null) doc.finishPage(page);
                    page = doc.startPage(new PdfDocument.PageInfo.Builder(842, 595, pageNo++).create());
                    canvas = page.getCanvas(); canvas.drawText("Medical Vitals Report", 28, 30, titlePaint);
                    canvas.drawText("Range: " + filterLabel() + "    Generated: " + new SimpleDateFormat("dd MMM yyyy HH:mm", Locale.US).format(new Date()), 28, 48, bodyPaint);
                    y = 72;
                }
                canvas.drawText(r.optString("date") + " " + r.optString("time") + "   Pulse/SpO2 " + value(r,"pulse") + "/" + value(r,"spo2") + "   BP " + value(r,"systolic") + "/" + value(r,"diastolic"), 28, y, bodyPaint); y += 14;
                canvas.drawText("Sugar pre/post/random: " + value(r,"preMeal") + " / " + value(r,"postMeal") + " / " + value(r,"randomSugar") + "   Notes: " + cut(r.optString("notes"), 70), 28, y, bodyPaint); y += 14;
                String alert = alerts(r); if (!alert.isEmpty()) { canvas.drawText("ALERT: " + cut(alert, 100), 28, y, alertPaint); y += 14; }
                y += 8;
            }
            if (page != null) doc.finishPage(page);
            String name = "medical-vitals-" + dateFormat.format(new Date()) + ".pdf";
            ContentValues values = values(name, "application/pdf");
            android.net.Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            try (OutputStream out = getContentResolver().openOutputStream(uri)) { doc.writeTo(out); }
            complete(uri); toast("PDF saved in Downloads/MedicalVitals");
        } catch (Exception e) { toast("Could not create PDF: " + e.getMessage()); }
        finally { doc.close(); }
    }

    private void writeFile(String name, String mime, byte[] data) {
        try {
            android.net.Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values(name, mime));
            try (OutputStream out = getContentResolver().openOutputStream(uri)) { out.write(data); }
            complete(uri); toast("Saved in Downloads/MedicalVitals");
        } catch (Exception e) { toast("Could not save file: " + e.getMessage()); }
    }

    private ContentValues values(String name, String mime) {
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name); v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, DOWNLOAD_FOLDER); v.put(MediaStore.MediaColumns.IS_PENDING, 1); return v;
    }
    private void complete(android.net.Uri uri) { ContentValues v = new ContentValues(); v.put(MediaStore.MediaColumns.IS_PENDING, 0); getContentResolver().update(uri, v, null, null); }

    private void loadRecords() {
        try {
            JSONArray array = new JSONArray(getSharedPreferences(PREFS, MODE_PRIVATE).getString(RECORDS_KEY, "[]"));
            for (int i=0;i<array.length();i++) records.add(array.getJSONObject(i));
        } catch (Exception ignored) { }
    }
    private void saveRecords() {
        JSONArray array = new JSONArray(); for (JSONObject r : records) array.put(r);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(RECORDS_KEY, array.toString()).apply();
    }

    private void setCurrentDateTime() { Date now = new Date(); date.setText(dateFormat.format(now)); time.setText(timeFormat.format(now)); }
    private void clearReadingFields() { pulse.setText(""); spo2.setText(""); systolic.setText(""); diastolic.setText(""); preMeal.setText(""); postMeal.setText(""); randomSugar.setText(""); notes.setText(""); }
    private String filterLabel() { String f=fromDate.getText().toString(), t=toDate.getText().toString(); return f.isEmpty()&&t.isEmpty()?"All data":(f.isEmpty()?"Any":f)+" to "+(t.isEmpty()?"Any":t); }
    private String value(JSONObject r, String key) { return !r.has(key) || r.isNull(key) ? "-" : trimNumber(r.optDouble(key)); }
    private String trimNumber(double n) { return n == Math.rint(n) ? String.valueOf((long)n) : String.valueOf(n); }
    private String q(String s) { return "\"" + (s==null?"":s.replace("\"","\"\"")) + "\""; }
    private String cut(String s, int max) { if (s==null) return ""; return s.length()<=max?s:s.substring(0,max-1)+"…"; }
    private boolean blank(EditText e) { return e.getText().toString().trim().isEmpty(); }
    private int safeInt(String s, int fallback) { try { return Math.max(1,Integer.parseInt(s.trim())); } catch(Exception e){ return fallback; } }
    private void toast(String m) { Toast.makeText(this, m, Toast.LENGTH_LONG).show(); }
    private Paint paint(float size, boolean bold) { Paint p=new Paint(Paint.ANTI_ALIAS_FLAG); p.setTextSize(size); p.setColor(Color.rgb(35,49,46)); p.setTypeface(Typeface.create(Typeface.SANS_SERIF,bold?Typeface.BOLD:Typeface.NORMAL)); return p; }

    private LinearLayout vertical() { LinearLayout l=new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout horizontal() { LinearLayout l=vertical(); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    private LinearLayout.LayoutParams weight() { LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT,1); p.setMargins(dp(3),dp(4),dp(3),dp(4)); return p; }
    private EditText field(String hint, boolean number) { EditText e=new EditText(this); e.setHint(hint); e.setTextSize(15); e.setSingleLine(true); e.setInputType(number?(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL):InputType.TYPE_CLASS_TEXT); e.setPadding(dp(10),dp(9),dp(10),dp(9)); e.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT)); return e; }
    private Spinner spinner(String[] items) { Spinner s=new Spinner(this); s.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,items)); s.setPadding(dp(6),dp(5),dp(6),dp(5)); return s; }
    private Button button(String text, boolean primary) { Button b=new Button(this); b.setText(text); b.setAllCaps(false); if(primary){b.setTextColor(Color.WHITE); b.setBackgroundColor(Color.rgb(15,118,110));} return b; }
    private TextView text(String text, float size, boolean bold) { TextView v=new TextView(this); v.setText(text); v.setTextSize(size); v.setTextColor(Color.rgb(35,49,46)); v.setTypeface(null,bold?Typeface.BOLD:Typeface.NORMAL); v.setPadding(dp(2),dp(5),dp(2),dp(5)); return v; }
    private TextView label(String text) { return text(text,13,true); }
    private TextView section(String text) { TextView v=text(text,20,true); v.setPadding(0,dp(22),0,dp(8)); v.setTextColor(Color.rgb(15,118,110)); return v; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private static class SimpleItemSelected implements android.widget.AdapterView.OnItemSelectedListener {
        private final Runnable action; SimpleItemSelected(Runnable action){this.action=action;}
        public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id){action.run();}
        public void onNothingSelected(android.widget.AdapterView<?> parent){}
    }
}
