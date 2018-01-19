/*
 * Copyright (C) 2011 University of Washington
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package org.pma2020.collect.android.widgets;

import java.util.ArrayList;
import java.util.List;

import android.content.res.Resources;
import android.support.v4.content.ContextCompat;
import android.text.method.LinkMovementMethod;
import org.javarosa.core.model.data.IAnswerData;
import org.javarosa.form.api.FormEntryPrompt;
import org.pma2020.collect.android.application.Collect;
import org.pma2020.collect.android.listeners.AudioPlayListener;
import org.pma2020.collect.android.views.MediaLayout;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnCompletionListener;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.os.Bundle;
import android.support.annotation.IdRes;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TableLayout;
import android.widget.TextView;
import org.javarosa.core.model.data.IAnswerData;
import org.javarosa.form.api.FormEntryPrompt;
import org.pma2020.collect.android.R;
import org.pma2020.collect.android.activities.FormEntryActivity;
import org.pma2020.collect.android.application.Collect;
import org.pma2020.collect.android.database.ActivityLogger;
import org.pma2020.collect.android.exception.JavaRosaException;
import org.pma2020.collect.android.listeners.AudioPlayListener;
import org.pma2020.collect.android.logic.FormController;
import org.pma2020.collect.android.utilities.TextUtils;
import org.pma2020.collect.android.utilities.ViewIds;
import org.pma2020.collect.android.views.MediaLayout;
import org.pma2020.collect.android.widgets.interfaces.BaseImageWidget;
import org.pma2020.collect.android.widgets.interfaces.ButtonWidget;
import org.pma2020.collect.android.widgets.interfaces.Widget;

import java.util.ArrayList;
import java.util.List;

public abstract class QuestionWidget
        extends RelativeLayout
        implements Widget, AudioPlayListener {


    @SuppressWarnings("unused")
    private final static String t = "QuestionWidget";
    private static int idGenerator = 1211322;

    /**
     * Generate a unique ID to keep Android UI happy when the screen orientation
     * changes.
     * 
     * @return
     */
    public static int newUniqueId() {
        return ++idGenerator;
    }

    protected FormEntryPrompt mPrompt;

    protected final int mQuestionFontsize;
    protected final int mAnswerFontsize;

    private MediaLayout mQuestionMediaLayout;
    private TextView mHelpTextView;

    protected MediaPlayer mPlayer;

    protected int mPlayColor = Color.BLUE;
    protected int mPlayBackgroundColor = Color.WHITE;

    public QuestionWidget(Context context, FormEntryPrompt p) {
        super(context);

        mPlayer = new MediaPlayer();
        mPlayer.setOnCompletionListener(new OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mediaPlayer) {
                mQuestionMediaLayout.resetTextFormatting();
                mediaPlayer.reset();
            }

        });
        mQuestionFontsize = Collect.getQuestionFontsize();
        mAnswerFontsize = mQuestionFontsize + 2;

        mPrompt = p;

        setGravity(Gravity.TOP);
        setPadding(0, 7, 0, 0);

        mQuestionMediaLayout = createQuestionMediaLayout(p);
        mHelpTextView = createHelpText(p);

        addQuestionMediaLayout(mQuestionMediaLayout);
        addHelpTextView(mHelpTextView);
    }

    private MediaLayout createQuestionMediaLayout(FormEntryPrompt p) {
        String imageURI = p.getImageText();
        String audioURI = p.getAudioText();
        String videoURI = p.getSpecialFormQuestionText("video");

        // shown when image is clicked
        String bigImageURI = p.getSpecialFormQuestionText("big-image");

        String promptText = p.getLongText();
        // Add the text view. Textview always exists, regardless of whether there's text.
        TextView questionText = new TextView(getContext());
        questionText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, mQuestionFontsize);
        questionText.setTypeface(null, Typeface.BOLD);
        questionText.setPadding(0, 0, 0, 7);
        // PMA BEGIN
        questionText.setText(promptText == null ? "" : promptText);
        // PMA END (uncomment below to get back original)
        // questionText.setText(promptText == null ? "" : TextUtils.textToHtml(promptText));

        // Wrap to the size of the parent view
        questionText.setHorizontallyScrolling(false);

        if (promptText == null || promptText.length() == 0) {
            questionText.setVisibility(GONE);
        }

        // Create the layout for audio, image, text
        MediaLayout questionMediaLayout = new MediaLayout(getContext(), mPlayer);
        questionMediaLayout.setId(QuestionWidget.newUniqueId()); // assign random id
        questionMediaLayout.setAVT(p.getIndex(), "", questionText, audioURI, imageURI, videoURI, bigImageURI);
        questionMediaLayout.setAudioListener(this);

        String playColorString = p.getFormElement().getAdditionalAttribute(null, "playColor");
        if (playColorString != null) {
            try {
                mPlayColor = Color.parseColor(playColorString);
            } catch (IllegalArgumentException e) {
                e.printStackTrace();
            }
        }
        questionMediaLayout.setPlayTextColor(mPlayColor);

        String playBackgroundColorString = p.getFormElement().getAdditionalAttribute(null, "playBackgroundColor");
        if (playBackgroundColorString != null) {
            try {
                mPlayBackgroundColor = Color.parseColor(playBackgroundColorString);
            } catch (IllegalArgumentException e) {
                e.printStackTrace();
            }
        }
        questionMediaLayout.setPlayTextBackgroundColor(mPlayBackgroundColor);

        return questionMediaLayout;
    }

    public MediaLayout getQuestionMediaLayout() {
        return mQuestionMediaLayout;
    }

    public TextView getHelpTextView () {
        return mHelpTextView;
    }

    public void playAudio() {
        playAllPromptText();
    }

    public void playVideo() {
        mQuestionMediaLayout.playVideo();
    }

    public FormEntryPrompt getPrompt() {
        return mPrompt;
    }


    public FormEntryPrompt getFormEntryPrompt() {
        return mPrompt;
    }

    // http://code.google.com/p/android/issues/detail?id=8488
    private void recycleDrawablesRecursive(ViewGroup viewGroup, List<ImageView> images) {

        int childCount = viewGroup.getChildCount();
        for (int index = 0; index < childCount; index++) {
            View child = viewGroup.getChildAt(index);
            if (child instanceof ImageView) {
                images.add((ImageView)child);
            } else if (child instanceof ViewGroup) {
                recycleDrawablesRecursive((ViewGroup)child, images);
            }
        }
        viewGroup.destroyDrawingCache();
    }

    // http://code.google.com/p/android/issues/detail?id=8488
    public void recycleDrawables() {
        List<ImageView> images = new ArrayList<ImageView>();
        // collect all the image views
        recycleDrawablesRecursive(this, images);
        for (ImageView imageView : images) {
            imageView.destroyDrawingCache();
            Drawable d = imageView.getDrawable();
            if (d != null && d instanceof BitmapDrawable) {
                imageView.setImageDrawable(null);
                BitmapDrawable bd = (BitmapDrawable)d;
                Bitmap bmp = bd.getBitmap();
                if (bmp != null) {
                    bmp.recycle();
                }
            }
        }
    }

    // Abstract methods
    public abstract IAnswerData getAnswer();

    public abstract void clearAnswer();

    public abstract void setFocus(Context context);

    public abstract void setOnLongClickListener(OnLongClickListener l);

    /**
     * Override this to implement fling gesture suppression (e.g. for embedded WebView treatments).
     * @param e1
     * @param e2
     * @param velocityX
     * @param velocityY
     * @return true if the fling gesture should be suppressed
     */
    public boolean suppressFlingGesture(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
       return false;
    }

    /**
     * Add a Views containing the question text, audio (if applicable), and image (if applicable).
     * To satisfy the RelativeLayout constraints, we add the audio first if it exists, then the
     * TextView to fit the rest of the space, then the image if applicable.
     */

    /**
     * Defaults to adding questionlayout to the top of the screen.
     * Overwrite to reposition.
     */
    protected void addQuestionMediaLayout(View v) {
        if (v == null) {
            Log.e(t, "cannot add a null view as questionMediaLayout");
            return;
        }
            // default for questionmedialayout
        RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
        params.addRule(RelativeLayout.ALIGN_PARENT_LEFT, RelativeLayout.TRUE);
        params.addRule(RelativeLayout.ALIGN_PARENT_TOP, RelativeLayout.TRUE);
        params.setMargins(10, 0, 10, 0);
        addView(v, params);
    }



    /**
     * Add a TextView containing the help text to the default location.
     * Override to reposition.
     */
    protected void addHelpTextView(View v) {
        if (v == null) {
            Log.e(t, "cannot add a null view as helpTextView");
            return;
        }

        // default for helptext
        RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.addRule(RelativeLayout.ALIGN_PARENT_LEFT, RelativeLayout.TRUE);
        params.addRule(RelativeLayout.BELOW, mQuestionMediaLayout.getId());
        params.setMargins(10, 0, 10, 0);
        addView(v, params);
    }

    private TextView createHelpText(FormEntryPrompt p) {
        TextView helpText = new TextView(getContext());
        String s = p.getHelpText();

        if (s != null && !s.equals("")) {
            helpText.setId(QuestionWidget.newUniqueId());
            helpText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, mQuestionFontsize - 3);
            helpText.setPadding(0, -5, 0, 7);
            // wrap to the widget of view
            helpText.setHorizontallyScrolling(false);
            helpText.setTypeface(null, Typeface.ITALIC);
            // PMA BEGIN
            helpText.setText(s);
            // PMA END (uncomment below to get back original)
            // helpText.setText(TextUtils.textToHtml(s));
            helpText.setMovementMethod(LinkMovementMethod.getInstance());
            return helpText;
        } else {
            helpText.setVisibility(View.GONE);
            return helpText;
        }
    }

    /**
     * Default place to put the answer
     * (below the help text or question text if there is no help text)
     * If you have many elements, use this first
     * and use the standard addView(view, params) to place the rest
     * @param v
     */
    protected void addAnswerView(View v) {
        if (v == null) {
            Log.e(t, "cannot add a null view as an answerView");
            return;
        }
            // default place to add answer
        RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        params.addRule(RelativeLayout.ALIGN_PARENT_LEFT, RelativeLayout.TRUE);
        if (mHelpTextView.getVisibility() == View.VISIBLE) {
            params.addRule(RelativeLayout.BELOW, mHelpTextView.getId());
        } else {
            params.addRule(RelativeLayout.BELOW, mQuestionMediaLayout.getId());
        }
        params.setMargins(10, 0, 10, 0);
        addView(v, params);
    }

    /**
     * Every subclassed widget should override this, adding any views they may contain, and calling
     * super.cancelLongPress()
     */
    public void cancelLongPress() {
        super.cancelLongPress();
        if (mQuestionMediaLayout != null) {
            mQuestionMediaLayout.cancelLongPress();
        }
        if (mHelpTextView != null) {
            mHelpTextView.cancelLongPress();
        }
    }

    /*
     * Prompts with items must override this
     */
    public void playAllPromptText() {
        mQuestionMediaLayout.playAudio();
    }

    public void setQuestionTextColor(int color) {
        mQuestionMediaLayout.setTextcolor(color);
    }

    public void resetQuestionTextColor() {
        mQuestionMediaLayout.resetTextFormatting();
    }

    @Override
    protected void onWindowVisibilityChanged (int visibility) {
        if (visibility == INVISIBLE || visibility == GONE) {
            if (mPlayer.isPlaying()) {
                mPlayer.stop();
                mPlayer.reset();
            }
        }
    }
    
    public void stopAudio() {
        if (mPlayer.isPlaying()) {
            mPlayer.stop();
            mPlayer.reset();
        }
    }

    protected Button getSimpleButton(String text, @IdRes final int withId) {
        final QuestionWidget questionWidget = this;
        final Button button = new Button(getContext());

        button.setId(withId);
        button.setText(text);
        button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, getAnswerFontSize());
        button.setPadding(20, 20, 20, 20);

        TableLayout.LayoutParams params = new TableLayout.LayoutParams();
        params.setMargins(7, 5, 7, 5);

        button.setLayoutParams(params);

        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (button.isClickable()) {
                    disableViewForOneSecond(button);
                    ((ButtonWidget) questionWidget).onButtonClick(withId);
                }
            }
        });
        return button;
    }

    protected Button getSimpleButton(@IdRes int id) {
        return getSimpleButton(null, id);
    }

    protected Button getSimpleButton(String text) {
        return getSimpleButton(text, R.id.simple_button);
    }

    protected TextView getCenteredAnswerTextView() {
        TextView textView = getAnswerTextView();
        textView.setGravity(Gravity.CENTER);

        return textView;
    }

    protected TextView getAnswerTextView() {
        TextView textView = new TextView(getContext());

        textView.setId(R.id.answer_text);
        textView.setTextColor(getResources().getColor(R.color.primaryTextColor));
//        textView.setTextColor(ContextCompat.getColor(getContext(), R.color.primaryTextColor));
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, getAnswerFontSize());
        textView.setPadding(20, 20, 20, 20);

        return textView;
    }

    protected ImageView getAnswerImageView(Bitmap bitmap) {
        final QuestionWidget questionWidget = this;
        final ImageView imageView = new ImageView(getContext());
        imageView.setId(ViewIds.generateViewId());
        imageView.setPadding(10, 10, 10, 10);
        imageView.setAdjustViewBounds(true);
        imageView.setImageBitmap(bitmap);
        imageView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (questionWidget instanceof BaseImageWidget) {
                    if (imageView.isClickable()) {
                        disableViewForOneSecond(imageView);
                        ((BaseImageWidget) questionWidget).onImageClick();
                    }
                }
            }
        });
        return imageView;
    }

    // This method is used to avoid opening more than one dialog or activity when user quickly clicks the button several times:
    // https://github.com/opendatakit/collect/issues/1624
    protected void disableViewForOneSecond(final View view) {
        view.setClickable(false);
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                view.setClickable(true);
            }
        }, 500);
    }

    public int getAnswerFontSize() {
        return mQuestionFontsize + 2;
    }

}
