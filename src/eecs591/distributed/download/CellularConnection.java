package eecs591.distributed.download;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;

import android.util.Log;
import android.widget.Toast;

public class CellularConnection implements Runnable {
	public void run() {
    	Log.d(WiFiDirectActivity.TAG, "CellularConnection Thread Start");
    	while(true) {
    		ChunkRequest chunk = null;
			try {
				chunk = WiFiDirectActivity.to_cellular.take();
			} catch (InterruptedException e1) {
				e1.printStackTrace();
			}
			try {
				Log.d(WiFiDirectActivity.TAG, "CellularConnection Thread Fetching: " + chunk.id + " of "+ chunk.url);
				URL urlObj = new URL(chunk.url);
                URLConnection connection = urlObj.openConnection();
                connection.setRequestProperty("Range", "bytes=" + (chunk.id * WiFiDirectActivity.chunk_size) + "-");
                connection.connect();
                InputStream input = new BufferedInputStream(connection.getInputStream());
                byte[] data = new byte[chunk.size];
                int read = 0;
                while (read < chunk.size) {
                	int ret = input.read(data, read, chunk.size - read);
                	if (ret == -1) {
                		continue;
                	} else {
                		read += ret;
                	}
                }
                input.close();
                Log.d(WiFiDirectActivity.TAG, "CellularConnection Thread Fetched: " + chunk.id + " of "+ chunk.url);
                ToastChunk toast = new ToastChunk();
                toast.id = chunk.id;
                WiFiDirectActivity.context.runOnUiThread(toast);
                chunk.data = data;
			} catch (Exception e) {
				Log.e(WiFiDirectActivity.TAG, "", e);
			}
			try {
				WiFiDirectActivity.from_cellular.put(chunk);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
    	}
    }
}

class ToastChunk implements Runnable  {
	int id;
	public void run() {
    	Toast.makeText(WiFiDirectActivity.context, "Downloaded Chunk: " + id, Toast.LENGTH_SHORT).show();
    }
}