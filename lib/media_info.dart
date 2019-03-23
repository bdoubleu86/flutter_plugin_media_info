import 'dart:async';

import 'package:flutter/services.dart';

/// Media information & basic thumbnail creation methods.
class MediaInfo {
  static const MethodChannel _channel =
      MethodChannel('asia.ivity.flutter/media_info');

  /// Utilizes platform methods (which may include a combination of HW and SW
  /// decoders) to analyze the media file at a given path.
  ///
  /// This will return *null* if the media file is invalid.
  ///
  /// Valid media files will generate a dictionary with relevant fields set.
  /// For video files, this includes:
  /// - width (int)
  /// - height (int)
  /// - frameRate (float)
  /// - durationMs (long)
  /// - numTracks (int)
  /// - mimeType (String)
  static Future<Map<String, dynamic>> getMediaInfo(String path) async {
    final dynamic version = await _channel.invokeMethod('getMediaInfo', path);

    return Map<String, dynamic>.from(version);
  }

  /// Generate a thumbnail for a video or image file.
  ///
  /// The thumbnail will be stored in the file path specified at [target].
  ///
  /// Additionally, a target width and height should be specified.
  ///
  /// Currently the thumbnail format is JPG, set to image quality 80.
  ///
  /// Errors will be propagated to the consumer of this API and need to be
  /// handled in the onError handler of the returned [Future].
  static Future<String> generateThumbnail(
    /// Absolute source file path, without the file:// scheme prepended.
    String path,

    /// Absolte target file path, without the file:// scheme prepended.
    String target,

    /// Target width.
    int width,

    /// Target height.
    /// TODO: Consider to remove the field or specify the fit/crop ratio better.
    int height,
  ) async {
    final dynamic successful =
        await _channel.invokeMethod('generateThumbnail', <String, dynamic>{
      'path': path,
      'target': target,
      'width': width,
      'height': height,
    });

    return successful;
  }
}
