package com.voiceclock.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Locale;

public class VoiceService extends Service implements RecognitionListener {

    private static final String TAG = "VoiceService";
    private static final String CHANNEL_ID = "voice_clock_channel";
    private static final int NOTIFICATION_ID = 1;
    private static final long RESTART_DELAY_MS = 1000;

    public static boolean isRunning = false;

    private SpeechRecognizer speechRecognizer;
    private TextToSpeech textToSpeech;
    private Handler handler;
    private boolean isListening = false;

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        createNotificationChannel();
        initTTS();
        initSpeechRecognizer();
        isRunning = true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = buildNotification();
        startForeground(NOTIFICATION_ID, notification);
        startListening();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
            speechRecognizer = null;
        }
        if (textToSpeech != null) {
            textToSpeech.shutdown();
            textToSpeech = null;
        }
        handler.removeCallbacksAndMessages(null);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "语音助手",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("语音时钟助手通知");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("语音助手运行中")
                .setContentText("正在监听语音...说\"几点了\"获取时间")
                .setSmallIcon(R.drawable.ic_notification)
                .setOngoing(true)
                .build();
    }

    private void initTTS() {
        textToSpeech = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                if (status == TextToSpeech.SUCCESS) {
                    int result = textToSpeech.setLanguage(Locale.CHINESE);
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        Log.w(TAG, "Chinese language not supported, trying default");
                        textToSpeech.setLanguage(Locale.getDefault());
                    }
                } else {
                    Log.e(TAG, "TTS initialization failed with status: " + status);
                }
            }
        });
    }

    private void initSpeechRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            speechRecognizer.setRecognitionListener(this);
        } else {
            Log.e(TAG, "Speech recognition not available on this device");
        }
    }

    private void startListening() {
        if (speechRecognizer == null || isListening) return;

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.CHINESE);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);

        try {
            speechRecognizer.startListening(intent);
            isListening = true;
        } catch (Exception e) {
            Log.e(TAG, "Error starting listening", e);
            isListening = false;
            scheduleRestart();
        }
    }

    private void scheduleRestart() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isRunning) {
                    startListening();
                }
            }
        }, RESTART_DELAY_MS);
    }

    private void speakTime() {
        Calendar calendar = Calendar.getInstance();
        int hour = calendar.get(Calendar.HOUR_OF_DAY);
        int minute = calendar.get(Calendar.MINUTE);

        String period;
        if (hour < 6) {
            period = "凌晨";
        } else if (hour < 12) {
            period = "上午";
        } else if (hour < 14) {
            period = "中午";
        } else if (hour < 18) {
            period = "下午";
        } else {
            period = "晚上";
        }

        int displayHour = hour % 12;
        if (displayHour == 0) displayHour = 12;

        String timeText = "现在是" + period + displayHour + "点" + minute + "分";
        Log.d(TAG, "Speaking: " + timeText);

        if (textToSpeech != null) {
            textToSpeech.speak(timeText, TextToSpeech.QUEUE_FLUSH, null, "time_announcement");
        }
    }

    // RecognitionListener methods
    @Override
    public void onReadyForSpeech(Bundle params) {
        Log.d(TAG, "Ready for speech");
    }

    @Override
    public void onBeginningOfSpeech() {
        Log.d(TAG, "Beginning of speech");
    }

    @Override
    public void onRmsChanged(float rmsdB) {
        // Not used
    }

    @Override
    public void onBufferReceived(byte[] buffer) {
        // Not used
    }

    @Override
    public void onEndOfSpeech() {
        Log.d(TAG, "End of speech");
        isListening = false;
        scheduleRestart();
    }

    @Override
    public void onError(int error) {
        Log.w(TAG, "Speech recognition error: " + error);
        isListening = false;
        scheduleRestart();
    }

    @Override
    public void onResults(Bundle results) {
        Log.d(TAG, "Got results");
        isListening = false;

        ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches != null && !matches.isEmpty()) {
            String text = matches.get(0);
            Log.d(TAG, "Recognized: " + text);

            if (text.contains("几点了") || text.contains("几点啦") || text.contains("什么时间")
                    || text.contains("现在时间") || text.contains("几时")) {
                speakTime();
            }
        }

        scheduleRestart();
    }

    @Override
    public void onPartialResults(Bundle partialResults) {
        ArrayList<String> matches = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches != null && !matches.isEmpty()) {
            String text = matches.get(0);
            if (text.contains("几点了") || text.contains("几点啦") || text.contains("什么时间")
                    || text.contains("现在时间") || text.contains("几时")) {
                speakTime();
            }
        }
    }

    @Override
    public void onEvent(int eventType, Bundle params) {
        // Not used
    }
}
