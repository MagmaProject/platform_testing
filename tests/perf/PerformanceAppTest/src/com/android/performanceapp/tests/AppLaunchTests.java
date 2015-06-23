/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.performanceapp.tests;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import android.app.ActivityManager;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Bundle;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.support.test.InstrumentationRegistry;
import android.test.InstrumentationTestCase;
import android.test.suitebuilder.annotation.MediumTest;
import android.util.Log;

/**
 * To test the App launch performance on the given target package for the list of activities. It
 * launches the activities present in the target package the number of times in launch count or it
 * launches only the activities mentioned in custom activity list the launch count times and returns
 * the path to the files where the atrace logs are stored corresponding to each activity launch
 */
public class AppLaunchTests extends InstrumentationTestCase {

    private static final String TAG = "AppLaunchInstrumentation";
    private static final String TARGETPACKAGE = "targetpackage";
    private static final String ACTIVITYLIST = "activitylist";
    private static final String LAUNCHCOUNT = "launchcount";
    private static final String ATRACE_START = "atrace --async_start am view gfx";
    private static final String ATRACE_DUMP = "atrace --async_dump";
    private static final String ATRACE_STOP = "atrace --async_stop";
    private static final String FORCE_STOP = "am force-stop ";

    private Context mContext;
    private Bundle mResult;
    private String mTargetPackageName;
    private int mLaunchCount;
    private String mCustomActivityList;
    private PackageInfo mPackageInfo;
    private List<String> mActivityList;

    /**
     * {@inheritDoc}
     */
    @Override
    public void setUp() throws Exception {
        super.setUp();
        mContext = getInstrumentation().getTargetContext();
        assertNotNull("Failed to get context", mContext);
        Bundle args = InstrumentationRegistry.getArguments();
        assertNotNull("Unable to get the args", args);
        mTargetPackageName = args.getString(TARGETPACKAGE);
        assertNotNull("Target package name not set", mTargetPackageName);
        mCustomActivityList = args.getString(ACTIVITYLIST);
        if (mCustomActivityList == null || mCustomActivityList.isEmpty()) {
            // Get full list of activities from the target package
            mActivityList = getActivityList("");
        } else {
            // Get only the user defined list of activities from the target package
            mActivityList = getActivityList(mCustomActivityList);
        }
        assertTrue("Activity List is empty", (mActivityList.size() > 0));
        mLaunchCount = Integer.parseInt(args.getString(LAUNCHCOUNT));
        assertTrue("Invalid Launch Count", mLaunchCount > 0);
        mResult = new Bundle();
    }

    @MediumTest
    public void testAppLaunchPerformance() throws Exception {
        assertTrue("Cannot write in External File", isExternalStorageWritable());
        File root = Environment.getExternalStorageDirectory();
        assertNotNull("Unable to get the root of the external storage", root);
        File logsDir = new File(root, "atrace_logs");
        assertTrue("Unable to create the directory to store atrace logs", logsDir.mkdir());
        for (int count = 0; count < mLaunchCount; count++) {
            for (String activityName : mActivityList) {
                ComponentName cn = new ComponentName(mTargetPackageName,
                        activityName);
                Intent intent = new Intent(Intent.ACTION_MAIN);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
                intent.setComponent(cn);

                // Start the atrace
                assertNotNull(
                        "Unable to start atrace async",
                        getInstrumentation().getUiAutomation().executeShellCommand(ATRACE_START));
                // Sleep for 10 secs to make sure atrace command is started
                Thread.sleep(10 * 1000);

                // Launch the activity
                mContext.startActivity(intent);
                Thread.sleep(5 * 1000);

                // Dump atrace info and write it to file
                int processId = getProcessId(mTargetPackageName);
                assertTrue("Not able to retrive the process id for the package:"
                        + mTargetPackageName, processId > 0);
                String fileName = String.format("%s-%d-%d", activityName, count, processId);
                ParcelFileDescriptor parcelFile =
                        getInstrumentation().getUiAutomation().executeShellCommand(ATRACE_DUMP);
                assertNotNull("Unable to get the File descriptor to standard out",
                        parcelFile);
                InputStream inputStream = new FileInputStream(parcelFile.getFileDescriptor());
                File file = new File(logsDir, fileName);
                FileOutputStream outputStream = new FileOutputStream(file);
                try {
                    byte[] buffer = new byte[1024];
                    int length;
                    while ((length = inputStream.read(buffer)) > 0) {
                        outputStream.write(buffer, 0, length);
                    }
                } catch (IOException e) {
                    Log.w(TAG, "Error writing atrace info to file", e);
                }
                inputStream.close();
                outputStream.close();

                // Stop the atrace
                assertNotNull(
                        "Unable to stop the atrace",
                        getInstrumentation().getUiAutomation().executeShellCommand(ATRACE_STOP));

                // To keep track of the activity name,list of atrace file name
                registerTraceFileNames(activityName, fileName);
                assertNotNull("Unable to stop atrace async",
                        getInstrumentation().getUiAutomation().executeShellCommand(
                                FORCE_STOP + mTargetPackageName));
                Thread.sleep(5 * 1000);
            }
        }
        getInstrumentation().sendStatus(0, mResult);
    }

    /**
     * Method to check if external storage is writable
     * @return
     */
    public boolean isExternalStorageWritable() {
        return Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState());
    }

    /**
     * Method to get list of activities present in given target package If customActivityList is
     * passed then include only those activities
     * @return list of activity names
     */
    private List<String> getActivityList(String customActivityList) {
        mActivityList = new ArrayList<String>();
        try {
            mPackageInfo = mContext.getPackageManager().getPackageInfo(
                    mTargetPackageName, 1);
            assertNotNull("Unable to get  the target package info", mPackageInfo);
        } catch (NameNotFoundException e) {
            fail(String.format("Target application: %s not found", mTargetPackageName));
        }
        for (ActivityInfo activityInfo : mPackageInfo.activities) {
            mActivityList.add(activityInfo.name);
        }
        if (!customActivityList.isEmpty()) {
            List<String> finalActivityList = new
                    ArrayList<String>();
            String customList[] = customActivityList.split(",");
            for (int count = 0; count < customList.length; count++) {
                if (mActivityList.contains(customList[count])) {
                    finalActivityList.add(customList[count]);
                } else {
                    fail(String.format("Activity: %s not present in the target package : %s ",
                            customList[count], mTargetPackageName));
                }
            }
            mActivityList = finalActivityList;
        }
        return mActivityList;
    }

    /**
     * Method to retrieve process id from the activity manager
     * @param processName
     * @return
     */
    private int getProcessId(String processName) {
        ActivityManager am = (ActivityManager) getInstrumentation()
                .getContext().getSystemService(Context.ACTIVITY_SERVICE);
        List<RunningAppProcessInfo> appsInfo = am.getRunningAppProcesses();
        assertNotNull("Unable to retrieve running apps info", appsInfo);
        for (RunningAppProcessInfo appInfo : appsInfo) {
            if (appInfo.processName.equals(processName)) {
                return appInfo.pid;
            }
        }
        return -1;
    }

    /**
     * To add the process id to the result map
     * @param activityNamereturn
     * @return
     * @throws IOException
     */
    private void registerTraceFileNames(String activityName, String absPath)
            throws IOException {
        if (mResult.containsKey(activityName)) {
            String existingResult = (String) mResult.get(activityName);
            mResult.putString(activityName, existingResult + "," + absPath);
        } else {
            mResult.putString(activityName, "" + absPath);
        }
    }
}
