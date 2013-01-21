/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package eecs591.distributed.download;

import java.util.Collections;
import java.util.Hashtable;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.IntentFilter;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.WifiP2pManager.ActionListener;
import android.net.wifi.p2p.WifiP2pManager.Channel;
import android.net.wifi.p2p.WifiP2pManager.ChannelListener;
import android.os.Bundle;
import android.provider.Settings.Secure;
import android.util.Log;
import android.util.Pair;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import eecs591.distributed.download.WiFiDirectListFragment.DeviceActionListener;

/**
 * An activity that uses WiFi Direct APIs to discover and connect with available
 * devices. WiFi Direct APIs are asynchronous and rely on callback mechanism
 * using interfaces to notify the application of operation success or failure.
 * The application should also register a BroadcastReceiver for notification of
 * WiFi state related events.
 */



public class WiFiDirectActivity extends Activity implements ChannelListener, DeviceActionListener {

    public static final String TAG = "wifidirectdemo";
    private WifiP2pManager manager;
    private boolean isWifiP2pEnabled = false;
    private boolean retryChannel = false;

    private final IntentFilter intentFilter = new IntentFilter();
    private Channel channel;
    private BroadcastReceiver receiver = null;
    
    public static Activity context;
    public static SynchronousQueue<ChunkRequest> to_wifi;
	public static SynchronousQueue<ChunkRequest> from_wifi;
	public static SynchronousQueue<ChunkRequest> to_cellular;
	public static SynchronousQueue<ChunkRequest> from_cellular;
	public static SynchronousQueue<ChunkRequest> to_storage;
	public static SynchronousQueue<ChunkRequest> from_storage;
	public static SynchronousQueue<Pair<ChunkRequest, Pair<SynchronousQueue<ChunkRequest>, SynchronousQueue<ChunkRequest> > > > check_storage;
	public static SynchronousQueue<String> urls;
	public static Set<String> seen_urls;
	
	public static Hashtable<String, Hashtable<Integer, ChunkInfo> > part_states;
	public static Long last_cellular;
	public static Long last_wifi;
	public static Long threshold = (long)30E9; // 30 seconds
	public static final long sleep = 15000; // 15 seconds
	
	public static String device_id;
	public static int port;
	public static final int max_chunks = 10;
	public static Semaphore chunk_tracker;
	public static Semaphore device_tracker;
	public static final int chunk_size = 1024*1024; // 1MB
	public static SQLStore db;

    /**
     * @param isWifiP2pEnabled the isWifiP2pEnabled to set
     */
    public void setIsWifiP2pEnabled(boolean isWifiP2pEnabled) {
        this.isWifiP2pEnabled = isWifiP2pEnabled;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        // add necessary intent values to be matched.

        intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);

        manager = (WifiP2pManager) getSystemService(Context.WIFI_P2P_SERVICE);
        channel = manager.initialize(this, getMainLooper(), null);
        
        WiFiDirectActivity.context = this;
        WiFiDirectActivity.to_wifi = new SynchronousQueue<ChunkRequest>(true);
        WiFiDirectActivity.from_wifi = new SynchronousQueue<ChunkRequest>(true);
        WiFiDirectActivity.to_cellular = new SynchronousQueue<ChunkRequest>(true);
        WiFiDirectActivity.from_cellular = new SynchronousQueue<ChunkRequest>(true);
        WiFiDirectActivity.to_storage = new SynchronousQueue<ChunkRequest>(true);
        WiFiDirectActivity.from_storage = new SynchronousQueue<ChunkRequest>(true);
        WiFiDirectActivity.check_storage = new SynchronousQueue<Pair<ChunkRequest, Pair<SynchronousQueue<ChunkRequest>, SynchronousQueue<ChunkRequest> > > >(true);
        WiFiDirectActivity.urls = new SynchronousQueue<String>();
        WiFiDirectActivity.device_id = Secure.getString(this.getContentResolver(), Secure.ANDROID_ID);
        WiFiDirectActivity.port = 8988;
        WiFiDirectActivity.chunk_tracker = new Semaphore(WiFiDirectActivity.max_chunks);
        WiFiDirectActivity.device_tracker = new Semaphore(1);
        WiFiDirectActivity.db = new SQLStore(this);
        WiFiDirectActivity.part_states = new Hashtable<String, Hashtable<Integer, ChunkInfo> >();
        WiFiDirectActivity.last_cellular = System.nanoTime();
        WiFiDirectActivity.last_wifi = System.nanoTime();
        WiFiDirectActivity.seen_urls = Collections.synchronizedSortedSet(new TreeSet<String>());
        WiFiDirectActivity.seen_urls.add("http://dl.dropbox.com/u/105540/fall.jpg");
        
        new Thread(new CellularConnection()).start();
        new Thread(new StorageManager()).start();
        new Thread(new DistributedDownloader()).start();
        new Thread(new WiFiServerConnection()).start();
        //new BluetoothDiscovery().discover();
    }

    /** register the BroadcastReceiver with the intent values to be matched */
    @Override
    public void onResume() {
        super.onResume();
        receiver = new WiFiDirectBroadcastReceiver(manager, channel, this);
        registerReceiver(receiver, intentFilter);
    }

    @Override
    public void onPause() {
        super.onPause();
        unregisterReceiver(receiver);
    }

    /**
     * Remove all peers and clear all fields. This is called on
     * BroadcastReceiver receiving a state change event.
     */
    public void resetData() {
    	WiFiDirectListFragment fragmentList = (WiFiDirectListFragment) getFragmentManager()
                .findFragmentById(R.id.frag_list);
    	WiFiDirectDetailFragment fragmentDetails = (WiFiDirectDetailFragment) getFragmentManager()
                .findFragmentById(R.id.frag_detail);
        if (fragmentList != null) {
            fragmentList.clearPeers();
        }
        if (fragmentDetails != null) {
            fragmentDetails.resetViews();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.action_items, menu);
        return true;
    }

    /*
     * (non-Javadoc)
     * @see android.app.Activity#onOptionsItemSelected(android.view.MenuItem)
     */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.atn_direct_enable:
            	AlertDialog.Builder alert = new AlertDialog.Builder(this);
            	alert.setTitle("Pick a URL").setItems(WiFiDirectActivity.seen_urls.toArray(new CharSequence[0]), new DialogInterface.OnClickListener() {
            	  public void onClick(DialogInterface dialog, int which) {
            		  if (WiFiDirectDetailFragment.mContentView != null) {
            			  ((EditText)WiFiDirectDetailFragment.mContentView.findViewById(R.id.url_text)).setText(WiFiDirectActivity.seen_urls.toArray(new CharSequence[0])[which]);
            		  }
            	  }
            	});
            	alert.show();
                return true;
            case R.id.atn_direct_discover:
                return startDiscovery();
            default:
                return super.onOptionsItemSelected(item);
        }
    }
    
    public boolean startDiscovery() {
    	if (!isWifiP2pEnabled) {
            Toast.makeText(WiFiDirectActivity.this, R.string.p2p_off_warning,
                    Toast.LENGTH_SHORT).show();
            return true;
        }
        final WiFiDirectListFragment fragment = (WiFiDirectListFragment) getFragmentManager()
                .findFragmentById(R.id.frag_list);
        fragment.onInitiateDiscovery();
        manager.discoverPeers(channel, new WifiP2pManager.ActionListener() {

            public void onSuccess() {
                Toast.makeText(WiFiDirectActivity.this, "Discovery Initiated",
                        Toast.LENGTH_SHORT).show();
            }

            public void onFailure(int reasonCode) {
                Toast.makeText(WiFiDirectActivity.this, "Discovery Failed : " + reasonCode,
                        Toast.LENGTH_SHORT).show();
            }
        });
        return true;
    }

    public void showDetails(WifiP2pDevice device) {
    	WiFiDirectDetailFragment fragment = (WiFiDirectDetailFragment) getFragmentManager()
                .findFragmentById(R.id.frag_detail);
        fragment.showDetails(device);

    }

    public void connect(WifiP2pConfig config) {
        manager.connect(channel, config, new ActionListener() {

            public void onSuccess() {
                // WiFiDirectBroadcastReceiver will notify us. Ignore for now.
            }

            public void onFailure(int reason) {
                Toast.makeText(WiFiDirectActivity.this, "Connect failed. Retry.",
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    public void disconnect() {
        final WiFiDirectDetailFragment fragment = (WiFiDirectDetailFragment) getFragmentManager()
                .findFragmentById(R.id.frag_detail);
        fragment.resetViews();
        manager.removeGroup(channel, new ActionListener() {

            public void onFailure(int reasonCode) {
                Log.d(TAG, "Disconnect failed. Reason :" + reasonCode);

            }

            public void onSuccess() {
                fragment.getView().setVisibility(View.GONE);
            }

        });
    }

    public void onChannelDisconnected() {
        // we will try once more
        if (manager != null && !retryChannel) {
            Toast.makeText(this, "Channel lost. Trying again", Toast.LENGTH_LONG).show();
            resetData();
            retryChannel = true;
            manager.initialize(this, getMainLooper(), this);
        } else {
            Toast.makeText(this,
                    "Severe! Channel is probably lost premanently. Try Disable/Re-Enable P2P.",
                    Toast.LENGTH_LONG).show();
        }
    }

    public void cancelDisconnect() {

        /*
         * A cancel abort request by user. Disconnect i.e. removeGroup if
         * already connected. Else, request WifiP2pManager to abort the ongoing
         * request
         */
        if (manager != null) {
            final WiFiDirectListFragment fragment = (WiFiDirectListFragment) getFragmentManager()
                    .findFragmentById(R.id.frag_list);
            if (fragment.getDevice() == null
                    || fragment.getDevice().status == WifiP2pDevice.CONNECTED) {
                disconnect();
            } else if (fragment.getDevice().status == WifiP2pDevice.AVAILABLE
                    || fragment.getDevice().status == WifiP2pDevice.INVITED) {

                manager.cancelConnect(channel, new ActionListener() {

                    public void onSuccess() {
                        Toast.makeText(WiFiDirectActivity.this, "Aborting connection",
                                Toast.LENGTH_SHORT).show();
                    }

                    public void onFailure(int reasonCode) {
                        Toast.makeText(WiFiDirectActivity.this,
                                "Connect abort request failed. Reason Code: " + reasonCode,
                                Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }

    }
}
