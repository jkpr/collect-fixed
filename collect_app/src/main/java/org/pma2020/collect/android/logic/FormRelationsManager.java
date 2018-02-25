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

package org.pma2020.collect.android.logic;

import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import org.apache.commons.io.IOUtils;
import org.javarosa.core.model.FormDef;
import org.javarosa.core.model.instance.InstanceInitializationFactory;
import org.javarosa.core.model.instance.TreeElement;
import org.javarosa.core.services.transport.payload.ByteArrayPayload;
import org.javarosa.form.api.FormEntryController;
import org.javarosa.form.api.FormEntryModel;
import org.javarosa.xform.parse.XFormParseException;
import org.javarosa.xform.util.XFormUtils;
import org.pma2020.collect.android.application.Collect;
import org.pma2020.collect.android.database.FormRelationsDb;
import org.pma2020.collect.android.database.FormRelationsDb.MappingData;
import org.pma2020.collect.android.exception.FormRelationsException;
import org.pma2020.collect.android.preferences.AdminPreferencesActivity;
import org.pma2020.collect.android.provider.FormsProviderAPI.FormsColumns;
import org.pma2020.collect.android.provider.InstanceProviderAPI;
import org.pma2020.collect.android.provider.InstanceProviderAPI.InstanceColumns;
import org.pma2020.collect.android.tasks.FormLoaderTask;
import org.pma2020.collect.android.tasks.SaveToDiskTask;
import org.pma2020.collect.android.tasks.UseLog;
import org.pma2020.collect.android.tasks.UseLogContract;
import org.pma2020.collect.android.utilities.FileUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

/**
 * Defines important functions for working with Parent/Child forms.
 *
 * This class is the workhorse of the form relations logic. It contains
 * routines that are run when users save, delete, and upload forms. General
 * uses are the following:
 *
 * 1. When saving, initialize `FormRelationsManager` with static factory
 * method. Test if anything will be deleted. Then `manageFormRelations()`.
 * 2. When deleting, use `removeAllReferences`.
 * 3. When uploading, use `getRelatedFormsFinalized`.
 *
 * Creator: James K. Pringle
 * E-mail: jpringle@jhu.edu
 * Created: 20 August 2015
 * Last modified: 23 August 2016
 */
public class FormRelationsManager {

    private static final String TAG = "FormRelationsManager";
    private static final boolean LOCAL_LOG = true;

    // Return codes for what to delete
    public static final int NO_DELETE = -1;
    public static final int DELETE_THIS = 0;
    public static final int DELETE_CHILD = 1;

    // Return codes for what related forms are finalized
    public static final int NO_RELATIONS = -1;
    public static final int ALL_FINALIZED = 0;
    public static final int CHILD_UNFINALIZED = 1;
    public static final int PARENT_UNFINALIZED = 2;
    public static final int SIBLING_UNFINALIZED = 3;

    // Error codes for FormRelationsException
    private static final int NO_ERROR_CODE = 0;
    private static final int PROVIDER_NO_FORM = 1;
    private static final int NO_INSTANCE_NO_FORM = 2;
    private static final int NO_REPEAT_NUMBER = 3;
    private static final int PROVIDER_NO_INSTANCE = 4;
    private static final int BAD_XPATH_INSTANCE = 5;

    // Important strings to search for in XForms
    private static final String SAVE_INSTANCE = "saveInstance";
    private static final String SAVE_FORM = "saveForm";
    private static final String DELETE_FORM = "deleteForm";

    // Public error codes
    public static final int CODE_NO_SUBFORM = -1;
    public static final int CODE_NO_XPATH = -2;

    private long mInstanceId;
    private ArrayList<TraverseData> mAllTraverseData;
    private int mMaxRepeatIndex;
    private ArrayList<TraverseData> mNonRelevantSaveForm;
    private boolean mHasDeleteForm;
    private static UseLog mUseLog;

    public FormRelationsManager() {
        mInstanceId = -1;
        mAllTraverseData = new ArrayList<TraverseData>();
        mMaxRepeatIndex = 0;
        mNonRelevantSaveForm = new ArrayList<TraverseData>();
        mHasDeleteForm = false;
    }

    public FormRelationsManager(long instanceId) {
        mInstanceId = instanceId;
        mAllTraverseData = new ArrayList<TraverseData>();
        mMaxRepeatIndex = 0;
        mNonRelevantSaveForm = new ArrayList<TraverseData>();
        mHasDeleteForm = false;
    }

    /**
     * Performs all necessary routines for maintaining form relations.
     *
     * When saving, the following tasks are always done in this order:
     *
     *   1. The parent form is updated
     *   2. Children forms are created and updated
     *   3. Relevant deletions are performed
     *
     * Of course, these subroutines are no-ops if nothing needs to be done or
     * if there is no parent/child form extent.
     *
     * This method is the entry point for the `SaveToDiskTask`. By the time
     * this method is called, the instance should already be saved to disk,
     * i.e. not stored as a temporary save file.
     *
     * After this method finishes, the form relations database should be
     * up-to-date, all paired nodes between parent and child forms should be
     * synced, and all relevant deletions should have taken place.
     *
     * @param uri Uri of the current survey, be it a form or instance uri.
     * @param instanceRoot Root of the JavaRosa tree built during the survey.
     * @return Returns number of other forms updated, or error code if linking
     * error
     */
    public static int manageFormRelations(Uri uri, TreeElement instanceRoot) {
        long instanceId = getIdFromSingleUri(uri);
        int parentCode = manageParentForm(instanceId);
        FormRelationsManager frm = getFormRelationsManager(instanceId, instanceRoot);
        int childCode = frm.outputOrUpdateChildForms();
        int deleteCode = frm.manageDeletions();

        int returnCode = 0;
        if (parentCode >= 0) {
            returnCode += parentCode;
        }
        if (childCode >= 0) {
            returnCode += childCode;
        }
        if (deleteCode >= 0) {
            returnCode += deleteCode;
        }

        // Currently (May 2016), childCode is the only thing that can have an error
        if (childCode < 0) {
            returnCode = childCode;
        }
        return returnCode;
    }

    /**
     * Modifies the parent form if a paired node with child form is changed.
     *
     * When this method is called, both child and parent instance are saved to
     * disk. After opening each file, this method gets all node pairs, or
     * mappings, and loops through them. For each mapping, the instance value
     * is obtained in both files. If there is a difference, then the parent
     * is modified in memory. If there is any change in the parent, then the
     * file is rewritten to disk. If there is an exception while evaulating
     * xpaths or doing anything else, updating the parent form is aborted.
     *
     * If the parent form is changed, then its status is changed to
     * incomplete.
     *
     * @param childId Instance id
     * @return Returns -1 if no parent, 0 if has parent, but no updates, 1 if
     * has parent and updates made.
     */
    private static int manageParentForm(long childId) {
        Long parentId = FormRelationsDb.getParent(childId);
        if (LOCAL_LOG) {
            Log.d(TAG, "Inside manageParentForm. Parent instance id is \'" + parentId + "\'");
        }
        if (parentId < 0) { // No parent form to manage
            return -1;
        }

        int returnCode = 0;

        try {
            String parentInstancePath = getInstancePath(getInstanceUriFromId(parentId));
            String childInstancePath = getInstancePath(getInstanceUriFromId(childId));

            Document parentDocument = getDocument(parentInstancePath);
            Document childDocument = getDocument(childInstancePath);

            XPath xpath = XPathFactory.newInstance().newXPath();
            ArrayList<MappingData> mappings = FormRelationsDb.getMappingsToParent(childId);
            boolean editedParentForm = false;
            mUseLog = new UseLog(parentInstancePath, true);
            for (MappingData mapping : mappings) {
                XPathExpression parentExpression = xpath.compile(mapping.parentNode);
                XPathExpression childExpression = xpath.compile(mapping.childNode);

                Node parentNode = (Node) parentExpression.evaluate(parentDocument,
                        XPathConstants.NODE);
                Node childNode = (Node) childExpression.evaluate(childDocument,
                        XPathConstants.NODE);

                if (null == parentNode || null == childNode) {
                    throw new FormRelationsException(BAD_XPATH_INSTANCE, "Child: " +
                            mapping.childNode + ", Parent: " + mapping.parentNode);
                }

                if ( !childNode.getTextContent().equals(parentNode.getTextContent()) ) {
                    mUseLog.log(UseLogContract.RELATION_CHANGE_VALUE, parentId, mapping.parentNode,
                            childNode.getTextContent());

                    Log.i(TAG, "Found difference updating parent form @ parent node \'" +
                            parentNode.getNodeName() + "\'. Child: \'" +
                            childNode.getTextContent() + "\' <> Parent: \'" +
                            parentNode.getTextContent() + "\'");
                    parentNode.setTextContent(childNode.getTextContent());
                    editedParentForm = true;
                }
            }

            if (editedParentForm) {
                writeDocumentToFile(parentDocument, parentInstancePath);
                mUseLog.writeBackLogAndClose();
                ContentValues cv = new ContentValues();
                cv.put(InstanceColumns.STATUS, InstanceProviderAPI.STATUS_INCOMPLETE);
                Collect.getInstance().getContentResolver().update(getInstanceUriFromId(parentId),
                        cv, null, null);
                returnCode = 1;
            }
        } catch (FormRelationsException e) {
            if (e.getErrorCode() == PROVIDER_NO_INSTANCE) {
                Log.w(TAG, "Unable to find the instance path for either this form (id=" +
                        childId + ") or its parent (id=" + parentId + ")");
            } else if (e.getErrorCode() == BAD_XPATH_INSTANCE) {
                Log.w(TAG, "Bad XPath from one of child or parent. " + e.getInfo());
            }
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        } catch (ParserConfigurationException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } catch (SAXException e) {
            e.printStackTrace();
        } catch (XPathExpressionException e) {
            e.printStackTrace();
        } catch (TransformerException e) {
            e.printStackTrace();
        }

        return returnCode;
    }

    /**
     * Initializes a `FormRelationsManager` with form relations information.
     *
     * A FormRelationsManager is initialized, and then the JavaRosa tree for
     * the current instance is traversed. All attributes and values are
     * analyzed to determine what relates to form relations.
     *
     * The `FormRelationsManager` is then used to determine what to create,
     * update, or delete.
     *
     * @param parentId The instance id of the current form
     * @param instanceRoot Root of the JavaRosa tree built during the survey.
     * @return Returns a properly initialized FormRelationsManager, containing
     * the information needed for subsequent CRUD.
     */
    public static FormRelationsManager getFormRelationsManager(long parentId,
                                                               TreeElement instanceRoot) {
        FormRelationsManager frm = new FormRelationsManager(parentId);
        try {
            traverseInstance(instanceRoot, frm, null);
        } catch (FormRelationsException e) {
            if (DELETE_FORM.equals(e.getMessage())) {
                if (LOCAL_LOG) {
                    Log.d(TAG, "Interrupt traverse to delete instance");
                }
                frm.setDeleteForm(true);
            }
        }
        return frm;
    }

    /**
     * Initializes a `FormRelationsManager` with form relations information.
     *
     * This is called in the UI thread in order to determine if any deletions
     * have become relevant. This information is used to determine what
     * warning dialogs, if any, to display. Note that the instance may not
     * be in the InstanceProvider if the form has not yet been saved.
     *
     * This method traverses the JavaRosa tree analyzing all attributes and
     * values. The only difference with the other version of this overloaded
     * method is that the object member `mInstanceId` is not set.
     *
     * @param instanceRoot Root of the JavaRosa tree built during the survey.
     * @return Returns a properly initialized FormRelationsManager, containing
     * the information needed to determine if a delete is necessary.
     */
    public static FormRelationsManager getFormRelationsManager(TreeElement instanceRoot) {
        FormRelationsManager frm = new FormRelationsManager();
        try {
            traverseInstance(instanceRoot, frm, null);
        } catch (FormRelationsException e) {
            if (DELETE_FORM.equals(e.getMessage())) {
                if (LOCAL_LOG) {
                    Log.d(TAG, "Interrupt traverse to delete instance");
                }
                frm.setDeleteForm(true);
            }
        }
        return frm;
    }

    /**
     * Initializes a `FormRelationsManager` with form relations information.
     *
     * This is called in the UI thread from `FormEntryActivity` in order to
     * determine if any deletions have become relevant. This information is
     * used to determine what warning dialogs, if any, to display. Note that
     * the instance may not be in the InstanceProvider if the form has not yet
     * been saved. That is why the intent data that starts FormEntryActivity
     * is passed here.
     *
     * This method traverses the JavaRosa tree analyzing all attributes and
     * values.
     *
     * @param formUri The Uri passed in the intent to start FormEntryActivity.
     * @param instanceRoot Root of the JavaRosa tree built during the survey.
     * @return Returns a properly initialized FormRelationsManager, containing
     * the information needed to determine if a delete is necessary.
     */
    public static FormRelationsManager getFormRelationsManager(Uri formUri,
                                                               TreeElement instanceRoot) {
        if (LOCAL_LOG) {
            Log.d(TAG, "Inside getFormRelationsManager with uri \'" + formUri.toString() + "\'");
        }
        long instanceId = getIdFromSingleUri(formUri);
        if (LOCAL_LOG) {
            Log.d(TAG, "Determined id to be \'" + instanceId + "\'");
        }
        FormRelationsManager frm = new FormRelationsManager(instanceId);
        try {
            traverseInstance(instanceRoot, frm, null);
        } catch (FormRelationsException e) {
            if (DELETE_FORM.equals(e.getMessage())) {
                if (LOCAL_LOG) {
                    Log.d(TAG, "Interrupt traverse to delete instance");
                }
                frm.setDeleteForm(true);
            }
        }
        return frm;
    }

    /**
     * Deletes current instance or children, as necessary.
     *
     * Pre-condition: The FormRelationsManager object should have been
     * initialized by one of the getFormRelationsManager methods. Thus all
     * form relations information in the current instance is collected.
     * Furthermore, the current instance should already be saved to disk.
     *
     * Post-condition: If a relevant deleteForm is discovered, then the
     * current instance (and its children) are deleted from the
     * InstanceProvider and from the form relations database. If an irrelevant
     * saveForm is discovered and it is associated with a child, then that
     * child is deleted. In this second case, the parent form is unmodified.
     *
     * @return The number of forms deleted is returned.
     */
    private int manageDeletions() {
        int nDeletions = 0;
        int deleteWhat = getWhatToDelete();
        if ( deleteWhat == DELETE_THIS ) {
            // PMA-Logging BEGIN
            //TODO: Temporary solution; fix the null pointer exception
            if(mUseLog != null) {
                mUseLog.log(UseLogContract.RELATION_SELF_DESTRUCT, mInstanceId, null, null);
                mUseLog.writeBackLogAndClose();
            }
//            try {
//                // possible racing? writing for value differences, then writing for deletion
//                long thisParentId = FormRelationsDb.getParent(mInstanceId);
//                String thisParent = getInstancePath(getInstanceUriFromId(thisParentId));
//                String repeatable = FormRelationsDb.getRepeatable(thisParentId, mInstanceId);
//                int repeatIndex = FormRelationsDb.getRepeatIndex(thisParentId, mInstanceId);
//                FormRelationsUseLog frul = new FormRelationsUseLog(thisParent);
//                frul.log(UseLogContract.RELATION_SELF_DESTRUCT, repeatable, String.valueOf(repeatIndex));
//                frul.writeBackLog(true);
//                frul.close();
//            } catch (FormRelationsException e) {
//                Log.w(TAG, "Failed to log self-deletion", e);
//            }
            // PMA-Logging END
            nDeletions = deleteInstance(mInstanceId);
        } else if ( deleteWhat == DELETE_CHILD ) {
            TreeSet<Integer> allRepeatIndices = new TreeSet<Integer>();
            for (TraverseData td : mNonRelevantSaveForm) {
                allRepeatIndices.add(td.repeatIndex);
            }
            TreeSet<Long> allWaywardChildren = new TreeSet<Long>();
            for (Integer i : allRepeatIndices) {
                Long childInstanceId = FormRelationsDb.getChild(mInstanceId, i);
                if (LOCAL_LOG) {
                    Log.d(TAG, "ParentId(" + mInstanceId + ") + RepeatIndex(" + i +
                            ") + ChildIdFound(" + childInstanceId +")");
                }
                if (childInstanceId != -1) {
                    allWaywardChildren.add(childInstanceId);
                }
            }
            for (Long childInstanceId : allWaywardChildren) {
                // PMA-Logging BEGIN
                // probably not good to keep track here. log file already being written to in formentry
                // // Get true mInstanceId, write rD to parent log.txt
                // PMA-Logging END
                deleteInstance(childInstanceId);
            }
            nDeletions = allWaywardChildren.size();
        }
        return nDeletions;
    }

    /**
     * Deletes a child when a repeat group in the parent form is removed.
     *
     * When a repeat is removed from a parent form, then the deletion process
     * must take place here. It is different from a normal child deletion
     * because siblings are possibly affected.
     *
     * FormRelationsDb handles fixing the database to account for the
     * deletion.
     *
     * Unfortunately, this is currently called in the UI thread.
     *
     * @param parentId The instance id of the current form
     * @param repeatIndex The repeat index in the parent instance, where a
     *                    repeatIndex is greater than zero.
     */
    public static void manageRepeatDelete(long parentId, int repeatIndex) {
        if (parentId >= 0 && repeatIndex >= 0) {
            Long childInstanceId = FormRelationsDb.getChild(parentId, repeatIndex);
            Uri childInstance = getInstanceUriFromId(childInstanceId);
            Collect.getInstance().getContentResolver().delete(childInstance, null, null);
            FormRelationsDb.deleteChild(parentId, repeatIndex);
        }
    }

    /**
     * Using the data from traversal, creates/updates children forms.
     *
     * During traversal, the largest repeat index is stored. From 1 up to and
     * including the largest repeat index, the saveForm and saveInstance
     * information is collected. If this information is not empty, then the
     * Uri or the child instance is obtained (perhaps creating the child
     * first). Everything is sent to the subroutine `insertAllIntoChild` to
     * finish off transferring parent information to the child. Raised
     * exceptions abort the process.
     *
     * @return Returns number of child forms that are modified ( >= 0) or an
     * error code.
     */
    private int outputOrUpdateChildForms() {
        if (mHasDeleteForm) { // Children to be deleted. Just return.
            // Counting happens later.
            return 0;
        }

        int returnCode = 0;
        int nModifiedChildren = 0;

        for (int i = 1; i <= mMaxRepeatIndex; i++ ) {
            ArrayList<TraverseData> saveFormMapping = new ArrayList<TraverseData>();
            ArrayList<TraverseData> saveInstanceMapping = new ArrayList<TraverseData>();

            // Build up `saveFormMapping` and `saveInstanceMapping` for repeat index `i`
            for (Iterator<TraverseData> it = mAllTraverseData.iterator(); it.hasNext(); ) {
                TraverseData td = it.next();
                if (td.repeatIndex == i) {
                    if (SAVE_FORM.equals(td.attr)) {
                        saveFormMapping.add(td);
                    } else if (SAVE_INSTANCE.equals(td.attr)) {
                        saveInstanceMapping.add(td);
                    } else {
                        String m = "Trying to output or update child form. Unexpected attr=\'" +
                                td.attr + "\' @" + td.instanceXpath;
                        Log.w(TAG, m);
                    }
                }
            }

            if (saveFormMapping.isEmpty() && saveInstanceMapping.isEmpty()) {
                Log.i(TAG, "No form relations information for repeat node (" + i +
                        "). Moving on...");
                continue;
            }

            try {
                if (LOCAL_LOG) {
                    Log.d(TAG, "Calling `getOrCreateChildForm` for index (" + i + ")");
                }
                Uri childInstance = getOrCreateChildForm(saveFormMapping, saveInstanceMapping);

                // Now that we have the URI, the child instance definitely exists. Need to
                // transfer over values that are different. mUseLog already initialized for this
                // child in `getOrCreateChildForm`.
                boolean isChildModified = insertAllIntoChild(saveFormMapping, saveInstanceMapping,
                                                             childInstance);
                if (isChildModified) {
                    nModifiedChildren++;
                }
            } catch (IOException e) {
                Log.w(TAG, e.getMessage());
                e.printStackTrace();
                continue;
            } catch (FormRelationsException e) {
                String msg = "Exception raised when getting or creating child form for repeat (" +
                        i + ")";

                switch (e.getErrorCode()) {
                    case NO_ERROR_CODE:
                        break;
                    case PROVIDER_NO_FORM:
                        returnCode = CODE_NO_SUBFORM;
                        msg = "No form with id \'" + e.getInfo() + "\' in FormProvider for " +
                                "repeat (" + i +")";
                        break;
                    case BAD_XPATH_INSTANCE:
                        long instanceId = Long.parseLong(e.getInfo());
                        deleteInstance(instanceId);
                        returnCode = CODE_NO_XPATH;
                        break;
                    case NO_INSTANCE_NO_FORM:
                        msg = "No child form exists, impossible to create one, no saveForm " +
                                "information in repeat node (" + i + ")!";
                        break;
                    case NO_REPEAT_NUMBER:
                        // This should never happen
                        msg = "No information from form relations to indicate which repeat (" +
                                i + ")";
                        break;
                    case PROVIDER_NO_INSTANCE:
                        // This should never happen
                        msg = "InstanceProvider does not have record of child for repeat (" +
                                i + ")";
                }
                Log.w(TAG, msg);
                continue;
            }
        }

        return returnCode < 0 ? returnCode : nModifiedChildren;
    }

    /**
     * Gets the Uri of the child instance, creating it first if necessary.
     *
     * @param saveFormMapping All `saveForm` information gathered from
     *                        traversal, filtered for this child.
     * @param saveInstanceMapping All `saveInstance` information gathered from
     *                            traversal, filtered for this child.
     * @return Returns the Uri of an instance in the InstanceProvider.
     * @throws FormRelationsException Process aborted if child must be created
     * and no appropriate form is in the FormProvider.
     * @throws IOException Process aborted if there is an IO error.
     */
    private Uri getOrCreateChildForm(ArrayList<TraverseData> saveFormMapping,
                                     ArrayList<TraverseData> saveInstanceMapping) throws
            FormRelationsException, IOException {
        Uri childInstance;

        int repeatIndex = getRepeatIndex(saveFormMapping, saveInstanceMapping);
        long childId = FormRelationsDb.getChild(mInstanceId, repeatIndex);
        if (LOCAL_LOG) {
            Log.d(TAG, "From relations database, child id is: " + childId);
        }
        if (childId < 0) {
            // There was no child for the given parent form and associated index, so create one.
            String childFormId = getChildFormId(saveFormMapping);

            String[] columns = {
                    FormsColumns._ID
            };
            String selection = FormsColumns.JR_FORM_ID + "=?";
            String[] selectionArgs = {
                    childFormId
            };

            Cursor cursor = Collect
                    .getInstance()
                    .getContentResolver()
                    .query(FormsColumns.CONTENT_URI, columns, selection, selectionArgs, null);
            long formId = -1;
            if (cursor != null) {
                if (cursor.getCount() > 0) {
                    cursor.moveToFirst();
                    formId = cursor.getLong(cursor.getColumnIndex(FormsColumns._ID));
                }
                cursor.close();
            }

            if (formId == -1) {
                throw new FormRelationsException(PROVIDER_NO_FORM, childFormId);
            }

            Uri formUri = getFormUriFromId(formId);
            // mUseLog initialized later in `createInstance`
            childInstance = createInstance(formUri);
        } else {
            // Get old instance
            childInstance = getInstanceUriFromId(childId);
            mUseLog = new UseLog(getInstancePath(childInstance), true);
        }

        return childInstance;
    }

    /**
     * Creates an instance from a form definition.
     *
     * In order to get the proper instance, we must first load the child form,
     * then save it to disk and only *then* can we edit the .xml file with the
     * appropriate values from the parent form. Much of this is similar to
     * what happens in FormLoaderTask, but we're already in a thread here.
     *
     * Unfortunately, we must violate DRY (don't repeat yourself). Normally,
     * creating an instance from a Uri is done at the end of onCreate in
     * FormEntryActivity and in doInBackground of FormLoaderTask, not in a
     * method that can be called. This is a lot of copy and paste.
     *
     * @param formUri A Uri of a form in the FormProvider.
     * @return Returns the Uri of the newly created instance.
     * @throws FormRelationsException A catch-all for various errors, any of
     * which aborts creation of an instance.
     * @throws IOException Routine aborted if there is an IO error.
     */
    public static Uri createInstance(Uri formUri) throws FormRelationsException, IOException {
        if (LOCAL_LOG) {
            Log.d(TAG, "Inside createInstance(" + formUri.toString() + ")");
        }
        FormDef fd = null;
        FileInputStream fis = null;
        String errorMsg = null;
        String formPath = "";
        Cursor c = Collect.getInstance().getContentResolver()
                .query(formUri, null, null, null, null);
        if (c != null) {
            if (c.getCount() == 1) {
                c.moveToFirst();
                formPath = c.getString(c.getColumnIndex(FormsColumns.FORM_FILE_PATH));
            }
            c.close();
        }

        if (formPath.equals("")) {
            throw new FormRelationsException();
        }

        FormDef.EvalBehavior mode = AdminPreferencesActivity
                .getConfiguredFormProcessingLogic(Collect.getInstance());
        FormDef.setEvalBehavior(mode);

        File formXml = new File(formPath);
        String formHash = FileUtils.getMd5Hash(formXml);
        File formBin = new File(Collect.CACHE_PATH + File.separator + formHash + ".formdef");

        if (formBin.exists()) {
            // if we have binary, deserialize binary
            Log.i(
                    TAG,
                    "Attempting to load " + formXml.getName() + " from cached file: "
                            + formBin.getAbsolutePath());
            fd = FormLoaderTask.deserializeFormDef(formBin);
            if (fd == null) {
                // some error occured with deserialization. Remove the file, and make a
                // new .formdef
                // from xml
                Log.w(
                        TAG,
                        "Deserialization FAILED! Deleting cache file: " +
                                formBin.getAbsolutePath());
                formBin.delete();
            }
        }
        if (fd == null) {
            // no binary, read from xml
            try {
                Log.i(TAG, "Attempting to load from: " + formXml.getAbsolutePath());
                fis = new FileInputStream(formXml);
                fd = XFormUtils.getFormFromInputStream(fis);
                if (fd == null) {
                    errorMsg = "Error reading XForm file";
                } else {
                    FormLoaderTask.serializeFormDef(fd, formPath);
                }
            } catch (FileNotFoundException e) {
                e.printStackTrace();
                errorMsg = e.getMessage();
            } catch (XFormParseException e) {
                errorMsg = e.getMessage();
                e.printStackTrace();
            } catch (Exception e) {
                errorMsg = e.getMessage();
                e.printStackTrace();
            } finally {
                IOUtils.closeQuietly(fis);
            }
        }

        if (errorMsg != null || fd == null) {
            throw new FormRelationsException();
        }

        // set paths to /sdcard/odk/forms/formfilename-media/
        String formFileName = formXml.getName().substring(0, formXml.getName().lastIndexOf("."));
        File formMediaDir = new File(formXml.getParent(), formFileName + "-media");

        // Skip ExternalDataManaging

        // create FormEntryController from formdef
        FormEntryModel fem = new FormEntryModel(fd);
        FormEntryController fec = new FormEntryController(fem);

        FormController formController = new FormController(formMediaDir, fec, null);

        // this is for preloaders, but gets rid of non-relevant variables. (the
        // boolean is new form).
        fd.initialize(true, new InstanceInitializationFactory());

        String instancePath = "";
        if (formController.getInstancePath() == null) {
            // Create new answer folder.
            String time = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SS",
                    Locale.ENGLISH).format(Calendar.getInstance().getTime());
            String file = formPath.substring(formPath.lastIndexOf('/') + 1,
                    formPath.lastIndexOf('.'));
            String path = Collect.INSTANCES_PATH + File.separator + file + "_"
                    + time;
            if (FileUtils.createFolder(path)) {
                formController.setInstancePath(new File(path + File.separator
                        + file + "_" + time + ".xml"));
                instancePath = path + File.separator + file + "_" + time + ".xml";
            }
        }

        exportData(formController);
        Uri createdInstance = updateInstanceDatabase(formUri, instancePath);
        mUseLog = new UseLog(getInstancePath(createdInstance), true);
        mUseLog.log(UseLogContract.RELATION_CREATE_FORM, getIdFromSingleUri(createdInstance), null,
                null);
        mUseLog.writeBackLogAndClose();
        return createdInstance;
    }

    /**
     * Writes data in an instance to disk.
     *
     * @param formController The form controller for an instance
     * @return Returns true if and only if no exception is raised
     * @throws IOException An IO error aborts the routine.
     */
    private static boolean exportData(FormController formController) throws IOException {
        ByteArrayPayload payload;
        payload = formController.getFilledInFormXml();
        // write out xml
        String instancePath = formController.getInstancePath().getAbsolutePath();
        SaveToDiskTask.exportXmlFile(payload, instancePath);
        return true;
    }

    /**
     * Adds to InstanceProvider using information from the form definition.
     *
     * After an instance is created and written to disk, it must be added to
     * the InstanceProvider. That happens here. This is mostly copypasta from
     * the private method inside the SaveToDiskTask.
     *
     * @param formUri The Uri for the form template for the instance.
     * @param instancePath The path to disk where the instance has been saved.
     * @return Returns the Uri of the record that was created in the
     * InstanceProvider.
     */
    private static Uri updateInstanceDatabase(Uri formUri, String instancePath) {
        ContentValues values = new ContentValues();
        values.put(InstanceColumns.STATUS, InstanceProviderAPI.STATUS_INCOMPLETE);
        values.put(InstanceColumns.CAN_EDIT_WHEN_COMPLETE, Boolean.toString(true));

        // Entry didn't exist, so create it.
        Cursor c = null;
        try {
            // retrieve the form definition...
            c = Collect.getInstance().getContentResolver().query(formUri, null, null, null, null);
            c.moveToFirst();
            String jrformid = c.getString(c.getColumnIndex(FormsColumns.JR_FORM_ID));
            String jrversion = c.getString(c.getColumnIndex(FormsColumns.JR_VERSION));
            String formname = c.getString(c.getColumnIndex(FormsColumns.DISPLAY_NAME));
            String submissionUri = null;
            if (!c.isNull(c.getColumnIndex(FormsColumns.SUBMISSION_URI))) {
                submissionUri = c.getString(c.getColumnIndex(FormsColumns.SUBMISSION_URI));
            }

            // add missing fields into values
            values.put(InstanceColumns.INSTANCE_FILE_PATH, instancePath);
            values.put(InstanceColumns.SUBMISSION_URI, submissionUri);
            values.put(InstanceColumns.DISPLAY_NAME, formname);
            values.put(InstanceColumns.JR_FORM_ID, jrformid);
            values.put(InstanceColumns.JR_VERSION, jrversion);
        } finally {
            if (c != null) {
                c.close();
            }
        }

        Uri insertedInstance = Collect.getInstance().getContentResolver()
                .insert(InstanceColumns.CONTENT_URI, values);
        if (LOCAL_LOG) {
            Log.d(TAG, "Successfully placed instance \'" + insertedInstance.toString() +
                    "\' into InstanceProvider");
        }
        return insertedInstance;

    }

    /**
     * Deletes an instance from form relations database and InstanceProvider.
     *
     * Deleting an instance should delete all descendant sub-forms. This
     * method assumes that there are no more than two generations, i.e. that
     * there are no grandparents or grandchildren or beyond for any given
     * form.
     *
     * First, all reference to the instance is removed from the form relations
     * database. Second, the instance is removed from the InstanceProvider.
     * Third, all children are removed from the InstanceProvider. These steps
     * are no-ops if there are no children, no form relations, not in the
     * InstanceProvider, etc.
     *
     * @param instanceId The id of the instance to be deleted.
     * @return The number of instances that are deleted, excluding this one.
     */
    public static int deleteInstance(long instanceId) {
        if (LOCAL_LOG) {
            Log.d(TAG, "### deleteInstance(" + instanceId + ")");
        }
        long[] childrenIds = FormRelationsDb.getChildren(instanceId);
        // Delete from relations.db
        FormRelationsDb.deleteAsParent(instanceId);
        FormRelationsDb.deleteAsChild(instanceId);
        // Delete from instance provider
        Uri thisInstance = getInstanceUriFromId(instanceId);
        Collect.getInstance().getContentResolver().delete(thisInstance, null, null);
        for (int i = 0; i < childrenIds.length; i++) {
            Uri childInstance = getInstanceUriFromId(instanceId);
            Collect.getInstance().getContentResolver().delete(childInstance, null, null);
        }
        return childrenIds.length;
    }

    /**
     * Iterates through the traversal data for insertion into a child form.
     *
     * First, the InstanceProvider is updated to show the correct
     * instanceName. Then for each item in saveInstanceMapping,
     * insertIntoChild copies the new information if necessary. Binary data is
     * copied if necessary. If saveInstanceMapping returns true, i.e. if the
     * child form is changed, then the child form is written to disk and its
     * status is set to incomplete.
     *
     * If an error is raised inside insertIntoChild, then that morsel of
     * traversal data is skipped and the next is processed. Other errors abort
     * the routine.
     *
     * @param saveFormMapping All `saveForm` information gathered from
     *                        traversal, filtered for this child.
     * @param saveInstanceMapping All `saveInstance` information gathered from
     *                            traversal, filtered for this child.
     * @param childInstance The Uri for the child instance
     * @return Returns true if and only if a child is updated.
     * @throws FormRelationsException This exception is propagated to the
     * calling method.
     */
    private boolean insertAllIntoChild(ArrayList<TraverseData> saveFormMapping,
                                    ArrayList<TraverseData> saveInstanceMapping,
                                    Uri childInstance) throws FormRelationsException {
        boolean isInstanceModified = false;

        String childInstancePath = getInstancePath(childInstance);

        try {
            Document document = getDocument(childInstancePath);

            // here we should have a good xml document in DOM
            if (saveFormMapping.size() > 0) {
                TraverseData td = saveFormMapping.get(0);
                ContentValues values = new ContentValues();
                values.put(InstanceColumns.DISPLAY_NAME, td.instanceValue);
                Collect.getInstance().getContentResolver()
                        .update(childInstance, values, null, null);
                if (LOCAL_LOG) {
                    Log.d(TAG, "Updated InstanceProvider to show correct instanceName: " +
                            td.instanceValue);
                }
            }

            int repeatIndex = getRepeatIndex(saveFormMapping, saveInstanceMapping);
            long childId = getIdFromSingleUri(childInstance);
            for (TraverseData td : saveInstanceMapping) {
                try {
                    boolean isThisModified = insertIntoChild(td, document);
                    if (isThisModified) {
                        // PMA-Logging BEGIN
                        if ( null == mUseLog ) {
                            Log.w(TAG, "Null mUseLog when should be initialized for child(" +
                                    childId + ")");
                        } else {
                            mUseLog.log(UseLogContract.RELATION_CHANGE_VALUE, childId,
                                    td.attrValue, td.instanceValue);
                        }
                        // PMA-Logging END
                        checkCopyBinaryFile(td, childInstancePath);
                    }
                    isInstanceModified = isInstanceModified || isThisModified;
                } catch (FormRelationsException e) {
                    if (e.getErrorCode() == BAD_XPATH_INSTANCE) {
                        Log.w(TAG, "Unable to insert value \'" + td.instanceValue +
                                "\' into child at " + e.getInfo());
                        e.setInfo(String.valueOf(childId));
                        throw e;
                    }
                }

                updateRelationsDatabase(mInstanceId, td.instanceXpath, repeatIndex, childId,
                        td.attrValue, td.repeatableNode);
            }

            if (isInstanceModified) {
                // only need to update xml if something changed
                writeDocumentToFile(document, childInstancePath);

                // PMA-Logging BEGIN
                //TODO: Temporary solution; fix the null pointer exception
                if(mUseLog != null)
                    mUseLog.writeBackLogAndClose();
                // PMA-Logging END

                // Set status to incomplete
                ContentValues values = new ContentValues();
                values.put(InstanceColumns.STATUS, InstanceProviderAPI.STATUS_INCOMPLETE);
                Collect.getInstance().getContentResolver()
                        .update(childInstance, values, null, null);

                if (LOCAL_LOG) {
                    Log.d(TAG, "Rewrote child instance because of changes at " + childInstancePath);
                }
            }
        } catch (FormRelationsException e) {
            throw e;
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        } catch (XPathExpressionException e) {
            e.printStackTrace();
        } catch (SAXException e) {
            e.printStackTrace();
        } catch (TransformerException e) {
            e.printStackTrace();
        } catch (ParserConfigurationException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }

        return isInstanceModified;
    }

    /**
     * Inserts data into child form from one morsel of traversal data.
     *
     * @param td A `saveInstance` morsel of traversal data.
     * @param document The child form represented as a document.
     * @return Returns true if and only if the child instance is modified.
     * @throws XPathExpressionException Another possible error that should
     * abort this routine.
     * @throws FormRelationsException Thrown if the xpath data for the child
     * form is bad.
     */
    private static boolean insertIntoChild(TraverseData td, Document document) throws
            XPathExpressionException, FormRelationsException {
        boolean isModified = false;
        String childInstanceValue = td.instanceValue;
        if (null != childInstanceValue) {
            // extract nodes using xpath
            XPath xpath = XPathFactory.newInstance().newXPath();
            XPathExpression expression;

            String childInstanceXpath = td.attrValue;

            expression = xpath.compile(childInstanceXpath);
            Node node = (Node) expression.evaluate(document, XPathConstants.NODE);
            if ( null == node ) {
                throw new FormRelationsException(BAD_XPATH_INSTANCE, childInstanceXpath);
            }
            if ( !node.getTextContent().equals(childInstanceValue) ) {
                Log.v(TAG, "Found difference saving child form @ child node \'" +
                        node.getNodeName() + "\'. Child: \'" + node.getTextContent() +
                        "\' <> Parent: \'" + childInstanceValue + "\'");
                node.setTextContent(childInstanceValue);
                isModified = true;
            }
        }
        return isModified;
    }

    /**
     * Removes a node identified by xpath from a document (instance).
     *
     * @param xpathStr The xpath for the node to remove.
     * @param document The document to mutate.
     * @return Returns true if and only if a node is removed.
     * @throws XPathExpressionException Another possible error that should
     * abort this routine.
     * @throws FormRelationsException Thrown if the xpath data for the
     * supplied document is bad.
     */
    private static boolean removeFromDocument(String xpathStr, Document document) throws
            XPathExpressionException, FormRelationsException {
        boolean isModified = false;
        if ( null != xpathStr ) {
            // extract nodes using xpath
            XPath xpath = XPathFactory.newInstance().newXPath();
            XPathExpression expression;

            expression = xpath.compile(xpathStr);
            Node node = (Node) expression.evaluate(document, XPathConstants.NODE);
            if ( null == node ) {
                throw new FormRelationsException(BAD_XPATH_INSTANCE, xpathStr);
            }

            if (LOCAL_LOG) {
                Log.i(TAG, "removeFromDocument -- attempting to delete: " + xpathStr);
            }

            Node removeNode = node.getParentNode();
            removeNode.removeChild(node);
            isModified = true;
        }
        return isModified;
    }

    /**
     * Deletes a repeatable in parent and fixes affected siblings.
     *
     * This method is called from within `DeleteInstancesTask`. The instance
     * goes through the meat-grinder there. This method wipes up the trail of
     * blood. The repeat group in the parent form associated with this child
     * is removed. Also, the sibling information from the child is corrected
     * from in the relations database.
     *
     * If the form is instead a parent, the form relations database is
     * cleared of that parent id.
     *
     * @param instanceId The id of the instance to remove.
     */
    public static void removeAllReferences(long instanceId) {
        boolean isParentModified = false;
        long parentId = FormRelationsDb.getParent(instanceId);
        if (parentId != -1) {
            try {
                String parentPath = getInstancePath(getInstanceUriFromId(parentId));
                Document parentDocument = getDocument(parentPath);
                String repeatXpathToRemove = FormRelationsDb.getRepeatable(parentId, instanceId);
                isParentModified = removeFromDocument(repeatXpathToRemove, parentDocument);
                if (isParentModified) {
                    writeDocumentToFile(parentDocument, parentPath);
                }
            } catch (FormRelationsException e) {
                if ( e.getErrorCode() == PROVIDER_NO_INSTANCE ) {
                    Log.w(TAG, "Removing all references for " + instanceId +
                            ". Parent in relations.db, but no parent in InstanceProvider");
                } else if ( e.getErrorCode() == BAD_XPATH_INSTANCE ) {
                    Log.w(TAG, "Unable to remove node @" + e.getInfo() +
                            " from parent of instance (" + instanceId + ")");
                } else {
                    Log.w(TAG, "OTHER FORMRELATIONSEXCEPTION in removeAllReferences: " +
                            e.getErrorCode());
                }
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
            } catch (XPathExpressionException e) {
                e.printStackTrace();
            } catch (SAXException e) {
                e.printStackTrace();
            } catch (TransformerException e) {
                e.printStackTrace();
            } catch (ParserConfigurationException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                if (isParentModified) {
                    int repeatIndex = FormRelationsDb.getRepeatIndex(parentId, instanceId);
                    FormRelationsDb.deleteChild(parentId, repeatIndex);
                } else {
                    FormRelationsDb.deleteAsChild(instanceId);
                }
            }
        }
        FormRelationsDb.deleteAsParent(instanceId);
    }

    /**
     * Writes a Document object to the supplied path.
     *
     * @param document The document object
     * @param path The output path
     * @throws FileNotFoundException An exception that aborts writing to disk
     * @throws TransformerException An exception that aborts writing to disk
     */
    private static void writeDocumentToFile(Document document, String path) throws
            FileNotFoundException, TransformerException {
        // there's a bug in streamresult that replaces spaces in the
        // filename with %20
        // so we use a fileoutput stream
        // http://stackoverflow.com/questions/10301674/save-file-in-android-with-spaces-in-file-name
        File outputFile = new File(path);
        FileOutputStream fos = new FileOutputStream(outputFile);
        Result output = new StreamResult(fos);
        Source input = new DOMSource(document);
        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.transform(input, output);
    }


    /**
     * Ensures the given information exists as a record in the relations db.
     *
     * First the method checks if there already is a row with this
     * information. If there is not, then it is added to the form relations
     * database.
     *
     * @param parentId Parent instance id.
     * @param parentXpath Xpath to the paired node in the parent.
     * @param repeatIndex The repeat index, parsed out of the xpath.
     * @param childId Child instance id.
     * @param childXpath Xpath to the paired node in the child.
     * @param repeatableNode The root of the repeat group that contains child
     *                       information.
     * @return Returns true if and only if the row exists in the form
     * relations database
     */
    private static boolean updateRelationsDatabase(long parentId, String parentXpath,
                                                   int repeatIndex, long childId,
                                                   String childXpath, String repeatableNode) {
        // First check if row exists.
        boolean rowExists = FormRelationsDb.isRowExists(String.valueOf(parentId), parentXpath,
                String.valueOf(repeatIndex), String.valueOf(childId), childXpath, repeatableNode);
        if ( !rowExists ) {
            if (LOCAL_LOG) {
                Log.v(TAG, "Inserting (parentId=" + parentId + ", parentNode=" + parentXpath +
                        ", index=" + repeatIndex + ", childId=" + childId + ", childNode=" +
                        childXpath + ", repeatableNode=" + repeatableNode +
                        ") into relations database");
            }

            // Otherwise add
            FormRelationsDb.insert(String.valueOf(parentId), parentXpath,
                    String.valueOf(repeatIndex), String.valueOf(childId), childXpath,
                    repeatableNode);
            rowExists = true;
        }
        return rowExists;
    }

    /**
     * Checks the value of a node, and if binary, the file is copied to child
     *
     * This check is performed on all data that is copied from parent to
     * child.
     *
     * @param td Traversal data for the current node
     * @param childInstancePath Path to the child instance save on disk
     * @return Returns true if and only if `copyBinaryFile` returns true.
     * @throws FormRelationsException An exception allowed to propagate from
     * subroutines in order to abort checking/copying.
     */
    private boolean checkCopyBinaryFile(TraverseData td, String childInstancePath) throws
            FormRelationsException {
        boolean toReturn = false;
        String childInstanceValue = td.instanceValue;
        if (childInstanceValue.endsWith(".jpg") || childInstanceValue.endsWith(".jpeg") ||
                childInstanceValue.endsWith(".3gpp") || childInstanceValue.endsWith(".3gp") ||
                childInstanceValue.endsWith(".mp4") || childInstanceValue.endsWith(".png")) {
            // check for more extensions?

            Uri parentInstance = getInstanceUriFromId(mInstanceId);
            String parentInstancePath = getInstancePath(parentInstance);
            toReturn = copyBinaryFile(parentInstancePath, childInstancePath, childInstanceValue);
        }
        return toReturn;
    }

    /**
     * Copies a binary file from one instance to another.
     *
     * This method accepts paths to instances. The enclosing directories are
     * determined, from which appropriate source and destination paths are
     * generated for the file to be copied.
     *
     * @param parentInstancePath The path to the instance of the parent.
     * @param childInstancePath The path to the instance of the child.
     * @param filename The file name of the file to be copied.
     * @return Returns true if everything happens without a hitch.
     */
    private static boolean copyBinaryFile(String parentInstancePath, String childInstancePath,
                                          String filename) {
        File parentFile = new File(parentInstancePath);
        File childFile = new File(childInstancePath);

        File parentImage = new File(parentFile.getParent() + "/" + filename);
        File childImage = new File(childFile.getParent() + "/" + filename);

        if (LOCAL_LOG) {
            Log.d(TAG, "copyBinaryFile \'" + filename + "\' from " +
                    parentFile.getParent() + " to " + childFile.getParent());
        }

        FileUtils.copyFile(parentImage, childImage);
        return true;
    }

    /**
     * Gets the id in the InstanceProvider for a given instance.
     *
     * @param instance The Uri for
     * @return Returns the id in the InstanceProvider for a given instance.
     * Returns -1 if no corresponding id is found.
     */
    private static long getIdFromSingleUri(Uri instance) {
        long id = -1;
        if (Collect.getInstance().getContentResolver().getType(instance)
                .equals(InstanceColumns.CONTENT_ITEM_TYPE)) {
            String idStr = instance.getLastPathSegment();
            id = Long.parseLong(idStr);
        } else if (Collect.getInstance().getContentResolver().getType(instance)
                .equals(FormsColumns.CONTENT_ITEM_TYPE)) { // if uri is for a form
            // first try to find by looking up absolute path
            FormController formController = Collect.getInstance().getFormController();
            String[] projection = {
                    InstanceColumns._ID
            };
            String selection = InstanceColumns.INSTANCE_FILE_PATH + "=?";
            String instancePath = formController.getInstancePath().getAbsolutePath();
            String[] selectionArgs = {
                    instancePath
            };
            Cursor c = Collect.getInstance().getContentResolver()
                    .query(InstanceColumns.CONTENT_URI, projection, selection, selectionArgs, null);
            if ( c != null ) {
                if ( c.getCount() > 0 ) {
                    c.moveToFirst();
                    id = c.getLong(c.getColumnIndex(InstanceColumns._ID));
                }
                c.close();
            }
        }
        return id;
    }

    /**
     * Converts an id number to Uri for an instance.
     *
     * @param id Id number
     * @return Returns the corresponding InstanceProvider Uri.
     */
    private static Uri getInstanceUriFromId(long id) {
        return Uri.withAppendedPath(InstanceColumns.CONTENT_URI, String.valueOf(id));
    }

    /**
     * Converts an id number to Uri for a form.
     *
     * @param id Id number
     * @return Returns the corresponding FormProvider Uri.
     */
    private static Uri getFormUriFromId(long id) {
        return Uri.withAppendedPath(FormsColumns.CONTENT_URI, String.valueOf(id));
    }

    /**
     * Gets repeat index based off of traversal data
     *
     * Both saveFormMapping and saveInstanceMapping should have been filtered
     * so that they only have the same repeat index at this point.
     *
     * @param saveFormMapping All `saveForm` information gathered from
     *                        traversal, filtered for this child.
     * @param saveInstanceMapping All `saveInstance` information gathered from
     *                            traversal, filtered for this child.
     * @return Returns the repeat index that is found.
     * @throws FormRelationsException If no repeat index is found, then an
     * exception is thrown.
     */
    private int getRepeatIndex(ArrayList<TraverseData> saveFormMapping, ArrayList<TraverseData>
            saveInstanceMapping) throws FormRelationsException {
        int repeatIndex = 0;
        if ( !saveFormMapping.isEmpty() ) {
            repeatIndex = saveFormMapping.get(0).repeatIndex;
        } else if ( !saveInstanceMapping.isEmpty() ) {
            repeatIndex = saveInstanceMapping.get(0).repeatIndex;
        }

        if (repeatIndex == 0) {
            throw new FormRelationsException(NO_REPEAT_NUMBER);
        }

        return repeatIndex;
    }

    /**
     * Gets the form id defined in a `saveForm`.
     *
     * @param saveFormMapping All `saveForm` information gathered from
     *                        traversal, filtered for this child.
     * @return Returns the found form id.
     * @throws FormRelationsException Raised only if `saveFormMapping` is
     * empty.
     */
    private String getChildFormId(ArrayList<TraverseData> saveFormMapping) throws
            FormRelationsException{
        if (saveFormMapping.isEmpty()) {
            throw new FormRelationsException(NO_INSTANCE_NO_FORM);
        }
        String childFormId = saveFormMapping.get(0).attrValue;
        return childFormId;
    }

    /**
     * Gets the instance path from a Uri for one instance
     *
     * @param oneInstance A Uri for a single instance (has primary key _ID)
     * @return Returns the path found in the InstanceProvider.
     * @throws FormRelationsException If the InstanceProvider does not have
     * the required information, this exception is thrown.
     */
    private static String getInstancePath(Uri oneInstance) throws FormRelationsException {
        String[] projection = {
                InstanceColumns.INSTANCE_FILE_PATH
        };
        Cursor childCursor = Collect.getInstance().getContentResolver()
                .query(oneInstance, projection, null, null, null);
        if (null == childCursor || childCursor.getCount() < 1) {
            throw new FormRelationsException(PROVIDER_NO_INSTANCE);
        }
        // If URI is for table, not for row, potential error (only getting the first row).
        childCursor.moveToFirst();
        String instancePath = childCursor.getString(childCursor.getColumnIndex(
                InstanceColumns.INSTANCE_FILE_PATH));
        childCursor.close();
        return instancePath;
    }

    /**
     * Gets the instance path from the id number of an instance in the instance provider
     *
     * A public static method for other classes to access this functionality.
     *
     * @param id Id number of an instance.
     * @return Returns the path found in the InstanceProvider. If no path is
     * found, then null is returned
     */
    public static String getInstancePath(long id) {
        String instancePath = null;
        try {
            Uri instanceUri = getInstanceUriFromId(id);
            instancePath = getInstancePath(instanceUri);
        } catch (FormRelationsException e) {
            Log.w(TAG, "No instance path found for instanceId(" + id + ")");
        }
        return instancePath;
    }

    /**
     * A container for saving information discovered during traversal.
     */
    private class TraverseData {
        String attr;
        String attrValue;
        String instanceXpath;
        String instanceValue;
        int repeatIndex;
        String repeatableNode;
    }

    // Cleans the input somewhat

    /**
     * Accepts raw information from traversal, cleans it, and stores it.
     *
     * The data is placed into a `TraverseData` object. Before storage, some
     * information is cleaned or parsed. The xpath is stripped of the initial
     * instance name information so that it starts with '/'. The repeat index
     * is parsed from the xpath.
     *
     * Depending on `isRelevant` the information goes into either the object
     * member `mAllTraverseData` or the object member `mNonRelevantSaveForm`.
     *
     * @param attr The tag attribute
     * @param attrValue The value associated with the attribute
     * @param instanceXpath The xpath to the tag (node)
     * @param instanceValue The value (text) stored inside the node
     * @param repeatableNode The nearest ancestor repeatable root
     * @param isRelevant Boolean, true if the node is relevant.
     * @throws FormRelationsException This exception is thrown if a
     * `deleteForm` is discovered. No more information is needed because this
     * form is going to be deleted.
     */
    private void addTraverseData(String attr, String attrValue, String instanceXpath,
                                 String instanceValue, String repeatableNode,
                                 boolean isRelevant) throws FormRelationsException {
        TraverseData td = new TraverseData();
        td.attr = attr;
        td.attrValue = attrValue;
        td.instanceXpath = cleanInstanceXpath(instanceXpath);
        td.instanceValue = instanceValue;
        td.repeatIndex = parseInstanceXpath(td.instanceXpath);
        td.repeatableNode = cleanInstanceXpath(repeatableNode);
        if ( isRelevant ) {
            if ( DELETE_FORM.equals(attr) ) {
                throw new FormRelationsException(DELETE_FORM);
            }
            mAllTraverseData.add(td);
        } else if ( SAVE_FORM.equals(td.attr) ) {
            mNonRelevantSaveForm.add(td);
        }

    }

    /**
     * Cleans an instance xpath when adding traverse data.
     *
     * @param instanceXpath The xpath of a given node.
     * @return Returns the instance path starting with the first '/'.
     */
    private String cleanInstanceXpath(String instanceXpath) {
        String toReturn = null;
        if (instanceXpath != null) {
            int firstSlash = instanceXpath.indexOf("/");
            if (firstSlash < 0) {
                toReturn = instanceXpath;
            } else {
                toReturn = instanceXpath.substring(firstSlash);
            }
        }
        return toReturn;
    }

    /**
     * Gets the largest child selector number in an xpath.
     *
     * From examination, xpaths have child selectors at each step of xpath
     * after root, i.e. /root/path[1]/to[1]/node[1]... etc. If any of the
     * child selectors are greater than one, then it must be a repeat group.
     * Usually children are created with the information from a repeat group.
     *
     * This method picks out the greatest child selector in the supplied
     * xpath.
     *
     * If there is more than one non-"1" child selector, then a warning is
     * logged. Perhaps an exception should be thrown?
     *
     * This method taught me how hard it is to debug Java code using an
     * Android device.
     *
     * @param instanceXpath Xpath to the node under examination
     * @return Returns a number greater than zero.
     */
    private int parseInstanceXpath(String instanceXpath) {
        int repeatIndex = 1;
        int numNonOne = 0;

        int leftBracket = instanceXpath.indexOf("[");
        int safety = 100;
        while (leftBracket >= 0 && safety > 0) {
            int rightBracket = instanceXpath.indexOf("]", leftBracket);
            if (rightBracket < 0) {
                break;
            }
            try {
                String repeat = instanceXpath.substring(leftBracket+1, rightBracket);
                int potentialRepeat = Integer.parseInt(repeat);
                if (potentialRepeat > 1) {
                    repeatIndex = potentialRepeat;
                    numNonOne += 1;
                }
                mMaxRepeatIndex = Math.max(mMaxRepeatIndex, repeatIndex);
            } catch (NumberFormatException e) {
                Log.w(TAG, "Error parsing repeat index to int: \'" + instanceXpath + "\'");
            }
            leftBracket = instanceXpath.indexOf("[", rightBracket);
            safety--;
        }

        if (numNonOne > 1) {
            Log.w(TAG, "Multiple repeats detected in this XPath: \'" + instanceXpath + "\'");
        }

        return repeatIndex;
    }

    /**
     * Traverses an instance and collects all form relations information.
     *
     * This is a recursive method that traverses a tree in a depth-first
     * search. It keeps a record of the nearest repeatable node as it goes. It
     * scans all attributes for all nodes in this search.
     *
     * @param te The current tree element
     * @param frm The `FormRelationsManager` object that stores traverse data.
     * @param repeatableNode The most recent repeatable node. It is null if
     *                       there is no repeatable ancestor node.
     * @throws FormRelationsException Thrown if a `deleteForm` is found to be
     * relevant, propagated from checkAttrs.
     */
    private static void traverseInstance(TreeElement te, FormRelationsManager frm,
                                         String repeatableNode) throws FormRelationsException {
        for (int i = 0; i < te.getNumChildren(); i++) {
            TreeElement teChild = te.getChildAt(i);

            String ref = teChild.getRef().toString(true);
            if (ref.contains("@template")) {
                // skip template nodes (from Nafundi)
                continue;
            }


            if (teChild.isRepeatable()) {
                if (LOCAL_LOG) {
                    Log.d(TAG, "In repeatable node @" + ref + " with index [" +
                            teChild.getMult() + "]");
                }
                repeatableNode = ref;
            }

            checkAttrs(teChild, frm, repeatableNode);

            // recurse
            if (teChild.getNumChildren() > 0) {
                traverseInstance(teChild, frm, repeatableNode);
            }
        }
    }

    /**
     * Checks attributes of a node for form relation material.
     *
     * @param te The current tree element
     * @param frm The `FormRelationsManager` object that stores traverse data.
     * @param repeatableNode The most recent repeatable node.
     * @throws FormRelationsException Thrown if a `deleteForm` is found to be
     * relevant, propagated from addTraverseData.
     */
    private static void checkAttrs(TreeElement te, FormRelationsManager frm,
                                   String repeatableNode) throws FormRelationsException {
        List<TreeElement> attrs = te.getBindAttributes();
        for (TreeElement attr : attrs) {
            boolean isFormRelationMaterial = SAVE_INSTANCE.equals(attr.getName()) ||
                    SAVE_FORM.equals(attr.getName()) || DELETE_FORM.equals(attr.getName());
            if (isFormRelationMaterial) {
                String thisAttr = attr.getName();
                String attrValue = attr.getAttributeValue();
                String instanceXpath = te.getRef().toString(true);
                String instanceValue = null;
                if (te.getValue() != null) {
                    instanceValue = te.getValue().getDisplayText();
                }
                frm.addTraverseData(thisAttr, attrValue, instanceXpath, instanceValue,
                        repeatableNode, te.isRelevant());
            }
        }
    }

    /**
     * Gets a Document object for a given instance path.
     *
     * @param path The path to the instance
     * @return Returns a Document object for the file at the supplied path.
     * @throws ParserConfigurationException One of various exceptions that
     * abort the routine.
     * @throws SAXException One of various exceptions that abort the routine.
     * @throws IOException One of various exceptions that abort the routine.
     */
    private static Document getDocument(String path) throws ParserConfigurationException,
            SAXException, IOException {
        File outputFile = new File(path);
        InputStream inputStream = new FileInputStream(outputFile);
        Reader reader = new InputStreamReader(inputStream, "UTF-8");
        InputSource inputSource = new InputSource(reader);
        Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(inputSource);
        inputStream.close();
        reader.close();
        return document;
    }

    // Assumes one generation span max.

    /**
     * Get the set of instanceIds of all related forms
     *
     * @param instanceId The instance id of the form's family to check
     * @return Returns a set with the instance ids of all family members
     */
    public static Set<Long> getRelatedForms(long instanceId) {
        Set<Long> relatedForms = new HashSet<Long>();
        long[] children = FormRelationsDb.getChildren(instanceId);
        for (int i = 0; i < children.length; i++) {
            relatedForms.add(children[i]);
        }
        long parent = FormRelationsDb.getParent(instanceId);
        if (parent != -1) {
            relatedForms.add(parent);
            long[] siblings = FormRelationsDb.getChildren(parent);
            for (int i = 0; i < siblings.length; i++) {
                relatedForms.add(siblings[i]);
            }

        }
        return relatedForms;
    }

    /**
     * Checks parents, children, and siblings for finalized-ness.
     *
     * Checks if there are relations. Checks if parent is finalized. Checks if
     * siblings are finalized. Checks if children are finalized.
     *
     * Does not check if self is finalized.
     *
     * @param instanceId The instance id of the form's family to check
     * @return Returns an integer code for one of five outcomes.
     */
    public static int getRelatedFormsFinalized(long instanceId) {
        int toReturn = NO_RELATIONS;

        long parent = FormRelationsDb.getParent(instanceId);
        long[] children = FormRelationsDb.getChildren(instanceId);

        if ( parent != -1 ) {
            toReturn = PARENT_UNFINALIZED;

            try {
                boolean isParentFinalized = isInstanceFinalized(parent);
                if ( isParentFinalized ) {
                    toReturn = ALL_FINALIZED;
                }
            } catch (FormRelationsException e) {
                Log.w(TAG, "Error searching for parent (" + parent +
                        ") when trying to determine finalized-ness");
            }

            long[] parentChildren = FormRelationsDb.getChildren(parent);
            if ( toReturn != PARENT_UNFINALIZED ) {
                try {
                    for (int i = 0; i < parentChildren.length; i++) {
                        boolean isParentChildFinalized = isInstanceFinalized(parentChildren[i]);
                        if ( !isParentChildFinalized ) {
                            toReturn = SIBLING_UNFINALIZED;
                            break;
                        }
                    }
                } catch (FormRelationsException e) {
                    toReturn = SIBLING_UNFINALIZED;
                    Log.w(TAG, "Error searching for child (" + e.getInfo() +
                            ") when trying to determine finalized-ness");
                }
            }
        }

        if ( toReturn != PARENT_UNFINALIZED && toReturn != SIBLING_UNFINALIZED ) {
            if ( children.length > 0 ) {
                toReturn = ALL_FINALIZED;
            }
            try {
                for (int i = 0; i < children.length; i++) {
                    boolean isChildFinalized = isInstanceFinalized(children[i]);
                    if ( !isChildFinalized ) {
                        toReturn = CHILD_UNFINALIZED;
                        break;
                    }
                }
            } catch (FormRelationsException e) {
                toReturn = CHILD_UNFINALIZED;
                Log.w(TAG, "Error searching for child (" + e.getInfo() +
                        ") when trying to determine finalized-ness");
            }
        }

        return toReturn;
    }

    /**
     * Checks if an instance is finalized
     *
     * Being finalized is defined as not having STATUS_INCOMPLETE. That means
     * being sent / having problems being sent / being finalized counts.
     *
     * It is assumed that at the family of forms spans at most two
     * generations, i.e. no grandparents or grandchildren or beyond.
     *
     * @param instanceId The instance id
     * @return Returns true if and only if the instance is proven to be finalized.
     * @throws FormRelationsException This exception is raised if
     */
    public static boolean isInstanceFinalized(long instanceId) throws FormRelationsException {
        boolean isFinalized = false;

        Uri instance = getInstanceUriFromId(instanceId);

        String[] projection = {
                InstanceColumns.STATUS
        };

        Cursor cursor = Collect.getInstance().getContentResolver().query(instance, projection,
                null, null, null);
        if (cursor != null) {
            if (cursor.getCount() > 0) {
                cursor.moveToFirst();
                String thisStatus = cursor.getString(cursor.getColumnIndex(InstanceColumns.STATUS));
                if ( !thisStatus.equals(InstanceProviderAPI.STATUS_INCOMPLETE) ) {
                    isFinalized = true;
                }
            } else {
                cursor.close();
                throw new FormRelationsException(PROVIDER_NO_INSTANCE, String.valueOf(instanceId));
            }
            cursor.close();
        } else {
            throw new FormRelationsException(PROVIDER_NO_INSTANCE, String.valueOf(instanceId));
        }

        return isFinalized;
    }

    /**
     * Gets how many forms to delete
     *
     * Pre-condition: a `FormRelationsManager` object has been initialized and
     * traverse data has been collected for a form.
     *
     * @return An integer representing how many forms to delete.
     */
    public int getHowManyToDelete() {
        int howMany = 0;
        if (mHasDeleteForm) {
            howMany++;
            howMany += FormRelationsDb.getChildren(mInstanceId).length;
        } else {
            TreeSet<Integer> allRepeatIndices = new TreeSet<Integer>();
            for (TraverseData td : mNonRelevantSaveForm) {
                allRepeatIndices.add(td.repeatIndex);
            }
            for (Integer i : allRepeatIndices) {
                if (FormRelationsDb.getChild(mInstanceId, i) != -1) {
                    howMany++;
                }
            }
        }
        return howMany;
    }

    /**
     * Gets how many forms to delete
     *
     * This version of the method is called when removing a repeat.
     *
     * Pre-condition: a `FormRelationsManager` object has been initialized and
     * traverse data has been collected for a form.
     *
     * @param repeatIndex The index of the repeat to be removed
     * @return An integer representing how many forms to delete.
     */
    public int getHowManyToDelete(int repeatIndex) {
        int howMany = 0;
        if (mHasDeleteForm) {
            howMany++;
            howMany += FormRelationsDb.getChildren(mInstanceId).length;
        } else {
            TreeSet<Integer> allRepeatIndices = new TreeSet<Integer>();
            allRepeatIndices.add(repeatIndex);
            for (TraverseData td : mNonRelevantSaveForm) {
                allRepeatIndices.add(td.repeatIndex);
            }
            for (Integer i : allRepeatIndices) {
                if (FormRelationsDb.getChild(mInstanceId, i) != -1) {
                    howMany++;
                }
            }
        }
        return howMany;
    }

    /**
     * Gets information to say what is scheduled for deletion
     *
     * Gets all repeat indices from non-relevant saveForm attributes and
     * checks if there are children associated with those indices.
     *
     * @return Returns one of three codes to say no deletions, delete this
     * form, or delete at least one child
     */
    public int getWhatToDelete() {
        int returnCode = NO_DELETE;
        if (mHasDeleteForm) {
            returnCode = DELETE_THIS;
        } else if ( mInstanceId != -1 ) {
            TreeSet<Integer> allRepeatIndices = new TreeSet<Integer>();
            for (TraverseData td : mNonRelevantSaveForm) {
                allRepeatIndices.add(td.repeatIndex);
            }
            for (Integer i : allRepeatIndices) {
                if (FormRelationsDb.getChild(mInstanceId, i) != -1) {
                    returnCode = DELETE_CHILD;
                    break;
                }
            }
        }
        return returnCode;
    }

    /**
     * Gets information to say what is scheduled for deletion
     *
     * Gets all repeat indices from non-relevant saveForm attributes and
     * checks if there are children associated with those indices. This
     * version of the method is called when removing a repeat, so that index
     * is checked as well.
     *
     * @param repeatIndex The index of the repeat to be removed
     * @return Returns one of three codes to say no deletions, delete this
     * form, or delete at least one child
     */
    public int getWhatToDelete(int repeatIndex) {
        int returnCode = NO_DELETE;
        if (mHasDeleteForm) {
            returnCode = DELETE_THIS;
        } else if ( mInstanceId != -1 ) {
            TreeSet<Integer> allRepeatIndices = new TreeSet<Integer>();
            allRepeatIndices.add(repeatIndex);
            for (TraverseData td : mNonRelevantSaveForm) {
                allRepeatIndices.add(td.repeatIndex);
            }
            for (Integer i : allRepeatIndices) {
                if (FormRelationsDb.getChild(mInstanceId, i) != -1) {
                    returnCode = DELETE_CHILD;
                    break;
                }
            }
        }
        return returnCode;
    }

    public void setDeleteForm(boolean val) {
        mHasDeleteForm = val;
    }

    public void setInstanceId(long id) {
        mInstanceId = id;
    }

    public long getInstanceId() {
        return mInstanceId;
    }

    public boolean getDeleteForm() {
        return mHasDeleteForm;
    }
}
