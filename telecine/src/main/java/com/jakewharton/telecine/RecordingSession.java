package com.jakewharton.telecine;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.hardware.display.VirtualDisplay;
import android.media.CamcorderProfile;
import android.media.MediaMetadataRetriever;
import android.media.MediaRecorder;
import android.media.MediaScannerConnection;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.DisplayMetrics;
import android.view.Surface;
import android.view.WindowManager;

import com.google.android.gms.analytics.HitBuilders;

import java.io.File;
import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import javax.inject.Provider;

import timber.log.Timber;

import static android.app.PendingIntent.FLAG_CANCEL_CURRENT;
import static android.content.Context.MEDIA_PROJECTION_SERVICE;
import static android.content.Context.NOTIFICATION_SERVICE;
import static android.content.Context.WINDOW_SERVICE;
import static android.content.Intent.ACTION_SEND;
import static android.content.Intent.ACTION_VIEW;
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION;
import static android.media.MediaRecorder.OutputFormat.MPEG_4;
import static android.media.MediaRecorder.VideoEncoder.H264;
import static android.media.MediaRecorder.VideoSource.SURFACE;
import static android.os.Environment.DIRECTORY_MOVIES;

/**
 * 录制的管理类
 */
final class RecordingSession {
    static final int NOTIFICATION_ID = 522592;

    private static final String DISPLAY_NAME = "telecine";
    private static final String MIME_TYPE = "video/mp4";

    interface Listener {
        /**
         * Invoked immediately prior to the start of recording.
         */
        void onStart();

        /**
         * Invoked immediately after the end of recording.
         */
        void onStop();

        /**
         * Invoked after all work for this session has completed.
         */
        void onEnd();
    }

    //获取主线程
    private final Handler mainThread = new Handler(Looper.getMainLooper());

    private final Context context;
    private final Listener listener;
    private final int resultCode;
    private final Intent data;

    private final Analytics analytics;
    private final Provider<Boolean> showCountDown;
    private final Provider<Integer> videoSizePercentage;

    private final File outputRoot;
    //输出文件的命名格式
    private final DateFormat fileFormat =
            new SimpleDateFormat("'Telecine_'yyyy-MM-dd-HH-mm-ss'.mp4'", Locale.US);

    //消息通知
    private final NotificationManager notificationManager;
    private final WindowManager windowManager;
    //录屏的管理
    private final MediaProjectionManager projectionManager;

    private OverlayView overlayView;
    //用于录制声音和视频
    private MediaRecorder recorder;
    //获取 录屏或者声音的token
    private MediaProjection projection;
    //捕捉 屏幕内容渲染到提供了 createVirtualDisplay 的surface view
    private VirtualDisplay display;
    private String outputFile;
    private boolean running;
    private long recordingStartNanos;
    private boolean recordAudio;

    RecordingSession(Context context, Listener listener, int resultCode, Intent data,
                     Analytics analytics, Provider<Boolean> showCountDown, Provider<Integer> videoSizePercentage, Boolean recordAudio) {
        this.context = context;
        this.listener = listener;
        this.resultCode = resultCode;
        this.data = data;
        this.analytics = analytics;
        this.recordAudio = recordAudio;

        this.showCountDown = showCountDown;
        this.videoSizePercentage = videoSizePercentage;
        //文件保存的路径
        File picturesDir = Environment.getExternalStoragePublicDirectory(DIRECTORY_MOVIES);
        outputRoot = new File(picturesDir, "Telecine");

        //通知管理
        notificationManager = (NotificationManager) context.getSystemService(NOTIFICATION_SERVICE);
        windowManager = (WindowManager) context.getSystemService(WINDOW_SERVICE);
        projectionManager = (MediaProjectionManager) context.getSystemService(MEDIA_PROJECTION_SERVICE);
    }

    /**
     * 展示覆盖层
     */
    public void showOverlay() {
        Timber.d("Adding overlay view to window.");

        /**
         * 覆盖层点击的回调处理
         */
        OverlayView.Listener overlayListener = new OverlayView.Listener() {
            @Override
            public void onCancel() {
                cancelOverlay();
            }

            @Override
            public void onStart() {
                //动画执行完成开始录制
                startRecording();
            }

            @Override
            public void onStop() {
                stopRecording();
            }
        };
        overlayView = OverlayView.create(context, overlayListener, showCountDown.get());
        windowManager.addView(overlayView, OverlayView.createLayoutParams(context));

        analytics.send(new HitBuilders.EventBuilder() //
                .setCategory(Analytics.CATEGORY_RECORDING)
                .setAction(Analytics.ACTION_OVERLAY_SHOW)
                .build());
    }

    private void hideOverlay() {
        if (overlayView != null) {
            Timber.d("Removing overlay view from window.");
            windowManager.removeView(overlayView);
            overlayView = null;

            analytics.send(new HitBuilders.EventBuilder() //
                    .setCategory(Analytics.CATEGORY_RECORDING)
                    .setAction(Analytics.ACTION_OVERLAY_HIDE)
                    .build());
        }
    }

    /**
     * 移除overLay 视图,注册 onEnd回调
     */
    private void cancelOverlay() {
        hideOverlay();
        listener.onEnd();

        analytics.send(new HitBuilders.EventBuilder() //
                .setCategory(Analytics.CATEGORY_RECORDING)
                .setAction(Analytics.ACTION_OVERLAY_CANCEL)
                .build());
    }

    /**
     * 获取配置好的需要录制视频的信息
     *
     * @return
     */
    private RecordingInfo getRecordingInfo() {
        //获取手机整个的宽高以及分辨率dp
        DisplayMetrics displayMetrics = new DisplayMetrics();
        WindowManager wm = (WindowManager) context.getSystemService(WINDOW_SERVICE);
        wm.getDefaultDisplay().getRealMetrics(displayMetrics);
        int displayWidth = displayMetrics.widthPixels;
        int displayHeight = displayMetrics.heightPixels;
        int displayDensity = displayMetrics.densityDpi;
        Timber.i("Display size: %s x %s @ %s", displayWidth, displayHeight, displayDensity);

        //获取 横竖屏 配置
        Configuration configuration = context.getResources().getConfiguration();
        boolean isLandscape = configuration.orientation == ORIENTATION_LANDSCAPE;
        Timber.i("Display landscape: %s", isLandscape);

        // Get the best camera profile available. We assume MediaRecorder supports the highest.
        //获取录像机的最高d帧率 没有就默认是30
        CamcorderProfile camcorderProfile = CamcorderProfile.get(CamcorderProfile.QUALITY_HIGH);
        //检查是否正确获取到了profile
        int cameraWidth = camcorderProfile != null ? camcorderProfile.videoFrameWidth : -1;
        int cameraHeight = camcorderProfile != null ? camcorderProfile.videoFrameHeight : -1;
        //通过 CamcorderProfile获取 camera的帧率,默认为30
        int cameraFrameRate = camcorderProfile != null ? camcorderProfile.videoFrameRate : 30;
        Timber.i("Camera size: %s x %s framerate: %s", cameraWidth, cameraHeight, cameraFrameRate);

        int sizePercentage = videoSizePercentage.get();
        Timber.i("Size percentage: %s", sizePercentage);

        return calculateRecordingInfo(displayWidth, displayHeight, displayDensity, isLandscape,
                cameraWidth, cameraHeight, cameraFrameRate, sizePercentage);
    }

    private void startRecording() {
        Timber.d("Starting screen recording...");

        //创建路径下的文件夹
        if (!outputRoot.mkdirs()) { //-- -- outputRoot一系列的操作可以再 Camera的api demo中找到
            Timber.e("Unable to create output directory '%s'.", outputRoot.getAbsolutePath());
            // We're probably about to crash, but at least the log will indicate as to why.
        }

        RecordingInfo recordingInfo = getRecordingInfo();
        Timber.d("Recording: %s x %s @ %s", recordingInfo.width, recordingInfo.height,
                recordingInfo.density);

        /**
         * 正式开始录屏操作
         * 所需要的数据是从 MediaProjection中获得token后才能操作
         * //获取 MediaProjection token来录屏
         MediaProjectionManager manager =
         (MediaProjectionManager) activity.getSystemService(MEDIA_PROJECTION_SERVICE);
         //开始录屏 但是不能获取到系统的声音 not system audio
         Intent intent = manager.createScreenCaptureIntent();
         activity.startActivityForResult(intent, CREATE_SCREEN_CAPTURE);
         */
        recorder = new MediaRecorder();
        if(recordAudio){
            recorder.setAudioSource(MediaRecorder.AudioSource.DEFAULT);
        }
        recorder.setVideoSource(SURFACE);//Camera2中新的api
//    recorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);//Camera 中老版本的api
        recorder.setOutputFormat(MPEG_4);//setVideoFrameRate 必须在outputFormat后调用
        if(recordAudio){
            recorder.setAudioEncodingBitRate(44100);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        }
        recorder.setVideoFrameRate(recordingInfo.frameRate);
        recorder.setVideoEncoder(H264);//H264利于在网络实时传播的编码
        recorder.setVideoSize(recordingInfo.width, recordingInfo.height);
        recorder.setVideoEncodingBitRate(8 * 1000 * 1000);

        String outputName = fileFormat.format(new Date());
        //文件名+时间的格式输出
        outputFile = new File(outputRoot, outputName).getAbsolutePath();
        Timber.i("Output file '%s'.", outputFile);
        recorder.setOutputFile(outputFile);

        try {
            recorder.prepare();
        } catch (IOException e) {
            throw new RuntimeException("Unable to prepare MediaRecorder.", e);
        }

        projection = projectionManager.getMediaProjection(resultCode, data);

        //通过recorder获取到 surface
        Surface surface = recorder.getSurface();//Camera2中recorder.setVideoSource(SURFACE);
        //通过projection(投影)获取到 virtualDisplay,在将virtualDisplay的内容渲染到surface上
        //VIRTUAL_DISPLAY_FLAG_PRESENTATION 是一个Flag 具体看DisplayManager
        display =
                projection.createVirtualDisplay(DISPLAY_NAME, recordingInfo.width, recordingInfo.height,
                        recordingInfo.density, VIRTUAL_DISPLAY_FLAG_PRESENTATION, surface, null, null);

        //开始渲染
        recorder.start();
        running = true;
        //记录渲染的开始时间
        recordingStartNanos = System.nanoTime();
        //设置回调，表示已经开始录制
        listener.onStart();

        Timber.d("Screen recording started.");

        analytics.send(new HitBuilders.EventBuilder() //
                .setCategory(Analytics.CATEGORY_RECORDING)
                .setAction(Analytics.ACTION_RECORDING_START)
                .build());
    }

    private void stopRecording() {
        Timber.d("Stopping screen recording...");

        if (!running) {
            throw new IllegalStateException("Not running.");
        }
        running = false;

        hideOverlay();

        // Stop the projection in order to flush everything to the recorder.
        //停止投影，刷新 recorder的数据
        projection.stop();

        // Stop the recorder which writes the contents to the file.
        //停止录制，将内容写入文件
        recorder.stop();

        long recordingStopNanos = System.nanoTime(); //返回结束的时候的纳秒时间 通常用来计算某个过程的时间段  而System.currentTimeMillis 是计算从1970开始到现在的时间段

        //release recorder 和 VirtualDisplay
        recorder.release();
        display.release();

        analytics.send(new HitBuilders.EventBuilder() //
                .setCategory(Analytics.CATEGORY_RECORDING)
                .setAction(Analytics.ACTION_RECORDING_STOP)
                .build());
        analytics.send(new HitBuilders.TimingBuilder() //
                .setCategory(Analytics.CATEGORY_RECORDING)
                .setValue(TimeUnit.NANOSECONDS.toMillis(recordingStopNanos - recordingStartNanos))
                .setVariable(Analytics.VARIABLE_RECORDING_LENGTH)
                .build());

        listener.onStop();

        Timber.d("Screen recording stopped. Notifying media scanner of new video.");

        //扫描视频文件---api中的工具类
        MediaScannerConnection.scanFile(context, new String[]{outputFile}, null,
                new MediaScannerConnection.OnScanCompletedListener() {
                    @Override
                    public void onScanCompleted(String path, final Uri uri) {
                        Timber.d("Media scanner completed.");
                        mainThread.post(new Runnable() {
                            @Override
                            public void run() {
                                showNotification(uri, null);
                            }
                        });
                    }
                });
    }

    /**
     * 消息通知
     *
     * @param uri
     * @param bitmap
     */
    private void showNotification(final Uri uri, Bitmap bitmap) {
        //android.intent.action.VIEW 更具用户传递的内容打开对应的activity --- 用于在Notification中观看效果 ！！
        Intent viewIntent = new Intent(ACTION_VIEW, uri);
        PendingIntent pendingViewIntent =
                PendingIntent.getActivity(context, 0, viewIntent, FLAG_CANCEL_CURRENT);


        //分享uri intent  -- Intent.EXTRA_STREAM
        Intent shareIntent = new Intent(ACTION_SEND);
        //分享的类型
        shareIntent.setType(MIME_TYPE);
        shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
        //Intent.createChooser 为改intent匹配默认的打开方式..供用户选择 -- 返回用户选择的打开方式
        shareIntent = Intent.createChooser(shareIntent, null);
        PendingIntent pendingShareIntent =
                PendingIntent.getActivity(context, 0, shareIntent, FLAG_CANCEL_CURRENT);

        //获取到删除操作的广播
        Intent deleteIntent = new Intent(context, DeleteRecordingBroadcastReceiver.class);
        deleteIntent.setData(uri);
        //获取延迟执行删除的广播
        PendingIntent pendingDeleteIntent =
                PendingIntent.getBroadcast(context, 0, deleteIntent, FLAG_CANCEL_CURRENT);

        //通过context直接获取到 string，color等 不用 getResource().getString.....等
        CharSequence title = context.getText(R.string.notification_captured_title);
        CharSequence subtitle = context.getText(R.string.notification_captured_subtitle);
        CharSequence share = context.getText(R.string.notification_captured_share);
        CharSequence delete = context.getText(R.string.notification_captured_delete);
        //构建通知
        Notification.Builder builder = new Notification.Builder(context) //
                .setContentTitle(title)
                .setContentText(subtitle)
                .setWhen(System.currentTimeMillis())//设置时间
                .setShowWhen(true) //显示时间?
                .setSmallIcon(R.drawable.ic_videocam_white_24dp)
                .setColor(context.getResources().getColor(R.color.primary_normal))
                .setContentIntent(pendingViewIntent) //内容区域点击跳转的intent
                .setAutoCancel(true) //用户点击后就自动消息
                .addAction(R.drawable.ic_share_white_24dp, share, pendingShareIntent)
                .addAction(R.drawable.ic_delete_white_24dp, delete, pendingDeleteIntent);

        if (bitmap != null) {
            builder.setLargeIcon(createSquareBitmap(bitmap))
                    .setStyle(new Notification.BigPictureStyle() //
                            .setBigContentTitle(title) //
                            .setSummaryText(subtitle) //
                            .bigPicture(bitmap));
        }

        notificationManager.notify(NOTIFICATION_ID, builder.build());

        if (bitmap != null) {
            listener.onEnd();
            return;
        }

        /**
         * 异步通过uri 获取到视频当前帧 返回成bitmap
         */
        new AsyncTask<Void, Void, Bitmap>() {
            @Override
            protected Bitmap doInBackground(@NonNull Void... none) {
                //MediaMetadataRetriever 获取视频相关的frame and meta data
                MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                retriever.setDataSource(context, uri);
                return retriever.getFrameAtTime();
            }

            @Override
            protected void onPostExecute(@Nullable Bitmap bitmap) {
                if (bitmap != null) {
                    showNotification(uri, bitmap);
                } else {
                    listener.onEnd();
                }
            }
        }.execute();
    }

    /**
     * 通过配置计算最终录制的profile
     *
     * @param displayWidth      展示的宽度
     * @param displayHeight     展示的高度
     * @param displayDensity    展示的密度 (默认最高)
     * @param isLandscapeDevice 是否横屏
     * @param cameraWidth       camera宽度
     * @param cameraHeight      camera高度
     * @param cameraFrameRate   camera帧率
     * @param sizePercentage    视频最终缩小的比例
     * @return
     */
    static RecordingInfo calculateRecordingInfo(int displayWidth, int displayHeight,
                                                int displayDensity, boolean isLandscapeDevice, int cameraWidth, int cameraHeight,
                                                int cameraFrameRate, int sizePercentage) {
        // Scale the display size before any maximum size calculations.
        displayWidth = displayWidth * sizePercentage / 100;
        displayHeight = displayHeight * sizePercentage / 100;

        if (cameraWidth == -1 && cameraHeight == -1) {
            // No cameras. Fall back to the display size.
            return new RecordingInfo(displayWidth, displayHeight, cameraFrameRate, displayDensity);
        }

        //横竖屏情况的录屏 将 宽高值交换
        int frameWidth = isLandscapeDevice ? cameraWidth : cameraHeight;
        int frameHeight = isLandscapeDevice ? cameraHeight : cameraWidth;
        if (frameWidth >= displayWidth && frameHeight >= displayHeight) {
            // Frame can hold the entire display. Use exact values.
            return new RecordingInfo(displayWidth, displayHeight, cameraFrameRate, displayDensity);
        }

        // Calculate new width or height to preserve aspect ratio.
        if (isLandscapeDevice) {
            frameWidth = displayWidth * frameHeight / displayHeight;
        } else {
            frameHeight = displayHeight * frameWidth / displayWidth;
        }
        return new RecordingInfo(frameWidth, frameHeight, cameraFrameRate, displayDensity);
    }

    /**
     * 录制视频的信息model
     * 全部用静态内部类写的 -- 这个习惯很好
     */
    static final class RecordingInfo {
        final int width;
        final int height;
        final int frameRate;
        final int density;

        RecordingInfo(int width, int height, int frameRate, int density) {
            this.width = width;
            this.height = height;
            this.frameRate = frameRate;
            this.density = density;
        }
    }

    private static Bitmap createSquareBitmap(Bitmap bitmap) {
        int x = 0;
        int y = 0;
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        if (width > height) {
            x = (width - height) / 2;
            //noinspection SuspiciousNameCombination
            width = height;
        } else {
            y = (height - width) / 2;
            //noinspection SuspiciousNameCombination
            height = width;
        }
        return Bitmap.createBitmap(bitmap, x, y, width, height, null, true);
    }

    public void destroy() {
        if (running) {
            Timber.w("Destroyed while running!");
            stopRecording();
        }
    }

    /**
     * 删除的广播接受者
     */
    public static final class DeleteRecordingBroadcastReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            NotificationManager notificationManager =
                    (NotificationManager) context.getSystemService(NOTIFICATION_SERVICE);
            notificationManager.cancel(NOTIFICATION_ID);
            final Uri uri = intent.getData();
            final ContentResolver contentResolver = context.getContentResolver();
            new AsyncTask<Void, Void, Void>() {
                @Override
                protected Void doInBackground(@NonNull Void... none) {
                    int rowsDeleted = contentResolver.delete(uri, null, null);
                    if (rowsDeleted == 1) {
                        Timber.i("Deleted recording.");
                    } else {
                        Timber.e("Error deleting recording.");
                    }
                    return null;
                }
            }.execute();
        }
    }
}
