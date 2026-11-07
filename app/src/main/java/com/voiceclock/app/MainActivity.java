package com.voiceclock.app;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int MAX_DEBUG_LINES = 50;

    private Button btnStartStop;
    private TextView tvStatus;
    private TextView tvDebug;
    private boolean serviceRunning = false;
    private StringBuilder debugLog = new StringBuilder();
    private SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    private BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String status = intent.getStringExtra(VoiceService.EXTRA_STATUS);
            String error = intent.getStringExtra("error");
            updateStatusText(status, error);
        }
    };

    private BroadcastReceiver debugReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String message = intent.getStringExtra(VoiceService.EXTRA_DEBUG);
            if (message != null) {
                appendDebug(message);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnStartStop = findViewById(R.id.btnStartStop);
        tvStatus = findViewById(R.id.tvStatus);
        tvDebug = findViewById(R.id.tvDebug);

        requestPermissions();

        btnStartStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!serviceRunning) {
                    startVoiceService();
                } else {
                    stopVoiceService();
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter statusFilter = new IntentFilter(VoiceService.ACTION_STATUS);
        IntentFilter debugFilter = new IntentFilter(VoiceService.ACTION_DEBUG);
        LocalBroadcastManager.getInstance(this).registerReceiver(statusReceiver, statusFilter);
        LocalBroadcastManager.getInstance(this).registerReceiver(debugReceiver, debugFilter);
        updateFromService();
    }

    @Override
    protected void onPause() {
        super.onPause();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(statusReceiver);
        LocalBroadcastManager.getInstance(this).unregisterReceiver(debugReceiver);
    }

    private void updateFromService() {
        serviceRunning = VoiceService.isRunning;
        updateUI();
        // Request a status update from the service if it's running
        if (serviceRunning) {
            Intent intent = new Intent(VoiceService.ACTION_REQUEST_STATUS);
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
        }
    }

    private void requestPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSION_REQUEST_CODE);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, PERMISSION_REQUEST_CODE);
            }
        }
    }

    private void startVoiceService() {
        debugLog.setLength(0);
        tvDebug.setText("");

        Intent intent = new Intent(this, VoiceService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        serviceRunning = true;
        updateUI();
        appendDebug("\u670d\u52a1\u542f\u52a8\u4e2d...");
    }

    private void stopVoiceService() {
        Intent intent = new Intent(this, VoiceService.class);
        stopService(intent);
        serviceRunning = false;
        updateUI();
        appendDebug("\u670d\u52a1\u5df2\u505c\u6b62");
    }

    private void appendDebug(String message) {
        String time = timeFormat.format(new Date());
        String line = "[" + time + "] " + message + "\n";
        debugLog.append(line);

        // Keep only last N lines to avoid memory issues
        String[] lines = debugLog.toString().split("\n");
        if (lines.length > MAX_DEBUG_LINES) {
            debugLog.setLength(0);
            int start = lines.length - MAX_DEBUG_LINES;
            for (int i = start; i < lines.length; i++) {
                debugLog.append(lines[i]).append("\n");
            }
        }

        tvDebug.setText(debugLog.toString());
    }

    private void updateStatusText(String status, String error) {
        if (status == null) return;

        String text;
        switch (status) {
            case VoiceService.STATUS_LOADING:
                text = "\u670d\u52a1\u72b6\u6001\uff1a\u52a0\u8f7d\u4e2d";
                break;
            case VoiceService.STATUS_READY:
            case VoiceService.STATUS_LISTENING:
                text = "\u670d\u52a1\u72b6\u6001\uff1a\u76d1\u542c\u4e2d";
                break;
            case VoiceService.STATUS_ERROR:
                text = "\u670d\u52a1\u72b6\u6001\uff1a\u9519\u8bef - " + (error != null ? error : "");
                break;
            default:
                text = "\u670d\u52a1\u72b6\u6001\uff1a" + status;
        }
        tvStatus.setText(text);
    }

    private void updateUI() {
        if (serviceRunning) {
            btnStartStop.setText("\u505c\u6b62\u670d\u52a1");
            // Don't reset status text here - let the broadcast receiver handle it
            // Only set a default if status is empty
            if (tvStatus.getText().toString().isEmpty() || 
                tvStatus.getText().toString().contains("\u5df2\u505c\u6b62")) {
                tvStatus.setText("\u670d\u52a1\u72b6\u6001\uff1a\u52a0\u8f7d\u4e2d...");
            }
        } else {
            btnStartStop.setText("\u542f\u52a8\u670d\u52a1");
            tvStatus.setText("\u670d\u52a1\u72b6\u6001\uff1a\u5df2\u505c\u6b62");
        }
    }
}
