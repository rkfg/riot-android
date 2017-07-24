/*
 * Copyright 2014 OpenMarket Ltd
 * Copyright 2017 Vector Creations Ltd
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

package im.vector;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.util.Pair;

import org.matrix.androidsdk.MXSession;
import org.matrix.androidsdk.util.Log;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;

import im.vector.activity.CommonActivityUtils;
import im.vector.activity.VectorCallViewActivity;
import im.vector.contacts.ContactsManager;
import im.vector.contacts.PIDsRetriever;
import im.vector.fragments.VectorSettingsPreferencesFragment;
import im.vector.ga.GAHelper;
import im.vector.gcm.GcmRegistrationManager;
import im.vector.receiver.HeadsetConnectionReceiver;
import im.vector.services.EventStreamService;
import im.vector.util.BugReporter;
import im.vector.util.PhoneNumberUtils;
import im.vector.util.RageShake;
import im.vector.util.ThemeUtils;
import im.vector.util.VectorCallSoundManager;
import im.vector.util.VectorMarkdownParser;

/**
 * The main application injection point
 */
public class VectorAppRkfgBeta extends Application {
    private static final String LOG_TAG = "VectorAppRkfgBeta";

    // key to save the crash status
    private static final String PREFS_CRASH_KEY = "PREFS_CRASH_KEY";

    /**
     * The current instance.
     */
    private static VectorAppRkfgBeta instance = null;

    /**
     * Rage shake detection to send a bug report.
     */
    private static final RageShake mRageShake = new RageShake();

    /**
     * Delay to detect if the application is in background.
     * If there is no active activity during the elapsed time, it means that the application is in background.
     */
    private static final long MAX_ACTIVITY_TRANSITION_TIME_MS = 4000;

    /**
     * The current active activity
     */
    private static Activity mCurrentActivity = null;

    /**
     * Background application detection
     */
    private Timer mActivityTransitionTimer;
    private TimerTask mActivityTransitionTimerTask;
    private boolean mIsInBackground = true;

    /**
     * Google analytics information.
     */
    public static int VERSION_BUILD = -1;
    private static String VECTOR_VERSION_STRING = "";
    private static String SDK_VERSION_STRING = "";

    /**
     * Tells if there a pending call whereas the application is backgrounded.
     */
    private boolean mIsCallingInBackground = false;

    /**
     * Monitor the created activities to detect memory leaks.
     */
    private final ArrayList<String> mCreatedActivities = new ArrayList<>();

    /**
     * Markdown parser
     */
    public VectorMarkdownParser mMarkdownParser;

    /**
     * @return the current instance
     */
    public static VectorAppRkfgBeta getInstance() {
        return instance;
    }

    /**
     * The directory in which the logs are stored
     */
    public static File mLogsDirectoryFile = null;

    @Override
    public void onCreate() {
        Log.d(LOG_TAG, "onCreate");
        super.onCreate();
        ThemeUtils.activitySetTheme(this);

        instance = this;
        mActivityTransitionTimer = null;
        mActivityTransitionTimerTask = null;

        try {
            PackageInfo packageInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            VERSION_BUILD = packageInfo.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(LOG_TAG, "fails to retrieve the package info " + e.getMessage());
        }

        VECTOR_VERSION_STRING = Matrix.getInstance(this).getVersion(true);

        // not the first launch
        if (null != Matrix.getInstance(this).getDefaultSession()) {
            SDK_VERSION_STRING = Matrix.getInstance(this).getDefaultSession().getVersion(true);
        } else {
            SDK_VERSION_STRING = "";
        }

        mLogsDirectoryFile = new File(getCacheDir().getAbsolutePath() + "/logs");

        org.matrix.androidsdk.util.Log.setLogDirectory(mLogsDirectoryFile);
        org.matrix.androidsdk.util.Log.init("RiotLog");

        // log the application version to trace update
        // useful to track backward compatibility issues

        Log.d(LOG_TAG, "----------------------------------------------------------------");
        Log.d(LOG_TAG, "----------------------------------------------------------------");
        Log.d(LOG_TAG, " Application version: " + VECTOR_VERSION_STRING);
        Log.d(LOG_TAG, " SDK version: " + SDK_VERSION_STRING);
        Log.d(LOG_TAG, " Local time: " + (new SimpleDateFormat("MM-dd HH:mm:ss.SSSZ", Locale.US)).format(new Date()));
        Log.d(LOG_TAG, "----------------------------------------------------------------");
        Log.d(LOG_TAG, "----------------------------------------------------------------\n\n\n\n");

        GAHelper.initGoogleAnalytics(getApplicationContext());

        mRageShake.start(this);

        // init the REST client
        MXSession.initUserAgent(getApplicationContext());

        this.registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
                Log.d(LOG_TAG, "onActivityCreated " + activity);
                mCreatedActivities.add(activity.toString());
            }

            @Override
            public void onActivityStarted(Activity activity) {
                Log.d(LOG_TAG, "onActivityStarted " + activity);
            }

            @Override
            public void onActivityResumed(Activity activity) {
                Log.d(LOG_TAG, "onActivityResumed " + activity);
                setCurrentActivity(activity);
            }

            @Override
            public void onActivityPaused(Activity activity) {
                Log.d(LOG_TAG, "onActivityPaused " + activity);
                setCurrentActivity(null);
            }

            @Override
            public void onActivityStopped(Activity activity) {
                Log.d(LOG_TAG, "onActivityStopped " + activity);
            }

            @Override
            public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
                Log.d(LOG_TAG, "onActivitySaveInstanceState " + activity);
            }

            @Override
            public void onActivityDestroyed(Activity activity) {
                Log.d(LOG_TAG, "onActivityDestroyed " + activity);
                mCreatedActivities.remove(activity.toString());

                if (mCreatedActivities.size() > 1) {
                    Log.d(LOG_TAG, "onActivityDestroyed : \n" + mCreatedActivities);
                }
            }
        });

        // detect if the headset is plugged / unplugged.
        registerReceiver(new HeadsetConnectionReceiver(), new IntentFilter(Intent.ACTION_HEADSET_PLUG));

        // create the markdown parser
        try {
            mMarkdownParser = new VectorMarkdownParser(this);
        } catch (Exception e) {
            // reported by GA
            Log.e(LOG_TAG, "cannot create the mMarkdownParser " + e.getMessage());
        }

        initApplicationLocale(this);

        fixMigrationIssues();
    }

    /**
     * Fix some migration issues
     */
    private void fixMigrationIssues() {
        // some key names have been updated to supported language switch
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);

        if (preferences.contains(getString(R.string.ga_use_settings))) {
            SharedPreferences.Editor editor = preferences.edit();
            editor.putBoolean(VectorSettingsPreferencesFragment.SETTINGS_GA_USE_SETTINGS_PREFERENCE_KEY, preferences.getBoolean(getString(R.string.ga_use_settings), false));
            editor.remove(getString(R.string.ga_use_settings));
            editor.commit();
        }

        if (preferences.contains(getString(R.string.settings_pin_missed_notifications))) {
            SharedPreferences.Editor editor = preferences.edit();
            editor.putBoolean(VectorSettingsPreferencesFragment.SETTINGS_PIN_MISSED_NOTIFICATIONS_PREFERENCE_KEY, preferences.getBoolean(getString(R.string.settings_pin_missed_notifications), false));
            editor.remove(getString(R.string.settings_pin_missed_notifications));
            editor.commit();
        }

        if (preferences.contains(getString(R.string.settings_pin_unread_messages))) {
            SharedPreferences.Editor editor = preferences.edit();
            editor.putBoolean(VectorSettingsPreferencesFragment.SETTINGS_PIN_UNREAD_MESSAGES_PREFERENCE_KEY, preferences.getBoolean(getString(R.string.settings_pin_unread_messages), false));
            editor.remove(getString(R.string.settings_pin_unread_messages));
            editor.commit();
        }
    }

    /**
     * Parse a markdown text
     *
     * @param text     the text to parse
     * @param listener the result listener
     */
    public static void markdownToHtml(final String text, final VectorMarkdownParser.IVectorMarkdownParserListener listener) {
        if (null != getInstance().mMarkdownParser) {
            getInstance().mMarkdownParser.markdownToHtml(text, listener);
        } else {
            (new Handler(Looper.getMainLooper())).post(new Runnable() {
                @Override
                public void run() {
                    // GA issue
                    listener.onMarkdownParsed(text, null);
                }
            });
        }
    }

    /**
     * Suspend background threads.
     */
    private void suspendApp() {
        GcmRegistrationManager gcmRegistrationManager = Matrix.getInstance(VectorAppRkfgBeta.this).getSharedGCMRegistrationManager();

        // suspend the events thread if the client uses GCM
        if (!gcmRegistrationManager.isBackgroundSyncAllowed() || (gcmRegistrationManager.useGCM() && gcmRegistrationManager.hasRegistrationToken())) {
            Log.d(LOG_TAG, "suspendApp ; pause the event stream");
            CommonActivityUtils.pauseEventStream(VectorAppRkfgBeta.this);
        } else {
            Log.d(LOG_TAG, "suspendApp ; the event stream is not paused because GCM is disabled.");
        }

        // the sessions are not anymore seen as "online"
        ArrayList<MXSession> sessions = Matrix.getInstance(this).getSessions();

        for (MXSession session : sessions) {
            if (session.isAlive()) {
                session.setIsOnline(false);
                session.setSyncDelay(gcmRegistrationManager.getBackgroundSyncDelay());
                session.setSyncTimeout(gcmRegistrationManager.getBackgroundSyncTimeOut());

                if (session.getDataHandler().areLeftRoomsSynced()) {
                    session.getDataHandler().releaseLeftRooms();
                }
            }
        }

        clearSyncingSessions();

        PIDsRetriever.getInstance().onAppBackgrounded();

        MyPresenceManager.advertiseAllUnavailable();
    }

    /**
     * Test if application is put in background.
     * i.e wait 2s before assuming that the application is put in background.
     */
    private void startActivityTransitionTimer() {
        Log.d(LOG_TAG, "## startActivityTransitionTimer()");

        mActivityTransitionTimer = new Timer();
        mActivityTransitionTimerTask = new TimerTask() {
            @Override
            public void run() {
                if (mActivityTransitionTimerTask != null) {
                    mActivityTransitionTimerTask.cancel();
                    mActivityTransitionTimerTask = null;
                }

                if (mActivityTransitionTimer != null) {
                    mActivityTransitionTimer.cancel();
                    mActivityTransitionTimer = null;
                }

                if (null != mCurrentActivity) {
                    Log.e(LOG_TAG, "## startActivityTransitionTimer() : the timer expires but there is an active activity.");
                } else {
                    VectorAppRkfgBeta.this.mIsInBackground = true;
                    mIsCallingInBackground = (null != VectorCallViewActivity.getActiveCall());

                    // if there is a pending call
                    // the application is not suspended
                    if (!mIsCallingInBackground) {
                        Log.d(LOG_TAG, "Suspend the application because there was no resumed activity within " + (MAX_ACTIVITY_TRANSITION_TIME_MS / 1000) + " seconds");
                        CommonActivityUtils.displayMemoryInformation(null, " app suspended");
                        suspendApp();
                    } else {
                        Log.d(LOG_TAG, "App not suspended due to call in progress");
                    }
                }
            }
        };

        mActivityTransitionTimer.schedule(mActivityTransitionTimerTask, MAX_ACTIVITY_TRANSITION_TIME_MS);
    }

    /**
     * Stop the background detection.
     */
    private void stopActivityTransitionTimer() {
        Log.d(LOG_TAG, "## stopActivityTransitionTimer()");

        if (mActivityTransitionTimerTask != null) {
            mActivityTransitionTimerTask.cancel();
            mActivityTransitionTimerTask = null;
        }

        if (mActivityTransitionTimer != null) {
            mActivityTransitionTimer.cancel();
            mActivityTransitionTimer = null;
        }

        if (isAppInBackground() && !mIsCallingInBackground) {

            // the event stream service has been killed
            if (null == EventStreamService.getInstance()) {
                CommonActivityUtils.startEventStreamService(VectorAppRkfgBeta.this);
            } else {
                CommonActivityUtils.resumeEventStream(VectorAppRkfgBeta.this);

                // try to perform a GCM registration if it failed
                // or if the GCM server generated a new push key
                GcmRegistrationManager gcmRegistrationManager = Matrix.getInstance(this).getSharedGCMRegistrationManager();

                if (null != gcmRegistrationManager) {
                    gcmRegistrationManager.checkRegistrations();
                }
            }

            // get the contact update at application launch
            ContactsManager.getInstance().clearSnapshot();
            ContactsManager.getInstance().refreshLocalContactsSnapshot();

            boolean hasActiveCall = false;

            ArrayList<MXSession> sessions = Matrix.getInstance(this).getSessions();
            for (MXSession session : sessions) {
                session.getMyUser().refreshUserInfos(null);
                session.setIsOnline(true);
                session.setSyncDelay(0);
                session.setSyncTimeout(0);
                hasActiveCall |= session.getDataHandler().getCallsManager().hasActiveCalls();
                addSyncingSession(session);
            }

            // detect if an infinite ringing has been triggered
            if (VectorCallSoundManager.isRinging() && !hasActiveCall && (null != EventStreamService.getInstance())) {
                Log.e(LOG_TAG, "## suspendApp() : fix an infinite ringing");
                EventStreamService.getInstance().hideCallNotifications();

                if (VectorCallSoundManager.isRinging()) {
                    VectorCallSoundManager.stopRinging();
                }
            }
        }

        MyPresenceManager.advertiseAllOnline();

        mIsCallingInBackground = false;
        mIsInBackground = false;
        setTheme();
    }

    private void setTheme() {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(this);
        String mode = sp.getString(getResources().getString(R.string.settings_theme), null);
        if (mode != null) {
            ThemeUtils.setTheme(mode);
        }
    }

    /**
     * Update the current active activity.
     * It manages the application background / foreground when it is required.
     *
     * @param activity the current activity, null if there is no more one.
     */
    private void setCurrentActivity(Activity activity) {
        Log.d(LOG_TAG, "## setCurrentActivity() : from " + mCurrentActivity + " to " + activity);

        if (VectorAppRkfgBeta.isAppInBackground() && (null != activity)) {
            Matrix matrixInstance =  Matrix.getInstance(activity.getApplicationContext());

            // sanity check
            if (null != matrixInstance) {
                matrixInstance.refreshPushRules();
            }

            Log.d(LOG_TAG, "The application is resumed");
            // display the memory usage when the application is put iun foreground..
            CommonActivityUtils.displayMemoryInformation(activity, " app resumed with " + activity);
        }

        // wait 2s to check that the application is put in background
        if (null != getInstance()) {
            if (null == activity) {
                getInstance().startActivityTransitionTimer();
            } else {
                getInstance().stopActivityTransitionTimer();
            }
        } else {
            Log.e(LOG_TAG, "The application is resumed but there is no active instance");
        }

        mCurrentActivity = activity;
    }

    /**
     * @return the current active activity
     */
    public static Activity getCurrentActivity() {
        return mCurrentActivity;
    }

    /**
     * Return true if the application is in background.
     */
    public static boolean isAppInBackground() {
        return (null == mCurrentActivity) && (null != getInstance()) && getInstance().mIsInBackground;
    }

    //==============================================================================================================
    // Calls management.
    //==============================================================================================================

    /**
     * The application is warned that a call is ended.
     */
    public void onCallEnd() {
        if (isAppInBackground() && mIsCallingInBackground) {
            Log.d(LOG_TAG, "onCallEnd : Suspend the events thread because the call was ended whereas the application was in background");
            suspendApp();
        }

        mIsCallingInBackground = false;
    }

    //==============================================================================================================
    // cert management : store the active activities.
    //==============================================================================================================

    private final EventEmitter<Activity> mOnActivityDestroyedListener = new EventEmitter<>();

    /**
     * @return the EventEmitter list.
     */
    public EventEmitter<Activity> getOnActivityDestroyedListener() {
        return mOnActivityDestroyedListener;
    }

    //==============================================================================================================
    // Media pickers : image backup
    //==============================================================================================================

    private static Bitmap mSavedPickerImagePreview = null;

    /**
     * The image taken from the medias picker is stored in a static variable because
     * saving it would take too much time.
     *
     * @return the saved image from medias picker
     */
    public static Bitmap getSavedPickerImagePreview() {
        return mSavedPickerImagePreview;
    }

    /**
     * Save the image taken in the medias picker
     *
     * @param aSavedCameraImagePreview the bitmap.
     */
    public static void setSavedCameraImagePreview(Bitmap aSavedCameraImagePreview) {
        if (aSavedCameraImagePreview != mSavedPickerImagePreview) {
            // force to release memory
            // reported by GA
            // it seems that the medias picker might be refreshed
            // while leaving the activity
            // recycle the bitmap trigger a rendering issue
            // Canvas: trying to use a recycled bitmap...

            /*if (null != mSavedPickerImagePreview) {
                mSavedPickerImagePreview.recycle();
                mSavedPickerImagePreview = null;
                System.gc();
            }*/


            mSavedPickerImagePreview = aSavedCameraImagePreview;
        }
    }

    //==============================================================================================================
    // Syncing mxSessions
    //==============================================================================================================

    /**
     * syncing sessions
     */
    private static final HashSet<MXSession> mSyncingSessions = new HashSet<>();

    /**
     * Add a session in the syncing sessions list
     *
     * @param session the session
     */
    public static void addSyncingSession(MXSession session) {
        synchronized (mSyncingSessions) {
            mSyncingSessions.add(session);
        }
    }

    /**
     * Remove a session in the syncing sessions list
     *
     * @param session the session
     */
    public static void removeSyncingSession(MXSession session) {
        if (null != session) {
            synchronized (mSyncingSessions) {
                mSyncingSessions.remove(session);
            }
        }
    }

    /**
     * Clear syncing sessions list
     */
    public static void clearSyncingSessions() {
        synchronized (mSyncingSessions) {
            mSyncingSessions.clear();
        }
    }

    /**
     * Tell if a session is syncing
     *
     * @param session the session
     * @return true if the session is syncing
     */
    public static boolean isSessionSyncing(MXSession session) {
        boolean isSyncing = false;

        if (null != session) {
            synchronized (mSyncingSessions) {
                isSyncing = mSyncingSessions.contains(session);
            }
        }

        return isSyncing;
    }

    //==============================================================================================================
    // GA management
    //==============================================================================================================
    /**
     * GA tags
     */
    public static final String GOOGLE_ANALYTICS_STATS_CATEGORY = "stats";

    public static final String GOOGLE_ANALYTICS_STATS_ROOMS_ACTION = "rooms";
    public static final String GOOGLE_ANALYTICS_STARTUP_STORE_PRELOAD_ACTION = "storePreload";
    public static final String GOOGLE_ANALYTICS_STARTUP_MOUNT_DATA_ACTION = "mountData";
    public static final String GOOGLE_ANALYTICS_STARTUP_LAUNCH_SCREEN_ACTION = "launchScreen";
    public static final String GOOGLE_ANALYTICS_STARTUP_CONTACTS_ACTION = "Contacts";

    /**
     * Send a GA stats
     *
     * @param context  the context
     * @param category the category
     * @param action   the action
     * @param label    the label
     * @param value    the value
     */
    public static void sendGAStats(Context context, String category, String action, String label, long value) {
        GAHelper.sendGAStats(context, category, action, label, value);
    }

    /**
     * An uncaught exception has been triggered
     *
     * @param threadName the thread name
     * @param throwable  the throwable
     * @return the exception description
     */
    public static String uncaughtException(String threadName, Throwable throwable) {
        StringBuilder b = new StringBuilder();
        String appName = Matrix.getApplicationName();

        b.append(appName + " Build : " + VectorAppRkfgBeta.VERSION_BUILD + "\n");
        b.append(appName + " Version : " + VectorAppRkfgBeta.VECTOR_VERSION_STRING + "\n");
        b.append("SDK Version : " + VectorAppRkfgBeta.SDK_VERSION_STRING + "\n");
        b.append("Phone : " + Build.MODEL.trim() + " (" + Build.VERSION.INCREMENTAL + " " + Build.VERSION.RELEASE + " " + Build.VERSION.CODENAME + ")\n");

        b.append("Memory statuses \n");

        long freeSize = 0L;
        long totalSize = 0L;
        long usedSize = -1L;
        try {
            Runtime info = Runtime.getRuntime();
            freeSize = info.freeMemory();
            totalSize = info.totalMemory();
            usedSize = totalSize - freeSize;
        } catch (Exception e) {
            e.printStackTrace();
        }
        b.append("usedSize   " + (usedSize / 1048576L) + " MB\n");
        b.append("freeSize   " + (freeSize / 1048576L) + " MB\n");
        b.append("totalSize   " + (totalSize / 1048576L) + " MB\n");

        b.append("Thread: ");
        b.append(threadName);

        Activity a = VectorAppRkfgBeta.getCurrentActivity();
        if (a != null) {
            b.append(", Activity:");
            b.append(a.getLocalClassName());
        }

        b.append(", Exception: ");

        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw, true);
        throwable.printStackTrace(pw);
        b.append(sw.getBuffer().toString());
        Log.e("FATAL EXCEPTION", b.toString());

        String bugDescription = b.toString();

        if (null != VectorAppRkfgBeta.getInstance()) {
            VectorAppRkfgBeta.getInstance().setAppCrashed(bugDescription);
        }

        return bugDescription;
    }

    /**
     * Warn that the application crashed
     *
     * @param description the crash description
     */
    private void setAppCrashed(String description) {
        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(VectorAppRkfgBeta.getInstance());
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean(PREFS_CRASH_KEY, true);
        editor.commit();

        BugReporter.saveCrashReport(this, description);
    }

    /**
     * Tells if the application crashed
     *
     * @return true if the application crashed
     */
    public boolean didAppCrash() {
        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(VectorAppRkfgBeta.getInstance());
        return preferences.getBoolean(PREFS_CRASH_KEY, false);
    }


    /**
     * Clear the crash status
     */
    public void clearAppCrashStatus() {
        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(VectorAppRkfgBeta.getInstance());
        SharedPreferences.Editor editor = preferences.edit();
        editor.remove(PREFS_CRASH_KEY);
        editor.commit();
    }

    //==============================================================================================================
    // Locale management
    //==============================================================================================================

    // the supported application languages
    private static final Set<Locale> mApplicationLocales = new HashSet<>();

    private static final String APPLICATION_LOCALE_COUNTRY_KEY = "APPLICATION_LOCALE_COUNTRY_KEY";
    private static final String APPLICATION_LOCALE_VARIANT_KEY = "APPLICATION_LOCALE_VARIANT_KEY";
    private static final String APPLICATION_LOCALE_LANGUAGE_KEY = "APPLICATION_LOCALE_LANGUAGE_KEY";

    private static final Locale mApplicationDefaultLanguage = new Locale("en", "UK");

    /**
     * Init the application locale from the saved one
     *
     * @param context the contact
     */
    private static void initApplicationLocale(Context context) {
        Locale locale = getApplicationLocale(context);

        Locale.setDefault(locale);
        Configuration config = new Configuration();
        config.locale = locale;
        context.getResources().updateConfiguration(config, context.getResources().getDisplayMetrics());

        // init the known locales in background
        AsyncTask<Void, Void, Void> task = new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                getApplicationLocales(VectorAppRkfgBeta.getInstance());
                return null;
            }
        };
        task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    /**
     * Provides the current application locale
     *
     * @param context the context
     * @return the application locale
     */
    public static Locale getApplicationLocale(Context context) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        Locale locale;

        if (!preferences.contains(APPLICATION_LOCALE_LANGUAGE_KEY)) {
            locale = Locale.getDefault();

            // detect if the default language is used
            String defaultStringValue = getString(context, mApplicationDefaultLanguage, R.string.resouces_country);
            if (TextUtils.equals(defaultStringValue, getString(context, locale, R.string.resouces_country))) {
                locale = mApplicationDefaultLanguage;
            }

            saveApplicationLocale(context, locale);
        } else {
            locale = new Locale(preferences.getString(APPLICATION_LOCALE_LANGUAGE_KEY, ""),
                    preferences.getString(APPLICATION_LOCALE_COUNTRY_KEY, ""),
                    preferences.getString(APPLICATION_LOCALE_VARIANT_KEY, "")
            );
        }

        return locale;
    }

    /**
     * Provides the device locale
     *
     * @param context the context
     * @return the device locale
     */
    public static Locale getDeviceLocale(Context context) {
        Locale locale = getApplicationLocale(context);

        try {
            PackageManager packageManager = context.getPackageManager();
            Resources resources = packageManager.getResourcesForApplication("android");
            locale = resources.getConfiguration().locale;
        } catch (Exception e) {
            Log.e(LOG_TAG, "## getDeviceLocale() failed " + e.getMessage());
        }

        return locale;
    }

    /**
     * Save the new application locale.
     *
     * @param context the context
     */
    private static void saveApplicationLocale(Context context, Locale locale) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);

        SharedPreferences.Editor editor = preferences.edit();

        String language = locale.getLanguage();
        if (!TextUtils.isEmpty(language)) {
            editor.putString(APPLICATION_LOCALE_LANGUAGE_KEY, language);
        } else {
            editor.remove(APPLICATION_LOCALE_LANGUAGE_KEY);
        }

        String country = locale.getCountry();
        if (!TextUtils.isEmpty(country)) {
            editor.putString(APPLICATION_LOCALE_COUNTRY_KEY, country);
        } else {
            editor.remove(APPLICATION_LOCALE_COUNTRY_KEY);
        }

        String variant = locale.getVariant();
        if (!TextUtils.isEmpty(variant)) {
            editor.putString(APPLICATION_LOCALE_VARIANT_KEY, variant);
        } else {
            editor.remove(APPLICATION_LOCALE_VARIANT_KEY);
        }

        editor.commit();
    }

    /**
     * Update the application locale.
     *
     * @param context context
     * @param locale  locale
     */
    public static void updateApplicationLocale(Context context, Locale locale) {
        saveApplicationLocale(context, locale);
        Locale.setDefault(locale);

        Configuration config = new Configuration();
        config.locale = locale;
        context.getResources().updateConfiguration(config, context.getResources().getDisplayMetrics());

        PhoneNumberUtils.onLocaleUpdate();
    }

    /**
     * Get String from a locale
     *
     * @param context    the context
     * @param locale     the locale
     * @param resourceId the string resource id
     * @return the localized string
     */
    private static String getString(Context context, Locale locale, int resourceId) {
        String result;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            Configuration config = new Configuration(context.getResources().getConfiguration());
            config.setLocale(locale);
            result = context.createConfigurationContext(config).getText(resourceId).toString();
        } else {
            Resources resources = context.getResources();
            Configuration conf = resources.getConfiguration();
            Locale savedLocale = conf.locale;
            conf.locale = locale;
            resources.updateConfiguration(conf, null);

            // retrieve resources from desired locale
            result = resources.getString(resourceId);

            // restore original locale
            conf.locale = savedLocale;
            resources.updateConfiguration(conf, null);
        }

        return result;
    }

    /**
     * Provides the supported application locales list
     *
     * @param context the context
     * @return the supported application locales list
     */
    public static List<Locale> getApplicationLocales(Context context) {
        if (mApplicationLocales.isEmpty()) {

            final Locale[] availableLocales = Locale.getAvailableLocales();

            Set<Pair<String, String>> knownLocalesSet = new HashSet<>();

            for (Locale locale : availableLocales) {
                knownLocalesSet.add(new Pair<>(getString(context, locale, R.string.resouces_language), getString(context, locale, R.string.resouces_country)));
            }

            for (Pair<String, String> knownLocale : knownLocalesSet) {
                mApplicationLocales.add(new Locale(knownLocale.first, knownLocale.second));
            }
        }

        List<Locale> sortedLocalesList = new ArrayList<>(mApplicationLocales);

        // sort by human display names
        Collections.sort(sortedLocalesList, new Comparator<Locale>() {
            @Override
            public int compare(Locale lhs, Locale rhs) {
                return localeToString(lhs).compareTo(localeToString(rhs));
            }
        });

        return sortedLocalesList;
    }

    /**
     * Convert a locale to a string
     *
     * @param locale the locale to convert
     * @return the string
     */
    public static String localeToString(Locale locale) {
        String res = locale.getDisplayLanguage();

        if (!TextUtils.isEmpty(locale.getDisplayCountry())) {
            res += " (" + locale.getDisplayCountry() + ")";
        }

        return res;
    }
}