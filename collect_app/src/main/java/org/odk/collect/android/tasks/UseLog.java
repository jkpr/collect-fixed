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
import android.util.Log;

import org.javarosa.core.model.data.IAnswerData;
import org.javarosa.form.api.FormEntryController;
import org.javarosa.form.api.FormEntryPrompt;
import org.odk.collect.android.application.Collect;
import org.odk.collect.android.exception.UseLogException;
import org.odk.collect.android.logic.FormController;

import java.io.File;

/**
 * Creator: James K. Pringle
 * E-mail: jpringle@jhu.edu
 * Created: 17 September 2015
 * Last modified: 17 September 2015
 */
public class UseLog {
    private static final String TAG = UseLog.class.getSimpleName();
    private static final boolean LOCAL_LOG = true;

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

    // Weird cases
    public static final int UNKNOWN_LOADING_COMPLETE = -1;

    private HandlerThread mThread;
    private LogHandler mHandler;
    // Create a thread to do work if not already created

    // Keep a reference to a handler.
    // Pass messages to handler at various times. Pass in a message

    public UseLog() {
        mThread = new HandlerThread(TAG);
        mThread.start();
        mHandler = new LogHandler(mThread.getLooper());
    }

    // This works. I tested with sleeping and timing in FormEntryActivity onStop / onDestroy
    public void close() {
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

    public void log(int event) {
        switch (event) {
            case ENTER_HIERARCHY:
            case LEAVE_HIERARCHY:
            case ENTER_PROMPT:
            case LEAVE_PROMPT:
                int formEvent = Collect.getInstance().getFormController().getEvent();
                DataContainer d = new DataContainer();
                if (formEvent == FormEntryController.EVENT_BEGINNING_OF_FORM) {
                    d.xpath = getActionCode(BEGIN_FORM);
                    d.timeStamp = getTimeStamp();
                    Message m = obtainMessage(event, d);
                    sendMessage(m);
                    break;
                } else if (formEvent == FormEntryController.EVENT_END_OF_FORM) {
                    d.xpath = getActionCode(FINISH_FORM);
                    d.timeStamp = getTimeStamp();
                    Message m = obtainMessage(event, d);
                    sendMessage(m);
                    break;
                }
                /////////
                FormController formController = Collect.getInstance().getFormController();
                if ( null != formController ) {
                    FormEntryPrompt[] prompts = formController.getQuestionPrompts();
                    for (FormEntryPrompt p : prompts) {
                        String timeStamp = getTimeStamp();
                        String xpath = formController.getXPath(p.getIndex());
                        IAnswerData answer = p.getAnswerValue();
                        String text = answer == null ? "" : answer.getDisplayText();
                        String savedInstancePath = null;
                        DataContainer data = new DataContainer(savedInstancePath, timeStamp, xpath,
                                text);
                        Message m = obtainMessage(event, data);
                        sendMessage(m);
                    }
                }
                break;
            case BEGIN_FORM:
            case FINISH_FORM:
                String timeStamp = getTimeStamp();
                String savedInstancePath = null;
                String text = null;
                String xpath = null;
                DataContainer data = new DataContainer(savedInstancePath, timeStamp, xpath, text);
                Message m = obtainMessage(event, data);
                sendMessage(m);
                break;
            default:
                break;
        }
    }

    private File getTempLog() throws UseLogException {
        FormController formController = Collect.getInstance().getFormController();
        if ( null == formController ) {
            throw new UseLogException();
        }
        File instancePath = formController.getInstancePath();
        if ( null == instancePath ) {
            throw new UseLogException();
        }

        File tempSave = SaveToDiskTask.savepointFile(instancePath);
        File tempLog = new File(tempSave.getAbsolutePath() + ".log");
        return tempLog;
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
                return "OP";
            case ON_RESUME:
                return "OR";
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
            default:
                return "##";
        }
    }

    class LogHandler extends Handler {
        static final String TAG = "LogHandler";

        LogHandler(Looper looper) {
            super(looper);
        }

        void print(Object obj) {
            Log.i(TAG, "Thread \'" + Thread.currentThread().getName() + "\': " + obj.toString());
        }

        String getRecord(int event, Object obj) {
            String actionCode = getActionCode(event);
            DataContainer data = (DataContainer) obj;
            String r = actionCode + ", " + data.toString();
            return r;
        }

        void insertRecord(int event, Object obj) {
            String record = getRecord(event, obj);
            Log.d(TAG, record);
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
                    insertRecord(msg.what, msg.obj);
                    break;
                case PRINT_STRING:
                    print(msg.obj);
                    break;
                default:
                    Log.w(TAG, Thread.currentThread().getName() +
                            ": Received unknown message code (" + msg.what + ")");
            }
        }
    }

    private Message obtainMessage(int what, Object obj) {
        Message message = mHandler.obtainMessage(what, obj);
        return message;
    }

}
