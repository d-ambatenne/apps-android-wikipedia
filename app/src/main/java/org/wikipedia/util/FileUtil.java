package org.wikipedia.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import androidx.annotation.NonNull;

import org.wikipedia.R;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

public final class FileUtil {
    private static final int JPEG_QUALITY = 85;
    private static final int KILOBYTE = 1000;

    static File writeToFile(ByteArrayOutputStream bytes, File destinationFile) throws IOException {
        FileOutputStream fo = new FileOutputStream(destinationFile);
        try {
            fo.write(bytes.toByteArray());
        } finally {
            fo.flush();
            fo.close();
        }
        return destinationFile;
    }

    static ByteArrayOutputStream compressBmpToJpg(Bitmap bitmap) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bytes);
        return bytes;
    }

    /**
     * Reads the contents of a file, preserving line breaks.
     * @return contents of the given file as a String.
     * @throws IOException
     */
    public static String readFile(final InputStream inputStream) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
        try {
            StringBuilder stringBuilder = new StringBuilder();
            String readStr;
            while ((readStr = reader.readLine()) != null) {
                stringBuilder.append(readStr).append('\n');
            }
            return stringBuilder.toString();
        } finally {
            reader.close();
        }
    }

    public static void deleteRecursively(@NonNull File f) {
        if (f.isDirectory()) {
            for (File child : f.listFiles()) {
                deleteRecursively(child);
            }
        }
        f.delete();
    }

    public static String sanitizeFileName(String fileName) {
        return fileName.replaceAll("[:\\\\/*\"?|<>']", "_");
    }

    public static boolean isVideo(String mimeType) {
        return mimeType.contains("ogg") || mimeType.contains("video");
    }

    public static boolean isAudio(String mimeType) {
        return mimeType.contains("audio");
    }

    public static boolean isImage(String mimeType) {
        return mimeType.contains("image");
    }


    private static float bytesToGB(long bytes) {
        return (float) bytes / KILOBYTE / KILOBYTE / KILOBYTE;
    }

    private static float bytesToMB(long bytes) {
        return (float) bytes / KILOBYTE / KILOBYTE;
    }

    public static String bytesToUserVisibleUnit(Context context, long bytes) {
        float sizeInGB = bytesToGB(bytes);
        if (sizeInGB >= 1.0) {
            return context.getString(R.string.size_gb, sizeInGB);
        }
        return context.getString(R.string.size_mb, bytesToMB(bytes));
    }

    static void writeToFileInDirectory(String data, String directory, String filename) {
        File file = new File(directory, filename);

        FileOutputStream outputStream = null;
        try {
            file.createNewFile();
            outputStream = new FileOutputStream(file, true);
            outputStream.write(data.getBytes());
            outputStream.flush();
            outputStream.close();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                if (outputStream != null) {
                    outputStream.flush();
                    outputStream.close();
                }
            } catch (IOException e) {
                Log.e("Exception", "File write failed: " + e.toString());
            }
        }
    }


    private FileUtil() { }
}
