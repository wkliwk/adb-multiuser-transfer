// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 wkliwk
package io.github.wkliwk.adbtransfer;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * File transfer for the adb shell into THIS Android user's /sdcard/Pictures and /sdcard/DCIM.
 *
 *   write : adb shell content write --user N --uri content://io.github.wkliwk.adbtransfer/demo/a.jpg < a.jpg
 *           (written to a hidden .part file, renamed when the stream closes, then media-scanned)
 *   read  : adb shell content read  --user N --uri content://io.github.wkliwk.adbtransfer/demo/a.jpg > a.jpg
 *   stat  : adb shell content call  --user N --uri content://io.github.wkliwk.adbtransfer --method stat --arg demo/a.jpg
 *   ls    : ... --method ls --arg demo
 *   rm    : ... --method rm --arg demo/a.jpg      (one regular file only, never a directory)
 *
 * Only the adb shell may call it, and only paths inside Pictures/ or DCIM/ are reachable.
 */
public class TransferProvider extends ContentProvider {
    private static final int SHELL_UID = 2000;
    // Only these top-level folders of the user's storage are reachable. A path may name one explicitly
    // ("DCIM/Camera/demo/..." / "Pictures/demo/...") or, for short callers, start below Pictures ("demo/...").
    private static final String[] ALLOWED_ROOTS = {"Pictures", "DCIM"};
    private static final String DEFAULT_ROOT = "Pictures";

    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    public boolean onCreate() {
        return true;
    }

    // ---- file streams ----------------------------------------------------------------------

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        requireShell();
        final File target = resolve(uri.getPath());
        if ("r".equals(mode)) {
            return ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_ONLY);
        }
        if (!"w".equals(mode) && !"wt".equals(mode)) {
            throw new IllegalArgumentException("unsupported mode: " + mode);
        }
        File dir = target.getParentFile();
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new FileNotFoundException("cannot create " + dir);
        }
        final File part = new File(dir, "." + target.getName() + ".part");
        int bits = ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_CREATE
                | ParcelFileDescriptor.MODE_TRUNCATE;
        try {
            return ParcelFileDescriptor.open(part, bits, main, new ParcelFileDescriptor.OnCloseListener() {
                @Override
                public void onClose(IOException error) {
                    if (error == null && part.renameTo(target)) {
                        scan(target);
                    } else {
                        part.delete(); // failed or truncated write: leave nothing behind
                    }
                }
            });
        } catch (IOException e) {
            throw new FileNotFoundException(e.getMessage());
        }
    }

    // ---- stat / ls / rm --------------------------------------------------------------------

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        requireShell();
        Bundle out = new Bundle();
        try {
            File f = resolve(arg == null ? "" : arg);
            switch (method) {
                case "stat":
                    out.putBoolean("exists", f.isFile());
                    if (f.isFile()) {
                        out.putLong("size", f.length());
                        out.putString("sha256", sha256(f));
                    }
                    break;
                case "ls":
                    File[] kids = f.listFiles();
                    String[] names = new String[kids == null ? 0 : kids.length];
                    for (int i = 0; i < names.length; i++) {
                        names[i] = kids[i].getName() + (kids[i].isDirectory() ? "/" : " " + kids[i].length());
                    }
                    Arrays.sort(names);
                    out.putStringArray("entries", names);
                    break;
                case "rm":
                    boolean ok = f.isFile() && f.delete();
                    if (ok) scan(f); // lets the media store drop the row
                    out.putBoolean("deleted", ok);
                    break;
                default:
                    throw new IllegalArgumentException("unknown method: " + method);
            }
        } catch (IOException e) {
            out.putString("error", String.valueOf(e.getMessage()));
        }
        return out;
    }

    // ---- helpers ---------------------------------------------------------------------------

    private static void requireShell() {
        if (Binder.getCallingUid() != SHELL_UID) {
            throw new SecurityException("adb shell only");
        }
    }

    /**
     * Maps "DCIM/Camera/a.jpg" or "Pictures/demo/a.jpg" to the same path under the user's storage; a path that does not
     * start with an allowed root ("demo/a.jpg") is taken as below Pictures. Refuses anything that resolves outside an
     * allowed root (".." and symlinks are canonicalised away).
     */
    private static File resolve(String relative) throws FileNotFoundException {
        String rel = relative.startsWith("/") ? relative.substring(1) : relative;
        String top = rel.contains("/") ? rel.substring(0, rel.indexOf('/')) : rel;
        boolean explicit = false;
        for (String r : ALLOWED_ROOTS) {
            if (r.equals(top)) explicit = true;
        }
        File storage = Environment.getExternalStorageDirectory();
        File file = rel.isEmpty() ? new File(storage, DEFAULT_ROOT) : explicit ? new File(storage, rel) : new File(new File(storage, DEFAULT_ROOT), rel);
        try {
            String path = file.getCanonicalPath();
            for (String r : ALLOWED_ROOTS) {
                String rootPath = new File(storage, r).getCanonicalPath();
                if (path.equals(rootPath) || path.startsWith(rootPath + File.separator)) return file;
            }
        } catch (IOException e) {
            throw new FileNotFoundException(e.getMessage());
        }
        throw new SecurityException("outside Pictures/ and DCIM/: " + relative);
    }

    private void scan(File f) {
        if (getContext() != null) {
            MediaScannerConnection.scanFile(getContext(), new String[] {f.getAbsolutePath()}, null, null);
        }
    }

    private static String sha256(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    @Override public String getType(Uri uri) { return null; }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { throw new UnsupportedOperationException(); }
    @Override public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
}
