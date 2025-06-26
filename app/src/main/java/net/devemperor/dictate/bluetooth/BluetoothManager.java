
package net.devemperor.dictate.bluetooth;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;

import java.util.List;

public class BluetoothManager {

    private final Context context;
    private final AudioManager audioManager;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothHeadset bluetoothHeadset;

    private final BroadcastReceiver bluetoothReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action != null && action.equals(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)) {
                int state = intent.getIntExtra(BluetoothHeadset.EXTRA_STATE, BluetoothHeadset.STATE_DISCONNECTED);
                if (state == BluetoothHeadset.STATE_CONNECTED) {
                    audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
                    audioManager.startBluetoothSco();
                    audioManager.setBluetoothScoOn(true);
                } else if (state == BluetoothHeadset.STATE_DISCONNECTED) {
                    audioManager.setMode(AudioManager.MODE_NORMAL);
                    audioManager.stopBluetoothSco();
                    audioManager.setBluetoothScoOn(false);
                }
            }
        }
    };

    @SuppressLint("MissingPermission")
    public BluetoothManager(Context context) {
        this.context = context;
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        this.bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter != null) {
            context.registerReceiver(bluetoothReceiver, new IntentFilter(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED));
            bluetoothAdapter.getProfileProxy(context, new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    if (profile == BluetoothProfile.HEADSET) {
                        bluetoothHeadset = (BluetoothHeadset) proxy;
                        List<BluetoothDevice> devices = bluetoothHeadset.getConnectedDevices();
                        if (!devices.isEmpty()) {
                            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
                            audioManager.startBluetoothSco();
                            audioManager.setBluetoothScoOn(true);
                        }
                    }
                }

                @Override
                public void onServiceDisconnected(int profile) {
                    if (profile == BluetoothProfile.HEADSET) {
                        bluetoothHeadset = null;
                    }
                }
            }, BluetoothProfile.HEADSET);
        }
    }

    public void close() {
        context.unregisterReceiver(bluetoothReceiver);
        if (bluetoothAdapter != null && bluetoothHeadset != null) {
            bluetoothAdapter.closeProfileProxy(BluetoothProfile.HEADSET, bluetoothHeadset);
        }
    }

    @SuppressLint("MissingPermission")
    public boolean isHeadsetConnected() {
        return bluetoothAdapter != null && bluetoothAdapter.isEnabled() && bluetoothHeadset != null && !bluetoothHeadset.getConnectedDevices().isEmpty();
    }
}
