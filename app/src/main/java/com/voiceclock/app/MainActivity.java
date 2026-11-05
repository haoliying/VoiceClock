package com.voiceclock.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 100;
    private Button btnStartStop;
    private TextView tvStatus;
    private boolean serviceRunning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnStartStop = findViewById(R.id.btnStartStop);
        tvStatus = findViewById(R.id.tvStatus);

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
        updateStatus();
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
        Intent intent = new Intent(this, VoiceService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        serviceRunning = true;
        updateUI();
    }

    private void stopVoiceService() {
        Intent intent = new Intent(this, VoiceService.class);
        stopService(intent);
        serviceRunning = false;
        updateUI();
    }

    private void updateStatus() {
        serviceRunning = VoiceService.isRunning;
        updateUI();
    }

    private void updateUI() {
        if (serviceRunning) {
            btnStartStop.setText("停止服务");
            tvStatus.setText("服务状态：运行中\n语音助手正在监听...\n说\u201C几点了\u201D获取当前时间");
        } else {
            btnStartStop.setText("启动服务");
            tvStatus.setText("服务状态：已停止\n点击按钮启动语音助手");
        }
    }
}
