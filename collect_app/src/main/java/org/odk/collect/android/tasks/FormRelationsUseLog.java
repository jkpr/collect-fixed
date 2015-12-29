/* The MIT License (MIT)
 *
 *       Copyright (c) 2015 PMA2020
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.odk.collect.android.tasks;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.text.TextUtils;
import android.util.Log;

import org.odk.collect.android.exception.UseLogException;
import org.odk.collect.android.tasks.UseLogContract.DataContainer;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.Buffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Creator: James K. Pringle
 * E-mail: jpringle@jhu.edu
 * Created: 9 December 2015
 * Last modified: 16 December 2015
 */
public class FormRelationsUseLog {
    private static final String TAG = UseLog.class.getSimpleName();
    private static final boolean LOCAL_LOG = true;

    private String mInstancePath;
    private HandlerThread mThread;
    private LogHandler mHandler;

    private List<String> mBackLog;
    private BufferedOutputStream mBufferedStream;

    public FormRelationsUseLog(String instancePath) {
        mBackLog = new ArrayList<String>();
        mInstancePath = instancePath;
        mThread = new HandlerThread(TAG);
        mThread.start();
        mHandler = new LogHandler(mThread.getLooper());
    }

    public void log(int event, String xpath, String value) {
        DataContainer d = new DataContainer();
        d.timeStamp = getTimeStamp();
        d.xpath = xpath;
        d.value = value;
        Message m = obtainMessage(event, d);
        sendMessage(m);
    }

    private void flush(boolean doInBackground) {
        Runnable r = new Runnable() {
            @Override
            public void run() {
                if ( null != mBufferedStream ) {
                    try {
                        mBufferedStream.flush();
                        if (LOCAL_LOG) {
                            Log.d(TAG, "Flushed buffer to file");
                        }
                    } catch ( IOException e ) {
                        Log.w(TAG, "Trying to flush buffered stream", e);
                    }

                } else {
                    if (LOCAL_LOG) {
                        Log.d(TAG, "Trying to flush, but buffer stream is null");
                    }
                }
            }
        };
        if ( doInBackground ) {
            mHandler.post(r);
        } else {
            r.run();
        }
    }

    public void close() {
        closeIo(true);

        Runnable r = new Runnable() {
            @Override
            public void run() {
                mThread.quit();
            }
        };
        mHandler.post(r);
    }

    private void closeIo(boolean doInBackground) {
        Runnable r = new Runnable() {
            @Override
            public void run() {
                flush(false);
                try {
                    if (null != mBufferedStream) {
                        mBufferedStream.close();
                    }
                } catch ( IOException e ) {
                    // nothing to do here
                }
                mBufferedStream = null;
            }
        };
        if ( doInBackground ) {
            mHandler.post(r);
        } else {
            r.run();
        }
    }

    // FileOutputStream( ... , append = True) (2nd parameter)
    private void openOutBuffer(File out) throws FileNotFoundException {
        boolean openNewFile = true;
        if ( out.exists() ) {
            openNewFile = false;
        }
        String fileName = out.getAbsolutePath();
        FileOutputStream fos = new FileOutputStream(fileName, true);
        mBufferedStream = new BufferedOutputStream(fos);
        if (LOCAL_LOG) {
            Log.d(TAG, "Opened stream at " + fileName);
        }
        if ( openNewFile ) {
            writePreamble();
        }
    }

    public void writePreamble() {
            String preamble = "# Log " + UseLogContract.LOG_VERSION;
            writeOutRecord(preamble);
    }

    void writeOutRecord(String record) {
        if (UseLogContract.DIVERT_TO_LOGCAT) {
            Log.v(TAG, record);
        } else {
            insertRecord(record);
        }
    }

    private File getUseLogFile() {
        File instancePath = new File(mInstancePath);
        String parentPath = instancePath.getAbsoluteFile().getParentFile().getAbsolutePath();
        String savedPath = parentPath + File.separator + UseLogContract.USE_LOG_NAME;
        return new File(savedPath);
    }

    void emptyBackLog() {
        while ( !mBackLog.isEmpty() ) {
            if ( LOCAL_LOG ) {
                Log.d(TAG, "mBackLog.size() is " + mBackLog.size() + ". Removing Message from mBackLog.");
            }
            String record = mBackLog.remove(0);
            if ( LOCAL_LOG ) {
                Log.d(TAG, "this message is null: " + (null == record));
            }
            writeOutRecord(record);
        }
    }

    // probably should always be called in background
    public void writeBackLog(boolean doInBackground) {
        Runnable r = new Runnable() {
            @Override
            public void run() {
                try {
                    File useLogFile = getUseLogFile();
                    openOutBuffer(useLogFile);
                    emptyBackLog();
                    closeIo(false);
                } catch (FileNotFoundException e) {
                    // nothing to do here
                }
            }
        };
        if ( doInBackground ) {
            mHandler.post(r);
        } else {
            r.run();
        }
    }

    private Message obtainMessage(int what, Object obj) {
        Message message = mHandler.obtainMessage(what, obj);
        return message;
    }

    private boolean sendMessage(Message msg) {
        boolean success = mHandler.sendMessage(msg);
        return success;
    }

    private String getTimeStamp() {
        long time = System.currentTimeMillis();
        return String.valueOf(time);
    }

    void insertRecord(String record) {
        if ( null == mBufferedStream ) {
            Log.w(TAG, record);
        } else {
            try {
                byte[] byteArray = record.getBytes(UseLogContract.ENCODING);
                mBufferedStream.write(byteArray);
                mBufferedStream.write('\n');
                if (LOCAL_LOG) {
                    Log.d(TAG, "Wrote record \'" + record + "\' to log");
                }
            } catch (UnsupportedEncodingException e) {
                // does not recognize UTF-8?
                Log.w(TAG, "Error in " + UseLogContract.ENCODING + " encoding of \'" + record + "\'");
            } catch (IOException e) {
                // IO error with buffer approach
                Log.w(TAG, "IOError while recording \'" + record + "\'");
            }
        }
    }

    class LogHandler extends Handler {

        LogHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case UseLogContract.RELATION_DELETE_FORM:
                case UseLogContract.RELATION_CREATE_FORM:
                case UseLogContract.RELATION_REMOVE_REPEAT:
                case UseLogContract.RELATION_CHANGE_VALUE:
                case UseLogContract.RELATION_SELF_DESTRUCT:
                    createRecord(msg);
                    break;
                default:
                    Log.w(TAG, Thread.currentThread().getName() +
                            ": Received unknown message code (" + msg.what + ")");
            }
        }

        void createRecord(Message msg) {
            int event = msg.what;
            DataContainer data = (DataContainer) msg.obj;
            String record = getRecord(event, data);
            mBackLog.add(record);
        }

        String getRecord(int event, DataContainer data) {
            String actionCode = UseLogContract.getActionCode(event);
            String xpath = thinXpath(data.xpath);
            String escapedValue = escapeForRecord(data.value);
            String[] recordData = {data.timeStamp, actionCode, xpath, escapedValue};
            String r = TextUtils.join("\t", recordData);
            return r;
        }

        String thinXpath(String s) {
            if ( null != s && UseLogContract.THIN_XPATH ) {
                int lastSlash = s.lastIndexOf("/");
                if ( lastSlash >= 0 ) {
                    int secondLastSlash = s.lastIndexOf("/", lastSlash - 1);
                    if ( secondLastSlash >= 0 ) {
                        s = s.substring(secondLastSlash);
                    }
                }
            }
            return s;
        }

        String escapeForRecord(String s) {
            if ( null != s ) {
                s = s.replace("\t", "\\t").replace("\r\n", "\\r\\n").replace("\n", "\\n");
            }
            return s;
        }
    }
}
