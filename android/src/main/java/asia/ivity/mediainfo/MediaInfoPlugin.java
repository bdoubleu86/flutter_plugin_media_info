package asia.ivity.mediainfo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.annotation.NonNull;
import asia.ivity.mediainfo.util.OutputSurface;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.Player.EventListener;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.TrackSelectionArray;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.util.Util;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry.Registrar;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java9.util.concurrent.CompletableFuture;

public class MediaInfoPlugin implements MethodCallHandler {

  private static final String NAMESPACE = "asia.ivity.flutter";
  private static final String TAG = "MediaInfoPlugin";

  private static final boolean USE_EXOPLAYER = true;

  public static void registerWith(Registrar registrar) {
    final MethodChannel channel =
        new MethodChannel(registrar.messenger(), NAMESPACE + "/media_info");
    channel.setMethodCallHandler(new MediaInfoPlugin(registrar.context()));
  }

  private ThreadPoolExecutor executorService;

  private Handler mainThreadHandler;

  private final Context context;

  private MediaInfoPlugin(Context context) {
    this.context = context;
  }

  private SimpleExoPlayer exoPlayer;

  @Override
  public void onMethodCall(@NonNull MethodCall call, @NonNull Result result) {
    if (executorService == null) {
      if (USE_EXOPLAYER) {
        executorService =
            new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
      } else {
        executorService =
            (ThreadPoolExecutor)
                Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
      }
    }

    if (mainThreadHandler == null) {
      mainThreadHandler = new Handler(Looper.myLooper());
    }

    if (call.method.equalsIgnoreCase("getMediaInfo")) {
      String path = (String) call.arguments;

      handleMediaInfo(context, path, result);
    } else if (call.method.equalsIgnoreCase("generateThumbnail")) {
      handleThumbnail(
          context,
          call.argument("path"),
          call.argument("target"),
          call.argument("width"),
          call.argument("height"),
          result,
          mainThreadHandler);
    }
  }

  private void handleMediaInfo(Context context, String path, Result result) {
    if (USE_EXOPLAYER) {
      CompletableFuture<VideoDetail> future = new CompletableFuture<>();

      executorService.execute(
          () -> {
            mainThreadHandler.post(() -> handleMediaInfoExoPlayer(context, path, future));

            try {
              VideoDetail info = future.get();
              mainThreadHandler.post(
                  () -> {
                    if (info != null) {
                      result.success(info.toMap());
                    } else {
                      result.error("MediaInfo", "InvalidFile", null);
                    }
                  });

            } catch (InterruptedException e) {
              mainThreadHandler.post(() -> result.error("MediaInfo", e.getMessage(), null));
            } catch (ExecutionException e) {
              mainThreadHandler.post(
                  () -> result.error("MediaInfo", e.getCause().getMessage(), null));
            }

            Log.d(TAG, "current ES queue size: " + executorService.getQueue().size());
            if (executorService.getQueue().size() < 1) {
              mainThreadHandler.post(this::releaseExoPlayerAndResources);
            }
          });
    } else {
      executorService.execute(
          () -> {
            final VideoDetail info = handleMediaInfoMediaStore(path);

            mainThreadHandler.post(
                () -> {
                  if (info != null) {
                    result.success(info.toMap());
                  } else {
                    result.error("MediaInfo", "InvalidFile", null);
                  }
                });
          });
    }
  }

  private void handleMediaInfoExoPlayer(
      Context context, String path, CompletableFuture<VideoDetail> future) {

    Log.d(TAG, "get exo media info of " + path);

    ensureExoPlayer();
    exoPlayer.clearVideoSurface();

    final EventListener listener =
        new EventListener() {
          @Override
          public void onTracksChanged(
              TrackGroupArray trackGroups, TrackSelectionArray trackSelections) {

            if (trackSelections.length == 0 || trackSelections.get(0) == null) {
              future.completeExceptionally(new IOException("TracksUnreadable"));
              return;
            }

            Format format = trackSelections.get(0).getSelectedFormat();

            int width = format.width;
            int height = format.height;
            int rotation = format.rotationDegrees;

            // Switch the width/height if video was taken in portrait mode
            if (rotation == 90 || rotation == 270) {
              int temp = width;
              width = height;
              height = temp;
            }

            VideoDetail info =
                new VideoDetail(
                    width,
                    height,
                    format.frameRate,
                    exoPlayer.getDuration(),
                    (short) trackGroups.length,
                    format.sampleMimeType);
            //            exoPlayer.release();
            future.complete(info);
          }

          @Override
          public void onPlayerError(ExoPlaybackException error) {
            Log.e(TAG, "Player Error for this file", error);
            future.completeExceptionally(error);
            //            exoPlayer.release();
          }
        };

    exoPlayer.addListener(listener);

    future.whenComplete(
        (videoDetail, throwable) -> {
          exoPlayer.removeListener(listener);
          Log.d(TAG, "get exo media info of " + path + " *FINISHED*");
        });

    DataSource.Factory dataSourceFactory =
        new DefaultDataSourceFactory(context, Util.getUserAgent(context, "media_info"));
    exoPlayer.prepare(
        new ProgressiveMediaSource.Factory(dataSourceFactory)
            .createMediaSource(Uri.fromFile(new File(path))));
  }

  private VideoDetail handleMediaInfoMediaStore(String path) {
    return VideoUtils.readVideoDetail(new File(path));
  }

  OutputSurface surface;

  private void handleThumbnail(
      Context context,
      String path,
      String targetPath,
      int width,
      int height,
      Result result,
      Handler mainThreadHandler) {

    final File target = new File(targetPath);

    executorService.submit(
        () -> {
          if (target.exists()) {
            Log.e(TAG, "Target $target file already exists.");
            mainThreadHandler.post(() -> result.error("MediaInfo", "FileOverwriteDenied", null));
            return;
          }

          if (context == null) {
            Log.e(TAG, "Context disappeared");
            mainThreadHandler.post(() -> result.error("MediaInfo", "ContextDisappeared", null));

            return;
          }

          if (USE_EXOPLAYER) {
            CompletableFuture<String> future = new CompletableFuture<>();

            mainThreadHandler.post(
                () -> handleThumbnailExoPlayer(context, path, width, height, target, future));

            try {
              final String futureResult = future.get();
              mainThreadHandler.post(() -> result.success(futureResult));
            } catch (InterruptedException e) {
              mainThreadHandler.post(() -> result.error("MediaInfo", "Interrupted", null));
            } catch (ExecutionException e) {
              Log.e(TAG, "Execution exception", e);
              mainThreadHandler.post(() -> result.error("MediaInfo", "Misc", null));
            }

            Log.d(TAG, "current ES queue size: " + executorService.getQueue().size());
            if (executorService.getQueue().size() < 1) {
              mainThreadHandler.post(this::releaseExoPlayerAndResources);
            }
          } else {
            handleThumbnailMediaStore(
                context, path, width, height, result, mainThreadHandler, target);
          }
        });
  }

  private void handleThumbnailExoPlayer(
      Context context,
      String path,
      int width,
      int height,
      File target,
      CompletableFuture<String> future) {
    Log.d(TAG, "Start decoding: " + path + ", in res: " + width + " x " + height);

    ensureExoPlayer();
    ensureSurface(width, height);

    surface.setFrameFinished(
        () -> {
          try {
            surface.awaitNewImage(500);
          } catch (Exception e) {
            //
          }

          surface.drawImage();

          try {
            final Bitmap bitmap = surface.saveFrame();
            bitmap.compress(CompressFormat.JPEG, 90, new FileOutputStream(target));
            bitmap.recycle();

            future.complete(target.getAbsolutePath());
          } catch (IOException e) {
            Log.e(TAG, "File not found", e);
            future.completeExceptionally(e);
          }
        });

    //    final AtomicBoolean renderedFirstFrame = new AtomicBoolean(false);
    //    final VideoListener videoListener =
    //        new VideoListener() {
    //          @Override
    //          public void onRenderedFirstFrame() {
    //            renderedFirstFrame.set(true);
    //          }
    //        };

    final EventListener eventListener =
        new EventListener() {
          //          @Override
          //          public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
          //            if (playbackState == ExoPlayer.STATE_READY) {
          //              if (renderedFirstFrame.get()) {}
          //            }
          //          }

          @Override
          public void onPlayerError(ExoPlaybackException error) {
            Log.e(TAG, "Player Error for this file", error);
            future.completeExceptionally(error);
          }
        };

    //    exoPlayer.addVideoListener(videoListener);
    exoPlayer.addListener(eventListener);

    future.whenComplete(
        (s, throwable) -> {
          //          exoPlayer.removeVideoListener(videoListener);
          exoPlayer.removeListener(eventListener);
          Log.d(
              TAG,
              "Start decoding: " + path + ", in res: " + width + " x " + height + " *FINISHED*");
        });

    DataSource.Factory dataSourceFactory =
        new DefaultDataSourceFactory(context, Util.getUserAgent(context, "media_info"));
    exoPlayer.prepare(
        new ProgressiveMediaSource.Factory(dataSourceFactory)
            .createMediaSource(Uri.fromFile(new File(path))));
  }

  private synchronized void ensureExoPlayer() {
    if (exoPlayer == null) {
      DefaultTrackSelector selector = new DefaultTrackSelector();
      exoPlayer = ExoPlayerFactory.newSimpleInstance(context, selector);

      int indexOfAudioRenderer = -1;
      for (int i = 0; i < exoPlayer.getRendererCount(); i++) {
        if (exoPlayer.getRendererType(i) == C.TRACK_TYPE_AUDIO) {
          indexOfAudioRenderer = i;
          break;
        }
      }

      selector.setRendererDisabled(indexOfAudioRenderer, true);
    }

    exoPlayer.setPlayWhenReady(false);
    exoPlayer.stop(true);
  }

  private void ensureSurface(int width, int height) {
    if (surface == null || surface.getWidth() != width || surface.getHeight() != height) {
      if (surface != null) {
        surface.release();
      }

      surface = new OutputSurface(width, height);
    }

    exoPlayer.setVideoSurface(surface.getSurface());
  }

  private void releaseExoPlayerAndResources() {
    if (exoPlayer != null) {
      exoPlayer.release();
      exoPlayer = null;
    }

    if (surface != null) {
      surface.release();
      surface = null;
    }
  }

  private void handleThumbnailMediaStore(
      Context context,
      String path,
      int width,
      int height,
      Result result,
      Handler mainThreadHandler,
      File target) {
    File file = ThumbnailUtils.generateVideoThumbnail(context, path, width, height);

    if (file != null && file.renameTo(target)) {
      mainThreadHandler.post(() -> result.success(target.getAbsolutePath()));
    } else {
      Log.e(TAG, "File does not generate or does not exist: " + file);
      mainThreadHandler.post(() -> result.error("MediaInfo", "FileCreationFailed", null));
    }
  }
}
