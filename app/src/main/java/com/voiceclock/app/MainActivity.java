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

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 100;
    private Button btnStartStop;
    private TextView tvStatus;
    private boolean serviceRunning = false;

    private BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String status = intent.getStringExtra(VoiceService.EXTRA_STATUS);
            String error = intent.getStringExtra("error");
            updateStatusText(status, error);
        }
    };

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
        LocalBroadcastManager.getInstance(this).registerReceiver(
                statusReceiver, new IntentFilter(VoiceService.ACTION_STATUS));
        updateFromService();
    }

    @Override
    protected void onPause() {
        super.onPause();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(statusReceiver);
    }

    private void updateFromService() {
        serviceRunning = VoiceService.isRunning;
        updateUI();
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
        tvStatus.setText("\u670d\u52a1\u72b6\u6001\uff1a\u542f\u52a8\u4e2d...\n\u6b63\u5728\u52a0\u8f7d\u79bb\u7ebf\u8bed\u97f3\u6a21\u578b\uff0c\u8bf7\u7a0d\u5019...");
    }

    private void stopVoiceService() {
        Intent intent = new Intent(this, VoiceService.class);
        stopService(intent);
        serviceRunning = false;
        updateUI();
    }

    private void updateStatusText(String status, String error) {
        if (status == null) return;

        String text;
        switch (status) {
            case VoiceService.STATUS_LOADING:
                text = "\u670d\u52a1\u72b6\u6001\uff1a\u52a0\u8f7d\u4e2d\n\u6b63\u5728\u52a0\u8f7d\u79bb\u7ebf\u8bed\u97f3\u6a21\u578b\uff0c\u8bf7\u7a0d\u5019...";
                break;
            case VoiceService.STATUS_READY:
            case VoiceService.STATUS_LISTENING:
                text = "\u670d\u52a1\u72b6\u6001\uff1a\u8fd0\u884c\u4e2d\n\u79bb\u7ebf\u8bed\u97f3\u52a9\u624b\u6b63\u5728\u76d1\u542c...\n\u8bf4\u201c\u51e0\u70b9\u4e86\u201d\u83b7\u53d6\u5f53\u524d\u65f6\u95f4";
                break;
            case VoiceService.STATUS_ERROR:
                text = "\u670d\u52a1\u72b6\u6001\uff1a\u9519\u8bef\n" + (error != null ? error : "\u672a\u77e5\u9519\u8bef");
                break;
            default:
                text = "\u670d\u52a1\u72b6\u6001\uff1a" + status;
        }
        tvStatus.setText(text);
    }

    private void updateUI() {
        if (serviceRunning) {
            btnStartStop.setText("\u505c\u6b62\u670d\u52a1");
        } else {
            btnStartStop.setText("\u542f\u52a8\u670d\u52a1");
            tvStatus.setText("\u670d\u52a1\u72b6\u6001\uff1a\u5df2\u505c\u6b62\n\u70b9\u51fb\u6309\u94ae\u542f\u52a8\u8bed\u97f3\u52a9\u624b");
        }
    }
}
