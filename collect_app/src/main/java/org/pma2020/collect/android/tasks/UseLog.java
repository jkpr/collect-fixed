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

package org.pma2020.collect.android.tasks;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.text.TextUtils;
import android.util.Log;

import org.javarosa.core.model.FormIndex;
import org.javarosa.core.model.data.IAnswerData;
import org.javarosa.form.api.FormEntryController;
import org.javarosa.form.api.FormEntryPrompt;
import org.pma2020.collect.android.application.Collect;
import org.pma2020.collect.android.database.FormRelationsDb;
import org.pma2020.collect.android.exception.UseLogException;
import org.pma2020.collect.android.logic.FormController;
import org.pma2020.collect.android.logic.FormRelationsManager;
import org.pma2020.collect.android.tasks.UseLogContract.DataContainer;
import org.pma2020.collect.android.views.ODKView;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * Creator: James K. Pringle
 * E-mail: jpringle@jhu.edu
 * Created: 17 September 2015
 * Last modified: 26 May 2016
 */
public class UseLog {
    private static final String TAG = UseLog.class.getSimpleName();
    private static final boolean LOCAL_LOG = true;

    // Exception error codes
    private static final int NULL_CONTROLLER = 1;
    private static final int NULL_INSTANCE = 2;
    private static final int MV_FAILED = 3;

    private List<String> mBackLog;
    private String mInstancePath;
    private HandlerThread mThread;
    private LogHandler mHandler;
    // to differentiate between the long time use (possible temp files)
    private boolean mOneTime;


    // Create a thread to do work if not already created

    // Keep a reference to a handler.
    // Pass messages to handler at various times. Pass in a message

    // Dealing with file I/O
    // Buffered stream
    private BufferedOutputStream mBufferedStream;
    // Keep track of file name for buffered stream
    private String mFile;

    public UseLog(String instancePath, boolean oneTime) {
        // A brand new blank form would have a null instancePath at this time.
        if ( LOCAL_LOG ) {
            Log.d(TAG, "Initializing UseLog with instancePath==\"" + instancePath + "\"");
        }
        mBackLog = new LinkedList<String>();
        mInstancePath = instancePath;
        mThread = new HandlerThread(TAG);
        mThread.start();
        mHandler = new LogHandler(mThread.getLooper());
        mOneTime = oneTime;
        //openIo(true);
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

    private boolean sendMessage(Message msg) {
        boolean success = mHandler.sendMessage(msg);
        return success;
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
//                    if ( !mOneTime ) {
//                    copyOldTemp();
//                    }
                    File writeLocation = getWriteLocation();
                    openOutBuffer(writeLocation);
                } catch ( UseLogException e ) {
                    // failure. not necessarily a problem...
                    closeIo(false);
                } catch ( FileNotFoundException e ) {
                    // failure
                    if ( LOCAL_LOG ) {
                        Log.d(TAG, "File not found exception", e);
                    }
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

    private void openOutBuffer(File out) throws FileNotFoundException {
        boolean openNewFile = true;
        if ( out.exists() ) {
            openNewFile = false;
        }
        // FileOutputStream( ... , append = True) (2nd parameter)
        FileOutputStream fos = new FileOutputStream(out, true);
        mBufferedStream = new BufferedOutputStream(fos);
        if (LOCAL_LOG) {
            Log.d(TAG, "Opened stream at " + out);
        }
        mFile = out.getAbsolutePath();
        if ( openNewFile ) {
            writePreamble();
        }
    }

    public void writePreamble() {
        String preamble = "# Log " + UseLogContract.LOG_VERSION;
        writeOutLine(preamble);
    }

    public void writeBackLogAndClose() {
        Runnable r = new Runnable() {
            @Override
            public void run() {
                openIo(false);
                emptyBackLog();
                closeIo(false);
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
        openIo(true);
        switch (event) {
            case UseLogContract.ENTER_HIERARCHY:
            case UseLogContract.LEAVE_HIERARCHY:
            case UseLogContract.ENTER_PROMPT:
            case UseLogContract.LEAVE_PROMPT:
            case UseLogContract.ON_PAUSE:
            case UseLogContract.ON_RESUME:
            case UseLogContract.ADD_REPEAT:
            case UseLogContract.REMOVE_REPEAT:
            case UseLogContract.SAVE_FORM:
            case UseLogContract.ENTER_FORM:
            case UseLogContract.LEAVE_FORM:
            case UseLogContract.CONTRAVENE_CONSTRAINT:
                FormController formController = Collect.getInstance().getFormController();
                if ( null == formController ) {
                    DataContainer d = new DataContainer();
                    d.timeStamp = getTimeStamp();
                    d.xpath = UseLogContract.getActionCode(UseLogContract.UNDEFINED_CONTROLLER);
                    Message m = obtainMessage(event, d);
                    sendMessage(m);
                } else {
                    int formEvent = formController.getEvent();
                    if (formEvent == FormEntryController.EVENT_BEGINNING_OF_FORM) {
                        DataContainer d = new DataContainer();
                        d.xpath = UseLogContract.getActionCode(UseLogContract.BEGIN_FORM);
                        d.timeStamp = getTimeStamp();
                        Message m = obtainMessage(event, d);
                        sendMessage(m);
                    } else if (formEvent == FormEntryController.EVENT_END_OF_FORM) {
                        DataContainer d = new DataContainer();
                        d.xpath = UseLogContract.getActionCode(UseLogContract.FINISH_FORM);
                        d.timeStamp = getTimeStamp();
                        Message m = obtainMessage(event, d);
                        sendMessage(m);
                    } else if ( event == UseLogContract.ADD_REPEAT ) {
                        DataContainer data = new DataContainer();
                        data.timeStamp = getTimeStamp();
                        data.xpath = formController.getXPath(formController.getFormIndex());
                        Message m = obtainMessage(event, data);
                        sendMessage(m);
                    } else {
                        String timeStamp = getTimeStamp();
                        FormEntryPrompt[] prompts = formController.getQuestionPrompts();
                        for (FormEntryPrompt p : prompts) {
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
            case UseLogContract.BEGIN_FORM:
            case UseLogContract.FINISH_FORM:
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

    public void log(int event, ODKView view) {
        if ( view == null ) {
            log(event);
            return;
        }
        openIo(true);
        switch (event) {
            case UseLogContract.CONTRAVENE_CONSTRAINT:
            case UseLogContract.REMOVE_REPEAT:
                String timeStamp = getTimeStamp();
                FormController formController = Collect.getInstance().getFormController();
                Map<FormIndex, IAnswerData> answers = view.getAnswers();
                Iterator<FormIndex> it = answers.keySet().iterator();
                while (it.hasNext()) {
                    FormIndex index = it.next();
                    if (formController.getEvent(index) == FormEntryController.EVENT_QUESTION) {
                        IAnswerData answer = answers.get(index);
                        String text = answer == null ? "" : answer.getDisplayText();
                        String xpath = formController.getXPath(index);
                        DataContainer d = new DataContainer();
                        d.timeStamp = timeStamp;
                        d.xpath = xpath;
                        d.value = text;
                        Message m = obtainMessage(event, d);
                        sendMessage(m);
                    }
                }
                break;
            default:
                log(event);
                break;
        }
    }

    public void log(int event, long instanceId, String xpath, String value) {
        switch (event) {
            case UseLogContract.RELATION_CHANGE_VALUE:
            case UseLogContract.RELATION_CREATE_FORM:
                if ( null == mInstancePath ) {
                    mInstancePath = FormRelationsManager.getInstancePath(instanceId);
                }
                break;
            case UseLogContract.RELATION_SELF_DESTRUCT:
                long parentId = FormRelationsDb.getParent(instanceId);
                if ( null == mInstancePath ) {
                    mInstancePath = FormRelationsManager.getInstancePath(parentId);
                }
                break;
//            case UseLogContract.RELATION_REMOVE_REPEAT:
//            case UseLogContract.RELATION_DELETE_FORM:
            default:
                break;
        }

        DataContainer d = new DataContainer();
        d.timeStamp = getTimeStamp();
        d.xpath = xpath;
        d.value = value;
        Message m = obtainMessage(event, d);
        sendMessage(m);
    }


/*
    from my testing, copying the old temp is not necessary. I cleared odk app from app list
    while filling out form, the app appended to the log as expected. when i started a new version
    of the same form, the log started on a new file corresponding to the new version.
     */
//    private void copyOldTemp() {
//        try {
//            if (null != mInstancePath) {
//                File oldTempSave = SaveToDiskTask.savepointFile(new File(mInstancePath));
//                File oldTempLog = new File(oldTempSave.getAbsolutePath() + ".log");
//                if (oldTempLog.exists()) {
//                    File newTempLog = getTempLog(true);
//                    FileUtils.copyFile(oldTempLog, newTempLog);
//                    if (LOCAL_LOG) {
//                        Log.d(TAG, "Copied " + oldTempLog.getAbsolutePath() +  " -> " +
//                                newTempLog.getAbsolutePath());
//                    }
//                }
//                mInstancePath = null;
//            }
//        } catch (UseLogException e) {
//            Log.w(TAG, "Null controller / null instance while trying to copy old temp log");
//        }
//    }

    private File getWriteLocation() throws UseLogException {
        if ( null != mFile && null != mBufferedStream )  {
            return new File(mFile);
        }

        File tempLog = getTempLog();
        File savedLog = getSavedLog();

        File writeLocation;
        if ( mOneTime || savedLog.exists() ) {
            writeLocation = savedLog;
        } else {
            writeLocation = tempLog;
        }
        return writeLocation;
    }

    private File getTempLog() throws UseLogException {
        File instancePath;
        if ( !mOneTime ) {
            FormController formController = Collect.getInstance().getFormController();
            if (null == formController) {
                throw new UseLogException(NULL_CONTROLLER);
            }
            instancePath = formController.getInstancePath();
            if (null == instancePath) {
                throw new UseLogException(NULL_INSTANCE);
            }
        } else {
            if ( null == mInstancePath ) {
                throw new UseLogException(NULL_INSTANCE);
            }
            instancePath = new File(mInstancePath);
        }

        File tempSave = SaveToDiskTask.savepointFile(instancePath);
        File tempLog = new File(tempSave.getAbsolutePath() + ".log");
        return tempLog;
    }

    private File getSavedLog() throws UseLogException {
        File instancePath;
        if ( !mOneTime ) {
            FormController formController = Collect.getInstance().getFormController();
            if ( null == formController ) {
                throw new UseLogException(NULL_CONTROLLER);
            }
            instancePath = formController.getInstancePath();
            if ( null == instancePath ) {
                throw new UseLogException(NULL_INSTANCE);
            }
        } else {
            if ( null == mInstancePath ) {
                throw new UseLogException(NULL_INSTANCE);
            }
            instancePath = new File(mInstancePath);
        }
        String parentPath = instancePath.getAbsoluteFile().getParentFile().getAbsolutePath();
        String savedPath = parentPath + File.separator + UseLogContract.USE_LOG_NAME;
        File savedLog = new File(savedPath);
        return savedLog;
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

    private void backLogPushBack(String s) {
        mBackLog.add(s);
        if ( LOCAL_LOG ) {
            Log.d(TAG, "mBackLog.size() is " + mBackLog.size() + ". Added \"" + s + "\" to mBackLog.");
        }
    }

    private void emptyBackLog() {
        while ( !mBackLog.isEmpty() ) {
            if ( LOCAL_LOG ) {
                Log.d(TAG, "mBackLog.size() is " + mBackLog.size() + ". Removing Message from mBackLog.");
            }
            String record = mBackLog.remove(0);
            if ( LOCAL_LOG ) {
                Log.d(TAG, "this message is null: " + (null == record));
            }
            writeOutLine(record);
        }
    }

    private void writeOutLine(String record) {
        if (UseLogContract.DIVERT_TO_LOGCAT) {
            Log.v(TAG, record);
        } else {
            appendLineToLog(record);
        }
    }

    private void appendLineToLog(String record) {
        if ( null == mBufferedStream ) {
            Log.w(TAG, "##" + record);
        } else {
            try {
                byte[] byteArray = record.getBytes(UseLogContract.ENCODING);
                mBufferedStream.write(byteArray);
                mBufferedStream.write('\n');
                if ( LOCAL_LOG ) {
                    Log.d(TAG, "To file \'" + mFile + "\' wrote record \'" + record +"\'");
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
        static final String TAG = "LogHandler";

        LogHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case UseLogContract.ENTER_PROMPT:
                case UseLogContract.LEAVE_PROMPT:
                case UseLogContract.ON_PAUSE:
                case UseLogContract.ON_RESUME:
                case UseLogContract.ADD_REPEAT:
                case UseLogContract.REMOVE_REPEAT:
                case UseLogContract.SAVE_FORM:
                case UseLogContract.ENTER_FORM:
                case UseLogContract.LEAVE_FORM:
                case UseLogContract.ENTER_HIERARCHY:
                case UseLogContract.LEAVE_HIERARCHY:
                case UseLogContract.BEGIN_FORM:
                case UseLogContract.FINISH_FORM:
                case UseLogContract.CONTRAVENE_CONSTRAINT:
                    writeRecord(msg);
                    break;
                case UseLogContract.PRINT_STRING:
                    print(msg.obj);
                    break;
                case UseLogContract.RELATION_REMOVE_REPEAT:
                case UseLogContract.RELATION_CREATE_FORM:
                case UseLogContract.RELATION_DELETE_FORM:
                case UseLogContract.RELATION_SELF_DESTRUCT:
                case UseLogContract.RELATION_CHANGE_VALUE:
                    addToCache(msg);
                    break;
                default:
                    Log.w(TAG, Thread.currentThread().getName() +
                            ": Received unknown message code (" + msg.what + ")");
            }
        }

        void addToCache(Message msg) {
            int event = msg.what;
            Object obj = msg.obj;
            String record = getRecord(event, obj);
            backLogPushBack(record);
        }

        void writeRecord(Message msg) {
            int event = msg.what;
            Object obj = msg.obj;
            String record = getRecord(event, obj);
            if ( null == mBufferedStream ) {
                Log.w(TAG, record);
                backLogPushBack(record);
                if ( LOCAL_LOG ) {
                    Log.d(TAG, "Added Message to mBackLog. Current size is " + mBackLog.size());
                }
            } else {
                emptyBackLog();
                writeOutLine(record);
            }
        }

        String getRecord(int event, Object obj) {
            String actionCode = UseLogContract.getActionCode(event);
            DataContainer data = (DataContainer) obj;
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
            else if ( null != s ){ // && !UseLogContract.THIN_XPATH
                int firstSlash = s.indexOf("/");
                if ( firstSlash >= 0 && firstSlash < s.length()) {
                    int secondSlash = s.indexOf("/", firstSlash + 1);
                    if ( secondSlash >= 0 && secondSlash < s.length() ) {
                        s = s.substring(secondSlash + 1);
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

        void print(Object obj) {
            Log.i(TAG, "Thread \'" + Thread.currentThread().getName() + "\': " + obj.toString());
        }
    }
}
