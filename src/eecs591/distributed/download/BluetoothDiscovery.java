package eecs591.distributed.download;

import java.util.HashSet;
import java.util.concurrent.Semaphore;

import android.util.Log;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

public class BluetoothDiscovery {
	
	private static Semaphore discovery;
		
	public void discover() {
		BluetoothDiscovery.discovery = new Semaphore(1);
		Log.d(WiFiDirectActivity.TAG, "BluetoothDiscovery: Thread Start");
		BroadcastReceiver reciever = new BroadcastReceiver() {
			HashSet<String> seen = new HashSet<String>();
		    public void onReceive(Context context, Intent intent) {
		        String action = intent.getAction();
		        if (BluetoothDevice.ACTION_FOUND.equals(action)) {
		            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
		            Log.d(WiFiDirectActivity.TAG, "BluetoothDiscovery Found: " + device.getName());
		            if(device.getName().equals("Galaxy Nexus")) {
		            	if (!seen.contains(device.getAddress())) {
		            		try {
								BluetoothDiscovery.discovery.acquire();
							} catch (InterruptedException e) {
								e.printStackTrace();
							}
			            	WiFiDirectActivity.context.runOnUiThread(new Runnable() {
			            	    public void run() {
			            	    	((WiFiDirectActivity) WiFiDirectActivity.context).startDiscovery();
			            	    }
			            	});
			            	seen.add(device.getAddress());
		            	}
		            }
		        } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
		        	//BluetoothAdapter.getDefaultAdapter().startDiscovery();
		        }
		    }
		};
		WiFiDirectActivity.context.registerReceiver(reciever, new IntentFilter(BluetoothDevice.ACTION_FOUND));
		WiFiDirectActivity.context.registerReceiver(reciever, new IntentFilter(BluetoothAdapter.ACTION_DISCOVERY_FINISHED));
		if(!BluetoothAdapter.getDefaultAdapter().isEnabled()) {
			WiFiDirectActivity.context.startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
		}
		WiFiDirectActivity.context.startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300));
		Log.d(WiFiDirectActivity.TAG, "BluetoothDiscovery: Start Discovery");
		BluetoothAdapter.getDefaultAdapter().startDiscovery();
	}
	
}
