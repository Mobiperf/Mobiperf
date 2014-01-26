/* Copyright 2012 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mobiperf;

import com.mobiperf.measurements.HttpTask;
import com.mobiperf.measurements.RRCTask;
import com.mobiperf.measurements.TCPThroughputTask;
import com.mobiperf.util.PhoneUtils;

import android.content.Context;
import android.content.Intent;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.util.Calendar;
import java.util.concurrent.Callable;

/**
 * A basic power manager implementation that decides whether a measurement can be scheduled
 * based on the current battery level: no measurements will be scheduled if the current battery
 * is lower than a threshold.
 */
public class BatteryCapPowerManager {
	/** The minimum threshold below which no measurements will be scheduled */
	private int minBatteryThreshold;
	private Context context = null;
	private int dataLimit;//in Byte


	public BatteryCapPowerManager(int batteryThresh, Context context) {
		this.minBatteryThreshold = batteryThresh;
		this.dataLimit=-1;
		this.context=context;
	}

	/** 
	 * Sets the minimum battery percentage below which measurements cannot be run.
	 * 
	 * @param batteryThresh the battery percentage threshold between 0 and 100
	 */
	public synchronized void setBatteryThresh(int batteryThresh) throws IllegalArgumentException {
		if (batteryThresh < 0 || batteryThresh > 100) {
			throw new IllegalArgumentException("batteryCap must fall between 0 and 100, inclusive");
		}
		this.minBatteryThreshold = batteryThresh;
	}

	public synchronized int getBatteryThresh() {
		return this.minBatteryThreshold;
	}

	public synchronized void setDataUsageLimit(String dataLimitStr){
		if(dataLimitStr.equals("0 MB")){
			dataLimit=0;

		}else if(dataLimitStr.equals("50 MB")){
			dataLimit=50*1024*1024;
		}else if(dataLimitStr.equals("100 MB")){
			dataLimit=100*1024*1024;
		}else if(dataLimitStr.equals("250 MB")){
			dataLimit=250*1024*1024;
		}else if(dataLimitStr.equals("500 GB")){
			dataLimit=500*1024*1024;
		}else if(dataLimitStr.equals("1 GB")){
			dataLimit=1024*1024*1024;
		}else{
			dataLimit=-1;
		}

	}

	public synchronized int getDataLimit() {
		return this.dataLimit;
	}

	private boolean isOverDataLimit(String nextTaskType) throws IOException{
		
		if(getDataLimit()==0 ){
			return true;
		}else if(getDataLimit()==50*1024*1024 && nextTaskType.equals(TCPThroughputTask.TYPE)){
			return true;
		}
		long usageStartTimeSec=-1;
		long dataUsed=-1;

		File file = new File(context.getFilesDir(), "datausage");
		if(file.exists()){
			String content="";
			BufferedReader br = new BufferedReader(new FileReader(file));
			String line;
			while ((line = br.readLine()) != null) {
				content+=line;
			}
			String[] toks = content.split("_");
			usageStartTimeSec=Long.parseLong(toks[0]);
			dataUsed=Long.parseLong(toks[1]);
			br.close();
		}
		if(dataUsed==-1 || usageStartTimeSec==-1){
			return false;
		}else if((System.currentTimeMillis()/1000)-usageStartTimeSec>Config.DEFAULT_DATA_MONITOR_PERIOD_HOUR*60*60){
			return false;
		}else if(dataUsed>=getDataLimit()){
			return true;
		}
		return false;
	}

	private void updateDataUsage(MeasurementResult result, String taskType) throws IOException {
		int taskDataUsed=0;
		if(taskType.equals(TCPThroughputTask.TYPE) &&
				result.getResult("total_data_sent_received")!=null){
			taskDataUsed=(Integer)(result.getResult("total_data_sent_received"));
		}else if(taskType.equals(RRCTask.TYPE)){
			taskDataUsed=RRCTask.AVG_DATA_USAGE_KBYTE*1024;
		}else if(taskType.equals(HttpTask.TYPE) && 
				result.getResult("headers_len")!=null && 
				result.getResult("body_len")!=null){
			taskDataUsed=((Integer)(result.getResult("headers_len"))+((Integer)(result.getResult("body_len"))));
		}


		File file = new File(context.getFilesDir(), "datausage");
		if(file.exists()){
			String content="";
			BufferedReader br = new BufferedReader(new FileReader(file));
			String line;
			while ((line = br.readLine()) != null) {
				content+=line;
			}
			String[] toks = content.split("_");
			long usageStartTimeSec=Long.parseLong(toks[0]);
			long dataUsed=Long.parseLong(toks[1]);
			br.close();

			file.createNewFile();
			FileOutputStream outputStream;
			outputStream = context.openFileOutput("datausage", Context.MODE_PRIVATE);

			if((long)(System.currentTimeMillis()/1000)-usageStartTimeSec>Config.DEFAULT_DATA_MONITOR_PERIOD_HOUR*60*60){
				dataUsed=taskDataUsed;
				usageStartTimeSec=(System.currentTimeMillis()/1000);
			}else{
				dataUsed+=taskDataUsed;
			}
			String usageStat=usageStartTimeSec+"_"+dataUsed;
			outputStream.write(usageStat.getBytes());
			outputStream.close();

		}else{
			file.createNewFile();
			FileOutputStream outputStream;
			outputStream = context.openFileOutput("datausage", Context.MODE_PRIVATE);
			long dataUsed=taskDataUsed;
			long usageStartTimeSec=(System.currentTimeMillis()/1000);
			String usageStat=usageStartTimeSec+"_"+dataUsed;
			outputStream.write(usageStat.getBytes());
			outputStream.close();
		}

	}


	/** 
	 * Returns whether a measurement can be run.
	 */
	public synchronized boolean canScheduleExperiment() {
		return (PhoneUtils.getPhoneUtils().isCharging() || 
				PhoneUtils.getPhoneUtils().getCurrentBatteryLevel() > minBatteryThreshold);
	}

	/**
	 * A task wrapper that is power aware, the real logic is carried out by realTask
	 * 
	 * @author wenjiezeng@google.com (Steve Zeng)
	 *
	 */
	public static class PowerAwareTask implements Callable<MeasurementResult> {

		private MeasurementTask realTask;
		private BatteryCapPowerManager pManager;
		private MeasurementScheduler scheduler;

		public PowerAwareTask(MeasurementTask task, BatteryCapPowerManager manager, 
				MeasurementScheduler scheduler) {
			realTask = task;
			pManager = manager;
			this.scheduler = scheduler;
		}

		private void broadcastMeasurementStart() {
			Logger.i("Starting PowerAwareTask " + realTask);
			Intent intent = new Intent();
			intent.setAction(UpdateIntent.SYSTEM_STATUS_UPDATE_ACTION);
			intent.putExtra(UpdateIntent.STATUS_MSG_PAYLOAD, "Running " + realTask.getDescriptor());

			scheduler.sendBroadcast(intent);
		}

		private void broadcastMeasurementEnd(MeasurementResult result, MeasurementError error) {
			Logger.i("Ending PowerAwareTask " + realTask);
			// Only broadcast information about measurements if they are true errors.
			if (!(error instanceof MeasurementSkippedException)) {
				Intent intent = new Intent();
				intent.setAction(UpdateIntent.MEASUREMENT_PROGRESS_UPDATE_ACTION);
				intent.putExtra(UpdateIntent.TASK_PRIORITY_PAYLOAD, 
						(int) realTask.getDescription().priority);
				// A progress value MEASUREMENT_END_PROGRESS indicates the end of an measurement
				intent.putExtra(UpdateIntent.PROGRESS_PAYLOAD, Config.MEASUREMENT_END_PROGRESS);


				if (result != null) {
					intent.putExtra(UpdateIntent.STRING_PAYLOAD, result.toString());
				} else {
					String errorString = "Measurement " + realTask.toString() + " failed. ";
					errorString += "\n\nTimestamp: " + Calendar.getInstance().getTime();
					if (error != null) {
						errorString += "\n\n" + error.toString();
					} 
					intent.putExtra(UpdateIntent.ERROR_STRING_PAYLOAD, errorString);
				}
				scheduler.sendBroadcast(intent);
			}
			scheduler.updateStatus();
		}

		@Override
		public MeasurementResult call() throws MeasurementError {
			MeasurementResult result = null;
			scheduler.sendStringMsg("Running:\n" + realTask.toString());
			try {
				PhoneUtils.getPhoneUtils().acquireWakeLock();
				if (scheduler.isPauseRequested()) {
					Logger.i("Skipping measurement - scheduler paused");
					throw new MeasurementSkippedException("Scheduler paused");
				}
				if (!pManager.canScheduleExperiment()) {
					Logger.i("Skipping measurement - low battery");
					throw new MeasurementSkippedException("Not enough battery power");
				}
				
				if (PhoneUtils.getPhoneUtils().getCurrentNetworkConnection()==PhoneUtils.TYPE_MOBILE){
						try {
							if(pManager.isOverDataLimit(realTask.getMeasurementType())) {
								scheduler.sendStringMsg("No cellular data is available for a server " +
							realTask.getDescription().type+" task");
								Logger.i("Skipping measurement - data limit is passed");
								throw new MeasurementSkippedException("Over data limit");
							}
						} catch (IOException e) {
							Logger.e("Exception occured during R/Wing of data stat file");
							e.printStackTrace();
						}
					
				}
				scheduler.setCurrentTask(realTask);
				broadcastMeasurementStart();
				try {
					Logger.i("Calling PowerAwareTask " + realTask);
					result = realTask.call(); 
					Logger.i("Got result " + result);
					pManager.updateDataUsage(result,realTask.getDescription().type);
					broadcastMeasurementEnd(result, null);
					return result;
				} catch (MeasurementError e) {
					Logger.e("Got MeasurementError running task", e);
					broadcastMeasurementEnd(null, e);
					throw e;
				} catch (Exception e) {
					Logger.e("Got exception running task", e);
					MeasurementError err = new MeasurementError("Got exception running task", e);
					broadcastMeasurementEnd(null, err);
					throw err;
				}
			} finally {
				PhoneUtils.getPhoneUtils().releaseWakeLock();
				scheduler.setCurrentTask(null);
				scheduler.sendStringMsg("Done running:\n" + realTask.toString());
			}
		}
	}
}
