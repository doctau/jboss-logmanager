/*
 * JBoss, Home of Professional Open Source.
 *
 * Copyright 2014 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jboss.logmanager.handlers;

import java.io.OutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import org.jboss.logmanager.ExtLogRecord;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.logging.ErrorManager;

public class SizeRotatingFileHandler extends FileHandler {
    // by default, rotate at 10MB
    private long rotateSize = 0xa0000L;
    private int maxBackupIndex = 1;
    private CountingOutputStream outputStream;
    private boolean rotateOnBoot;
    private String suffix;

    /**
     * Construct a new instance with no formatter and no output file.
     */
    public SizeRotatingFileHandler() {
    }

    /**
     * Construct a new instance with the given output file.
     *
     * @param file the file
     *
     * @throws java.io.FileNotFoundException if the file could not be found on open
     */
    public SizeRotatingFileHandler(final File file) throws FileNotFoundException {
        super(file);
    }

    /**
     * Construct a new instance with the given output file and append setting.
     *
     * @param file the file
     * @param append {@code true} to append, {@code false} to overwrite
     *
     * @throws java.io.FileNotFoundException if the file could not be found on open
     */
    public SizeRotatingFileHandler(final File file, final boolean append) throws FileNotFoundException {
        super(file, append);
    }

    /**
     * Construct a new instance with the given output file.
     *
     * @param fileName the file name
     *
     * @throws java.io.FileNotFoundException if the file could not be found on open
     */
    public SizeRotatingFileHandler(final String fileName) throws FileNotFoundException {
        super(fileName);
    }

    /**
     * Construct a new instance with the given output file and append setting.
     *
     * @param fileName the file name
     * @param append {@code true} to append, {@code false} to overwrite
     *
     * @throws java.io.FileNotFoundException if the file could not be found on open
     */
    public SizeRotatingFileHandler(final String fileName, final boolean append) throws FileNotFoundException {
        super(fileName, append);
    }

    /**
     * Construct a new instance with no formatter and no output file.
     */
    public SizeRotatingFileHandler(final long rotateSize, final int maxBackupIndex) {
        this.rotateSize = rotateSize;
        this.maxBackupIndex = maxBackupIndex;
    }

    /**
     * Construct a new instance with the given output file.
     *
     * @param file the file
     *
     * @throws java.io.FileNotFoundException if the file could not be found on open
     */
    public SizeRotatingFileHandler(final File file, final long rotateSize, final int maxBackupIndex) throws FileNotFoundException {
        super(file);
        this.rotateSize = rotateSize;
        this.maxBackupIndex = maxBackupIndex;
    }

    /**
     * Construct a new instance with the given output file and append setting.
     *
     * @param file the file
     * @param append {@code true} to append, {@code false} to overwrite
     *
     * @throws java.io.FileNotFoundException if the file could not be found on open
     */
    public SizeRotatingFileHandler(final File file, final boolean append, final long rotateSize, final int maxBackupIndex) throws FileNotFoundException {
        super(file, append);
        this.rotateSize = rotateSize;
        this.maxBackupIndex = maxBackupIndex;
    }

    /** {@inheritDoc} */
    public void setOutputStream(final OutputStream outputStream) {
        synchronized (outputLock) {
            this.outputStream = outputStream == null ? null : new CountingOutputStream(outputStream);
            super.setOutputStream(this.outputStream);
        }
    }

    /** {@inheritDoc} */
    public void setFile(final File file) throws FileNotFoundException {
        synchronized (outputLock) {
            // Check for a rotate
            if (rotateOnBoot && maxBackupIndex > 0 && file != null && file.exists() && file.length() > 0L) {
                rotate(file);
            }
            super.setFile(file);
            if (outputStream != null)
                outputStream.currentSize = file == null ? 0L : file.length();
        }
    }

    /**
     * Indicates whether or a not the handler should rotate the file before the first log record is written.
     *
     * @return {@code true} if file should rotate on boot, otherwise {@code false}/
     */
    public boolean isRotateOnBoot() {
        synchronized (outputLock) {
            return rotateOnBoot;
        }
    }

    /**
     * Set to a value of {@code true} if the file should be rotated before the a new file is set. The rotation only
     * happens if the file names are the same and the file has a {@link java.io.File#length() length} greater than 0.
     *
     * @param rotateOnBoot {@code true} to rotate on boot, otherwise {@code false}
     */
    public void setRotateOnBoot(final boolean rotateOnBoot) {
        checkAccess(this);
        synchronized (outputLock) {
            this.rotateOnBoot = rotateOnBoot;
        }
    }

    /**
     * Set the rotation size, in bytes.
     *
     * @param rotateSize the number of bytes before the log is rotated
     */
    public void setRotateSize(final long rotateSize) {
        checkAccess(this);
        synchronized (outputLock) {
            this.rotateSize = rotateSize;
        }
    }

    /**
     * Set the maximum backup index (the number of log files to keep around).
     *
     * @param maxBackupIndex the maximum backup index
     */
    public void setMaxBackupIndex(final int maxBackupIndex) {
        checkAccess(this);
        synchronized (outputLock) {
            this.maxBackupIndex = maxBackupIndex;
        }
    }

    /**
     * Returns the suffix set to be appended to files during rotation.
     *
     * @return the suffix or {@code null} if no suffix should be used
     */
    public String getSuffix() {
        return suffix;
    }

    /**
     * Sets the suffix to be appended to the file name during the file rotation. The suffix does not play a role in
     * determining when the file should be rotated.
     * <p/>
     * The suffix must be a string understood by the {@link java.text.SimpleDateFormat}.
     * <p/>
     * <b>Note:</b> Any files rotated with the suffix appended will not be deleted. The {@link #setMaxBackupIndex(int)
     * maxBackupIndex} is not used for files with a suffix.
     *
     * @param suffix the suffix to place after the filename when the file is rotated
     */
    public void setSuffix(final String suffix) {
        checkAccess(this);
        synchronized (outputLock) {
            this.suffix = suffix;
        }
    }

    /** {@inheritDoc} */
    protected void preWrite(final ExtLogRecord record) {
        final int maxBackupIndex = this.maxBackupIndex;
        final long currentSize = (outputStream == null ? Long.MIN_VALUE : outputStream.currentSize);
        if (currentSize > rotateSize && maxBackupIndex > 0) {
            try {
                final File file = getFile();
                if (file == null) {
                    // no file is set; a direct output stream or writer was specified
                    return;
                }
                // close the old file.
                setFile(null);
                rotate(file);
                // start with new file.
                setFile(file);
            } catch (FileNotFoundException e) {
                reportError("Unable to rotate log file", e, ErrorManager.OPEN_FAILURE);
            }
        }
    }

    private void rotate(final File file) {
        if (suffix == null) {
            // rotate.  First, drop the max file (if any), then move each file to the next higher slot.
            new File(file.getAbsolutePath() + "." + maxBackupIndex).delete();
            for (int i = maxBackupIndex - 1; i >= 1; i--) {
                new File(file.getAbsolutePath() + "." + i).renameTo(new File(file.getAbsolutePath() + "." + (i + 1)));
            }
            file.renameTo(new File(file.getAbsolutePath() + ".1"));
        } else {
            // This is not efficient, but performance risks were noted on the setSuffix() method
            final String suffix = new SimpleDateFormat(this.suffix).format(new Date());
            // Create the file name
            final String newBaseFilename = file.getAbsolutePath() + suffix;

            // Rename any incremental files found
            for (int i = maxBackupIndex - 1; i >= 1; i--) {
                new File(newBaseFilename + "." + i).renameTo(new File(newBaseFilename + "." + (i + 1)));
            }
            // Rename the current file
            file.renameTo(new File(newBaseFilename + ".1"));
        }
    }
}
