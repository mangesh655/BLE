package com.android.ble;

import androidx.appcompat.app.AppCompatActivity;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;

import com.android.ble.client.ClientActivity;
import com.android.ble.server.ServerActivity;

public class MainActivity extends AppCompatActivity {

    Button launchServerButton, launchClientButton;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        launchServerButton = findViewById(R.id.launch_server_button);
        launchClientButton = findViewById(R.id.launch_client_button);

        launchServerButton.setOnClickListener(v -> startActivity(new Intent(MainActivity.this,
                ServerActivity.class)));
        launchClientButton.setOnClickListener(v -> startActivity(new Intent(MainActivity.this,
                ClientActivity.class)));
    }
}
