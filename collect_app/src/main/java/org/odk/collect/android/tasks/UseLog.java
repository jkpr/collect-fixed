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

import org.javarosa.core.model.data.IAnswerData;
import org.javarosa.form.api.FormEntryController;
import org.javarosa.form.api.FormEntryPrompt;
import org.odk.collect.android.application.Collect;
import org.odk.collect.android.exception.UseLogException;
import org.odk.collect.android.logic.FormController;
import org.odk.collect.android.utilities.FileUtils;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;

/**
 * Creator: James K. Pringle
 * E-mail: jpringle@jhu.edu
 * Created: 17 September 2015
 * Last modified: 2 December 2015
 */
public class UseLog {
    private static final String TAG = UseLog.class.getSimpleName();
    private static final boolean LOCAL_LOG = true;
    private static final boolean DIVERT_TO_LOGCAT = false;

    private static final String ENCODING = "UTF-8";

    // Exception error codes
    private static final int NULL_CONTROLLER = 1;
    private static final int NULL_INSTANCE = 2;
    private static final int MV_FAILED = 3;

    // Events
    public static final int PRINT_STRING = 0;
    public static final int ENTER_PROMPT = 1;
    public static final int LEAVE_PROMPT = 2;
    public static final int ON_PAUSE = 3;
    public static final int ON_RESUME = 4;
    public static final int ADD_REPEAT = 5;
    public static final int REMOVE_REPEAT = 6;
    public static final int SAVE_FORM = 7;
    public static final int ENTER_FORM = 8;
    public static final int LEAVE_FORM = 9;
    public static final int ENTER_HIERARCHY = 10;
    public static final int LEAVE_HIERARCHY = 11;
    public static final int BEGIN_FORM = 12;
    public static final int FINISH_FORM = 13;

    // Weird events
    public static final int UNDEFINED_CONTROLLER = -1;
    public static final int UNKNOWN_LOADING_COMPLETE = -2;

    private String mInstancePath;
    private HandlerThread mThread;
    private LogHandler mHandler;
    // Create a thread to do work if not already created

    // Keep a reference to a handler.
    // Pass messages to handler at various times. Pass in a message

    // Dealing with mFile I/O
    private String mFile;
    private BufferedOutputStream mBufferedStream;

    public UseLog(String instancePath) {
        mInstancePath = instancePath;
        mThread = new HandlerThread(TAG);
        mThread.start();
        mHandler = new LogHandler(mThread.getLooper());
    }

    // This works. I tested with sleeping and timing in FormEntryActivity onStop / onDestroy
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

    public void p(String s) {
        Message m = obtainMessage(PRINT_STRING, s);
        sendMessage(m);
    }

    public boolean sendMessage(Message msg) {
        boolean success = mHandler.sendMessage(msg);
        return success;
    }

    public boolean isAlive() {
        if (mThread == null) {
            if (LOCAL_LOG) {
                Log.i(TAG, "Unexpectedly discovered null HandlerThread. isAlive() returns false.");
            }
            return false;
        } else {
            return mThread.isAlive();
        }
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

    // opens buffered output stream if not already open
    private void openIo(boolean doInBackground) {
        Runnable r = new Runnable() {
            @Override
            public void run() {
                if ( null != mBufferedStream ) {
                    return;
                }
                try {
                    copyOldTemp();
                    File writeLocation = getWriteLocation();
                    openOutBuffer(writeLocation);
                } catch ( UseLogException e ) {
                    // failure.
                    closeIo(false);
                } catch ( FileNotFoundException e ) {
                    // failure
                    closeIo(false);
                }
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
        mFile = out.getAbsolutePath();
        FileOutputStream fos = new FileOutputStream(mFile, true);
        mBufferedStream = new BufferedOutputStream(fos);
        if (LOCAL_LOG) {
            Log.d(TAG, "Opened stream at " + mFile);
        }
    }

    public void flush(boolean doInBackground) {
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

    // to be called when FormEntryActivity saves. UI thread has this run in background
    public void makeTempPermanent() {
        Runnable r = new Runnable() {
            @Override
            public void run() {
                try{
                    File tempLog = getTempLog();
                    File saveLog = getSavedLog();

                    if ( saveLog.exists() ) {
                        return;
                    }

                    // Finish current stream
                    closeIo(false);
                    // Copy file
                    boolean success = tempLog.renameTo(saveLog);
                    if ( !success ) {
                        throw new UseLogException(MV_FAILED);
                    }

                    // Open new stream
                    openOutBuffer(saveLog);

                } catch (UseLogException e) {
                    if (e.getErrorCode() == MV_FAILED) {
                        Log.w(TAG, "Unable to mv file from tmp to permanent location");
                    } else if (e.getErrorCode() == NULL_CONTROLLER || e.getErrorCode() == NULL_INSTANCE) {
                        if (LOCAL_LOG) {
                            Log.d(TAG, "Trying to copy log file with null controller/instance");
                        }
                    }
                } catch (FileNotFoundException e){
                    // failure
                    closeIo(false);
                }
            }
        };
        mHandler.post(r);
    }

    // runs in ui thread. collects all the information needed for log and passes to looper
    public void log(int event) {
        // Open OutputStream if possible
        openIo(false);
        switch (event) {
            case ENTER_HIERARCHY:
            case LEAVE_HIERARCHY:
            case ENTER_PROMPT:
            case LEAVE_PROMPT:
            case ON_PAUSE:
            case ON_RESUME:
            case ADD_REPEAT:
            case REMOVE_REPEAT:
            case SAVE_FORM:
            case ENTER_FORM:
            case LEAVE_FORM:
                FormController formController = Collect.getInstance().getFormController();
                if ( null == formController ) {
                    DataContainer d = new DataContainer();
                    d.timeStamp = getTimeStamp();
                    d.xpath = getActionCode(UNDEFINED_CONTROLLER);
                    Message m = obtainMessage(event, d);
                    sendMessage(m);
                } else {
                    int formEvent = formController.getEvent();
                    if (formEvent == FormEntryController.EVENT_BEGINNING_OF_FORM) {
                        DataContainer d = new DataContainer();
                        d.xpath = getActionCode(BEGIN_FORM);
                        d.timeStamp = getTimeStamp();
                        Message m = obtainMessage(event, d);
                        sendMessage(m);
                    } else if (formEvent == FormEntryController.EVENT_END_OF_FORM) {
                        DataContainer d = new DataContainer();
                        d.xpath = getActionCode(FINISH_FORM);
                        d.timeStamp = getTimeStamp();
                        Message m = obtainMessage(event, d);
                        sendMessage(m);
                    } else {
                        FormEntryPrompt[] prompts = formController.getQuestionPrompts();
                        for (FormEntryPrompt p : prompts) {
                            String timeStamp = getTimeStamp();
                            String xpath = formController.getXPath(p.getIndex());
                            IAnswerData answer = p.getAnswerValue();
                            String text = answer == null ? "" : answer.getDisplayText();
                            String savedInstancePath = null;
                            DataContainer data = new DataContainer(savedInstancePath, timeStamp,
                                    xpath, text);
                            Message m = obtainMessage(event, data);
                            sendMessage(m);
                        }
                    }
                }
                break;
            case BEGIN_FORM:
            case FINISH_FORM:
            default:
                String timeStamp = getTimeStamp();
                String savedInstancePath = null;
                String text = null;
                String xpath = null;
                DataContainer data = new DataContainer(savedInstancePath, timeStamp, xpath, text);
                Message m = obtainMessage(event, data);
                sendMessage(m);
                break;
        }
    }

    private void copyOldTemp() {
        try {
            if (null != mInstancePath) {
                File oldTempSave = SaveToDiskTask.savepointFile(new File(mInstancePath));
                File oldTempLog = new File(oldTempSave.getAbsolutePath() + ".log");
                if (oldTempLog.exists()) {
                    File newTempLog = getTempLog();
                    FileUtils.copyFile(oldTempLog, newTempLog);
                    if (LOCAL_LOG) {
                        Log.d(TAG, "Copied " + oldTempLog.getAbsolutePath() +  " -> " +
                                newTempLog.getAbsolutePath());
                    }
                }
                mInstancePath = null;
            }
        } catch (UseLogException e) {
            Log.w(TAG, "Null controller / null instance while trying to copy old temp log");
        }
    }

    private File getTempLog() throws UseLogException {
        FormController formController = Collect.getInstance().getFormController();
        if ( null == formController ) {
            throw new UseLogException(NULL_CONTROLLER);
        }
        File instancePath = formController.getInstancePath();
        if ( null == instancePath ) {
            throw new UseLogException(NULL_INSTANCE);
        }

        File tempSave = SaveToDiskTask.savepointFile(instancePath);
        File tempLog = new File(tempSave.getAbsolutePath() + ".log");
        return tempLog;
    }

    private File getSavedLog() throws UseLogException {
        FormController formController = Collect.getInstance().getFormController();
        if ( null == formController ) {
            throw new UseLogException(NULL_CONTROLLER);
        }
        File instancePath = formController.getInstancePath();
        if ( null == instancePath ) {
            throw new UseLogException(NULL_INSTANCE);
        }

        File savedLog = new File(instancePath.getAbsolutePath() + ".log");
        return savedLog;
    }

    private File getWriteLocation() throws UseLogException {
        if ( null != mFile )  {
            return new File(mFile);
        }

        File tempLog = getTempLog();
        File savedLog = getSavedLog();

        File writeLocation;
        if ( savedLog.exists() ) {
            writeLocation = savedLog;
        } else {
            writeLocation = tempLog;
        }
        return writeLocation;
    }

    private String getTimeStamp() {
        long time = System.currentTimeMillis();
        return String.valueOf(time);
    }

    private String getXpath() throws UseLogException {
        FormController fc = Collect.getInstance().getFormController();
        if ( null == fc ) {
            throw new UseLogException();
        }

        String index = fc.getXPath(fc.getFormIndex());
        return index;
    }

    private Message obtainMessage(int what, Object obj) {
        Message message = mHandler.obtainMessage(what, obj);
        return message;
    }

    class DataContainer {
        String savedInstancePath;
        String timeStamp;
        String xpath;
        String value;

        DataContainer() {}
        DataContainer(String sip, String ts, String xp, String v) {
            savedInstancePath = sip;
            timeStamp = ts;
            xpath = xp;
            value = v;
        }

        @Override
        public String toString() {
            String  toReturn = "\'" + savedInstancePath + "\', \'" + timeStamp + "\', \'" +
                    xpath + "\', \'" + value + "\'";
            return toReturn;
        }
    }

    static String getActionCode(int event) {
        switch (event) {
            case PRINT_STRING:
                return "**";
            case ENTER_PROMPT:
                return "EP";
            case LEAVE_PROMPT:
                return "LP";
            case ON_PAUSE:
                return "oP";
            case ON_RESUME:
                return "oR";
            case ADD_REPEAT:
                return "AR";
            case REMOVE_REPEAT:
                return "RR";
            case SAVE_FORM:
                return "SF";
            case ENTER_FORM:
                return "EF";
            case LEAVE_FORM:
                return "LF";
            case ENTER_HIERARCHY:
                return "EH";
            case LEAVE_HIERARCHY:
                return "LH";
            case BEGIN_FORM:
                return "BF";
            case FINISH_FORM:
                return "FF";
            case UNKNOWN_LOADING_COMPLETE:
                return "uL";
            case UNDEFINED_CONTROLLER:
                return "uC";
            default:
                return "##";
        }
    }

    class LogHandler extends Handler {
        static final String TAG = "LogHandler";

        LogHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case ENTER_PROMPT:
                case LEAVE_PROMPT:
                case ON_PAUSE:
                case ON_RESUME:
                case ADD_REPEAT:
                case REMOVE_REPEAT:
                case SAVE_FORM:
                case ENTER_FORM:
                case LEAVE_FORM:
                case ENTER_HIERARCHY:
                case LEAVE_HIERARCHY:
                case BEGIN_FORM:
                case FINISH_FORM:
                    writeRecord(msg.what, msg.obj);
                    break;
                case PRINT_STRING:
                    print(msg.obj);
                    break;
                default:
                    Log.w(TAG, Thread.currentThread().getName() +
                            ": Received unknown message code (" + msg.what + ")");
            }
        }

        void writeRecord(int event, Object obj) {
            String record = getRecord(event, obj);
            if (DIVERT_TO_LOGCAT) {
                Log.v(TAG, record);
            } else {
                insertRecord(record);
            }
        }

        void insertRecord(String record) {
            if ( null == mBufferedStream ) {
                Log.w(TAG, record);
            } else {
                try {
                    byte[] byteArray = record.getBytes(ENCODING);
                    mBufferedStream.write(byteArray);
                    mBufferedStream.write('\n');
                    if (LOCAL_LOG) {
                        Log.d(TAG, "Wrote record \'" + record + "\' to file: " + mFile);
                    }
                } catch (UnsupportedEncodingException e) {
                    // does not recognize UTF-8?
                    Log.w(TAG, "Error in " + ENCODING + " encoding of \'" + record + "\'");
                } catch (IOException e) {
                    // IO error with buffer approach
                    Log.w(TAG, "IOError while recording \'" + record + "\'");
                }
            }
        }

        String getRecord(int event, Object obj) {
            String actionCode = getActionCode(event);
            DataContainer data = (DataContainer) obj;
            String escapedValue = escapeForRecord(data.value);
            String[] recordData = {data.timeStamp, actionCode, data.xpath, escapedValue};
            String r = TextUtils.join("\t", recordData);
            return r;
        }

        String escapeForRecord(String s) {
            if ( null != s ) {
                s = s.replace("\t", "\\t").replace("\r\n", "\\r\\n").replace("\n", "\\n");
            }
            return s;
        }

        void print(Object obj) {
            Log.i(TAG, "Thread \'" + Thread.currentThread().getName() + "\': " + obj.toString());
        }
    }
}
