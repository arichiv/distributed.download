package eecs591.distributed.download;

import java.util.concurrent.SynchronousQueue;

import android.util.Log;
import android.util.Pair;

public class DistributedDownloader implements Runnable {
	
	public void run() {
		Log.d(WiFiDirectActivity.TAG, "DistributedDownloader Thread Start");
		new Thread(new RouteFromStorage()).start();
		new Thread(new RouteWiFiToCellular()).start();
		new Thread(new RouteCellularToWiFi()).start();
    }
}

class RouteFromStorage implements Runnable {

	public void run() {
		Log.d(WiFiDirectActivity.TAG, "RouteFromStorage Thread Start");
		while(true) {
			ChunkRequest got = null;
			try {
				got = WiFiDirectActivity.from_storage.take();
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			Log.d(WiFiDirectActivity.TAG, "RouteFromStorage Saw: " + got.id);
			try {
				WiFiDirectActivity.to_wifi.put(got);
				WiFiDirectActivity.part_states.get(got.url).put(got.id, new ChunkInfo(got, ChunkState.QUEUED_WIFI));
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}		
    }
}

class RouteWiFiToCellular implements Runnable {
	
	public static int count = 0;
	
	public void run() {
		Log.d(WiFiDirectActivity.TAG, "RouteWiFiToCellular Thread Start");
		while(true) {
			ChunkRequest got = null;
			try {
				got = WiFiDirectActivity.from_wifi.take();
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			WiFiDirectActivity.seen_urls.add(got.url);
			Log.d(WiFiDirectActivity.TAG, "RouteWiFiToCellular Saw: " + got.id + " from " + got.device);
			synchronized(WiFiDirectActivity.last_wifi) {
				WiFiDirectActivity.last_wifi = System.nanoTime();
			}
			if (got.cache == true) {
				if (got.device.equals(WiFiDirectActivity.device_id)) {
					if (got.data != null) {
						try {
							WiFiDirectActivity.to_storage.put(got);
						} catch (InterruptedException e) {
							e.printStackTrace();
						}
					} else {
						got.cache = false;
						SynchronousQueue<ChunkRequest> dst;
						ChunkState stt;
						if (count % (WiFiDirectActivity.device_tracker.availablePermits() + 1) == 0) {
							dst = WiFiDirectActivity.to_cellular;
							stt = ChunkState.QUEUED_CELLULAR;
						} else {
							dst = WiFiDirectActivity.to_wifi;
							stt = ChunkState.QUEUED_WIFI;
						}
						count++;
						try {
							dst.put(got);
							WiFiDirectActivity.part_states.get(got.url).put(got.id, new ChunkInfo(got, stt));
			 			} catch (InterruptedException e) {
			 				e.printStackTrace();
			 			}
						RouteWiFiToCellular.count++;
					}
				} else {
					try {
						WiFiDirectActivity.check_storage.put(new Pair<ChunkRequest, Pair<SynchronousQueue<ChunkRequest>, SynchronousQueue<ChunkRequest> > >(got, new Pair<SynchronousQueue<ChunkRequest>, SynchronousQueue<ChunkRequest> >(WiFiDirectActivity.to_wifi, WiFiDirectActivity.to_wifi)));
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
				}
			} else {
				if (got.device.equals(WiFiDirectActivity.device_id)) {
					try {
						WiFiDirectActivity.to_storage.put(got);
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
				} else {
					if (got.data == null) {
						try {
							WiFiDirectActivity.check_storage.put(new Pair<ChunkRequest, Pair<SynchronousQueue<ChunkRequest>, SynchronousQueue<ChunkRequest> > >(got, new Pair<SynchronousQueue<ChunkRequest>, SynchronousQueue<ChunkRequest> >(WiFiDirectActivity.to_wifi, WiFiDirectActivity.to_cellular)));
						} catch (InterruptedException e) {
							e.printStackTrace();
						}
					} else {
						try {
							WiFiDirectActivity.to_storage.put(got);
							ChunkRequest.syncCreate();
							WiFiDirectActivity.to_wifi.put(new ChunkRequest(got));
						} catch (InterruptedException e) {
							e.printStackTrace();
						}
					}
	    		}
			}
		}		
    }
}

class RouteCellularToWiFi implements Runnable {
	
	public void run() {
		Log.d(WiFiDirectActivity.TAG, "RouteCellularToWiFi Thread Start");
		while(true) {
			ChunkRequest got = null;
			try {
				got = WiFiDirectActivity.from_cellular.take();
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			Log.d(WiFiDirectActivity.TAG, "RouteCellularToWiFi Saw: " + got.id + " from " + got.device);
			synchronized(WiFiDirectActivity.last_cellular){
				WiFiDirectActivity.last_cellular = System.nanoTime();
			}
			try {
				WiFiDirectActivity.to_storage.put(got);
				if (!got.device.equals(WiFiDirectActivity.device_id)) {
					ChunkRequest.syncCreate();
					WiFiDirectActivity.to_wifi.put(new ChunkRequest(got));
				}
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}		
    }
}
