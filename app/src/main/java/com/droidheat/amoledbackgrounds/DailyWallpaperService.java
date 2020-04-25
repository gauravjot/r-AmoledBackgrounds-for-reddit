package com.droidheat.amoledbackgrounds;

import android.annotation.SuppressLint;
import android.app.DownloadManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;
import android.util.Log;
import android.widget.Toast;

import com.droidheat.amoledbackgrounds.Utils.FunctionUtils;

import java.io.File;
import java.util.HashMap;

public class DailyWallpaperService extends Service {

    private final String NOTIFICATION_CHANNEL = "daily_wallpaper";
    private final String SERVICE_NAME = "DailyWallpaperService";
    private long downloadID;
    private HashMap<String, String> item;
    private NotificationManager notificationManager;

    public DailyWallpaperService() {
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        HashMap<String, String> wallpaper;
        String ext, titleStr;
        notificationManager = getSystemService(NotificationManager.class);

        pushNotification("Looking up the wallpaper to Reddit...", notificationManager);
        int sort_i = new SharedPrefsUtils(this).readSharedPrefsInt("auto_sort", 0);
        String sort = "hot.json";
        if (sort_i == 1) {
            sort = "top.json?t=day";
        } else if (sort_i == 2) {
            sort = "top.json?t=week";
        }
        String url = "https://www.reddit.com/r/amoledbackgrounds/" + sort;

        try {
            wallpaper = (new UtilsJSON()).grabPostsAsArrayList(getBaseContext(), url.trim()).get(0);
        } catch (Exception e) {
            pushNotification("Error: Unable to reach Reddit for daily wallpaper.",
                    notificationManager);
            stopForeground(false);
            return START_NOT_STICKY;
        }

        if (wallpaper != null && !wallpaper.isEmpty()) {

            titleStr = (new FunctionUtils()).purifyRedditFileTitle(
                    wallpaper.get("title"),
                    wallpaper.get("name")
            );
            ext = (new FunctionUtils()).purifyRedditFileExtension(wallpaper.get("image"));

            item = new HashMap<>();
            item.put("title", titleStr);
            item.put("ext", ext);

            // If file is already downloaded before then we simply applyAsync it

            String filePath =(new FunctionUtils()).getFilePath(this,titleStr + ext);
            File file = new File(filePath);
            Log.d("Location",filePath);
            if (file.exists()) {
                pushNotification("Setting the wallpaper...",
                        notificationManager);
                (new FunctionUtils()).changeWallpaper(getApplicationContext(),filePath);
                stopForeground(true);
                return START_NOT_STICKY;
            }

            final DownloadManager mgr = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);

            Uri downloadUri = Uri.parse(wallpaper.get("image"));
            DownloadManager.Request request = new DownloadManager.Request(
                    downloadUri);

            request.setAllowedNetworkTypes(
                    DownloadManager.Request.NETWORK_WIFI
                            | DownloadManager.Request.NETWORK_MOBILE)
                    .setTitle(titleStr)
                    .setDescription("AmoledBackgrounds")
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_PICTURES, titleStr + ext)
                    .setAllowedOverMetered(true)
                    .setAllowedOverRoaming(true)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE);

            try {
                registerReceiver(receiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
                downloadID = mgr.enqueue(request);
                Log.d(SERVICE_NAME, "Requested service to start a new download");
                pushNotification("Downloading the wallpaper...", notificationManager);
            } catch (Exception e) {
                Log.d(SERVICE_NAME, "Problem making download request. Ending with failure...");
                pushNotification("Error: Unable to download daily wallpaper from Reddit", notificationManager);
                stopForeground(false);
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Toast.makeText(this,"Service Destroyed",Toast.LENGTH_SHORT).show();
    }

    BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(action)) {
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (downloadID == id) {
                    //AppUtils.saveToMediaStore(context,item.get("title") + item.get("ext"));
                    pushNotification("Setting the wallpaper...", notificationManager);
                    SetWallpaperAsyncTask setWallpaperAsyncTask = new SetWallpaperAsyncTask(context,this);
                    setWallpaperAsyncTask.execute();
                }
            }
        }
    };

    private void pushNotification(String message, NotificationManager notificationManager) {
        createNotificationChannel(notificationManager);

        // Create an explicit intent for an Activity in your app
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, 0);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher_round)
                .setContentTitle("Daily Wallpaper")
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true);

        int NOTIFICATION_ID = 456653;
        startForeground(NOTIFICATION_ID, builder.build());
    }

    private void createNotificationChannel(NotificationManager notificationManager) {
        // Create the NotificationChannel, but only on API 26+ because
        // the NotificationChannel class is new and not in the support library
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = "Daily Wallpaper";
            String description = "Notification runs when daily wallpaper updates.";
            int importance = NotificationManager.IMPORTANCE_LOW;
            NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL, name, importance);
            channel.setDescription(description);
            // Register the channel with the system; you can't change the importance
            // or other notification behaviors after this
            notificationManager.createNotificationChannel(channel);
        }
    }

    @SuppressLint("StaticFieldLeak")
    class SetWallpaperAsyncTask extends AsyncTask<String, Integer, String> {

        Context context;
        BroadcastReceiver broadcastReceiver;
        SetWallpaperAsyncTask(Context context, BroadcastReceiver broadcastReceiver) {
            this.context = context;
            this.broadcastReceiver = broadcastReceiver;
        }
        @Override
        protected String doInBackground(String... strings) {
            Log.d(SERVICE_NAME,"Applying Wallpaper...");
            return (new FunctionUtils()).changeWallpaper(context,
                    (new FunctionUtils()).getFilePath(context,item.get("title") + item.get("ext")));
        }

        @Override
        protected void onPostExecute(String s) {
            if (s == "success") {
                Log.d(SERVICE_NAME,"Wallpaper is set with success.");
                pushNotification("Daily Wallpaper is applied", notificationManager);
            } else {
                Log.d(SERVICE_NAME, "A failure has occurred while setting wallpaper.");
                pushNotification("Unable to apply daily wallpaper", notificationManager);
            }
            context.unregisterReceiver(broadcastReceiver);
            stopForeground(false);
        }
    }
}
