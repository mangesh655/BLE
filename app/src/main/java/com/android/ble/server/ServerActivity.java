package com.android.ble.server;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.provider.Settings;
import android.util.Log;
import android.view.View;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.databinding.DataBindingUtil;

import com.android.ble.R;
import com.android.ble.client.ClientActivity;
import com.android.ble.databinding.ActivityServerBinding;
import com.android.ble.util.BluetoothUtils;
import com.android.ble.util.StringUtils;
import com.karumi.dexter.Dexter;
import com.karumi.dexter.PermissionToken;
import com.karumi.dexter.listener.PermissionDeniedResponse;
import com.karumi.dexter.listener.PermissionGrantedResponse;
import com.karumi.dexter.listener.PermissionRequest;
import com.karumi.dexter.listener.single.PermissionListener;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.android.ble.Constants.CHARACTERISTIC_ECHO_UUID;
import static com.android.ble.Constants.CHARACTERISTIC_TIME_UUID;
import static com.android.ble.Constants.CLIENT_CONFIGURATION_DESCRIPTOR_UUID;
import static com.android.ble.Constants.SERVICE_UUID;


public class ServerActivity extends AppCompatActivity implements GattServerActionListener {

    private static final String TAG = "ServerActivity";

    private ActivityServerBinding mBinding;

    private Handler mHandler;
    private Handler mLogHandler;
    private List<BluetoothDevice> mDevices;
    private Map<String, byte[]> mClientConfigurations;

    private BluetoothGattServer mGattServer;
    private BluetoothManager mBluetoothManager;
    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothLeAdvertiser mBluetoothLeAdvertiser;

    // Lifecycle

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mHandler = new Handler();
        mLogHandler = new Handler(Looper.getMainLooper());
        mDevices = new ArrayList<>();
        mClientConfigurations = new HashMap<>();

        mBluetoothManager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        mBluetoothAdapter = mBluetoothManager.getAdapter();

        mBinding = DataBindingUtil.setContentView(this, R.layout.activity_server);
        mBinding.sendTimestampButton.setOnClickListener(v -> sendTimestamp());
        mBinding.restartServerButton.setOnClickListener(v -> restartServer());
        mBinding.viewServerLog.clearLogButton.setOnClickListener(v -> clearLogs());
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Check if bluetooth is enabled
        if (mBluetoothAdapter == null || !mBluetoothAdapter.isEnabled()) {
            // Request user to enable it
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivity(enableBtIntent);
            finish();
            return;
        }

        // Check low energy support
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            // Get a newer device
            log("No LE Support.");
            finish();
            return;
        }

        // Check advertising
        if (!mBluetoothAdapter.isMultipleAdvertisementSupported()) {
            // Unable to run the server on this device, get a better device
            log("No Advertising Support.");
            finish();
            return;
        }

        mBluetoothLeAdvertiser = mBluetoothAdapter.getBluetoothLeAdvertiser();
        GattServerCallback gattServerCallback = new GattServerCallback(this);
        mGattServer = mBluetoothManager.openGattServer(this, gattServerCallback);

        @SuppressLint("HardwareIds")
        String deviceInfo = "Device Info"
                + "\nName: "+ mBluetoothAdapter.getName()
                + "\nAddress: " + mBluetoothAdapter.getAddress();
        mBinding.serverDeviceInfoTextView.setText(deviceInfo);

        setupServer();
        startAdvertising();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopAdvertising();
        stopServer();
    }

    // GattServer

    private void setupServer() {
        BluetoothGattService service = new BluetoothGattService(SERVICE_UUID,
                BluetoothGattService.SERVICE_TYPE_PRIMARY);

        // Write characteristic
        BluetoothGattCharacteristic writeCharacteristic = new BluetoothGattCharacteristic(
                CHARACTERISTIC_ECHO_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                        // Somehow this is not necessary, the client can still enable notifications
//                        | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_WRITE);

        // Characteristic with Descriptor
        BluetoothGattCharacteristic notifyCharacteristic = new BluetoothGattCharacteristic(
                CHARACTERISTIC_TIME_UUID,
                // Somehow this is not necessary, the client can still enable notifications
//                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                0,
                0);

        BluetoothGattDescriptor clientConfigurationDescriptor = new BluetoothGattDescriptor(
                CLIENT_CONFIGURATION_DESCRIPTOR_UUID,
                BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE);
        clientConfigurationDescriptor.setValue(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);

        notifyCharacteristic.addDescriptor(clientConfigurationDescriptor);

        service.addCharacteristic(writeCharacteristic);
        service.addCharacteristic(notifyCharacteristic);

        mGattServer.addService(service);
    }

    private void stopServer() {
        if (mGattServer != null) {
            mGattServer.close();
        }
    }

    private void restartServer() {
        stopAdvertising();
        stopServer();
        setupServer();
        startAdvertising();
    }

    // Advertising

    private void startAdvertising() {
        if (mBluetoothLeAdvertiser == null) {
            return;
        }

        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setConnectable(true)
                .setTimeout(0)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_LOW)
                .build();

        ParcelUuid parcelUuid = new ParcelUuid(SERVICE_UUID);
        AdvertiseData data = new AdvertiseData.Builder()
                //.setIncludeDeviceName(true)
                .addServiceUuid(parcelUuid)
                .build();

        mBluetoothLeAdvertiser.startAdvertising(settings, data, mAdvertiseCallback);
    }

    private void stopAdvertising() {
        if (mBluetoothLeAdvertiser != null) {
            mBluetoothLeAdvertiser.stopAdvertising(mAdvertiseCallback);
        }
    }

    private AdvertiseCallback mAdvertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            log("Peripheral advertising started.");
        }

        @Override
        public void onStartFailure(int errorCode) {
            log("Peripheral advertising failed: " + errorCode);
        }
    };

    // Notifications

    private void notifyCharacteristicTime(byte[] value) {
        notifyCharacteristic(value, CHARACTERISTIC_TIME_UUID);
    }

    private void notifyCharacteristic(byte[] value, UUID uuid) {
        BluetoothGattService service = mGattServer.getService(SERVICE_UUID);
        BluetoothGattCharacteristic characteristic = service.getCharacteristic(uuid);
        log("Notifying characteristic " + characteristic.getUuid().toString()
                + ", new value: " + StringUtils.byteArrayInHexFormat(value));

        characteristic.setValue(value);
        // Indications require confirmation, notifications do not
        boolean confirm = BluetoothUtils.requiresConfirmation(characteristic);
        for (BluetoothDevice device : mDevices) {
            if (clientEnabledNotifications(device, characteristic)) {
                mGattServer.notifyCharacteristicChanged(device, characteristic, confirm);
            }
        }
    }

    private boolean clientEnabledNotifications(BluetoothDevice device, BluetoothGattCharacteristic characteristic) {
        List<BluetoothGattDescriptor> descriptorList = characteristic.getDescriptors();
        BluetoothGattDescriptor descriptor = BluetoothUtils.findClientConfigurationDescriptor(descriptorList);
        if (descriptor == null) {
            // There is no client configuration descriptor, treat as true
            return true;
        }
        String deviceAddress = device.getAddress();
        byte[] clientConfiguration = mClientConfigurations.get(deviceAddress);
        if (clientConfiguration == null) {
            // Descriptor has not been set
            return false;
        }

        byte[] notificationEnabled = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
        return clientConfiguration.length == notificationEnabled.length
                && (clientConfiguration[0] & notificationEnabled[0]) == notificationEnabled[0]
                && (clientConfiguration[1] & notificationEnabled[1]) == notificationEnabled[1];
    }

    // Characteristic operations

    private byte[] getTimestampBytes() {
        @SuppressLint("SimpleDateFormat")
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String timestamp = dateFormat.format(new Date());
        return StringUtils.bytesFromString(timestamp);
    }

    private void sendTimestamp() {
        byte[] timestampBytes = getTimestampBytes();
        notifyCharacteristicTime(timestampBytes);
    }

    // Logging

    private void clearLogs() {
        mLogHandler.post(() -> mBinding.viewServerLog.logTextView.setText(""));
    }

    // Gatt Server Action Listener

    @Override
    public void log(String msg) {
        Log.d(TAG, msg);
        mLogHandler.post(() -> {
            mBinding.viewServerLog.logTextView.append(msg + "\n");
            mBinding.viewServerLog.logScrollView.post(() -> mBinding.viewServerLog.logScrollView.fullScroll(View.FOCUS_DOWN));
        });
    }

    @Override
    public void addDevice(BluetoothDevice device) {
        log("Deviced added: " + device.getAddress());
        mHandler.post(() -> mDevices.add(device));
    }

    @Override
    public void removeDevice(BluetoothDevice device) {
        log("Deviced removed: " + device.getAddress());
        mHandler.post(() -> {
            mDevices.remove(device);
            String deviceAddress = device.getAddress();
            mClientConfigurations.remove(deviceAddress);
        });
    }

    @Override
    public void addClientConfiguration(BluetoothDevice device, byte[] value) {
        String deviceAddress = device.getAddress();
        mClientConfigurations.put(deviceAddress, value);
    }

    @Override
    public void sendResponse(BluetoothDevice device, int requestId, int status, int offset, byte[] value) {
        mGattServer.sendResponse(device, requestId, status, 0, null);
    }

    @Override
    public void notifyCharacteristicEcho(byte[] value) {
        notifyCharacteristic(value, CHARACTERISTIC_ECHO_UUID);
    }


    private void requestLocationPermission() {
        Dexter.withActivity(this)
                .withPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                .withListener(new PermissionListener() {
                    @Override
                    public void onPermissionGranted(PermissionGrantedResponse response) {
                        // permission is granted
                        //StartBluetoothService();
                    }

                    @Override
                    public void onPermissionDenied(PermissionDeniedResponse response) {
                        // check for permanent denial of permission
                        showSettingsDialog();
                        //StartBluetoothService();

                    }

                    @Override
                    public void onPermissionRationaleShouldBeShown(PermissionRequest permission, PermissionToken token) {
                        token.continuePermissionRequest();
                    }
                }).check();
        log("Requested user enable Location. Try starting the scan again.");

    }


    /**
     * Showing Alert Dialog with Settings option
     * Navigates user to app settings
     * NOTE: Keep proper title and message depending on your app
     */
    private void showSettingsDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(ServerActivity.this);
        builder.setTitle("Need Permissions");
        builder.setMessage("Android 6.0  or above requires you to open location service to find nearby devices." +
                "You can grant them in app settings.");
        builder.setPositiveButton("GOTO SETTINGS", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.cancel();
                openSettings();
            }
        });
        builder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.cancel();
            }
        });
        builder.show();

    }

    // navigating user to app settings
    private void openSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        Uri uri = Uri.fromParts("package", getPackageName(), null);
        intent.setData(uri);
        startActivityForResult(intent, 101);
    }
}
