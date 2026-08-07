/*
 * Copyright 2026 the original author or authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet.util;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Crash-safe replacement for {@code openFileOutput(name, MODE_PRIVATE)} + write.
 *
 * <p>Writes into a sibling {@code <name>.tmp}, fsyncs it, then {@code rename(2)}s it over the
 * destination. Because the destination is never opened for writing it can never be observed
 * half-written: a kill at any point leaves either the previous complete file or the new complete
 * file, plus at worst an abandoned temp.
 *
 * <p>This is the same discipline dashj uses for the primary wallet
 * ({@code Wallet.saveToFile(File temp, File dest)}), applied to the app's own key backup, which had
 * been written in place. A truncated key backup is unrecoverable: it is the sole fallback when the
 * primary wallet fails to parse, so losing it turns a recoverable load failure into a startup
 * crash-loop with no way out but a reinstall.
 *
 * <p>Temp files are named {@code <name>.tmp} so that {@code WalletApplication.cleanupFiles()}, which
 * sweeps {@code *.tmp} from the files dir on launch, garbage-collects any temp abandoned by a kill.
 */
public final class AtomicFileWriter {

    /** Suffix appended to the destination name to form the temp file. */
    public static final String TEMP_SUFFIX = ".tmp";

    /** Writes the file body. May throw; the destination is left untouched if it does. */
    public interface ContentWriter {
        void writeTo(OutputStream out) throws IOException;
    }

    private AtomicFileWriter() {
        // no instances
    }

    /**
     * Atomically (re)writes {@code filename} in the app's internal files dir with {@code MODE_PRIVATE}.
     *
     * @throws IOException if the content could not be written or the rename failed. In either case
     *         any pre-existing destination file is left exactly as it was.
     */
    public static void write(final Context context, final String filename, final ContentWriter writer)
            throws IOException {
        final String tempFilename = filename + TEMP_SUFFIX;
        final File tempFile = new File(context.getFilesDir(), tempFilename);
        final File destFile = new File(context.getFilesDir(), filename);

        FileOutputStream os = null;
        boolean renamed = false;
        try {
            // openFileOutput rather than a bare FileOutputStream so the temp inherits MODE_PRIVATE:
            // callers use this for key material.
            os = context.openFileOutput(tempFilename, Context.MODE_PRIVATE);
            writer.writeTo(os);
            os.flush();
            // Force the bytes to disk BEFORE the rename, so a power loss cannot publish a
            // rename that points at unwritten data.
            os.getFD().sync();
            os.close();
            os = null;

            if (!tempFile.renameTo(destFile))
                throw new IOException("failed to rename " + tempFile + " to " + destFile);
            renamed = true;
        } finally {
            if (os != null) {
                try {
                    os.close();
                } catch (final IOException x) {
                    // swallow: the real failure is already propagating
                }
            }
            if (!renamed) {
                //noinspection ResultOfMethodCallIgnored
                tempFile.delete();
            }
        }
    }
}
