package com.boomys.jarvis;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * J.A.R.V.I.S. — kırmızı temalı Türkçe sesli asistan (native Android).
 * Kamera, mikrofon, konum, flaş, uygulama açma ve AI beyni tek Activity'de.
 */
public class MainActivity extends Activity implements RecognitionListener {

    // ---- renkler ----
    private static final int C_BG = 0xFF070305;
    private static final int C_RED = 0xFFFF2B3D;
    private static final int C_RED_SOFT = 0xFFFF5F6D;
    private static final int C_TEXT = 0xFFFFE9EC;
    private static final int C_MUTED = 0xFFC98F97;
    private static final int C_CARD = 0xFF16070A;

    // ---- AI beyni (sunucudaki Worker) ----
    private static final String AI_URL =
            "https://site-2c429ebe943e42499156764fc0b8ff73.freebuff.page/api/chat";
    private static final String AI_STATUS_URL =
            "https://site-2c429ebe943e42499156764fc0b8ff73.freebuff.page/api/status";

    private static final int REQ_MIC = 101;
    private static final int REQ_CAMERA_PERM = 102;
    private static final int REQ_LOCATION = 103;
    private static final int REQ_NOTIF = 104;
    private static final int REQ_CAMERA_INTENT = 201;

    private LinearLayout chat;
    private ScrollView chatScroll;
    private TextView subText;
    private TextView micButton;
    private EditText input;
    private ReactorView reactor;

    private TextToSpeech tts;
    private boolean ttsReady = false;

    private SpeechRecognizer recognizer;
    private Intent recIntent;
    private boolean listening = false;
    private boolean awaitingCommand = false;
    private boolean destroyed = false;

    private boolean voiceOn = true;
    private boolean wakeOn = false;
    private boolean torchOn = false;

    private SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<String[]> history = new ArrayList<>();
    private final List<String[]> notes = new ArrayList<>();
    private Runnable pendingAction;

    /* ============================ yaşam döngüsü ============================ */

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("jarvis", MODE_PRIVATE);
        voiceOn = prefs.getBoolean("voice", true);
        wakeOn = prefs.getBoolean("wake", false);
        loadNotes();

        getWindow().setStatusBarColor(C_BG);
        getWindow().setNavigationBarColor(C_BG);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        buildUi();
        initTts();
        initRecognizer();
        addBubble("Efendim, emrinizdeyim. Mikrofona dokunun ya da yazın.", false);
        setState(0);
        checkAiStatus();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        if (recognizer != null) {
            try {
                recognizer.destroy();
            } catch (Exception ignored) {
            }
        }
        if (tts != null) {
            try {
                tts.stop();
                tts.shutdown();
            } catch (Exception ignored) {
            }
        }
        super.onDestroy();
    }

    /* ============================ arayüz ============================ */

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);
        root.setPadding(dp(16), dp(18), dp(16), dp(14));

        TextView brand = new TextView(this);
        brand.setText("J . A . R . V . I . S");
        brand.setTextColor(C_RED_SOFT);
        brand.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        brand.setLetterSpacing(0.18f);
        brand.setTextSize(14);
        root.addView(brand);

        reactor = new ReactorView(this);
        root.addView(reactor, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(210)));

        subText = new TextView(this);
        subText.setText("Efendim, emrinizdeyim.");
        subText.setTextColor(C_MUTED);
        subText.setTextSize(12.5f);
        subText.setGravity(Gravity.CENTER);
        subText.setPadding(0, dp(2), 0, dp(10));
        root.addView(subText);

        chatScroll = new ScrollView(this);
        chatScroll.setFillViewport(true);
        chat = new LinearLayout(this);
        chat.setOrientation(LinearLayout.VERTICAL);
        chatScroll.addView(chat, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(chatScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // yazma alanı
        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        inputRow.setPadding(0, dp(8), 0, dp(6));

        input = new EditText(this);
        input.setHint("Yazın efendim…");
        input.setHintTextColor(0x99C98F97);
        input.setTextColor(C_TEXT);
        input.setTextSize(14);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setBackground(roundBg(C_CARD, 0x55FF2B3D, 22));
        input.setPadding(dp(14), dp(10), dp(14), dp(10));
        inputRow.addView(input, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button send = new Button(this);
        send.setText("↑");
        send.setTextSize(16);
        send.setTextColor(Color.WHITE);
        send.setBackground(roundBg(C_RED, 0, 22));
        send.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(46), dp(44));
        sp.leftMargin = dp(8);
        inputRow.addView(send, sp);
        send.setOnClickListener(v -> submit(input.getText().toString()));
        input.setOnEditorActionListener((v, actionId, event) -> {
            submit(input.getText().toString());
            return true;
        });
        root.addView(inputRow);

        // kontrol satırı
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);

        Button settings = smallButton("AYARLAR");
        settings.setOnClickListener(v -> showSettings());
        controls.addView(settings);
        controls.addView(spacer());

        micButton = new TextView(this);
        micButton.setText("🎙");
        micButton.setTextSize(26);
        micButton.setGravity(Gravity.CENTER);
        micButton.setTextColor(Color.WHITE);
        micButton.setBackground(roundBg(C_RED, 0x88FFD9DE, 40));
        micButton.setOnClickListener(v -> {
            if (listening) stopListening();
            else startListening();
        });
        controls.addView(micButton, new LinearLayout.LayoutParams(dp(76), dp(76)));
        controls.addView(spacer());

        Button stop = smallButton("DUR");
        stop.setOnClickListener(v -> {
            stopListening();
            if (ttsReady) tts.stop();
            setState(0);
            subText.setText("Efendim, emrinizdeyim.");
        });
        controls.addView(stop);

        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.topMargin = dp(4);
        root.addView(controls, cp);

        setContentView(root);
    }

    private View spacer() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        return v;
    }

    private GradientDrawable roundBg(int fill, int stroke, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        if (stroke != 0) d.setStroke(dp(1), stroke);
        return d;
    }

    private Button smallButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(11);
        b.setTextColor(C_RED_SOFT);
        b.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        b.setBackgroundTintList(ColorStateList.valueOf(C_CARD));
        b.setMinWidth(dp(72));
        b.setMinHeight(dp(44));
        return b;
    }

    private void addBubble(String text, boolean user) {
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        wrapper.setGravity(user ? Gravity.END : Gravity.START);

        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setTextColor(user ? Color.WHITE : C_TEXT);
        tv.setPadding(dp(12), dp(9), dp(12), dp(9));
        tv.setBackground(roundBg(user ? 0x22FFFFFF : C_CARD, user ? 0x22FFFFFF : 0x55FF2B3D, 14));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.topMargin = dp(4);
        tp.bottomMargin = dp(4);
        wrapper.addView(tv, tp);

        chat.addView(wrapper);
        scrollToBottom();
    }

    private void addImage(Bitmap bmp) {
        ImageView iv = new ImageView(this);
        iv.setImageBitmap(bmp);
        iv.setAdjustViewBounds(true);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(200), dp(200));
        p.topMargin = dp(4);
        chat.addView(iv, p);
        scrollToBottom();
    }

    private void scrollToBottom() {
        chatScroll.post(() -> chatScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void setState(int s) {
        reactor.setState(s);
        if (s == 1) {
            micButton.setBackground(roundBg(0xFFFF6A75, 0xCCFFFFFF, 40));
        } else {
            micButton.setBackground(roundBg(C_RED, 0x88FFD9DE, 40));
        }
    }

    /* ============================ sesli yanıt ============================ */

    private void initTts() {
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int r = tts.setLanguage(new Locale("tr", "TR"));
                tts.setSpeechRate(1.0f);
                tts.setPitch(0.92f);
                ttsReady = !(r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED);
            }
        });
    }

    private void speak(String text) {
        subText.setText(text.length() > 120 ? text.substring(0, 117) + "…" : text);
        if (!voiceOn || !ttsReady) return;
        setState(3);
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis");
        main.postDelayed(() -> {
            if (listening) setState(1);
            else setState(0);
        }, Math.min(6000, 700 + text.length() * 55L));
    }

    private void say(String text) {
        addBubble(text, false);
        speak(text);
    }

    /* ============================ konuşma tanıma ============================ */

    private void initRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            addBubble("Bu cihazda konuşma tanıma yok efendim; yazarak komut verebilirsiniz.", false);
            return;
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(this);

        recIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        recIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        recIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR");
        recIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "tr-TR");
        recIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        recIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
    }

    private void startListening() {
        if (recognizer == null) {
            say("Konuşma tanıma hazır değil efendim, yazarak komut verebilirsiniz.");
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        try {
            recognizer.cancel();
            recognizer.startListening(recIntent);
            listening = true;
            setState(1);
            subText.setText("Dinliyorum efendim…");
        } catch (Exception e) {
            listening = false;
            setState(0);
        }
    }

    private void stopListening() {
        listening = false;
        awaitingCommand = false;
        if (recognizer != null) {
            try {
                recognizer.cancel();
            } catch (Exception ignored) {
            }
        }
        setState(0);
        subText.setText("Efendim, emrinizdeyim.");
    }

    @Override
    public void onReadyForSpeech(Bundle params) {
        listening = true;
        setState(1);
    }

    @Override
    public void onBeginningOfSpeech() {
        subText.setText("Dinliyorum efendim…");
    }

    @Override
    public void onRmsChanged(float rmsdB) {
    }

    @Override
    public void onBufferReceived(byte[] buffer) {
    }

    @Override
    public void onEndOfSpeech() {
        listening = false;
        setState(2);
    }

    @Override
    public void onError(int error) {
        listening = false;
        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
            setState(0);
            say("Mikrofon izni gerekiyor efendim.");
            return;
        }
        setState(0);
        if (wakeOn && !destroyed) main.postDelayed(this::startListening, 900);
    }

    @Override
    public void onResults(Bundle results) {
        listening = false;
        ArrayList<String> list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        String text = (list != null && !list.isEmpty()) ? list.get(0) : "";
        if (!text.trim().isEmpty()) onHeard(text.trim());
        if (wakeOn && !destroyed && !listening) main.postDelayed(this::startListening, 700);
        else setState(0);
    }

    @Override
    public void onPartialResults(Bundle partialResults) {
        ArrayList<String> list = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (list != null && !list.isEmpty()) subText.setText(list.get(0));
    }

    @Override
    public void onEvent(int eventType, Bundle params) {
    }

    private void onHeard(String text) {
        if (wakeOn && !awaitingCommand) {
            String t = norm(text);
            if (!(t.contains("jarvis") || t.contains("carvis") || t.contains("jarvıs"))) return;
            awaitingCommand = true;
            addBubble(text, true);
            say("Efendim?");
            return;
        }
        awaitingCommand = false;
        submit(text);
    }

    /* ============================ giriş akışı ============================ */

    private void submit(String raw) {
        final String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) return;
        input.setText("");
        stopListening();
        addBubble(text, true);

        if (!runCommand(text)) askAi(text);
    }

    /* ============================ komut motoru ============================ */

    private static String norm(String s) {
        return s.toLowerCase(new Locale("tr", "TR"))
                .replace('\u2019', '\'')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private boolean has(String t, String... words) {
        for (String w : words) if (t.contains(w)) return true;
        return false;
    }

    private boolean runCommand(String raw) {
        String t = norm(raw);

        // selamlama / kimlik
        if (t.matches("^(selam|merhaba|hey|günaydın|iyi akşamlar|alo)\\b.*") || t.equals("jarvis")) {
            say("Efendim, hoş geldiniz. Emrinizdeyim.");
            return true;
        }
        if (has(t, "sen kimsin", "adın ne", "kimsin", "kendini tanıt")) {
            say("Ben J.A.R.V.I.S., efendim. Sizin kişisel asistanınızım. Sesli komut verin; uygulama açayım, kamera, flaş, konum, not ve çağrı işlerinizi yapayım.");
            return true;
        }
        if (has(t, "teşekkür", "sağ ol", "sağol", "eyvallah")) {
            say("Rica ederim efendim. Her zaman.");
            return true;
        }

        // saat / tarih
        if (has(t, "saat kaç", "saati söyle") || t.equals("saat")) {
            String s = new SimpleDateFormat("HH:mm", new Locale("tr", "TR")).format(new Date());
            say("Saat " + s + " efendim.");
            return true;
        }
        if (has(t, "tarih", "günlerden", "hangi gün")) {
            String d = new SimpleDateFormat("d MMMM yyyy, EEEE", new Locale("tr", "TR")).format(new Date());
            say("Bugün " + d + " efendim.");
            return true;
        }

        // pil
        if (has(t, "pil", "şarj", "batarya")) {
            Intent b = registerReceiver(null, new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b == null) {
                say("Pil bilgisini okuyamadım efendim.");
                return true;
            }
            int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int pct = scale > 0 ? (int) (level * 100f / scale) : -1;
            int st = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            boolean charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL;
            say("Pil yüzde " + pct + " efendim, " + (charging ? "şarj oluyor." : "şarjda değil."));
            return true;
        }

        // fotoğraf / kamera
        if (has(t, "fotoğraf çek", "resim çek", "selfie", "foto çek")) {
            takePhoto();
            return true;
        }
        if (has(t, "kamerayı aç", "kamera aç", "kamerayı başlat") || t.equals("kamera")) {
            takePhoto();
            say("Kamerayı açıyorum efendim.");
            return true;
        }

        // flaş
        if (has(t, "flaş", "fener")) {
            toggleTorch();
            return true;
        }

        // konum
        if (has(t, "konum", "neredeyim")) {
            findLocation(false);
            return true;
        }
        if (has(t, "haritada göster", "haritayı aç")) {
            findLocation(true);
            return true;
        }

        // notlar
        if (has(t, "not al")) {
            String note = raw.replaceAll("(?i).*?not al[\\s:]*", "").trim();
            if (note.isEmpty()) {
                say("Ne not alacağımı söyleyin efendim.");
                return true;
            }
            notes.add(new String[]{note, String.valueOf(System.currentTimeMillis())});
            saveNotes();
            say("Not aldım efendim: " + note);
            return true;
        }
        if (has(t, "notlarım", "notları oku", "notları göster")) {
            if (notes.isEmpty()) {
                say("Henüz not almamışsınız efendim.");
                return true;
            }
            StringBuilder sb = new StringBuilder("Efendim, " + notes.size() + " notunuz var:\n");
            for (int i = Math.max(0, notes.size() - 8); i < notes.size(); i++) {
                sb.append("• ").append(notes.get(i)[0]).append("\n");
            }
            addBubble(sb.toString().trim(), false);
            speak("Efendim, " + notes.size() + " notunuz var. Son notunuz: " + notes.get(notes.size() - 1)[0]);
            return true;
        }
        if (has(t, "notları sil", "notları temizle")) {
            notes.clear();
            saveNotes();
            say("Tüm notları sildim efendim.");
            return true;
        }

        // şaka
        if (has(t, "şaka", "fıkra")) {
            String[] jokes = {
                    "Efendim, yapay zekâlar tatil yapmaz; çünkü sürekli işlem halindedir.",
                    "Efendim, bilgisayar neden denize girmiş? Dalgaları yakalamak için.",
                    "Efendim, bir programcı karanlıkta neden yürüyemez? Yolu debug etmesi gerekir."
            };
            say(jokes[(int) (Math.random() * jokes.length)]);
            return true;
        }

        // arama
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(youtube'?da|youtube|google'?da|googleda|internette)\\s+(.+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(raw);
        if (m.find()) {
            String q = m.group(2).replaceAll("(?i)\\s+(ara|bul)$", "").trim();
            boolean yt = m.group(1).toLowerCase(Locale.ROOT).contains("youtube");
            openUrl((yt ? "https://www.youtube.com/results?search_query=" : "https://www.google.com/search?q=")
                    + Uri.encode(q));
            say((yt ? "YouTube" : "Google") + "'da " + q + " arıyorum efendim.");
            return true;
        }

        // çağrı / mesaj
        java.util.regex.Matcher num = java.util.regex.Pattern.compile("(\\d[\\d\\s]{5,})").matcher(raw);
        String number = num.find() ? num.group(1).replaceAll("\\s+", "") : null;
        if (number != null && has(t, "ara", "telefon et")) {
            startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + number)));
            say("Aramayı başlatıyorum efendim.");
            return true;
        }
        if (has(t, "mesaj at", "sms at", "sms gönder")) {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("sms:" + (number == null ? "" : number))));
            say("Mesaj uygulamasını açıyorum efendim.");
            return true;
        }

        // uygulama açma
        boolean wantsOpen = has(t, "aç", "başlat", "çalıştır", "gir");
        for (Map.Entry<String, String[]> e : APPS.entrySet()) {
            if (t.contains(e.getKey()) && (wantsOpen || t.split(" ").length <= 3)) {
                String[] v = e.getValue(); // {etiket, paket, şema, web}
                launchApp(v[0], v[1], v[2], v[3]);
                say(v[0] + " açılıyor efendim.");
                return true;
            }
        }

        if (has(t, "müzik aç", "şarkı aç", "müzik çal", "şarkı çal")) {
            String track = raw.replaceAll("(?i).*?(müzik|şarkı)\\s*(aç|çal)?\\s*", "").trim();
            openUrl(track.isEmpty() ? "https://open.spotify.com"
                    : "https://www.youtube.com/results?search_query=" + Uri.encode(track));
            say(track.isEmpty() ? "Müzik uygulamasını açıyorum efendim." : track + " çalıyorum efendim.");
            return true;
        }

        if (has(t, "yardım", "neler yapabilirsin", "komutlar")) {
            say("Efendim; WhatsApp veya istediğiniz uygulamayı açabilirim, kamera ve flaşı çalıştırıp fotoğraf çekebilirim, saat, tarih, pil ve konum söyleyebilirim, not alıp okuyabilirim, arama yapabilirim.");
            addBubble("WhatsApp'ı aç · kamerayı aç · fotoğraf çek · flaşı aç · not al … · pil durumu · konumum neresi", false);
            return true;
        }

        return false;
    }

    /* ============================ uygulama açma ============================ */

    private static final Map<String, String[]> APPS = new HashMap<>();

    static {
        APPS.put("whatsapp", new String[]{"WhatsApp", "com.whatsapp", "whatsapp://send", "https://wa.me/"});
        APPS.put("instagram", new String[]{"Instagram", "com.instagram.android", "instagram://app", "https://instagram.com"});
        APPS.put("youtube", new String[]{"YouTube", "com.google.android.youtube", "vnd.youtube://", "https://youtube.com"});
        APPS.put("spotify", new String[]{"Spotify", "com.spotify.music", "spotify://", "https://open.spotify.com"});
        APPS.put("telegram", new String[]{"Telegram", "org.telegram.messenger", "tg://", "https://t.me"});
        APPS.put("tiktok", new String[]{"TikTok", "com.zhiliaoapp.musically", "snssdk1128://", "https://tiktok.com"});
        APPS.put("gmail", new String[]{"Gmail", "com.google.android.gm", "googlegmail://", "https://mail.google.com"});
        APPS.put("harita", new String[]{"Haritalar", "com.google.android.apps.maps", "geo:0,0", "https://maps.google.com"});
        APPS.put("maps", new String[]{"Haritalar", "com.google.android.apps.maps", "geo:0,0", "https://maps.google.com"});
        APPS.put("chrome", new String[]{"Chrome", "com.android.chrome", null, "https://google.com"});
        APPS.put("ayarlar", new String[]{"Ayarlar", null, null, null});
        APPS.put("telefon", new String[]{"Telefon", null, "tel:", null});
    }

    private void launchApp(String label, String pkg, String scheme, String web) {
        if ("Ayarlar".equals(label)) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
            return;
        }
        if (pkg != null) {
            Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
            if (i != null) {
                try {
                    startActivity(i);
                    return;
                } catch (Exception ignored) {
                }
            }
        }
        if (scheme != null) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(scheme)));
                return;
            } catch (ActivityNotFoundException ignored) {
            }
        }
        if (web != null) {
            openUrl(web);
            return;
        }
        say(label + " bu cihazda kurulu değil efendim.");
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            say("Açacak bir uygulama bulamadım efendim.");
        }
    }

    /* ============================ kamera / flaş ============================ */

    private void takePhoto() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            pendingAction = this::takePhoto;
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA_PERM);
            return;
        }
        Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        try {
            startActivityForResult(i, REQ_CAMERA_INTENT);
            speak("Kamera açılıyor efendim.");
        } catch (ActivityNotFoundException e) {
            say("Bu cihazda kamera uygulaması bulamadım efendim.");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CAMERA_INTENT && resultCode == RESULT_OK && data != null) {
            Bitmap bmp = (Bitmap) data.getParcelableExtra("data");
            if (bmp != null) {
                addImage(bmp);
                say("Fotoğrafı çektim efendim.");
            } else {
                say("Fotoğraf alınamadı efendim.");
            }
        }
    }

    private void toggleTorch() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            pendingAction = this::toggleTorch;
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA_PERM);
            return;
        }
        try {
            CameraManager cm = (CameraManager) getSystemService(CAMERA_SERVICE);
            if (cm == null) throw new IllegalStateException("no camera service");
            torchOn = !torchOn;
            boolean applied = false;
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics ch = cm.getCameraCharacteristics(id);
                Boolean flash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (flash != null && flash) {
                    cm.setTorchMode(id, torchOn);
                    applied = true;
                    break;
                }
            }
            if (!applied) {
                torchOn = false;
                say("Bu cihazda flaş bulunamadı efendim.");
            } else {
                say(torchOn ? "Flaş açık efendim." : "Flaş kapatıldı efendim.");
            }
        } catch (Exception e) {
            torchOn = false;
            say("Flaşı kontrol edemedim efendim.");
        }
    }

    /* ============================ konum ============================ */

    private void findLocation(final boolean openMaps) {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            pendingAction = () -> findLocation(openMaps);
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
            return;
        }
        final LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (lm == null) {
            say("Konum servisi yok efendim.");
            return;
        }
        Location last = null;
        try {
            last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (last == null) last = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
        } catch (SecurityException ignored) {
        }
        if (last != null) {
            deliverLocation(last, openMaps);
            return;
        }
        say("Konumunuzu arıyorum efendim…");
        final LocationListener listener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                deliverLocation(location, openMaps);
                try {
                    lm.removeUpdates(this);
                } catch (Exception ignored) {
                }
            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {
            }

            @Override
            public void onProviderEnabled(String provider) {
            }

            @Override
            public void onProviderDisabled(String provider) {
            }
        };
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, listener, Looper.getMainLooper());
            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0, 0, listener, Looper.getMainLooper());
        } catch (Exception e) {
            say("Konum alınamadı efendim. Konum servisleri kapalı olabilir.");
            return;
        }
        main.postDelayed(() -> {
            try {
                lm.removeUpdates(listener);
            } catch (Exception ignored) {
            }
        }, 20000);
    }

    private void deliverLocation(Location l, boolean openMaps) {
        String coords = String.format(new Locale("tr", "TR"), "%.5f, %.5f", l.getLatitude(), l.getLongitude());
        if (openMaps) {
            openUrl("https://maps.google.com/?q=" + coords.replace(",", "%2C"));
            say("Haritayı açıyorum efendim.");
        } else {
            say("Yaklaşık konumunuz " + coords + " efendim.");
        }
    }

    /* ============================ izinler ============================ */

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            if (requestCode == REQ_MIC) {
                startListening();
            } else if (requestCode == REQ_LOCATION) {
                // izin verildi: konumu tekrar dene
                findLocation(false);
            } else if (requestCode == REQ_CAMERA_PERM) {
                say("Kamera izni verildi efendim.");
            } else if (requestCode == REQ_NOTIF) {
                say("Bildirim izni verildi efendim.");
            }
            if (pendingAction != null) {
                Runnable a = pendingAction;
                pendingAction = null;
                a.run();
            }
        } else {
            say("İzin verilmedi efendim. Ayarlardan izin verebilirsiniz.");
        }
    }

    /* ============================ notlar ============================ */

    private void loadNotes() {
        String raw = prefs.getString("notes", "");
        if (raw.isEmpty()) return;
        for (String line : raw.split("\n")) {
            if (!line.trim().isEmpty()) notes.add(new String[]{line, "0"});
        }
    }

    private void saveNotes() {
        StringBuilder sb = new StringBuilder();
        for (String[] n : notes) sb.append(n[0]).append("\n");
        prefs.edit().putString("notes", sb.toString()).apply();
    }

    /* ============================ AI beyni ============================ */

    private void checkAiStatus() {
        new Thread(() -> {
            String body = httpGet(AI_STATUS_URL);
            boolean ready = body != null && body.contains("\"ai\":true");
            runOnUiThread(() -> {
                if (!ready) {
                    addBubble("AI beyni: bağlı değil. Sunucuya anahtar eklenince serbest sohbet açılır; komutlar şimdiden çalışıyor.", false);
                }
            });
        }).start();
    }

    private void askAi(final String text) {
        history.add(new String[]{"user", text});
        if (history.size() > 12) history.remove(0);
        subText.setText("Düşünüyorum efendim…");
        setState(2);

        new Thread(() -> {
            String reply = null;
            int code = -1;
            try {
                JSONArray arr = new JSONArray();
                for (String[] m : history) {
                    JSONObject o = new JSONObject();
                    o.put("role", m[0]);
                    o.put("content", m[1]);
                    arr.put(o);
                }
                JSONObject body = new JSONObject();
                body.put("messages", arr);

                HttpURLConnection c = (HttpURLConnection) new URL(AI_URL).openConnection();
                c.setRequestMethod("POST");
                c.setConnectTimeout(15000);
                c.setReadTimeout(40000);
                c.setDoOutput(true);
                c.setRequestProperty("content-type", "application/json");
                OutputStream os = c.getOutputStream();
                os.write(body.toString().getBytes("UTF-8"));
                os.flush();
                os.close();
                code = c.getResponseCode();
                InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
                String resp = readAll(is);
                if (code == 200 && resp != null) {
                    reply = new JSONObject(resp).optString("reply", "");
                }
            } catch (Exception e) {
                Log.w("jarvis", "ai error", e);
            }

            final String r = reply;
            final int c = code;
            runOnUiThread(() -> {
                setState(0);
                if (r != null && !r.isEmpty()) {
                    history.add(new String[]{"assistant", r});
                    say(r);
                } else if (c == 503) {
                    say("Beynim henüz bağlı değil efendim. Sunucuya AI anahtarı eklenmeli. O zamana kadar komutlarım çalışıyor.");
                } else if (c == 429) {
                    say("Biraz çok soru sordunuz efendim. Birkaç dakika sonra deneyin.");
                } else {
                    say("Anlayamadım efendim. İnternet bağlantınızı kontrol edin ya da 'yardım' deyin.");
                }
            });
        }).start();
    }

    private String httpGet(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            return readAll(c.getInputStream());
        } catch (Exception e) {
            return null;
        }
    }

    private String readAll(InputStream is) {
        if (is == null) return null;
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            is.close();
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    /* ============================ ayarlar ============================ */

    private void showSettings() {
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(dp(20), dp(14), dp(20), dp(6));

        final CheckBox voice = new CheckBox(this);
        voice.setText("Sesli yanıt");
        voice.setTextColor(C_TEXT);
        voice.setChecked(voiceOn);
        ll.addView(voice);

        final CheckBox wake = new CheckBox(this);
        wake.setText("\"Jarvis\" ile uyandır");
        wake.setTextColor(C_TEXT);
        wake.setChecked(wakeOn);
        ll.addView(wake);

        Button permMic = smallButton("MİKROFON İZNİ");
        permMic.setOnClickListener(v -> requestPermissions(
                new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC));
        ll.addView(permMic);

        Button permCam = smallButton("KAMERA İZNİ");
        permCam.setOnClickListener(v -> requestPermissions(
                new String[]{Manifest.permission.CAMERA}, REQ_CAMERA_PERM));
        ll.addView(permCam);

        Button permLoc = smallButton("KONUM İZNİ");
        permLoc.setOnClickListener(v -> requestPermissions(new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION));
        ll.addView(permLoc);

        Button permNotif = smallButton("BİLDİRİM İZNİ");
        permNotif.setOnClickListener(v -> {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
            } else {
                Toast.makeText(this, "Bu sürümde bildirim izni gerekmiyor.", Toast.LENGTH_SHORT).show();
            }
        });
        ll.addView(permNotif);

        Button appSettings = smallButton("UYGULAMA AYARLARI");
        appSettings.setOnClickListener(v -> {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        });
        ll.addView(appSettings);

        TextView hint = new TextView(this);
        hint.setText("AI beyni: " + AI_URL + "\nJARVIS v1.0 · kırmızı tema");
        hint.setTextColor(C_MUTED);
        hint.setTextSize(10);
        hint.setPadding(0, dp(10), 0, 0);
        ll.addView(hint);

        ScrollView sv = new ScrollView(this);
        sv.addView(ll);

        new AlertDialog.Builder(this)
                .setTitle("Sistem ayarları")
                .setView(sv)
                .setPositiveButton("KAYDET", (d, w) -> {
                    voiceOn = voice.isChecked();
                    boolean newWake = wake.isChecked();
                    prefs.edit().putBoolean("voice", voiceOn).putBoolean("wake", newWake).apply();
                    if (newWake != wakeOn) {
                        wakeOn = newWake;
                        if (wakeOn) {
                            say("Uyandırma sözcüğü açık efendim. Adımı söylemeniz yeterli.");
                            startListening();
                        } else {
                            stopListening();
                        }
                    }
                })
                .setNegativeButton("KAPAT", null)
                .show();
    }
}
