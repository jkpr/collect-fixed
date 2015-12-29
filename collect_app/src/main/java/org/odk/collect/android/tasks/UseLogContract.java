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

/**
 * Creator: James K. Pringle
 * E-mail: jpringle@jhu.edu
 * Created: 9 December 2015
 * Last modified: 9 December 2015
 */
public class UseLogContract {
    protected static final boolean DIVERT_TO_LOGCAT = false;
    protected static final boolean THIN_XPATH = true;

    protected static final String USE_LOG_NAME = "log.txt";
    protected static final String LOG_VERSION = "v0.1";
    protected static final String ENCODING = "UTF-8";

    // Initialization events
    public static final int UNDEFINED_CONTROLLER = -1;
    public static final int UNKNOWN_LOADING_COMPLETE = -2;

    // UseLog Events
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

    // FormRelationsUseLog Events
    public static final int RELATION_CHANGE_VALUE = 20;
    public static final int RELATION_REMOVE_REPEAT = 21;
    public static final int RELATION_CREATE_FORM = 22;
    public static final int RELATION_DELETE_FORM = 23;
    public static final int RELATION_SELF_DESTRUCT = 24;

    static String getActionCode(int event) {
        switch (event) {
            case UNKNOWN_LOADING_COMPLETE:
                return "uL";
            case UNDEFINED_CONTROLLER:
                return "uC";
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
            case RELATION_CHANGE_VALUE:
                return "rV";
            case RELATION_REMOVE_REPEAT:
                return "rR";
            case RELATION_CREATE_FORM:
                return "rC";
            case RELATION_DELETE_FORM:
                return "rD";
            case RELATION_SELF_DESTRUCT:
                return "rS";
            default:
                return "##";
        }
    }

    protected static class DataContainer {
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
}
