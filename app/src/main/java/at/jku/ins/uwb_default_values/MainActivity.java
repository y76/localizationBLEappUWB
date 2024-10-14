package at.jku.ins.uwb_default_values;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.ParcelUuid;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.uwb.RangingParameters;
import androidx.core.uwb.RangingResult;
import androidx.core.uwb.UwbAddress;
import androidx.core.uwb.UwbClientSessionScope;
import androidx.core.uwb.UwbComplexChannel;
import androidx.core.uwb.UwbControleeSessionScope;
import androidx.core.uwb.UwbControllerSessionScope;
import androidx.core.uwb.UwbDevice;
import androidx.core.uwb.UwbManager;
import androidx.core.uwb.rxjava3.UwbClientSessionScopeRx;
import androidx.core.uwb.rxjava3.UwbManagerRx;

import com.google.common.primitives.Shorts;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.reactivex.rxjava3.disposables.Disposable;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_PERMISSIONS = 1;
    private static final int REQUEST_ENABLE_BT = 2;
    private static final long SCAN_PERIOD = 10000;
    private static final UUID UWB_SERVICE_UUID = UUID.fromString("0000181C-0000-1000-8000-00805f9b34fb");

    private Button stopRangingButton;
    private Button communicateButton;
    private Button startScanButton;
    private Button startAdvertiseButton;
    private AtomicReference<Disposable> rangingResultObservable;
    private TextView distanceDisplay;
    private TextView elevationDisplay;
    private TextView azimuthDisplay;
    private UwbManager uwbManager;
    private AtomicReference<UwbClientSessionScope> currentUwbSessionScope;
    private Switch isControllerSwitch;
    private ListView deviceListView;

    private Button toggleAdvertiseButton;
    private boolean isAdvertising = false;
    private ArrayAdapter<String> deviceListAdapter;
    private ArrayList<BluetoothDevice> bluetoothDevices;
    private boolean isController;
    private UwbAddress localUwbAddress;
    private UwbComplexChannel localUwbChannel;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bluetoothLeScanner;
    private BluetoothLeAdvertiser bluetoothLeAdvertiser;
    private Handler handler;
    private boolean isScanning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        requestPermissions();

        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager.getAdapter();
        if (bluetoothAdapter != null) {
            bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
            bluetoothLeAdvertiser = bluetoothAdapter.getBluetoothLeAdvertiser();
        }
        handler = new Handler();

        uwbManager = UwbManager.createInstance(this);
        rangingResultObservable = new AtomicReference<>(null);
        currentUwbSessionScope = new AtomicReference<>(null);

        initializeUIComponents();

        bluetoothDevices = new ArrayList<>();
        deviceListAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1);
        deviceListView.setAdapter(deviceListAdapter);

        toggleAdvertiseButton = findViewById(R.id.start_advertise_button);
        toggleAdvertiseButton.setOnClickListener(v -> toggleBleAdvertising());

        setupButtonListeners();

        isController = isControllerSwitch.isChecked();
       // refreshUwbSession();

        new Thread(this::refreshUwbSession).start();
    }

    private void requestPermissions() {
        ArrayList<String> permissionList = new ArrayList<>();
        permissionList.add(Manifest.permission.ACCESS_FINE_LOCATION);
        permissionList.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        permissionList.add(Manifest.permission.UWB_RANGING);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionList.add(Manifest.permission.BLUETOOTH_SCAN);
            permissionList.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            permissionList.add(Manifest.permission.BLUETOOTH_CONNECT);
        }

        String[] permissions = permissionList.toArray(new String[0]);
        ActivityCompat.requestPermissions(this, permissions, REQUEST_PERMISSIONS);
    }

    private void initializeUIComponents() {
        stopRangingButton = findViewById(R.id.stop_ranging_button);
        communicateButton = findViewById(R.id.communicate_button);
        startScanButton = findViewById(R.id.start_scan_button);
        startAdvertiseButton = findViewById(R.id.start_advertise_button);
        isControllerSwitch = findViewById(R.id.is_controller);
        distanceDisplay = findViewById(R.id.distance_display);
        elevationDisplay = findViewById(R.id.elevation_display);
        azimuthDisplay = findViewById(R.id.azimuth_display);
        deviceListView = findViewById(R.id.device_list_view);

        stopRangingButton.setEnabled(false);
    }

    private void setupButtonListeners() {
        isControllerSwitch.setOnClickListener(v -> {
            stopRanging();
            refreshUwbSession();
        });

        findViewById(R.id.get_values_button).setOnClickListener(this::displayUwbValues);

        communicateButton.setOnClickListener(v -> startUwbRanging());

        stopRangingButton.setOnClickListener(v -> {
            stopRanging();
            refreshUwbSession();
        });

        startScanButton.setOnClickListener(v -> startBleScan());

        startAdvertiseButton.setOnClickListener(v -> startBleAdvertising());
    }

    private void refreshUwbSession() {
        isController = isControllerSwitch.isChecked();
        if (isController) {
            UwbControllerSessionScope controllerScope = UwbManagerRx.controllerSessionScopeSingle(uwbManager).blockingGet();
            currentUwbSessionScope.set(controllerScope);
            localUwbAddress = controllerScope.getLocalAddress();
            localUwbChannel = controllerScope.getUwbComplexChannel();
        } else {
            UwbControleeSessionScope controleeScope = UwbManagerRx.controleeSessionScopeSingle(uwbManager).blockingGet();
            currentUwbSessionScope.set(controleeScope);
            localUwbAddress = controleeScope.getLocalAddress();
            // Note: Controlees don't have channel information initially
        }
        runOnUiThread(() -> {
            displayUwbValues(null);
          //  updateBleAdvertising();
        });
    }

    private void displayUwbValues(android.view.View view) {
        if (isControllerSwitch.isChecked()) {
            UwbControllerSessionScope controllerSessionScope = (UwbControllerSessionScope) currentUwbSessionScope.get();
            String message = "Your Address is: " + Shorts.fromByteArray(controllerSessionScope.getLocalAddress().getAddress()) +
                    "\nuwbComplexChannel channel is: " + controllerSessionScope.getUwbComplexChannel().getChannel() +
                    "\nuwbComplexChannel preambleIndex is: " + controllerSessionScope.getUwbComplexChannel().getPreambleIndex();
            showAlert("CONTROLLER / SERVER", message, view);
        } else {
            UwbControleeSessionScope controleeSessionScope = (UwbControleeSessionScope) currentUwbSessionScope.get();
            String message = "Your Address is: " + Shorts.fromByteArray(controleeSessionScope.getLocalAddress().getAddress()) +
                    "\nYour Device supports Distance: " + controleeSessionScope.getRangingCapabilities().isDistanceSupported() +
                    "\nYour Device supports Azimuth: " + controleeSessionScope.getRangingCapabilities().isAzimuthalAngleSupported() +
                    "\nYour Device supports Elevation: " + controleeSessionScope.getRangingCapabilities().isElevationAngleSupported();
            showAlert("CONTROLLEE / CLIENT", message, view);
        }
    }

    private void showAlert(String title, String message, android.view.View view) {
        runOnUiThread(() -> {
            AlertDialog.Builder builder = new AlertDialog.Builder(view != null ? view.getContext() : this);
            builder.setTitle(title)
                    .setMessage(message)
                    .setNeutralButton("OK", (a, b) -> {})
                    .create().show();
        });
    }

    private void startUwbRanging() {
        EditText addressInputField = findViewById(R.id.address_input);
        EditText preambleInputField = findViewById(R.id.preamble_input);

        try {
            int otherSideLocalAddress = Integer.parseInt(addressInputField.getText().toString());
            UwbAddress partnerAddress = new UwbAddress(Shorts.toByteArray((short) otherSideLocalAddress));
            UwbComplexChannel uwbComplexChannel;

            if (isControllerSwitch.isChecked()) {
                uwbComplexChannel = ((UwbControllerSessionScope)currentUwbSessionScope.get()).getUwbComplexChannel();
            } else {
                int channelPreamble = Integer.parseInt(preambleInputField.getText().toString());
                uwbComplexChannel = new UwbComplexChannel(9, channelPreamble);
            }

            RangingParameters partnerParameters = new RangingParameters(RangingParameters.CONFIG_MULTICAST_DS_TWR, 12345, 0,
                    new byte[]{0, 0, 0, 0, 0, 0, 0, 0}, null, uwbComplexChannel,
                    Collections.singletonList(new UwbDevice(partnerAddress)),
                    RangingParameters.RANGING_UPDATE_RATE_AUTOMATIC);

            stopRanging();

            rangingResultObservable.set(UwbClientSessionScopeRx.rangingResultsObservable(currentUwbSessionScope.get(), partnerParameters)
                    .subscribe(rangingResult -> {
                                if (rangingResult instanceof RangingResult.RangingResultPosition) {
                                    RangingResult.RangingResultPosition rangingResultPosition = (RangingResult.RangingResultPosition) rangingResult;
                                    updateDisplay(rangingResultPosition);
                                } else {
                                    System.out.println("CONNECTION LOST");
                                }
                            },
                            System.out::println,
                            () -> {
                                System.out.println("Completed the observing of RangingResults");
                                runOnUiThread(() -> {
                                    stopRangingButton.setEnabled(false);
                                    communicateButton.setEnabled(true);
                                });
                            }
                    ));

            runOnUiThread(() -> {
                stopRangingButton.setEnabled(true);
                communicateButton.setEnabled(false);
            });

        } catch (NumberFormatException e) {
            Toast.makeText(this, "Invalid input. Please check address and preamble.", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Error starting UWB ranging: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            e.printStackTrace();
        }
    }

    private void startUwbCommunication(UwbAddress partnerAddress, UwbComplexChannel channelToUse) {
        runOnUiThread(() -> {
            Toast.makeText(this, "Starting UWB communication with device: " + partnerAddress, Toast.LENGTH_SHORT).show();
        });

        try {
            RangingParameters partnerParameters = new RangingParameters(
                    RangingParameters.CONFIG_MULTICAST_DS_TWR,
                    12345, // session ID
                    0, // sub-session ID
                    new byte[]{0, 0, 0, 0, 0, 0, 0, 0}, // session key (optional)
                    null, // sub-session key (optional)
                    channelToUse,
                    Collections.singletonList(new UwbDevice(partnerAddress)),
                    RangingParameters.RANGING_UPDATE_RATE_AUTOMATIC
            );

            stopRanging(); // Stop any existing ranging

            rangingResultObservable.set(UwbClientSessionScopeRx.rangingResultsObservable(currentUwbSessionScope.get(), partnerParameters)
                    .subscribe(
                            rangingResult -> {
                                if (rangingResult instanceof RangingResult.RangingResultPosition) {
                                    RangingResult.RangingResultPosition rangingResultPosition = (RangingResult.RangingResultPosition) rangingResult;
                                    updateDisplay(rangingResultPosition);
                                } else {
                                    System.out.println("CONNECTION LOST");
                                    runOnUiThread(this::resetDisplays);
                                }
                            },
                            error -> {
                                System.err.println("Error in ranging: " + error.getMessage());
                                error.printStackTrace();
                                runOnUiThread(this::resetDisplays);
                            },
                            () -> {
                                System.out.println("Completed the observing of RangingResults");
                                runOnUiThread(() -> {
                                    stopRangingButton.setEnabled(false);
                                    communicateButton.setEnabled(true);
                                    resetDisplays();
                                });
                            }
                    ));

            runOnUiThread(() -> {
                stopRangingButton.setEnabled(true);
                communicateButton.setEnabled(false);
            });

        } catch (Exception e) {
            runOnUiThread(() -> {
                Toast.makeText(this, "Error starting UWB communication: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            });
            e.printStackTrace();
        }
    }

    private void stopRanging() {
        if (rangingResultObservable.get() != null) {
            rangingResultObservable.get().dispose();
            rangingResultObservable.set(null);
        }
        runOnUiThread(() -> {
            stopRangingButton.setEnabled(false);
            communicateButton.setEnabled(true);
            resetDisplays();
        });
    }

    private void stopBleAdvertising() {
        if (bluetoothLeAdvertiser != null) {
            bluetoothLeAdvertiser.stopAdvertising(advertiseCallback);
            runOnUiThread(() -> {
                isAdvertising = false;
                toggleAdvertiseButton.setText("Start Advertising");
                Toast.makeText(MainActivity.this, "Advertising stopped", Toast.LENGTH_SHORT).show();
            });
        }
    }

    private void updateDisplay(RangingResult.RangingResultPosition rangingResultPosition) {
        runOnUiThread(() -> {
            if (rangingResultPosition.getPosition().getDistance() != null) {
                distanceDisplay.setText(String.format("%.2f", rangingResultPosition.getPosition().getDistance().getValue()));
            }
            if (rangingResultPosition.getPosition().getAzimuth() != null) {
                azimuthDisplay.setText(String.format("%.2f", rangingResultPosition.getPosition().getAzimuth().getValue()));
            }
            if (rangingResultPosition.getPosition().getElevation() != null) {
                elevationDisplay.setText(String.format("%.2f", rangingResultPosition.getPosition().getElevation().getValue()));
            }
        });
    }

    private void resetDisplays() {
        distanceDisplay.setText("0.00");
        azimuthDisplay.setText("0.00");
        elevationDisplay.setText("0.00");
    }

    private void startBleScan() {
        if (!isScanning) {
            deviceListAdapter.clear();
            bluetoothDevices.clear();

            isScanning = true;
            bluetoothLeScanner.startScan(scanCallback);
            startScanButton.setText("Stop Scan");

            handler.postDelayed(() -> {
                isScanning = false;
                bluetoothLeScanner.stopScan(scanCallback);
                startScanButton.setText("Start Scan");
            }, SCAN_PERIOD);
        } else {
            isScanning = false;
            bluetoothLeScanner.stopScan(scanCallback);
            startScanButton.setText("Start Scan");
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            if (!bluetoothDevices.contains(device)) {
                bluetoothDevices.add(device);
                String deviceName = device.getName() != null ? device.getName() : device.getAddress();

                StringBuilder deviceInfo = new StringBuilder(deviceName);

                byte[] uwbServiceData = result.getScanRecord().getServiceData(new ParcelUuid(UWB_SERVICE_UUID));
                if (uwbServiceData != null && uwbServiceData.length >= 7) {
                    ByteBuffer buffer = ByteBuffer.wrap(uwbServiceData);

                    short uwbAddress = buffer.getShort(0);
                    int channel = buffer.get(2) & 0xFF;
                    int preambleIndex = buffer.get(3) & 0xFF;
                    short sessionId = buffer.getShort(4);
                    boolean deviceIsController = buffer.get(6) == 1;

                    deviceInfo.append("\nUWB Address: ").append(uwbAddress)
                            .append("\nChannel: ").append(channel)
                            .append("\nPreamble Index: ").append(preambleIndex)
                            .append("\nSession ID: ").append(sessionId)
                            .append("\nIs Controller: ").append(deviceIsController);

                    // Check if the discovered device is compatible for UWB communication
                    if (isController != deviceIsController) {
                        UwbAddress partnerAddress = new UwbAddress(Shorts.toByteArray(uwbAddress));
                        UwbComplexChannel channelToUse;

                        if (isController) {
                            // If we're the controller, use our own channel and preamble index
                            channelToUse = localUwbChannel;
                        } else {
                            // If we're the controlee, use the discovered controller's channel and preamble index
                            channelToUse = new UwbComplexChannel(channel, preambleIndex);
                        }

                        startUwbCommunication(partnerAddress, channelToUse);
                    }
                } else {
                    deviceInfo.append("\nNo UWB info available");
                }

                final String finalDeviceInfo = deviceInfo.toString();
                runOnUiThread(() -> {
                    deviceListAdapter.add(finalDeviceInfo);
                    deviceListAdapter.notifyDataSetChanged();
                });
            }
        }
    };

    private byte[] getUwbInfoBytes() {
        ByteBuffer buffer = ByteBuffer.allocate(7); // 2 bytes for address, 1 for channel, 1 for preamble index, 2 for session ID, 1 for isController

        UwbClientSessionScope sessionScope = currentUwbSessionScope.get();
        if (sessionScope == null) {
            return buffer.array(); // Return empty array if no session
        }

        buffer.put(sessionScope.getLocalAddress().getAddress());

        if (sessionScope instanceof UwbControllerSessionScope) {
            UwbControllerSessionScope controllerScope = (UwbControllerSessionScope) sessionScope;
            UwbComplexChannel channel = controllerScope.getUwbComplexChannel();
            buffer.put((byte) channel.getChannel());
            buffer.put((byte) channel.getPreambleIndex());
            buffer.putShort((short) 12345); // Session ID, replace with actual session ID if available
            buffer.put((byte) 1); // isController = true
        } else if (sessionScope instanceof UwbControleeSessionScope) {
            UwbControleeSessionScope controleeScope = (UwbControleeSessionScope) sessionScope;
            buffer.put((byte) 0); // Channel (not known for controlee)
            buffer.put((byte) 0); // Preamble index (not known for controlee)
            buffer.putShort((short) 0); // Session ID (not known for controlee)
            buffer.put((byte) 0); // isController = false
        }

        return buffer.array();
    }

    private void toggleBleAdvertising() {
        if (isAdvertising) {
            stopBleAdvertising();
        } else {
            startBleAdvertising();
        }
    }
    private void startBleAdvertising() {
        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(false)
                .build();

        AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .addServiceUuid(new ParcelUuid(UWB_SERVICE_UUID))
                .addServiceData(new ParcelUuid(UWB_SERVICE_UUID), getUwbInfoBytes())
                .build();

        bluetoothLeAdvertiser.startAdvertising(settings, data, advertiseCallback);
    }

    private void updateBleAdvertising() {
        if (bluetoothLeAdvertiser != null) {
            bluetoothLeAdvertiser.stopAdvertising(advertiseCallback);
            startBleAdvertising();
        }
    }

    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            runOnUiThread(() -> {
                isAdvertising = true;
                toggleAdvertiseButton.setText("Stop Advertising");
                Toast.makeText(MainActivity.this, "Advertising started with UWB info", Toast.LENGTH_SHORT).show();
            });
        }

        @Override
        public void onStartFailure(int errorCode) {
            runOnUiThread(() -> {
                isAdvertising = false;
                toggleAdvertiseButton.setText("Start Advertising");
                Toast.makeText(MainActivity.this, "Advertising failed to start", Toast.LENGTH_SHORT).show();
            });
        }
    };

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                Toast.makeText(this, "All permissions granted", Toast.LENGTH_SHORT).show();
                initializeBluetooth();
            } else {
                Toast.makeText(this, "Some permissions were denied. App functionality may be limited.", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void initializeBluetooth() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ENABLE_BT) {
            if (resultCode == RESULT_OK) {
                Toast.makeText(this, "Bluetooth enabled", Toast.LENGTH_SHORT).show();
                bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
                bluetoothLeAdvertiser = bluetoothAdapter.getBluetoothLeAdvertiser();
            } else {
                Toast.makeText(this, "Bluetooth is required for BLE functionality", Toast.LENGTH_SHORT).show();
            }
        }
    }
}