package com.gomes.nowplaying;

import androidx.annotation.NonNull;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.VectorDrawable;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/** NowPlayingPlugin */
public class NowPlayingPlugin implements FlutterPlugin, MethodCallHandler, ActivityAware {
    private static final String TAG = "NowPlayingPlugin";

    public static final String ACTION = "com.gomes.nowplaying";

    private static final String ENABLED_NOTIFICATION_LISTENERS =
            "enabled_notification_listeners";
    private static final String ACTION_NOTIFICATION_LISTENER_SETTINGS =
            "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS";

    private static final String COMMAND_TRACK = "track";
    private static final String COMMAND_ENABLED = "isEnabled";
    private static final String COMMAND_REQUEST_PERMISSIONS = "requestPermissions";

    private static final int STATE_PLAYING = 0;
    private static final int STATE_PAUSED = 1;
    private static final int STATE_STOPPED = 2;
    private static final int STATE_UNKNOWN = -1;

    private static final int MAX_SAME_STATE_COUNT = 10;
    private static final int POLLING_INTERVAL_MS = 500;

    private MethodChannel channel;
    private ChangeBroadcastReceiver changeBroadcastReceiver;
    private Context context;
    private final Object trackDataLock = new Object();
    private Map<String, Object> trackData = new HashMap<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Thread pollingThread;
    private volatile boolean isReceiverRegistered = false;

    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull Result result) {
        if (COMMAND_TRACK.equals(call.method)) {
            synchronized (trackDataLock) {
                result.success(new HashMap<>(trackData));
            }
        } else if (COMMAND_ENABLED.equals(call.method)) {
            final boolean isEnabled = isNotificationListenerServiceEnabled();
            result.success(isEnabled);
        } else if (COMMAND_REQUEST_PERMISSIONS.equals(call.method)) {
            final boolean isEnabled = isNotificationListenerServiceEnabled();
            if (!isEnabled) {
                try {
                    Intent intent = new Intent(ACTION_NOTIFICATION_LISTENER_SETTINGS);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(intent);
                } catch (Exception e) {
                    Log.e(TAG, "Error opening notification settings", e);
                    result.error("SETTINGS_ERROR", "Could not open notification settings", null);
                    return;
                }
            } else {
                // Service is already enabled, request an update
                requestServiceUpdate();
            }
            result.success(true);
        } else {
            result.notImplemented();
        }
    }

    @Override
    public void onAttachedToActivity(ActivityPluginBinding binding) {
        attach(binding);
    }

    @Override
    public void onReattachedToActivityForConfigChanges(ActivityPluginBinding binding) {
        attach(binding);
    }

    @Override
    public void onDetachedFromActivity() {
        detach();
    }

    @Override
    public void onDetachedFromActivityForConfigChanges() {
        detach();
    }

    private void attach(ActivityPluginBinding binding) {
        Log.d(TAG, "Attaching to activity");
        context = binding.getActivity();

        // Only register receiver if not already registered
        if (!isReceiverRegistered) {
            try {
                changeBroadcastReceiver = new ChangeBroadcastReceiver();
                IntentFilter intentFilter = new IntentFilter();
                intentFilter.addAction(NowPlayingPlugin.ACTION);
                if (Build.VERSION.SDK_INT >= 33) {
                    context.registerReceiver(changeBroadcastReceiver, intentFilter, Context.RECEIVER_EXPORTED);
                } else {
                    context.registerReceiver(changeBroadcastReceiver, intentFilter);
                }
                isReceiverRegistered = true;
                Log.d(TAG, "Broadcast receiver registered");
            } catch (Exception e) {
                Log.e(TAG, "Error registering broadcast receiver", e);
            }
        }

        // Request update after a short delay to allow service to initialize
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                requestServiceUpdate();
            }
        }, 500);
    }

    private void requestServiceUpdate() {
        if (context == null) {
            Log.w(TAG, "Cannot request service update: context is null");
            return;
        }

        if (!isNotificationListenerServiceEnabled()) {
            Log.w(TAG, "Cannot request service update: service not enabled");
            return;
        }

        try {
            Intent intent = new Intent(context, NowPlayingListenerService.class);
            intent.setAction(NowPlayingListenerService.ACTION_REQUEST_UPDATE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
            Log.d(TAG, "Service update requested");
        } catch (Exception e) {
            Log.e(TAG, "Error requesting service update", e);
        }
    }

    private void detach() {
        Log.d(TAG, "Detaching from activity");
        stopPolling();

        if (isReceiverRegistered && context != null && changeBroadcastReceiver != null) {
            try {
                context.unregisterReceiver(changeBroadcastReceiver);
                isReceiverRegistered = false;
                Log.d(TAG, "Broadcast receiver unregistered");
            } catch (Exception e) {
                Log.e(TAG, "Error unregistering broadcast receiver", e);
            }
        }

        context = null;
        changeBroadcastReceiver = null;
    }

    private boolean isNotificationListenerServiceEnabled() {
        if (context == null) {
            return false;
        }

        final String pkgName = context.getPackageName();
        final String flat = Settings.Secure.getString(context.getContentResolver(), ENABLED_NOTIFICATION_LISTENERS);
        if (!TextUtils.isEmpty(flat)) {
            final String[] names = flat.split(":");
            for (int i = 0; i < names.length; i++) {
                final ComponentName cn = ComponentName.unflattenFromString(names[i]);
                if (cn != null && TextUtils.equals(pkgName, cn.getPackageName())) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding flutterPluginBinding) {
        Log.d(TAG, "Plugin attached to engine");
        channel = new MethodChannel(flutterPluginBinding.getBinaryMessenger(), "gomes.com.es/nowplaying");
        channel.setMethodCallHandler(this);
    }

    public class ChangeBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (context == null) {
                Log.w(TAG, "Received broadcast with null context");
                return;
            }

            final String action = intent.getStringExtra(NowPlayingListenerService.FIELD_ACTION);
            final Icon icon = intent.getParcelableExtra(NowPlayingListenerService.FIELD_ICON);
            final MediaSession.Token token = intent.getParcelableExtra(NowPlayingListenerService.FIELD_TOKEN);

            Log.d(TAG, "Broadcast received: " + action);

            if (NowPlayingListenerService.ACTION_POSTED.equals(action)) {
                startPolling(token, icon);
            } else if (NowPlayingListenerService.ACTION_REMOVED.equals(action)) {
                stopPolling();
                finishPlaying(token);
            }
        }
    }

    /**
     * Start polling for updates in between notifications
     * This is needed to catch playback state changes that do not trigger a new notification
     */
    private void startPolling(MediaSession.Token token, Icon icon) {
        stopPolling();

        pollingThread = new Thread(() -> {
            Log.d(TAG, "Polling thread started");
            int sameStateCount = 0;
            Integer lastState = null;

            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Map<String, Object> data = extractFieldsFor(token, icon);
                    if (data != null) {
                        mainHandler.post(() -> sendTrack(data));
                        Integer currentState = (Integer) data.get("state");
                        if (currentState != null && currentState.equals(lastState)) {
                            if (++sameStateCount >= MAX_SAME_STATE_COUNT) {
                                Log.d(TAG, "State unchanged for max count, stopping poll");
                                break;
                            }
                        } else {
                            lastState = currentState;
                            sameStateCount = 1;
                        }
                    } else {
                        lastState = null;
                        sameStateCount = 0;
                    }

                    Thread.sleep(POLLING_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Log.d(TAG, "Polling thread interrupted");
                    break;
                } catch (Exception e) {
                    Log.e(TAG, "Error in polling thread", e);
                }
            }
            Log.d(TAG, "Polling thread ended");
        });
        pollingThread.start();
    }

    private void stopPolling() {
        if (pollingThread != null) {
            Log.d(TAG, "Stopping polling thread");
            pollingThread.interrupt();
            try {
                pollingThread.join(1000); // Wait up to 1 second for thread to finish
            } catch (InterruptedException e) {
                Log.w(TAG, "Interrupted while waiting for polling thread to finish");
            }
            pollingThread = null;
        }
    }

    void finishPlaying(MediaSession.Token token) {
        if (context == null) {
            return;
        }

        try {
            MediaController controller = new MediaController(context, token);
            MediaMetadata mediaMetadata = controller.getMetadata();
            if (mediaMetadata == null) {
                return;
            }

            final String id = deriveId(mediaMetadata);
            synchronized (trackDataLock) {
                final String lastId = (String) trackData.get("id");
                if (id.equals(lastId)) {
                    sendTrack(null);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error finishing playing", e);
        }
    }

    private void sendTrack(Map<String, Object> data) {
        synchronized (trackDataLock) {
            if (data == null) {
                trackData.clear();
            } else {
                trackData = data;
            }
        }

        ArrayList<Object> arguments = new ArrayList<>();
        arguments.add(data);

        mainHandler.post(() -> {
            if (channel != null) {
                channel.invokeMethod(COMMAND_TRACK, arguments);
            }
        });
    }

    private Map<String, Object> extractFieldsFor(MediaSession.Token token, Icon icon) {
        if (context == null) {
            return null;
        }

        try {
            final MediaController controller = new MediaController(context, token);

            final MediaMetadata mediaMetadata = controller.getMetadata();
            if (mediaMetadata == null) {
                return null;
            }

            final String id = deriveId(mediaMetadata);
            String lastId;
            synchronized (trackDataLock) {
                lastId = (String) trackData.get("id");
            }

            final PlaybackState playbackState = controller.getPlaybackState();
            if (playbackState == null) {
                return null;
            }

            final int state = getPlaybackState(playbackState);

            // back out now if we're not interested in this state
            if (state == STATE_UNKNOWN) return null;
            if (state == STATE_PAUSED && lastId != null && !id.equals(lastId)) return null;
            if (state == STATE_STOPPED && !id.equals(lastId)) return null;

            final Map<String, Object> data = new HashMap<>();

            data.put("id", id);
            data.put("source", controller.getPackageName());
            data.put("state", state);

            data.put("album", mediaMetadata.getString(MediaMetadata.METADATA_KEY_ALBUM));
            data.put("title", mediaMetadata.getString(MediaMetadata.METADATA_KEY_TITLE));
            data.put("artist", mediaMetadata.getString(MediaMetadata.METADATA_KEY_ARTIST));
            data.put("genre", mediaMetadata.getString(MediaMetadata.METADATA_KEY_GENRE));
            data.put("duration", mediaMetadata.getLong(MediaMetadata.METADATA_KEY_DURATION));
            data.put("position", playbackState.getPosition());

            if (state != STATE_STOPPED && !id.equals(lastId)) {
                // do the onerous imagey stuff only if we're on a new paused or playing media item

                data.put("sourceIcon", convertIcon(icon));

                byte[] image = extractBitmap((Bitmap) mediaMetadata.getBitmap(MediaMetadata.METADATA_KEY_ART));
                if (image == null) {
                    image = extractBitmap((Bitmap) mediaMetadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART));
                }
                if (image != null) {
                    data.put("image", image);
                } else {
                    String imageUri = mediaMetadata.getString(MediaMetadata.METADATA_KEY_ART_URI);
                    if (imageUri == null) {
                        imageUri = mediaMetadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI);
                    }
                    data.put("imageUri", imageUri);
                }
            }

            return data;
        } catch (Exception e) {
            Log.e(TAG, "Error extracting fields", e);
            return null;
        }
    }

    private String deriveId(MediaMetadata mediaMetadata) {
        final String album = mediaMetadata.getString(MediaMetadata.METADATA_KEY_ALBUM);
        final String title = mediaMetadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        final String artist = mediaMetadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
        return title + ":" + artist + ":" + album;
    }

    private byte[] extractBitmap(Bitmap bitmap) {
        if (bitmap == null) return null;

        try {
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
            return stream.toByteArray();
        } catch (Exception e) {
            Log.e(TAG, "Error extracting bitmap", e);
            return null;
        }
    }

    private byte[] convertIcon(Icon icon) {
        if (icon == null || context == null) {
            return null;
        }

        try {
            final Drawable drawable = icon.loadDrawable(context);
            if (drawable instanceof BitmapDrawable) {
                final Bitmap bitmap = ((BitmapDrawable) drawable).getBitmap();
                return extractBitmap(bitmap);
            } else if (drawable instanceof VectorDrawable) {
                final VectorDrawable vector = (VectorDrawable) drawable;
                final Bitmap bitmap = Bitmap.createBitmap(
                        vector.getIntrinsicWidth(),
                        vector.getIntrinsicHeight(),
                        Bitmap.Config.ARGB_8888
                );
                final Canvas canvas = new Canvas(bitmap);
                vector.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
                vector.draw(canvas);
                return extractBitmap(bitmap);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error converting icon", e);
        }
        return null;
    }

    private int getPlaybackState(PlaybackState state) {
        switch (state.getState()) {
            case PlaybackState.STATE_PLAYING:
                return NowPlayingPlugin.STATE_PLAYING;
            case PlaybackState.STATE_PAUSED:
                return NowPlayingPlugin.STATE_PAUSED;
            case PlaybackState.STATE_STOPPED:
                return NowPlayingPlugin.STATE_STOPPED;
            default:
                return NowPlayingPlugin.STATE_UNKNOWN;
        }
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        Log.d(TAG, "Plugin detached from engine");
        stopPolling();
        if (channel != null) {
            channel.setMethodCallHandler(null);
            channel = null;
        }
        if (isReceiverRegistered && context != null && changeBroadcastReceiver != null) {
            try {
                context.unregisterReceiver(changeBroadcastReceiver);
                isReceiverRegistered = false;
            } catch (Exception e) {
                Log.e(TAG, "Error unregistering receiver on engine detach", e);
            }
        }
    }
}