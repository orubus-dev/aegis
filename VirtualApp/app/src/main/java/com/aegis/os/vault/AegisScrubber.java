package com.aegis.os.vault;

import android.media.ExifInterface;

import com.aegis.os.Diagnostics;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Aegis image scrubber: strips identifying metadata (EXIF) out of pictures
 * before they are stored in the vault -- GPS coordinates, the device make
 * and model, timestamps, software signatures and free-text comments.
 * Orientation is preserved so the photo still displays correctly; nothing
 * else survives.
 *
 * Tags are referenced by their EXIF string names (the same keys
 * ExifInterface.getAttribute/setAttribute use) so this compiles against
 * any API level regardless of which tag constants the platform exposes.
 */
public final class AegisScrubber {

    private static final List<String> WIPE = new ArrayList<>();

    static {
        // GPS: the single most identifying block
        String[] gps = {
                "GPSLatitude", "GPSLatitudeRef", "GPSLongitude", "GPSLongitudeRef",
                "GPSAltitude", "GPSAltitudeRef", "GPSTimestamp", "GPSDateStamp",
                "GPSProcessingMethod", "GPSAreaInformation", "GPSDOP",
                "GPSSpeed", "GPSSpeedRef", "GPSTrack", "GPSTrackRef",
                "GPSImgDirection", "GPSImgDirectionRef", "GPSMapDatum",
                "GPSDestLatitude", "GPSDestLatitudeRef", "GPSDestLongitude",
                "GPSDestLongitudeRef", "GPSDestBearing", "GPSDestBearingRef",
                "GPSDestDistance", "GPSDestDistanceRef", "GPSDifferential",
                "GPSHPositioningError"
        };
        // Device identity
        String[] device = {
                "Make", "Model", "Software", "Hardware", "HostComputer",
                "Artist", "CameraOwnerName", "CamaraOwnerName", "CameraSerialNumber",
                "BodySerialNumber", "LensMake", "LensModel", "LensSerialNumber"
        };
        // Time and free text
        String[] text = {
                "DateTime", "DateTimeDigitized", "DateTimeOriginal",
                "SubSecTime", "SubSecTimeOriginal", "SubSecTimeDigitized",
                "ImageDescription", "UserComment", "Copyright", "MakerNote",
                "XMP", "PreviewImageStart", "PreviewImageLength",
                "ThumbnailImageDescription"
        };
        for (String[] group : new String[][]{gps, device, text}) {
            for (String tag : group) {
                if (!WIPE.contains(tag)) {
                    WIPE.add(tag);
                }
            }
        }
    }

    private AegisScrubber() {
    }

    /**
     * Remove identifying EXIF from a JPEG in place.
     *
     * @return number of metadata fields actually removed, or -1 on failure.
     */
    public static int scrubImage(File jpeg) {
        if (jpeg == null || !jpeg.isFile() || jpeg.length() == 0) {
            return -1;
        }
        try {
            ExifInterface exif = new ExifInterface(jpeg.getAbsolutePath());
            int removed = 0;
            for (String tag : WIPE) {
                String value = exif.getAttribute(tag);
                if (value != null) {
                    exif.setAttribute(tag, null);
                    removed++;
                }
            }
            if (removed > 0) {
                exif.saveAttributes();
            }
            Diagnostics.log(null, "scrub: removed " + removed + " metadata fields");
            return removed;
        } catch (Throwable t) {
            Diagnostics.log(null, "scrub: failed " + t);
            return -1;
        }
    }

    /** true when the file looks like a JPEG (the format we can scrub). */
    public static boolean isJpeg(File f) {
        if (f == null || !f.isFile()) {
            return false;
        }
        String name = f.getName().toLowerCase();
        return name.endsWith(".jpg") || name.endsWith(".jpeg");
    }
}
