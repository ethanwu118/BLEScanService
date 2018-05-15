package com.android.bleScanService;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.IBinder;
import android.util.Log;
import android.os.SystemProperties;
import java.io.IOException;
import java.math.BigInteger;

import java.util.ArrayList;
import java.util.List;
import android.os.Handler;
import android.os.Message;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.os.RemoteException;
import android.provider.Settings;
import android.database.ContentObserver;
import com.tinno.feature.FeatureMBA;

public class BleScanService extends Service{
	private static final String TAG = "BleScanService";
	private Context mContext;
	private ScreenBroadcastReceiver mScreenReceiver;
	private BluetoothManager BTManager;
	private BluetoothAdapter BTAdapter;
	private BluetoothLeScanner mScanner;
	private boolean scanning;
	private boolean mIsScreenOn;
	private Handler handler;
	private int mBtSettingValue;
	private int mRetryTimes =0;
	private String DeviceName = android.os.SystemProperties.get("persist.specific_ble_name", "BR521684,BR501980");
	private long SCAN_TIMEOUT_MS = android.os.SystemProperties.getInt("persist.check.timeout", 2000);
	private long PERIOD_SCAN_TIME_MS = android.os.SystemProperties.getInt("persist.check.period_time", 180000);//default 3 min
	private long AIRPLANE_ON_SCAN_DELAY_TIME_MS =1500 ;
	private long SCAN_FAIL_RETRY_DELAY_TIME_MS =200 ;
	private long TURN_ON_BLE_FAIL_DELAY_TIME_MS =3000 ;

	private static final int MSG_STOP_SCAN= 100;
	private static final int MSG_TIMEOUT_STOP_SCAN= 101;
	private static final int MSG_TURN_ON_BLE= 102;
	private static final int MSG_SCAN_FAIL_RETRY= 103;
	private static final int MSG_BOOTUP_DELAY_SCAN= 104;
	private static final int MSG_PERIOD_SCAN= 105;
	private static final int MSG_FORCE_DISABLE_BT= 106;

	public void onCreate() {
		super.onCreate();
		Log.d(TAG, "onCreate");
		mContext = getBaseContext();
		setBleProperty(false);
		mScreenReceiver = new ScreenBroadcastReceiver();
		startObserver();

		BTManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
		BTAdapter = BTManager.getAdapter();
		mScanner = BTAdapter.getBluetoothLeScanner();
		scanning = false;
		mIsScreenOn=true;
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		Log.d(TAG, "onStartCommand");
		mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_BOOTUP_DELAY_SCAN), 3000);
		mContext.getContentResolver().registerContentObserver(
			Settings.Global.getUriFor(Settings.Global.BLUETOOTH_ON), true,mBtSettingStateObserver);
		return START_STICKY;
	}
	
	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	public void onDestroy() {
		Log.d(TAG, "onDestroy");
		super.onDestroy();
	}

	public void startObserver() {
		registerListener();
	}

	private void registerListener() {
		if(mContext != null){
			IntentFilter filter = new IntentFilter();
			filter.addAction(Intent.ACTION_SCREEN_ON);
			filter.addAction(Intent.ACTION_SCREEN_OFF);
			filter.addAction(Intent.ACTION_USER_PRESENT);
			filter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
			mContext.registerReceiver(mScreenReceiver, filter);
		}
	}

	private void startScanning() {
		Log.d(TAG, "Start scanning()");
		boolean retVal =false;
		if(!isSupportRestrictCamera()) {
			Log.d(TAG, "No support to restrict camera");
			return;
		}
		if(BTManager ==null) {
			Log.d(TAG, "No Bluetooth Manager");
	 		BTManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
	 	}
  		if (BTAdapter == null) {
			BTAdapter = BTManager.getAdapter();
			if(BTAdapter == null){
   				Log.d(TAG, "No Bluetooth Adapter");
   				return;
			}
  		}
		if(mScanner == null) {
			 mScanner = BTAdapter.getBluetoothLeScanner();
		}

		if(!BTAdapter.isLeEnabled()){
			IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_BLE_STATE_CHANGED);
			mContext.registerReceiver(mBtLeStateChangeReceiver, filter);

			Log.d(TAG, "BLE is disabled, try to enable");
			retVal = BTAdapter.enableBLE();
			if (!retVal) {
				Log.d(TAG, "BLE can not enable, delay and try again");
				mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_BOOTUP_DELAY_SCAN), TURN_ON_BLE_FAIL_DELAY_TIME_MS);
			}
			else {
			 	// enable BLE successful and if state is BLE_ON, then start scan
				Log.d(TAG, "Register BLE state change");
			}
			return;
		} else {
			Log.d(TAG, "BLE already enabled, start to scan");
		}

		if (scanning == false) {
			mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_TIMEOUT_STOP_SCAN), SCAN_TIMEOUT_MS);

			List<ScanFilter> filters = new ArrayList<ScanFilter>();
			final String[] splittedDeviceName = DeviceName.split(",");

			for (String s : splittedDeviceName) {
				ScanFilter.Builder serviceFilter = new ScanFilter.Builder();
				serviceFilter.setDeviceName(s);
				filters.add(serviceFilter.build());
			}

			ScanSettings.Builder settings = new ScanSettings.Builder();
			settings.setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY);//SCAN_MODE_LOW_POWER,SCAN_MODE_LOW_LATENCY
			settings.setReportDelay(0);//0: trigger onScanResult(); >0:trigger onBatchScanResults()

			Log.d(TAG, "ready to scan...");
			try {
				scanning = true;
				mScanner.startScan(filters, settings.build(), mScanCallback);
			} catch (Exception e) {
				scanning = false;
				Log.e(TAG, "fail to start le scan: " + e.toString());
			}
		} else {
			Log.d(TAG, "has already scanned");
		}
	}

	private void stopScanning() {
		if(scanning){
			if (mScanner != null) {
				try{
					Log.d(TAG, "stopScanning");
					mScanner.stopScan(mScanCallback);
					scanning = false;
				}catch (IllegalStateException e) {
					Log.e(TAG, "fail to stop ble scan: " + e.toString());
				}
			}
		}
	}

	private void setBleProperty(boolean value){
		if(value){
			SystemProperties.set("persist.sys.restrict.camera", "true");
		}else {
			SystemProperties.set("persist.sys.restrict.camera", "false");
		}
	}

	private boolean isSupportRestrictCamera(){
		//boolean isSupport = FeatureMBA.MBA_FTR_BeaconChurchSouthAfrica_REQC8838Temp;
		//Log.d(TAG, "isSupportRestrictCamera : " + isSupport);
		return false;//isSupport;
	}

	private boolean isAirplaneTurnOn(){
		return Settings.Global.getInt(mContext.getContentResolver(),
						Settings.Global.AIRPLANE_MODE_ON, 0) == 1;
	}
	
	private void handleAirplaneStateChange(boolean mAirplaneState){
		Log.d(TAG, "handleAirplaneStateChange(), state = " + mAirplaneState);
		mHandler.removeMessages(MSG_PERIOD_SCAN);
		mHandler.removeMessages(MSG_BOOTUP_DELAY_SCAN);
		mHandler.removeMessages(MSG_SCAN_FAIL_RETRY);
		mHandler.removeMessages(MSG_TIMEOUT_STOP_SCAN);
		mRetryTimes = 0;
		try {
			if(mContext!=null){
				mContext.unregisterReceiver(mBtLeStateChangeReceiver);
			}
		} catch (IllegalArgumentException e) {
			Log.e(TAG, "ble Receiver not registered.");
		}

		if(mAirplaneState){
			mBtSettingValue = Settings.Global.getInt(mContext.getContentResolver(),
										   Settings.Global.BLUETOOTH_ON, -1);
			Log.d(TAG, "current bt settings state: " + mBtSettingValue);

			if(!scanning){
				stopScanning();
				Log.d(TAG, "try to disable BLE under airplane on");
				if(BTAdapter != null){
					boolean retVal = BTAdapter.disableBLE();
					if (!retVal) {
						Log.d(TAG, "BLE can not diable");
					}
				}
				//wait bt service shutting down if turn airplane on.
				mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_BOOTUP_DELAY_SCAN), AIRPLANE_ON_SCAN_DELAY_TIME_MS);
			}else{
				Log.d(TAG, "scanning..., skip" );
			}
		}else {
			if(scanning){
				stopScanning();
			}
			Log.d(TAG, "airplane off, set timer: " +PERIOD_SCAN_TIME_MS);
			mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_PERIOD_SCAN), PERIOD_SCAN_TIME_MS);
			if(mBtSettingValue == 0){
				mHandler.removeMessages(MSG_FORCE_DISABLE_BT);
				Log.d(TAG, "airplane off, need to disable BT, previous setting: " +mBtSettingValue);
				mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_FORCE_DISABLE_BT), 2000);
			}
		}
	}

	private ContentObserver mBtSettingStateObserver = new ContentObserver(new Handler()) {
		@Override
		public void onChange(boolean selfChange) {
			if(isAirplaneTurnOn()){
				int value = Settings.Global.getInt(mContext.getContentResolver(),
							Settings.Global.BLUETOOTH_ON, -1);
				Log.d(TAG, "airplane turn on and user change bt setting state : " +value );
				if(value==1 &&mBtSettingValue ==0){
					mBtSettingValue =1;
				}
			}
		}
	};

	private class ScreenBroadcastReceiver extends BroadcastReceiver {
		private String mAction = null;

		@Override
		public void onReceive(Context context, Intent intent) {
			mAction = intent.getAction();
			if (Intent.ACTION_SCREEN_ON.equals(mAction)) {
				Log.d(TAG, "ACTION_SCREEN_ON");
				mIsScreenOn = true;
				mRetryTimes = 0;
				startScanning();
			} else if (Intent.ACTION_SCREEN_OFF.equals(mAction)) {
				Log.d(TAG, "ACTION_SCREEN_OFF");
				mIsScreenOn = false;
				mRetryTimes = 0;
				mHandler.removeMessages(MSG_TIMEOUT_STOP_SCAN);
				mHandler.removeMessages(MSG_BOOTUP_DELAY_SCAN);
				mHandler.removeMessages(MSG_PERIOD_SCAN);
				setBleProperty(false);
				stopScanning();
			} else if (Intent.ACTION_USER_PRESENT.equals(mAction)) {
				Log.d(TAG, "ACTION_USER_PRESENT");
			}else if (Intent.ACTION_AIRPLANE_MODE_CHANGED.equals(mAction)) {
				boolean enabled = intent.getBooleanExtra("state", false);
				Log.d(TAG, "ACTION_AIRPLANE_MODE_CHANGED enabled =" + enabled);
				handleAirplaneStateChange(enabled);
			}
		}
	}

	private ScanCallback mScanCallback = new ScanCallback() {
		@Override
		public void onScanResult(int callbackType, ScanResult result) {
			BluetoothDevice device = result.getDevice();
			Log.d(TAG, "onScanResult, device: " +device.getName() );
			setBleProperty(true);
			mRetryTimes = 0;
			mHandler.sendMessage(mHandler.obtainMessage(MSG_STOP_SCAN));
			mHandler.removeMessages(MSG_TIMEOUT_STOP_SCAN);
			mHandler.removeMessages(MSG_PERIOD_SCAN);
			//set timer to rescan
			Log.d(TAG, "set rescan timer: " +PERIOD_SCAN_TIME_MS);
			mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_PERIOD_SCAN), PERIOD_SCAN_TIME_MS);

			//force to enable ble since we shoule disabled it.
			Log.d(TAG, "scan complete, try to disable BLE");
			if(BTAdapter != null){
				boolean retVal = BTAdapter.disableBLE();
				if (!retVal) {
					Log.d(TAG, "BLE can not diable");
				}
			}
		}

		@Override
		public void onBatchScanResults(List<ScanResult> results) {
			Log.d(TAG, "onBatchScanResults(), size : "+ results.size() );
			for (ScanResult r : results) {
				Log.d(TAG, "Batch scanResult: device name: " + r.getDevice().getName()
					+ " ,Alias name : " +  r.getDevice().getAliasName());
			}
		}

		@Override
		public void onScanFailed(int errorCode) {
			Log.w(TAG, "Scan failed: " + errorCode);
			mHandler.sendMessage(mHandler.obtainMessage(MSG_STOP_SCAN));
			mHandler.removeMessages(MSG_TIMEOUT_STOP_SCAN);
			mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_SCAN_FAIL_RETRY), SCAN_FAIL_RETRY_DELAY_TIME_MS);
		}
	};

	// Handle BLE state changes
	private final BroadcastReceiver mBtLeStateChangeReceiver = new BroadcastReceiver() {
		public void onReceive(Context context, Intent intent) {
			final String action = intent.getAction();
			if (action.equals(BluetoothAdapter.ACTION_BLE_STATE_CHANGED)) {
				int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
				switch (state) {
					case BluetoothAdapter.STATE_BLE_TURNING_ON:
						Log.d(TAG, "BtLeStateChangeReceiver STATE_BLE_TURNING_ON, wait BLE TURN ON event");
						break;
					case BluetoothAdapter.STATE_BLE_ON:
					   	Log.d(TAG, "BtLeStateChangeReceiver STATE_BLE_ON");
						mHandler.sendMessage(mHandler.obtainMessage(MSG_TURN_ON_BLE, state, 0));
						break;
					case BluetoothAdapter.STATE_BLE_TURNING_OFF:
						Log.d(TAG, "BtLeStateChangeReceiver STATE_BLE_TURNING_OFF, unregisterReceiver");
						mHandler.sendMessage(mHandler.obtainMessage(MSG_TURN_ON_BLE, state, 0));
						break;
				}
			}
		}
	};

	private final Handler mHandler = new Handler() {
			@Override
			public void handleMessage(Message msg) {
				switch (msg.what) {
					case MSG_STOP_SCAN:
						Log.d(TAG, "MSG_STOP_SCAN");
						stopScanning();
						if(!mIsScreenOn){
							setBleProperty(false);
						}
						break;
					case MSG_TIMEOUT_STOP_SCAN:
						Log.d(TAG, "MSG_TIMEOUT_STOP_SCAN, not find BLE device");
						setBleProperty(false);
						stopScanning();
						break;
					case MSG_TURN_ON_BLE:
						Log.d(TAG, "MSG_TURN_ON_BLE, BLE state: "+ msg.arg1);
						mContext.unregisterReceiver(mBtLeStateChangeReceiver);
						if(msg.arg1 == BluetoothAdapter.STATE_BLE_ON){
							startScanning();
						}
						break;
					case MSG_SCAN_FAIL_RETRY:
						Log.d(TAG, "MSG_SCAN_FAIL_RETRY");
						mRetryTimes++;
						if(mRetryTimes <=3){
							startScanning();
						}else{
							Log.d(TAG, "expire retry time");
						}
						break;
					case MSG_BOOTUP_DELAY_SCAN:
						Log.d(TAG, "MSG_BOOTUP_DELAY_SCAN");
						startScanning();
						break;
					case MSG_PERIOD_SCAN:
						Log.d(TAG, "MSG_PERIOD_SCAN");
						if(mIsScreenOn){
							mRetryTimes = 0;
							Log.d(TAG, "MSG_PERIOD_SCAN, start scan");
							startScanning();
						}else{
							Log.d(TAG, "MSG_PERIOD_SCAN, stop scan because screen off");
						}
						break;
					case MSG_FORCE_DISABLE_BT:
						Log.d(TAG, "MSG_FORCE_DISABLE_BT, try to disable BT");
						if(BTAdapter != null){
							boolean retVal = BTAdapter.disable();
							if (!retVal) {
								Log.d(TAG, "BT can not diable");
							}
						}
						break;
				}
			}
	};
}
