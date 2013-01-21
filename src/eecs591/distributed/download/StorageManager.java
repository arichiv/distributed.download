package eecs591.distributed.download;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.Serializable;
import java.net.URL;
import java.net.URLConnection;
import java.util.Hashtable;
import java.util.Map.Entry;
import java.util.concurrent.SynchronousQueue;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;
import android.util.Pair;

public class StorageManager implements Runnable {

	public void run() {
		Log.d(WiFiDirectActivity.TAG, "StorageManager Thread Start");
		new Thread(new StorageChunker()).start();
		new Thread(new StorageStorer()).start();
		new Thread(new StorageChecker()).start();
		new Thread(new StorageRequeuer()).start();
	}
}

class StorageChunker implements Runnable {

	public void run() {
		Log.d(WiFiDirectActivity.TAG, "StorageChunker Thread Start");
		while (true) {
			String url = null;
			try {
				url = WiFiDirectActivity.urls.take();
			} catch (InterruptedException e1) {
				e1.printStackTrace();
			}
			int size = 0;
			try {
				URL urlObj = new URL(url);
				URLConnection connection = urlObj.openConnection();
				connection.connect();
				size = connection.getContentLength();
			} catch (Exception e) {
				Log.e(WiFiDirectActivity.TAG, "", e);
			}
			Log.d(WiFiDirectActivity.TAG, "StorageChunker URL: " + url + " is size " + size);
			WiFiDirectActivity.part_states.put(url, new Hashtable<Integer,ChunkInfo>());
			for (int i = 0; i * WiFiDirectActivity.chunk_size < size; i++) {
				ChunkRequest.syncCreate();
				int s = Math.min(WiFiDirectActivity.chunk_size, size - i * WiFiDirectActivity.chunk_size);
				ChunkRequest request = new ChunkRequest(WiFiDirectActivity.device_id, url, i, s, null, true);
				WiFiDirectActivity.part_states.get(request.url).put(request.id, new ChunkInfo(request, ChunkState.NONE));
				try {
					WiFiDirectActivity.check_storage.put(new Pair<ChunkRequest, Pair<SynchronousQueue<ChunkRequest>, SynchronousQueue<ChunkRequest> > >(request, new Pair<SynchronousQueue<ChunkRequest>, SynchronousQueue<ChunkRequest> >(WiFiDirectActivity.to_storage, WiFiDirectActivity.from_storage)));
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
		}
	}
}

class StorageStorer implements Runnable {

	private boolean is_finished(String url) {
		synchronized(WiFiDirectActivity.part_states) {
			for (Entry<Integer, ChunkInfo> entry : WiFiDirectActivity.part_states.get(url).entrySet()) {
				if (entry.getValue().state != ChunkState.STORED) {
					Log.e(WiFiDirectActivity.TAG, "Missing: " + entry.getValue().id);
					return false;
				}
			}
		}
		return true;
	}
	
	public void run() {
		Log.d(WiFiDirectActivity.TAG, "StorageStorer Thread Start");
		while (true) {
			ChunkRequest chunk = null;
			try {
				chunk = WiFiDirectActivity.to_storage.take();
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			Log.d(WiFiDirectActivity.TAG, "StorageStorer Thread Saving: " + chunk.id);
			if (chunk.data != null) {
				synchronized(WiFiDirectActivity.db) {
					WiFiDirectActivity.db.storeChunk(chunk);
				}
			}
			ChunkRequest.syncDestroy();
			if (chunk.device.equals(WiFiDirectActivity.device_id)) {
				WiFiDirectActivity.part_states.get(chunk.url).get(chunk.id).state = ChunkState.STORED;
				if (is_finished(chunk.url)) {
					Log.d(WiFiDirectActivity.TAG, "StorageStorer Thread Opening: " + chunk.url);
					File f;
					synchronized(WiFiDirectActivity.db) {
						f = WiFiDirectActivity.db.getFile(chunk.url);
					}
					Intent intent = new Intent();
	                intent.setAction(android.content.Intent.ACTION_VIEW);
	                intent.setDataAndType(Uri.fromFile(f), URLConnection.guessContentTypeFromName(f.getName()));
	                WiFiDirectActivity.context.startActivity(intent);
				}
			}
		}
	}
}

class StorageRequeuer implements Runnable {
	
	private ChunkInfo oldest(ChunkState state) {
		ChunkInfo ret = null;
		for (Entry<String, Hashtable<Integer,ChunkInfo> > ent_a : WiFiDirectActivity.part_states.entrySet()) {
			for (Entry<Integer, ChunkInfo> ent_b : ent_a.getValue().entrySet()) {
				if (ent_b.getValue().device.equals(WiFiDirectActivity.device_id) && ent_b.getValue().state == state && (ret == null || ent_b.getValue().timestamp < ret.timestamp)){
					ret = ent_b.getValue();
				}
			}
		}
		return ret;
	}
	
	private ChunkInfo try_requeue(Long timer, SynchronousQueue<ChunkRequest> queue, ChunkState sentinalState, ChunkState nextState, ChunkInfo prev) {
		synchronized(timer) {
			if (System.nanoTime() - timer < WiFiDirectActivity.threshold) {
				return prev;
			}
		}
		if (!queue.isEmpty()) {
			return prev;
		}
		ChunkInfo old = oldest(sentinalState);
		if (old == prev) {
			return prev;
		}
		try {
			old.timestamp = System.nanoTime();
			old.state = nextState;
			ChunkRequest.syncCreate();
			queue.put(new ChunkRequest(old));
			Log.i(WiFiDirectActivity.TAG, "StorageRequeuer: Requeuing part " + old.id);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		return old;
	}
	
	public void run() {
		Log.d(WiFiDirectActivity.TAG, "StorageRequeuer Thread Start");
		while (true) {
			try {
				Thread.sleep(WiFiDirectActivity.sleep);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			ChunkInfo last = try_requeue(WiFiDirectActivity.last_wifi, WiFiDirectActivity.to_cellular, ChunkState.QUEUED_WIFI, ChunkState.QUEUED_CELLULAR, null);
			try_requeue(WiFiDirectActivity.last_cellular, WiFiDirectActivity.to_wifi, ChunkState.QUEUED_CELLULAR, ChunkState.QUEUED_WIFI, last);
		}
	}
}

class StorageChecker implements Runnable {

	public void run() {
		Log.d(WiFiDirectActivity.TAG, "StorageChecker Thread Start");
		while (true) {
			Pair<ChunkRequest, Pair<SynchronousQueue<ChunkRequest>, SynchronousQueue<ChunkRequest> > > chunk_pair = null;
			try {
				chunk_pair = WiFiDirectActivity.check_storage.take();
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			boolean is_member = false;;
			Log.d(WiFiDirectActivity.TAG, "StorageChecker Thread Checking: " + chunk_pair.first.id);
			synchronized(WiFiDirectActivity.db) {
				is_member = WiFiDirectActivity.db.hasChunk(chunk_pair.first);
			}
			SynchronousQueue<ChunkRequest> dest_queue = null;
			if (is_member) {
				dest_queue = chunk_pair.second.first;
			} else {
				dest_queue = chunk_pair.second.second;
			}
			if (dest_queue != null) {
				try {
					dest_queue.put(chunk_pair.first);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
		}
	}
}

enum ChunkState {NONE, QUEUED_CELLULAR, QUEUED_WIFI, STORED};

class ChunkInfo {
	public String device;
	public String url;
	public int id;
	public int size;
	public ChunkState state;
	public long timestamp;

	public ChunkInfo(ChunkRequest chunk, ChunkState state) {
		this.timestamp = System.nanoTime();
		this.device = chunk.device;
		this.url = chunk.url;
		this.id = chunk.id;
		this.size = chunk.size;
		this.state = state;
	}
}

class ChunkRequest implements Serializable {
	private static final long serialVersionUID = -2185582460485671651L;
	public String device;
	public String url;
	public int id;
	public int size;
	public byte[] data;
	public boolean cache;
	
	public ChunkRequest(String device, String url, int id, int size, byte[] data, boolean cache) {
		this.device = device;
		this.url = url;
		this.id = id;
		this.size = size;
		this.data = data;
		this.cache = cache;
	}
	
	public ChunkRequest(ChunkRequest chunk) {
		this.device = chunk.device;
		this.url = chunk.url;
		this.id = chunk.id;
		this.size = chunk.size;
		this.data = chunk.data;
		this.cache = chunk.cache;
	}
	
	public ChunkRequest(ChunkInfo ci) {
		this(ci.device, ci.url, ci.id, ci.size, null, false);
	}
	
	public static void syncCreate() {
		try {
			WiFiDirectActivity.chunk_tracker.acquire();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}
	
	public static void syncDestroy() {
		WiFiDirectActivity.chunk_tracker.release();
	}
}

class SQLStore extends SQLiteOpenHelper {
    private static final int DATABASE_VERSION = 1;
    private static final String DATABASE_NAME = "sql_store";
    private static final String TABLE_NAME = "chunk_store";
 
    private static final String KEY_URL = "url";
    private static final String KEY_ID = "id";
    private static final String KEY_DATA = "data";
 
    public SQLStore(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }
 
    @Override
    public void onCreate(SQLiteDatabase db) {
        String CREATE_TABLE = "CREATE TABLE " + TABLE_NAME + "(" + KEY_URL + " TEXT," + KEY_ID + " INTEGER," + KEY_DATA + " BLOB, PRIMARY KEY (" + KEY_URL + "," + KEY_ID + "))";
        db.execSQL(CREATE_TABLE);
    }
 
    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_NAME);
        onCreate(db);
    }
    
    public boolean hasChunk(ChunkRequest chunk) {
    	String selectQuery = "SELECT  " + KEY_DATA + " FROM " + TABLE_NAME + " WHERE " + KEY_ID + "=" + chunk.id + " AND " + KEY_URL + "=\"" + chunk.url + "\"";
        SQLiteDatabase db = this.getWritableDatabase();
        Cursor cursor = db.rawQuery(selectQuery, null);
        boolean has = cursor.moveToFirst();
        if (has) {
        	try {
        		chunk.data = cursor.getBlob(0);
			} catch (Exception e) {
				e.printStackTrace();
			}
        }
        cursor.close();
        db.close();
        return has;
    }

    public void storeChunk(ChunkRequest chunk) {
    	SQLiteDatabase db = this.getWritableDatabase();
    	ContentValues values = new ContentValues();
        values.put(KEY_URL, chunk.url);
        values.put(KEY_ID, chunk.id);
        values.put(KEY_DATA, chunk.data);
        db.insertWithOnConflict(TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_REPLACE);
        db.close();
    }
    
    public File getFile(String url) {
        String selectQuery = "SELECT  " + KEY_DATA + " FROM " + TABLE_NAME + " WHERE " + KEY_URL + " = \"" + url + "\" ORDER BY " + KEY_ID;
        SQLiteDatabase db = this.getWritableDatabase();
        Cursor cursor = db.rawQuery(selectQuery, null);
        String[] split = url.split("\\.");
        File outputFile = null;
        FileOutputStream fstream = null;;
		try {
			outputFile = new File(Environment.getExternalStorageDirectory() + "/WiFiDirect/" + System.currentTimeMillis() + "." + split[split.length - 1]);
			File dirs = new File(outputFile.getParent());
			if (!dirs.exists())
                dirs.mkdirs();
			outputFile.createNewFile();
			fstream = new FileOutputStream(outputFile);
		} catch (IOException e) {
			e.printStackTrace();
		}
        if (cursor.moveToFirst()) {
            do {
            	try {
            		fstream.write(cursor.getBlob(0));
				} catch (IOException e) {
					e.printStackTrace();
				}
            } while (cursor.moveToNext());
        }
        try {
			fstream.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
        cursor.close();
        db.close();
        return outputFile;
    }
}
