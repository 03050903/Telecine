package com.jakewharton.telecine;

import android.content.ContentResolver;
import android.content.SharedPreferences;

import com.google.android.gms.analytics.GoogleAnalytics;
import com.google.android.gms.analytics.Tracker;

import java.util.Map;

import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;
import timber.log.Timber;

import static android.content.Context.MODE_PRIVATE;

@Module(
    //声明要注入此Module对象的类
    injects = {
    TelecineActivity.class,
    TelecineService.class,
    TelecineShortcutConfigureActivity.class,
    TelecineShortcutLaunchActivity.class,
    //include标签可以引入已经声明的module
    //library标签表明这个Module可能不会被使用
    //complete 意思是这是一个不完整的module, 为何这么说, 可以看出provideLocationManager的参数没有响应提供值的Providers呢,
    // 为什么呢, 因为这个module接下来要被另外一个module引用, 所以application这个参数我们将在下一个module里提供.
})
final class TelecineModule {
  private static final String PREFERENCES_NAME = "telecine";
  private static final boolean DEFAULT_SHOW_COUNTDOWN = true;
  private static final boolean DEFAULT_HIDE_FROM_RECENTS = false;
  private static final boolean DEFAULT_SHOW_TOUCHES = false;
  private static final boolean DEFAULT_RECORDING_NOTIFICATION = false;
  private static final int DEFAULT_VIDEO_SIZE_PERCENTAGE = 100;

  private final TelecineApplication app;

  TelecineModule(TelecineApplication app) {
    this.app = app;
  }

  @Provides @Singleton Analytics provideAnalytics() {
    if (BuildConfig.DEBUG) {
      return new Analytics() {
        @Override public void send(Map<String, String> params) {
          Timber.tag("Analytics").d(String.valueOf(params));
        }
      };
    }

    GoogleAnalytics googleAnalytics = GoogleAnalytics.getInstance(app);
    Tracker tracker = googleAnalytics.newTracker(BuildConfig.ANALYTICS_KEY);
    tracker.setSessionTimeout(300); // ms? s? better be s.
    return new Analytics.GoogleAnalytics(tracker);
  }

  /**
   * 定义一个获取内容提供者的module
   * Singleton表示单例且线程安全
   *
   * 如果有同样类型的对象注入的问题,
   * 问题的解决办法就是使用@Named来标识其使用的是哪一个@Provides
   *
   *
   * @return
   */
  @Provides @Singleton ContentResolver provideContentResolver() {
    return app.getContentResolver();
  }

  @Provides @Singleton SharedPreferences provideSharedPreferences() {
    return app.getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE);
  }

  @Provides @Singleton @ShowCountdown BooleanPreference provideShowCountdownPreference(
      SharedPreferences prefs) {
    return new BooleanPreference(prefs, "show-countdown", DEFAULT_SHOW_COUNTDOWN);
  }

  @Provides @ShowCountdown Boolean provideShowCountdown(@ShowCountdown BooleanPreference pref) {
    return pref.get();
  }

  @Provides @Singleton @RecordingNotification
  BooleanPreference provideRecordingNotificationPreference(SharedPreferences prefs) {
    return new BooleanPreference(prefs, "recording-notification", DEFAULT_RECORDING_NOTIFICATION);
  }

  @Provides @RecordingNotification Boolean provideRecordingNotification(
      @RecordingNotification BooleanPreference pref) {
    return pref.get();
  }

  @Provides @Singleton @HideFromRecents BooleanPreference provideHideFromRecentsPreference(
      SharedPreferences prefs) {
    return new BooleanPreference(prefs, "hide-from-recents", DEFAULT_HIDE_FROM_RECENTS);
  }

  @Provides @Singleton @ShowTouches BooleanPreference provideShowTouchesPreference(
      SharedPreferences prefs) {
    return new BooleanPreference(prefs, "show-touches", DEFAULT_SHOW_TOUCHES);
  }

  @Provides @ShowTouches Boolean provideShowTouches(@ShowTouches BooleanPreference pref) {
    return pref.get();
  }

  @Provides @Singleton @VideoSizePercentage IntPreference provideVideoSizePercentagePreference(
      SharedPreferences prefs) {
    return new IntPreference(prefs, "video-size", DEFAULT_VIDEO_SIZE_PERCENTAGE);
  }

  @Provides @VideoSizePercentage Integer provideVideoSizePercentage(
      @VideoSizePercentage IntPreference pref) {
    return pref.get();
  }
}
