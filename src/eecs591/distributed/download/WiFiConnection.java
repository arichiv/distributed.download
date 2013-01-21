package eecs591.distributed.download;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import android.content.Context;
import android.util.Log;



public class WiFiConnection implements Runnable {

	protected Context context;
	protected String host;

	public WiFiConnection(String host) {
		this.host = host;
	}

	public void run() {
		Log.d(WiFiDirectActivity.TAG, "WiFiConnection Thread Start");
		ObjectInputStream oistream = null;
		ObjectOutputStream oostream = null;
		try {
			Socket socket = new Socket();
			socket.bind(null);
			socket.connect((new InetSocketAddress(this.host, WiFiDirectActivity.port)), 100000);
			oostream = new ObjectOutputStream(socket.getOutputStream());
			oistream = new ObjectInputStream(socket.getInputStream());
			
		} catch (Exception e) {
			Log.e(WiFiDirectActivity.TAG, "", e);
		}
		new Thread(new WiFiInConnection(oistream)).start();
		new Thread(new WiFiOutConnection(oostream)).start();

	}
}

class WiFiServerConnection implements Runnable {

	public void run() {
		try {
			Log.d(WiFiDirectActivity.TAG, "WiFiServerConnection Start");
			ServerSocket server = new ServerSocket(WiFiDirectActivity.port);
			while (true) {
				Socket socket = server.accept();
				Log.d(WiFiDirectActivity.TAG, "WiFiServerConnection Accept");
				new Thread(new WiFiInConnection(new ObjectInputStream(socket.getInputStream()))).start();
				new Thread(new WiFiOutConnection(new ObjectOutputStream(socket.getOutputStream()))).start();
			} 
		} catch (IOException e) {
			Log.e(WiFiDirectActivity.TAG, "", e);
		}
	}
}

class WiFiInConnection implements Runnable {

	protected ObjectInputStream oistream;

	public WiFiInConnection(ObjectInputStream oistream) {
		this.oistream = oistream;
	}

	public void run() {
		Log.d(WiFiDirectActivity.TAG, "WiFiInConnection Thread Start");
		WiFiDirectActivity.device_tracker.release();
		while(true) {
			try {
				ChunkRequest recv = (ChunkRequest) this.oistream.readObject();
				ChunkRequest.syncCreate();
				Log.d(WiFiDirectActivity.TAG, "WiFiInConnection Saw:" + recv.id);
				WiFiDirectActivity.from_wifi.put(recv);
			} catch (Exception e) {}
		}
	}
}

class WiFiOutConnection implements Runnable {

	protected ObjectOutputStream oostream;

	public WiFiOutConnection(ObjectOutputStream oostream) {
		this.oostream = oostream;
	}

	public void run() {
		Log.d(WiFiDirectActivity.TAG, "WiFiOutConnection Thread Start");
		while(true) {
			ChunkRequest send = null;
			try {
				send = WiFiDirectActivity.to_wifi.take();
			} catch (InterruptedException e1) {
				e1.printStackTrace();
			}
			try {
				Log.d(WiFiDirectActivity.TAG, "WiFiOutConnection Saw:" + send.id);
				this.oostream.writeObject(send);
				ChunkRequest.syncDestroy();
			} catch (Exception e) {
				Log.e(WiFiDirectActivity.TAG, "", e);
			}	
		}
	}
}
