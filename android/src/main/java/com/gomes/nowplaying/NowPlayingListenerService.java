package com.gomes.nowplaying;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import java.util.HashMap;
import java.util.Map;

/**
 * MIT License
 *
 * Copyright (c) 2020 Nic Ford
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
public class NowPlayingListenerService extends NotificationListenerService {
    private static final String TAG = "NowPlayingService";

    public static final String FIELD_ACTION = "com.gomes.nowplaying.action";
    public static final String FIELD_TOKEN = "com.gomes.nowplaying.token";
    public static final String FIELD_ICON = "com.gomes.nowplaying.icon";
    public static final String ACTION_POSTED = "posted";
    public static final String ACTION_REMOVED = "removed";
    public static final String ACTION_REQUEST_UPDATE = "com.gomes.nowplaying.REQUEST_UPDATE";

    private Map<String, MediaSession.Token> tokens = new HashMap<>();
    private boolean isConnected = false;

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            final String CHANNEL_ID = "nowplaying_service_channel";
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    "Now Playing Service",
                    NotificationManager.IMPORTANCE_DEFAULT);

            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);

            Notification notification = new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("Now Playing")
                    .setContentText("Monitoring for media updates")
                    .build();

            // For Android 14+ (API 34+), specify the foreground service type
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(1, notification);
            }
        }
        Log.d(TAG, "NowPlayingListenerService created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand called with action: " +
                (intent != null ? intent.getAction() : "null"));

        if (intent != null && ACTION_REQUEST_UPDATE.equals(intent.getAction())) {
            if (isConnected) {
                SbnAndToken sbnAndToken = findTokenForState();
                if (sbnAndToken != null) {
                    tokens.put(sbnAndToken.sbn.getKey(), sbnAndToken.token);
                    sendData(sbnAndToken.token, sbnAndToken.sbn, ACTION_POSTED);
                } else {
                    Log.d(TAG, "No active notification found for update request");
                }
            } else {
                Log.w(TAG, "Service not connected yet, cannot fulfill update request");
            }
        }
        // Return START_STICKY to ensure service restarts if killed
        return START_STICKY;
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        isConnected = true;
        Log.d(TAG, "NotificationListenerService connected");

        // Send initial state when connected
        SbnAndToken sbnAndToken = findTokenForState();
        if (sbnAndToken != null) {
            tokens.put(sbnAndToken.sbn.getKey(), sbnAndToken.token);
            sendData(sbnAndToken.token, sbnAndToken.sbn, ACTION_POSTED);
        }
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        isConnected = false;
        Log.w(TAG, "NotificationListenerService disconnected");
        // Clear tokens when disconnected
        tokens.clear();
    }

    private SbnAndToken findTokenForState() {
        SbnAndToken playingToken = null;
        SbnAndToken pausedToken = null;

        try {
            StatusBarNotification[] notifications = this.getActiveNotifications();
            if (notifications == null) {
                Log.d(TAG, "No active notifications available");
                return null;
            }

            for (StatusBarNotification sbn : notifications) {
                final MediaSession.Token token = getTokenIfAvailable(sbn);
                if (token != null) {
                    try {
                        final MediaController controller = new MediaController(this, token);
                        final PlaybackState playbackState = controller.getPlaybackState();
                        if (playbackState != null) {
                            final int state = playbackState.getState();
                            if (state == PlaybackState.STATE_PLAYING) {
                                playingToken = new SbnAndToken(sbn, token);
                            } else if (state == PlaybackState.STATE_PAUSED) {
                                pausedToken = new SbnAndToken(sbn, token);
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error accessing media controller", e);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error finding token for state", e);
        }

        if (playingToken != null) {
            return playingToken;
        }
        return pausedToken; // may also be null
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (!isConnected) {
            Log.w(TAG, "Notification posted but service not connected");
            return;
        }

        final MediaSession.Token token = getTokenIfAvailable(sbn);
        if (token != null) {
            tokens.put(sbn.getKey(), token);
            sendData(token, sbn, ACTION_POSTED);
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (!isConnected) {
            return;
        }

        final MediaSession.Token token = tokens.remove(sbn.getKey());
        if (token != null) {
            sendData(token, sbn, ACTION_REMOVED);
        }
    }

    private void sendData(MediaSession.Token token, StatusBarNotification sbn, String action) {
        try {
            final Intent intent = new Intent(NowPlayingPlugin.ACTION);
            intent.putExtra(FIELD_ACTION, action);
            intent.putExtra(FIELD_TOKEN, token);
            intent.putExtra(FIELD_ICON, sbn.getNotification().getSmallIcon());
            sendBroadcast(intent);
            Log.d(TAG, "Broadcast sent: " + action);
        } catch (Exception e) {
            Log.e(TAG, "Error sending broadcast", e);
        }
    }

    private MediaSession.Token getTokenIfAvailable(StatusBarNotification sbn) {
        try {
            final Notification notif = sbn.getNotification();
            final Bundle bundle = notif.extras;
            return (MediaSession.Token) bundle.getParcelable("android.mediaSession");
        } catch (Exception e) {
            Log.e(TAG, "Error getting token from notification", e);
            return null;
        }
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "NowPlayingListenerService destroyed");
        tokens.clear();
        isConnected = false;
        super.onDestroy();
    }

    private class SbnAndToken {
        protected final StatusBarNotification sbn;
        protected final MediaSession.Token token;

        public SbnAndToken(StatusBarNotification sbn, MediaSession.Token token) {
            this.sbn = sbn;
            this.token = token;
        }
    }
}