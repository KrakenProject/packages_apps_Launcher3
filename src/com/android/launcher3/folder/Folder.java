/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.launcher3.folder;

import static android.text.TextUtils.isEmpty;

import static androidx.core.util.Preconditions.checkNotNull;

import static com.android.launcher3.LauncherAnimUtils.SPRING_LOADED_EXIT_DELAY;
import static com.android.launcher3.LauncherSettings.Favorites.CONTAINER_DESKTOP;
import static com.android.launcher3.LauncherSettings.Favorites.CONTAINER_HOTSEAT;
import static com.android.launcher3.LauncherState.NORMAL;
import static com.android.launcher3.compat.AccessibilityManagerCompat.sendCustomAccessibilityEvent;
import static com.android.launcher3.config.FeatureFlags.ALWAYS_USE_HARDWARE_OPTIMIZATION_FOR_FOLDER_ANIMATIONS;
import static com.android.launcher3.logging.LoggerUtils.newContainerTarget;
import static com.android.launcher3.model.data.FolderInfo.FLAG_MANUAL_FOLDER_NAME;
import static com.android.launcher3.userevent.LauncherLogProto.Target.FromFolderLabelState.FROM_CUSTOM;
import static com.android.launcher3.userevent.LauncherLogProto.Target.FromFolderLabelState.FROM_EMPTY;
import static com.android.launcher3.userevent.LauncherLogProto.Target.FromFolderLabelState.FROM_FOLDER_LABEL_STATE_UNSPECIFIED;
import static com.android.launcher3.userevent.LauncherLogProto.Target.FromFolderLabelState.FROM_SUGGESTED;

import static java.util.Arrays.asList;
import static java.util.Arrays.stream;
import static java.util.Optional.ofNullable;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.annotation.SuppressLint;
import android.appwidget.AppWidgetHostView;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.Rect;
import android.text.InputType;
import android.text.Selection;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Pair;
import android.view.FocusFinder;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewDebug;
import android.view.accessibility.AccessibilityEvent;
import android.view.animation.AnimationUtils;
import android.view.inputmethod.EditorInfo;
import android.widget.TextView;

import com.android.launcher3.AbstractFloatingView;
import com.android.launcher3.Alarm;
import com.android.launcher3.BubbleTextView;
import com.android.launcher3.CellLayout;
import com.android.launcher3.DeviceProfile;
import com.android.launcher3.DragSource;
import com.android.launcher3.DropTarget;
import com.android.launcher3.ExtendedEditText;
import com.android.launcher3.Launcher;
import com.android.launcher3.LauncherSettings;
import com.android.launcher3.OnAlarmListener;
import com.android.launcher3.PagedView;
import com.android.launcher3.R;
import com.android.launcher3.ShortcutAndWidgetContainer;
import com.android.launcher3.Utilities;
import com.android.launcher3.Workspace;
import com.android.launcher3.Workspace.ItemOperator;
import com.android.launcher3.accessibility.AccessibleDragListenerAdapter;
import com.android.launcher3.accessibility.FolderAccessibilityHelper;
import com.android.launcher3.config.FeatureFlags;
import com.android.launcher3.dragndrop.DragController;
import com.android.launcher3.dragndrop.DragController.DragListener;
import com.android.launcher3.dragndrop.DragLayer;
import com.android.launcher3.dragndrop.DragOptions;
import com.android.launcher3.model.data.AppInfo;
import com.android.launcher3.model.data.FolderInfo;
import com.android.launcher3.model.data.FolderInfo.FolderListener;
import com.android.launcher3.model.data.ItemInfo;
import com.android.launcher3.model.data.WorkspaceItemInfo;
import com.android.launcher3.pageindicators.PageIndicatorDots;
import com.android.launcher3.userevent.LauncherLogProto.Action;
import com.android.launcher3.userevent.LauncherLogProto.ContainerType;
import com.android.launcher3.userevent.LauncherLogProto.ItemType;
import com.android.launcher3.userevent.LauncherLogProto.LauncherEvent;
import com.android.launcher3.userevent.LauncherLogProto.Target;
import com.android.launcher3.userevent.LauncherLogProto.Target.ToFolderLabelState;
import com.android.launcher3.userevent.nano.LauncherLogProto;
import com.android.launcher3.util.Executors;
import com.android.launcher3.util.Thunk;
import com.android.launcher3.views.ClipPathView;
import com.android.launcher3.widget.PendingAddShortcutInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Represents a set of icons chosen by the user or generated by the system.
 */
public class Folder extends AbstractFloatingView implements ClipPathView, DragSource,
        View.OnLongClickListener, DropTarget, FolderListener, TextView.OnEditorActionListener,
        View.OnFocusChangeListener, DragListener, ExtendedEditText.OnBackKeyListener {
    private static final String TAG = "Launcher.Folder";
    private static final boolean DEBUG = false;
    /**
     * We avoid measuring {@link #mContent} with a 0 width or height, as this
     * results in CellLayout being measured as UNSPECIFIED, which it does not support.
     */
    private static final int MIN_CONTENT_DIMEN = 5;

    static final int STATE_NONE = -1;
    static final int STATE_SMALL = 0;
    static final int STATE_ANIMATING = 1;
    static final int STATE_OPEN = 2;

    /**
     * Time for which the scroll hint is shown before automatically changing page.
     */
    public static final int SCROLL_HINT_DURATION = 500;
    public static final int RESCROLL_DELAY = PagedView.PAGE_SNAP_ANIMATION_DURATION + 150;

    public static final int SCROLL_NONE = -1;
    public static final int SCROLL_LEFT = 0;
    public static final int SCROLL_RIGHT = 1;

    /**
     * Fraction of icon width which behave as scroll region.
     */
    private static final float ICON_OVERSCROLL_WIDTH_FACTOR = 0.45f;

    private static final int FOLDER_NAME_ANIMATION_DURATION = 633;

    private static final int REORDER_DELAY = 250;
    private static final int ON_EXIT_CLOSE_DELAY = 400;
    private static final Rect sTempRect = new Rect();
    private static final int MIN_FOLDERS_FOR_HARDWARE_OPTIMIZATION = 10;

    private final Alarm mReorderAlarm = new Alarm();
    private final Alarm mOnExitAlarm = new Alarm();
    private final Alarm mOnScrollHintAlarm = new Alarm();
    @Thunk final Alarm mScrollPauseAlarm = new Alarm();

    @Thunk final ArrayList<View> mItemsInReadingOrder = new ArrayList<View>();

    private AnimatorSet mCurrentAnimator;
    private boolean mIsAnimatingClosed = false;

    protected final Launcher mLauncher;
    protected DragController mDragController;
    public FolderInfo mInfo;

    @Thunk FolderIcon mFolderIcon;

    @Thunk FolderPagedView mContent;
    public FolderNameEditText mFolderName;
    private PageIndicatorDots mPageIndicator;

    protected View mFooter;
    private int mFooterHeight;

    // Cell ranks used for drag and drop
    @Thunk int mTargetRank, mPrevTargetRank, mEmptyCellRank;

    private Path mClipPath;

    @ViewDebug.ExportedProperty(category = "launcher",
            mapping = {
                    @ViewDebug.IntToString(from = STATE_NONE, to = "STATE_NONE"),
                    @ViewDebug.IntToString(from = STATE_SMALL, to = "STATE_SMALL"),
                    @ViewDebug.IntToString(from = STATE_ANIMATING, to = "STATE_ANIMATING"),
                    @ViewDebug.IntToString(from = STATE_OPEN, to = "STATE_OPEN"),
            })
    @Thunk int mState = STATE_NONE;
    @ViewDebug.ExportedProperty(category = "launcher")
    private boolean mRearrangeOnClose = false;
    boolean mItemsInvalidated = false;
    private View mCurrentDragView;
    private boolean mIsExternalDrag;
    private boolean mDragInProgress = false;
    private boolean mDeleteFolderOnDropCompleted = false;
    private boolean mSuppressFolderDeletion = false;
    private boolean mItemAddedBackToSelfViaIcon = false;
    private boolean mIsEditingName = false;

    @ViewDebug.ExportedProperty(category = "launcher")
    private boolean mDestroyed;

    // Folder scrolling
    private int mScrollAreaOffset;

    @Thunk int mScrollHintDir = SCROLL_NONE;
    @Thunk int mCurrentScrollDir = SCROLL_NONE;

    private String mPreviousLabel;
    private boolean mIsPreviousLabelSuggested;

    /**
     * Used to inflate the Workspace from XML.
     *
     * @param context The application's context.
     * @param attrs The attributes set containing the Workspace's customization values.
     */
    public Folder(Context context, AttributeSet attrs) {
        super(context, attrs);
        setAlwaysDrawnWithCacheEnabled(false);

        mLauncher = Launcher.getLauncher(context);
        // We need this view to be focusable in touch mode so that when text editing of the folder
        // name is complete, we have something to focus on, thus hiding the cursor and giving
        // reliable behavior when clicking the text field (since it will always gain focus on click).
        setFocusableInTouchMode(true);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mContent = findViewById(R.id.folder_content);
        mContent.setFolder(this);

        mPageIndicator = findViewById(R.id.folder_page_indicator);
        mFolderName = findViewById(R.id.folder_name);
        mFolderName.setOnBackKeyListener(this);
        mFolderName.setOnFocusChangeListener(this);
        mFolderName.setOnEditorActionListener(this);
        mFolderName.setSelectAllOnFocus(true);
        mFolderName.setInputType(mFolderName.getInputType()
                & ~InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
                & ~InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        mFolderName.forceDisableSuggestions(!FeatureFlags.FOLDER_NAME_SUGGEST.get());

        mFooter = findViewById(R.id.folder_footer);

        // We find out how tall footer wants to be (it is set to wrap_content), so that
        // we can allocate the appropriate amount of space for it.
        int measureSpec = MeasureSpec.UNSPECIFIED;
        mFooter.measure(measureSpec, measureSpec);
        mFooterHeight = mFooter.getMeasuredHeight();
    }

    public boolean onLongClick(View v) {
        // Return if global dragging is not enabled
        if (!mLauncher.isDraggingEnabled()) return true;
        return startDrag(v, new DragOptions());
    }

    public boolean startDrag(View v, DragOptions options) {
        Object tag = v.getTag();
        if (tag instanceof WorkspaceItemInfo) {
            WorkspaceItemInfo item = (WorkspaceItemInfo) tag;

            mEmptyCellRank = item.rank;
            mCurrentDragView = v;

            mDragController.addDragListener(this);
            if (options.isAccessibleDrag) {
                mDragController.addDragListener(new AccessibleDragListenerAdapter(
                        mContent, FolderAccessibilityHelper::new) {
                            @Override
                            protected void enableAccessibleDrag(boolean enable) {
                                super.enableAccessibleDrag(enable);
                                mFooter.setImportantForAccessibility(enable
                                        ? IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                                        : IMPORTANT_FOR_ACCESSIBILITY_AUTO);
                            }
                        });
            }

            mLauncher.getWorkspace().beginDragShared(v, this, options);
        }
        return true;
    }

    @Override
    public void onDragStart(DropTarget.DragObject dragObject, DragOptions options) {
        if (dragObject.dragSource != this) {
            return;
        }

        mContent.removeItem(mCurrentDragView);
        if (dragObject.dragInfo instanceof WorkspaceItemInfo) {
            mItemsInvalidated = true;

            // We do not want to get events for the item being removed, as they will get handled
            // when the drop completes
            try (SuppressInfoChanges s = new SuppressInfoChanges()) {
                mInfo.remove((WorkspaceItemInfo) dragObject.dragInfo, true);
            }
        }
        mDragInProgress = true;
        mItemAddedBackToSelfViaIcon = false;
    }

    @Override
    public void onDragEnd() {
        if (mIsExternalDrag && mDragInProgress) {
            completeDragExit();
        }
        mDragInProgress = false;
        mDragController.removeDragListener(this);
    }

    public boolean isEditingName() {
        return mIsEditingName;
    }

    public void startEditingFolderName() {
        post(() -> {
            if (FeatureFlags.FOLDER_NAME_SUGGEST.get()) {
                ofNullable(mInfo)
                        .map(info -> info.suggestedFolderNames)
                        .map(folderNames -> (FolderNameInfo[]) folderNames
                                .getParcelableArrayExtra(FolderInfo.EXTRA_FOLDER_SUGGESTIONS))
                        .ifPresent(nameInfos -> showLabelSuggestions(nameInfos));
            }
            mFolderName.setHint("");
            mIsEditingName = true;
        });
    }

    @Override
    public boolean onBackKey() {
        // Convert to a string here to ensure that no other state associated with the text field
        // gets saved.
        String newTitle = mFolderName.getText().toString();
        if (DEBUG) {
            Log.d(TAG, "onBackKey newTitle=" + newTitle);
        }

        mInfo.title = newTitle;
        mInfo.setOption(FLAG_MANUAL_FOLDER_NAME, !getAcceptedSuggestionIndex().isPresent(),
                mLauncher.getModelWriter());
        mFolderIcon.onTitleChanged(newTitle);
        mLauncher.getModelWriter().updateItemInDatabase(mInfo);

        if (TextUtils.isEmpty(mInfo.title)) {
            mFolderName.setHint(R.string.folder_hint_text);
            mFolderName.setText("");
        } else {
            mFolderName.setHint(null);
        }

        sendCustomAccessibilityEvent(
                this, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                getContext().getString(R.string.folder_renamed, newTitle));

        // This ensures that focus is gained every time the field is clicked, which selects all
        // the text and brings up the soft keyboard if necessary.
        mFolderName.clearFocus();

        Selection.setSelection(mFolderName.getText(), 0, 0);
        mIsEditingName = false;
        return true;
    }

    public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
        if (DEBUG) {
            Log.d(TAG, "onEditorAction actionId=" + actionId + " key="
                    + (event != null ? event.getKeyCode() : "null event"));
        }
        if (actionId == EditorInfo.IME_ACTION_DONE) {
            mFolderName.dispatchBackKey();
            return true;
        }
        return false;
    }

    public FolderIcon getFolderIcon() {
        return mFolderIcon;
    }

    public void setDragController(DragController dragController) {
        mDragController = dragController;
    }

    public void setFolderIcon(FolderIcon icon) {
        mFolderIcon = icon;
    }

    @Override
    protected void onAttachedToWindow() {
        // requestFocus() causes the focus onto the folder itself, which doesn't cause visual
        // effect but the next arrow key can start the keyboard focus inside of the folder, not
        // the folder itself.
        requestFocus();
        super.onAttachedToWindow();
    }

    @Override
    public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent event) {
        // When the folder gets focus, we don't want to announce the list of items.
        return true;
    }

    @Override
    public View focusSearch(int direction) {
        // When the folder is focused, further focus search should be within the folder contents.
        return FocusFinder.getInstance().findNextFocus(this, null, direction);
    }

    /**
     * @return the FolderInfo object associated with this folder
     */
    public FolderInfo getInfo() {
        return mInfo;
    }

    void bind(FolderInfo info) {
        mInfo = info;
        ArrayList<WorkspaceItemInfo> children = info.contents;
        Collections.sort(children, ITEM_POS_COMPARATOR);
        updateItemLocationsInDatabaseBatch(true);

        DragLayer.LayoutParams lp = (DragLayer.LayoutParams) getLayoutParams();
        if (lp == null) {
            lp = new DragLayer.LayoutParams(0, 0);
            lp.customPosition = true;
            setLayoutParams(lp);
        }
        mItemsInvalidated = true;
        mInfo.addListener(this);
        Optional.ofNullable(mInfo.title).ifPresent(title -> mPreviousLabel = title.toString());
        mIsPreviousLabelSuggested = !mInfo.hasOption(FLAG_MANUAL_FOLDER_NAME);

        if (!isEmpty(mInfo.title)) {
            mFolderName.setText(mInfo.title);
            mFolderName.setHint(null);
        } else {
            mFolderName.setText("");
            mFolderName.setHint(R.string.folder_hint_text);
        }
        // In case any children didn't come across during loading, clean up the folder accordingly
        mFolderIcon.post(() -> {
            if (getItemCount() <= 1) {
                replaceFolderWithFinalItem();
            }
        });
    }


    /**
     * Show suggested folder title in FolderEditText if the first suggestion is non-empty, push
     * rest of the suggestions to InputMethodManager.
     */
    private void showLabelSuggestions(FolderNameInfo[] nameInfos) {
        if (nameInfos == null) {
            return;
        }
        // Open the Folder and Keyboard when the first or second suggestion is valid non-empty
        // string.
        boolean shouldOpen = nameInfos.length > 0 && nameInfos[0] != null && !isEmpty(
                nameInfos[0].getLabel())
                || nameInfos.length > 1 && nameInfos[1] != null && !isEmpty(
                nameInfos[1].getLabel());

        if (shouldOpen) {
            // update the primary suggestion if the folder name is empty.
            if (isEmpty(mFolderName.getText())) {
                CharSequence firstLabel = nameInfos[0] == null ? "" : nameInfos[0].getLabel();
                if (!isEmpty(firstLabel)) {
                    mFolderName.setHint("");
                    mFolderName.setText(firstLabel);
                }
            }
            mFolderName.showKeyboard();
            mFolderName.displayCompletions(
                    asList(nameInfos).subList(0, nameInfos.length).stream()
                            .filter(Objects::nonNull)
                            .map(s -> s.getLabel().toString())
                            .filter(s -> !s.isEmpty())
                            .filter(s -> !s.equalsIgnoreCase(mFolderName.getText().toString()))
                            .collect(Collectors.toList()));
        }
    }

    /**
     * Creates a new UserFolder, inflated from R.layout.user_folder.
     *
     * @param launcher The main activity.
     *
     * @return A new UserFolder.
     */
    @SuppressLint("InflateParams")
    static Folder fromXml(Launcher launcher) {
        return (Folder) launcher.getLayoutInflater()
                .inflate(R.layout.user_folder_icon_normalized, null);
    }

    private void startAnimation(final AnimatorSet a) {
        final Workspace workspace = mLauncher.getWorkspace();
        final CellLayout currentCellLayout =
                (CellLayout) workspace.getChildAt(workspace.getCurrentPage());
        final boolean useHardware = shouldUseHardwareLayerForAnimation(currentCellLayout);
        final boolean wasHardwareAccelerated = currentCellLayout.isHardwareLayerEnabled();

        a.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                if (useHardware) {
                    currentCellLayout.enableHardwareLayer(true);
                }
                mState = STATE_ANIMATING;
                mCurrentAnimator = a;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (useHardware) {
                    currentCellLayout.enableHardwareLayer(wasHardwareAccelerated);
                }
                mCurrentAnimator = null;
            }
        });
        a.start();
    }

    private boolean shouldUseHardwareLayerForAnimation(CellLayout currentCellLayout) {
        if (ALWAYS_USE_HARDWARE_OPTIMIZATION_FOR_FOLDER_ANIMATIONS.get()) return true;

        int folderCount = 0;
        final ShortcutAndWidgetContainer container = currentCellLayout.getShortcutsAndWidgets();
        for (int i = container.getChildCount() - 1; i >= 0; --i) {
            final View child = container.getChildAt(i);
            if (child instanceof AppWidgetHostView) return false;
            if (child instanceof FolderIcon) ++folderCount;
        }
        return folderCount >= MIN_FOLDERS_FOR_HARDWARE_OPTIMIZATION;
    }

    /**
     * Opens the folder as part of a drag operation
     */
    public void beginExternalDrag() {
        mIsExternalDrag = true;
        mDragInProgress = true;

        // Since this folder opened by another controller, it might not get onDrop or
        // onDropComplete. Perform cleanup once drag-n-drop ends.
        mDragController.addDragListener(this);

        ArrayList<WorkspaceItemInfo> items = new ArrayList<>(mInfo.contents);
        mEmptyCellRank = items.size();
        items.add(null);    // Add an empty spot at the end

        animateOpen(items, mEmptyCellRank / mContent.itemsPerPage());
    }

    /**
     * Opens the user folder described by the specified tag. The opening of the folder
     * is animated relative to the specified View. If the View is null, no animation
     * is played.
     */
    public void animateOpen() {
        animateOpen(mInfo.contents, 0);
    }

    /**
     * Opens the user folder described by the specified tag. The opening of the folder
     * is animated relative to the specified View. If the View is null, no animation
     * is played.
     */
    private void animateOpen(List<WorkspaceItemInfo> items, int pageNo) {
        animateOpen(items, pageNo, false);
    }

    /**
     * Opens the user folder described by the specified tag. The opening of the folder
     * is animated relative to the specified View. If the View is null, no animation
     * is played.
     */
    private void animateOpen(List<WorkspaceItemInfo> items, int pageNo, boolean skipUserEventLog) {
        Folder openFolder = getOpen(mLauncher);
        if (openFolder != null && openFolder != this) {
            // Close any open folder before opening a folder.
            openFolder.close(true);
        }

        mContent.bindItems(items);
        centerAboutIcon();
        mItemsInvalidated = true;
        updateTextViewFocus();

        mIsOpen = true;

        DragLayer dragLayer = mLauncher.getDragLayer();
        // Just verify that the folder hasn't already been added to the DragLayer.
        // There was a one-off crash where the folder had a parent already.
        if (getParent() == null) {
            dragLayer.addView(this);
            mDragController.addDropTarget(this);
        } else {
            if (FeatureFlags.IS_STUDIO_BUILD) {
                Log.e(TAG, "Opening folder (" + this + ") which already has a parent:"
                        + getParent());
            }
        }

        mContent.completePendingPageChanges();
        mContent.snapToPageImmediately(pageNo);

        // This is set to true in close(), but isn't reset to false until onDropCompleted(). This
        // leads to an inconsistent state if you drag out of the folder and drag back in without
        // dropping. One resulting issue is that replaceFolderWithFinalItem() can be called twice.
        mDeleteFolderOnDropCompleted = false;

        if (mCurrentAnimator != null && mCurrentAnimator.isRunning()) {
            mCurrentAnimator.cancel();
        }
        AnimatorSet anim = new FolderAnimationManager(this, true /* isOpening */).getAnimator();
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                mFolderIcon.setIconVisible(false);
                mFolderIcon.drawLeaveBehindIfExists();
            }
            @Override
            public void onAnimationEnd(Animator animation) {
                mState = STATE_OPEN;
                announceAccessibilityChanges();

                if (!skipUserEventLog) {
                    mLauncher.getUserEventDispatcher().logActionOnItem(
                            LauncherLogProto.Action.Touch.TAP,
                            LauncherLogProto.Action.Direction.NONE,
                            LauncherLogProto.ItemType.FOLDER_ICON, mInfo.cellX, mInfo.cellY);
                }


                mContent.setFocusOnFirstChild();
            }
        });

        // Footer animation
        if (mContent.getPageCount() > 1 && !mInfo.hasOption(FolderInfo.FLAG_MULTI_PAGE_ANIMATION)) {
            int footerWidth = mContent.getDesiredWidth()
                    - mFooter.getPaddingLeft() - mFooter.getPaddingRight();

            float textWidth =  mFolderName.getPaint().measureText(mFolderName.getText().toString());
            float translation = (footerWidth - textWidth) / 2;
            mFolderName.setTranslationX(mContent.mIsRtl ? -translation : translation);
            mPageIndicator.prepareEntryAnimation();

            // Do not update the flag if we are in drag mode. The flag will be updated, when we
            // actually drop the icon.
            final boolean updateAnimationFlag = !mDragInProgress;
            anim.addListener(new AnimatorListenerAdapter() {

                @SuppressLint("InlinedApi")
                @Override
                public void onAnimationEnd(Animator animation) {
                    mFolderName.animate().setDuration(FOLDER_NAME_ANIMATION_DURATION)
                        .translationX(0)
                        .setInterpolator(AnimationUtils.loadInterpolator(
                                mLauncher, android.R.interpolator.fast_out_slow_in));
                    mPageIndicator.playEntryAnimation();

                    if (updateAnimationFlag) {
                        mInfo.setOption(FolderInfo.FLAG_MULTI_PAGE_ANIMATION, true,
                                mLauncher.getModelWriter());
                    }
                }
            });
        } else {
            mFolderName.setTranslationX(0);
        }

        mPageIndicator.stopAllAnimations();
        startAnimation(anim);

        // Make sure the folder picks up the last drag move even if the finger doesn't move.
        if (mDragController.isDragging()) {
            mDragController.forceTouchMove();
        }
        mContent.verifyVisibleHighResIcons(mContent.getNextPage());
    }

    @Override
    protected boolean isOfType(int type) {
        return (type & TYPE_FOLDER) != 0;
    }

    @Override
    protected void handleClose(boolean animate) {
        mIsOpen = false;

        if (!animate && mCurrentAnimator != null && mCurrentAnimator.isRunning()) {
            mCurrentAnimator.cancel();
        }

        if (isEditingName()) {
            mFolderName.dispatchBackKey();
        }

        if (mFolderIcon != null) {
            mFolderIcon.clearLeaveBehindIfExists();
        }

        if (animate) {
            animateClosed();
        } else {
            closeComplete(false);
            post(this::announceAccessibilityChanges);
        }

        // Notify the accessibility manager that this folder "window" has disappeared and no
        // longer occludes the workspace items
        mLauncher.getDragLayer().sendAccessibilityEvent(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
    }

    private void animateClosed() {
        if (mIsAnimatingClosed) {
            return;
        }
        if (mCurrentAnimator != null && mCurrentAnimator.isRunning()) {
            mCurrentAnimator.cancel();
        }
        AnimatorSet a = new FolderAnimationManager(this, false /* isOpening */).getAnimator();
        a.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                mIsAnimatingClosed = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                closeComplete(true);
                announceAccessibilityChanges();
                mIsAnimatingClosed = false;
            }
        });
        startAnimation(a);
    }

    @Override
    protected Pair<View, String> getAccessibilityTarget() {
        return Pair.create(mContent, mIsOpen ? mContent.getAccessibilityDescription()
                : getContext().getString(R.string.folder_closed));
    }

    private void closeComplete(boolean wasAnimated) {
        // TODO: Clear all active animations.
        DragLayer parent = (DragLayer) getParent();
        if (parent != null) {
            parent.removeView(this);
        }
        mDragController.removeDropTarget(this);
        clearFocus();
        if (mFolderIcon != null) {
            mFolderIcon.setVisibility(View.VISIBLE);
            mFolderIcon.setIconVisible(true);
            mFolderIcon.mFolderName.setTextVisibility(true);
            if (wasAnimated) {
                mFolderIcon.animateBgShadowAndStroke();
                mFolderIcon.onFolderClose(mContent.getCurrentPage());
                if (mFolderIcon.hasDot()) {
                    mFolderIcon.animateDotScale(0f, 1f);
                }
                mFolderIcon.requestFocus();
            }
        }

        if (mRearrangeOnClose) {
            rearrangeChildren();
            mRearrangeOnClose = false;
        }
        if (getItemCount() <= 1) {
            if (!mDragInProgress && !mSuppressFolderDeletion) {
                replaceFolderWithFinalItem();
            } else if (mDragInProgress) {
                mDeleteFolderOnDropCompleted = true;
            }
        } else if (!mDragInProgress) {
            mContent.unbindItems();
        }
        mSuppressFolderDeletion = false;
        clearDragInfo();
        mState = STATE_SMALL;
        mContent.setCurrentPage(0);
    }

    @Override
    public boolean acceptDrop(DragObject d) {
        final ItemInfo item = d.dragInfo;
        final int itemType = item.itemType;
        return ((itemType == LauncherSettings.Favorites.ITEM_TYPE_APPLICATION ||
                itemType == LauncherSettings.Favorites.ITEM_TYPE_SHORTCUT ||
                itemType == LauncherSettings.Favorites.ITEM_TYPE_DEEP_SHORTCUT));
    }

    public void onDragEnter(DragObject d) {
        mPrevTargetRank = -1;
        mOnExitAlarm.cancelAlarm();
        // Get the area offset such that the folder only closes if half the drag icon width
        // is outside the folder area
        mScrollAreaOffset = d.dragView.getDragRegionWidth() / 2 - d.xOffset;
    }

    OnAlarmListener mReorderAlarmListener = new OnAlarmListener() {
        public void onAlarm(Alarm alarm) {
            mContent.realTimeReorder(mEmptyCellRank, mTargetRank);
            mEmptyCellRank = mTargetRank;
        }
    };

    public boolean isLayoutRtl() {
        return (getLayoutDirection() == LAYOUT_DIRECTION_RTL);
    }

    private int getTargetRank(DragObject d, float[] recycle) {
        recycle = d.getVisualCenter(recycle);
        return mContent.findNearestArea(
                (int) recycle[0] - getPaddingLeft(), (int) recycle[1] - getPaddingTop());
    }

    @Override
    public void onDragOver(DragObject d) {
        if (mScrollPauseAlarm.alarmPending()) {
            return;
        }
        final float[] r = new float[2];
        mTargetRank = getTargetRank(d, r);

        if (mTargetRank != mPrevTargetRank) {
            mReorderAlarm.cancelAlarm();
            mReorderAlarm.setOnAlarmListener(mReorderAlarmListener);
            mReorderAlarm.setAlarm(REORDER_DELAY);
            mPrevTargetRank = mTargetRank;

            if (d.stateAnnouncer != null) {
                d.stateAnnouncer.announce(getContext().getString(R.string.move_to_position,
                        mTargetRank + 1));
            }
        }

        float x = r[0];
        int currentPage = mContent.getNextPage();

        float cellOverlap = mContent.getCurrentCellLayout().getCellWidth()
                * ICON_OVERSCROLL_WIDTH_FACTOR;
        boolean isOutsideLeftEdge = x < cellOverlap;
        boolean isOutsideRightEdge = x > (getWidth() - cellOverlap);

        if (currentPage > 0 && (mContent.mIsRtl ? isOutsideRightEdge : isOutsideLeftEdge)) {
            showScrollHint(SCROLL_LEFT, d);
        } else if (currentPage < (mContent.getPageCount() - 1)
                && (mContent.mIsRtl ? isOutsideLeftEdge : isOutsideRightEdge)) {
            showScrollHint(SCROLL_RIGHT, d);
        } else {
            mOnScrollHintAlarm.cancelAlarm();
            if (mScrollHintDir != SCROLL_NONE) {
                mContent.clearScrollHint();
                mScrollHintDir = SCROLL_NONE;
            }
        }
    }

    private void showScrollHint(int direction, DragObject d) {
        // Show scroll hint on the right
        if (mScrollHintDir != direction) {
            mContent.showScrollHint(direction);
            mScrollHintDir = direction;
        }

        // Set alarm for when the hint is complete
        if (!mOnScrollHintAlarm.alarmPending() || mCurrentScrollDir != direction) {
            mCurrentScrollDir = direction;
            mOnScrollHintAlarm.cancelAlarm();
            mOnScrollHintAlarm.setOnAlarmListener(new OnScrollHintListener(d));
            mOnScrollHintAlarm.setAlarm(SCROLL_HINT_DURATION);

            mReorderAlarm.cancelAlarm();
            mTargetRank = mEmptyCellRank;
        }
    }

    OnAlarmListener mOnExitAlarmListener = new OnAlarmListener() {
        public void onAlarm(Alarm alarm) {
            completeDragExit();
        }
    };

    public void completeDragExit() {
        if (mIsOpen) {
            close(true);
            mRearrangeOnClose = true;
        } else if (mState == STATE_ANIMATING) {
            mRearrangeOnClose = true;
        } else {
            rearrangeChildren();
            clearDragInfo();
        }
    }

    private void clearDragInfo() {
        mCurrentDragView = null;
        mIsExternalDrag = false;
    }

    public void onDragExit(DragObject d) {
        // We only close the folder if this is a true drag exit, ie. not because
        // a drop has occurred above the folder.
        if (!d.dragComplete) {
            mOnExitAlarm.setOnAlarmListener(mOnExitAlarmListener);
            mOnExitAlarm.setAlarm(ON_EXIT_CLOSE_DELAY);
        }
        mReorderAlarm.cancelAlarm();

        mOnScrollHintAlarm.cancelAlarm();
        mScrollPauseAlarm.cancelAlarm();
        if (mScrollHintDir != SCROLL_NONE) {
            mContent.clearScrollHint();
            mScrollHintDir = SCROLL_NONE;
        }
    }

    /**
     * When performing an accessibility drop, onDrop is sent immediately after onDragEnter. So we
     * need to complete all transient states based on timers.
     */
    @Override
    public void prepareAccessibilityDrop() {
        if (mReorderAlarm.alarmPending()) {
            mReorderAlarm.cancelAlarm();
            mReorderAlarmListener.onAlarm(mReorderAlarm);
        }
    }

    @Override
    public void onDropCompleted(final View target, final DragObject d,
            final boolean success) {
        if (success) {
            if (mDeleteFolderOnDropCompleted && !mItemAddedBackToSelfViaIcon && target != this) {
                replaceFolderWithFinalItem();
            }
        } else {
            // The drag failed, we need to return the item to the folder
            WorkspaceItemInfo info = (WorkspaceItemInfo) d.dragInfo;
            View icon = (mCurrentDragView != null && mCurrentDragView.getTag() == info)
                    ? mCurrentDragView : mContent.createNewView(info);
            ArrayList<View> views = getIconsInReadingOrder();
            info.rank = Utilities.boundToRange(info.rank, 0, views.size());
            views.add(info.rank, icon);
            mContent.arrangeChildren(views);
            mItemsInvalidated = true;

            try (SuppressInfoChanges s = new SuppressInfoChanges()) {
                mFolderIcon.onDrop(d, true /* itemReturnedOnFailedDrop */);
            }
        }

        if (target != this) {
            if (mOnExitAlarm.alarmPending()) {
                mOnExitAlarm.cancelAlarm();
                if (!success) {
                    mSuppressFolderDeletion = true;
                }
                mScrollPauseAlarm.cancelAlarm();
                completeDragExit();
            }
        }

        mDeleteFolderOnDropCompleted = false;
        mDragInProgress = false;
        mItemAddedBackToSelfViaIcon = false;
        mCurrentDragView = null;

        // Reordering may have occured, and we need to save the new item locations. We do this once
        // at the end to prevent unnecessary database operations.
        updateItemLocationsInDatabaseBatch(false);
        // Use the item count to check for multi-page as the folder UI may not have
        // been refreshed yet.
        if (getItemCount() <= mContent.itemsPerPage()) {
            // Show the animation, next time something is added to the folder.
            mInfo.setOption(FolderInfo.FLAG_MULTI_PAGE_ANIMATION, false,
                    mLauncher.getModelWriter());
        }
    }

    private void updateItemLocationsInDatabaseBatch(boolean isBind) {
        FolderGridOrganizer verifier = new FolderGridOrganizer(
                mLauncher.getDeviceProfile().inv).setFolderInfo(mInfo);

        ArrayList<ItemInfo> items = new ArrayList<>();
        int total = mInfo.contents.size();
        for (int i = 0; i < total; i++) {
            WorkspaceItemInfo itemInfo = mInfo.contents.get(i);
            if (verifier.updateRankAndPos(itemInfo, i)) {
                items.add(itemInfo);
            }
        }

        if (!items.isEmpty()) {
            mLauncher.getModelWriter().moveItemsInDatabase(items, mInfo.id, 0);
        }
        if (FeatureFlags.FOLDER_NAME_SUGGEST.get() && !isBind) {
            Executors.MODEL_EXECUTOR.post(() -> {
                FolderNameInfo[] nameInfos =
                        new FolderNameInfo[FolderNameProvider.SUGGEST_MAX];
                FolderNameProvider fnp = FolderNameProvider.newInstance(getContext());
                fnp.getSuggestedFolderName(
                        getContext(), mInfo.contents, nameInfos);
                mInfo.suggestedFolderNames = new Intent().putExtra(
                        FolderInfo.EXTRA_FOLDER_SUGGESTIONS,
                        nameInfos);
            });
        }
    }

    public void notifyDrop() {
        if (mDragInProgress) {
            mItemAddedBackToSelfViaIcon = true;
        }
    }

    public boolean isDropEnabled() {
        return mState != STATE_ANIMATING;
    }

    private void centerAboutIcon() {
        DeviceProfile grid = mLauncher.getDeviceProfile();

        DragLayer.LayoutParams lp = (DragLayer.LayoutParams) getLayoutParams();
        DragLayer parent = mLauncher.getDragLayer();
        int width = getFolderWidth();
        int height = getFolderHeight();

        parent.getDescendantRectRelativeToSelf(mFolderIcon, sTempRect);
        int centerX = sTempRect.centerX();
        int centerY = sTempRect.centerY();
        int centeredLeft = centerX - width / 2;
        int centeredTop = centerY - height / 2;

        // We need to bound the folder to the currently visible workspace area
        if (mLauncher.getStateManager().getState().overviewUi) {
            parent.getDescendantRectRelativeToSelf(mLauncher.getOverviewPanel(), sTempRect);
        } else {
            mLauncher.getWorkspace().getPageAreaRelativeToDragLayer(sTempRect);
        }
        int left = Math.min(Math.max(sTempRect.left, centeredLeft),
                sTempRect.right- width);
        int top = Math.min(Math.max(sTempRect.top, centeredTop),
                sTempRect.bottom - height);

        int distFromEdgeOfScreen = mLauncher.getWorkspace().getPaddingLeft() + getPaddingLeft();

        if (grid.isPhone && (grid.availableWidthPx - width) < 4 * distFromEdgeOfScreen) {
            // Center the folder if it is very close to being centered anyway, by virtue of
            // filling the majority of the viewport. ie. remove it from the uncanny valley
            // of centeredness.
            left = (grid.availableWidthPx - width) / 2;
        } else if (width >= sTempRect.width()) {
            // If the folder doesn't fit within the bounds, center it about the desired bounds
            left = sTempRect.left + (sTempRect.width() - width) / 2;
        }
        if (height >= sTempRect.height()) {
            // Folder height is greater than page height, center on page
            top = sTempRect.top + (sTempRect.height() - height) / 2;
        } else {
            // Folder height is less than page height, so bound it to the absolute open folder
            // bounds if necessary
            Rect folderBounds = grid.getAbsoluteOpenFolderBounds();
            left = Math.max(folderBounds.left, Math.min(left, folderBounds.right - width));
            top = Math.max(folderBounds.top, Math.min(top, folderBounds.bottom - height));
        }

        int folderPivotX = width / 2 + (centeredLeft - left);
        int folderPivotY = height / 2 + (centeredTop - top);
        setPivotX(folderPivotX);
        setPivotY(folderPivotY);

        lp.width = width;
        lp.height = height;
        lp.x = left;
        lp.y = top;
    }

    protected int getContentAreaHeight() {
        DeviceProfile grid = mLauncher.getDeviceProfile();
        int maxContentAreaHeight = grid.availableHeightPx - grid.getTotalWorkspacePadding().y
                - mFooterHeight;
        int height = Math.min(maxContentAreaHeight,
                mContent.getDesiredHeight());
        return Math.max(height, MIN_CONTENT_DIMEN);
    }

    private int getContentAreaWidth() {
        return Math.max(mContent.getDesiredWidth(), MIN_CONTENT_DIMEN);
    }

    private int getFolderWidth() {
        return getPaddingLeft() + getPaddingRight() + mContent.getDesiredWidth();
    }

    private int getFolderHeight() {
        return getFolderHeight(getContentAreaHeight());
    }

    private int getFolderHeight(int contentAreaHeight) {
        return getPaddingTop() + getPaddingBottom() + contentAreaHeight + mFooterHeight;
    }

    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int contentWidth = getContentAreaWidth();
        int contentHeight = getContentAreaHeight();

        int contentAreaWidthSpec = MeasureSpec.makeMeasureSpec(contentWidth, MeasureSpec.EXACTLY);
        int contentAreaHeightSpec = MeasureSpec.makeMeasureSpec(contentHeight, MeasureSpec.EXACTLY);

        mContent.setFixedSize(contentWidth, contentHeight);
        mContent.measure(contentAreaWidthSpec, contentAreaHeightSpec);

        if (mContent.getChildCount() > 0) {
            int cellIconGap = (mContent.getPageAt(0).getCellWidth()
                    - mLauncher.getDeviceProfile().iconSizePx) / 2;
            mFooter.setPadding(mContent.getPaddingLeft() + cellIconGap,
                    mFooter.getPaddingTop(),
                    mContent.getPaddingRight() + cellIconGap,
                    mFooter.getPaddingBottom());
        }
        mFooter.measure(contentAreaWidthSpec,
                MeasureSpec.makeMeasureSpec(mFooterHeight, MeasureSpec.EXACTLY));

        int folderWidth = getPaddingLeft() + getPaddingRight() + contentWidth;
        int folderHeight = getFolderHeight(contentHeight);
        setMeasuredDimension(folderWidth, folderHeight);
    }

    /**
     * Rearranges the children based on their rank.
     */
    public void rearrangeChildren() {
        mContent.arrangeChildren(getIconsInReadingOrder());
        mItemsInvalidated = true;
    }

    public int getItemCount() {
        return mInfo.contents.size();
    }

    @Thunk void replaceFolderWithFinalItem() {
        // Add the last remaining child to the workspace in place of the folder
        Runnable onCompleteRunnable = new Runnable() {
            @Override
            public void run() {
                int itemCount = getItemCount();
                if (itemCount <= 1) {
                    View newIcon = null;
                    WorkspaceItemInfo finalItem = null;

                    if (itemCount == 1) {
                        // Move the item from the folder to the workspace, in the position of the
                        // folder
                        CellLayout cellLayout = mLauncher.getCellLayout(mInfo.container,
                                mInfo.screenId);
                        finalItem =  mInfo.contents.remove(0);
                        newIcon = mLauncher.createShortcut(cellLayout, finalItem);
                        mLauncher.getModelWriter().addOrMoveItemInDatabase(finalItem,
                                mInfo.container, mInfo.screenId, mInfo.cellX, mInfo.cellY);
                    }

                    // Remove the folder
                    mLauncher.removeItem(mFolderIcon, mInfo, true /* deleteFromDb */);
                    if (mFolderIcon instanceof DropTarget) {
                        mDragController.removeDropTarget((DropTarget) mFolderIcon);
                    }

                    if (newIcon != null) {
                        // We add the child after removing the folder to prevent both from existing
                        // at the same time in the CellLayout.  We need to add the new item with
                        // addInScreenFromBind() to ensure that hotseat items are placed correctly.
                        mLauncher.getWorkspace().addInScreenFromBind(newIcon, mInfo);

                        // Focus the newly created child
                        newIcon.requestFocus();
                    }
                    if (finalItem != null) {
                        mLauncher.folderConvertedToItem(mFolderIcon.getFolder(), finalItem);
                    }
                }
            }
        };
        View finalChild = mContent.getLastItem();
        if (finalChild != null) {
            mFolderIcon.performDestroyAnimation(onCompleteRunnable);
        } else {
            onCompleteRunnable.run();
        }
        mDestroyed = true;
    }

    public boolean isDestroyed() {
        return mDestroyed;
    }

    // This method keeps track of the first and last item in the folder for the purposes
    // of keyboard focus
    public void updateTextViewFocus() {
        final View firstChild = mContent.getFirstItem();
        final View lastChild = mContent.getLastItem();
        if (firstChild != null && lastChild != null) {
            mFolderName.setNextFocusDownId(lastChild.getId());
            mFolderName.setNextFocusRightId(lastChild.getId());
            mFolderName.setNextFocusLeftId(lastChild.getId());
            mFolderName.setNextFocusUpId(lastChild.getId());
            // Hitting TAB from the folder name wraps around to the first item on the current
            // folder page, and hitting SHIFT+TAB from that item wraps back to the folder name.
            mFolderName.setNextFocusForwardId(firstChild.getId());
            // When clicking off the folder when editing the name, this Folder gains focus. When
            // pressing an arrow key from that state, give the focus to the first item.
            this.setNextFocusDownId(firstChild.getId());
            this.setNextFocusRightId(firstChild.getId());
            this.setNextFocusLeftId(firstChild.getId());
            this.setNextFocusUpId(firstChild.getId());
            // When pressing shift+tab in the above state, give the focus to the last item.
            setOnKeyListener(new OnKeyListener() {
                @Override
                public boolean onKey(View v, int keyCode, KeyEvent event) {
                    boolean isShiftPlusTab = keyCode == KeyEvent.KEYCODE_TAB &&
                            event.hasModifiers(KeyEvent.META_SHIFT_ON);
                    if (isShiftPlusTab && Folder.this.isFocused()) {
                        return lastChild.requestFocus();
                    }
                    return false;
                }
            });
        } else {
            setOnKeyListener(null);
        }
    }

    @Override
    public void onDrop(DragObject d, DragOptions options) {
        // If the icon was dropped while the page was being scrolled, we need to compute
        // the target location again such that the icon is placed of the final page.
        if (!mContent.rankOnCurrentPage(mEmptyCellRank)) {
            // Reorder again.
            mTargetRank = getTargetRank(d, null);

            // Rearrange items immediately.
            mReorderAlarmListener.onAlarm(mReorderAlarm);

            mOnScrollHintAlarm.cancelAlarm();
            mScrollPauseAlarm.cancelAlarm();
        }
        mContent.completePendingPageChanges();

        PendingAddShortcutInfo pasi = d.dragInfo instanceof PendingAddShortcutInfo
                ? (PendingAddShortcutInfo) d.dragInfo : null;
        WorkspaceItemInfo pasiSi = pasi != null ? pasi.activityInfo.createWorkspaceItemInfo() : null;
        if (pasi != null && pasiSi == null) {
            // There is no WorkspaceItemInfo, so we have to go through a configuration activity.
            pasi.container = mInfo.id;
            pasi.rank = mEmptyCellRank;

            mLauncher.addPendingItem(pasi, pasi.container, pasi.screenId, null, pasi.spanX,
                    pasi.spanY);
            d.deferDragViewCleanupPostAnimation = false;
            mRearrangeOnClose = true;
        } else {
            final WorkspaceItemInfo si;
            if (pasiSi != null) {
                si = pasiSi;
            } else if (d.dragInfo instanceof AppInfo) {
                // Came from all apps -- make a copy.
                si = ((AppInfo) d.dragInfo).makeWorkspaceItem();
            } else {
                // WorkspaceItemInfo
                si = (WorkspaceItemInfo) d.dragInfo;
            }

            View currentDragView;
            if (mIsExternalDrag) {
                currentDragView = mContent.createAndAddViewForRank(si, mEmptyCellRank);

                // Actually move the item in the database if it was an external drag. Call this
                // before creating the view, so that WorkspaceItemInfo is updated appropriately.
                mLauncher.getModelWriter().addOrMoveItemInDatabase(
                        si, mInfo.id, 0, si.cellX, si.cellY);
                mIsExternalDrag = false;
            } else {
                currentDragView = mCurrentDragView;
                mContent.addViewForRank(currentDragView, si, mEmptyCellRank);
            }

            if (d.dragView.hasDrawn()) {
                // Temporarily reset the scale such that the animation target gets calculated
                // correctly.
                float scaleX = getScaleX();
                float scaleY = getScaleY();
                setScaleX(1.0f);
                setScaleY(1.0f);
                mLauncher.getDragLayer().animateViewIntoPosition(d.dragView, currentDragView, null);
                setScaleX(scaleX);
                setScaleY(scaleY);
            } else {
                d.deferDragViewCleanupPostAnimation = false;
                currentDragView.setVisibility(VISIBLE);
            }

            mItemsInvalidated = true;
            rearrangeChildren();

            // Temporarily suppress the listener, as we did all the work already here.
            try (SuppressInfoChanges s = new SuppressInfoChanges()) {
                mInfo.add(si, mEmptyCellRank, false);
            }

            // We only need to update the locations if it doesn't get handled in
            // #onDropCompleted.
            if (d.dragSource != this) {
                updateItemLocationsInDatabaseBatch(false);
            }
        }

        // Clear the drag info, as it is no longer being dragged.
        mDragInProgress = false;

        if (mContent.getPageCount() > 1) {
            // The animation has already been shown while opening the folder.
            mInfo.setOption(FolderInfo.FLAG_MULTI_PAGE_ANIMATION, true, mLauncher.getModelWriter());
        }

        mLauncher.getStateManager().goToState(NORMAL, SPRING_LOADED_EXIT_DELAY);
        if (d.stateAnnouncer != null) {
            d.stateAnnouncer.completeAction(R.string.item_moved);
        }
    }

    // This is used so the item doesn't immediately appear in the folder when added. In one case
    // we need to create the illusion that the item isn't added back to the folder yet, to
    // to correspond to the animation of the icon back into the folder. This is
    public void hideItem(WorkspaceItemInfo info) {
        View v = getViewForInfo(info);
        if (v != null) {
            v.setVisibility(INVISIBLE);
        }
    }
    public void showItem(WorkspaceItemInfo info) {
        View v = getViewForInfo(info);
        if (v != null) {
            v.setVisibility(VISIBLE);
        }
    }

    @Override
    public void onAdd(WorkspaceItemInfo item, int rank) {
        FolderGridOrganizer verifier = new FolderGridOrganizer(
                mLauncher.getDeviceProfile().inv).setFolderInfo(mInfo);
        verifier.updateRankAndPos(item, rank);
        mLauncher.getModelWriter().addOrMoveItemInDatabase(item, mInfo.id, 0, item.cellX,
                item.cellY);
        updateItemLocationsInDatabaseBatch(false);

        if (mContent.areViewsBound()) {
            mContent.createAndAddViewForRank(item, rank);
        }
        mItemsInvalidated = true;
    }

    public void onRemove(WorkspaceItemInfo item) {
        mItemsInvalidated = true;
        View v = getViewForInfo(item);
        mContent.removeItem(v);
        if (mState == STATE_ANIMATING) {
            mRearrangeOnClose = true;
        } else {
            rearrangeChildren();
        }
        if (getItemCount() <= 1) {
            if (mIsOpen) {
                close(true);
            } else {
                replaceFolderWithFinalItem();
            }
        }
    }

    private View getViewForInfo(final WorkspaceItemInfo item) {
        return mContent.iterateOverItems((info, view) -> info == item);
    }

    @Override
    public void onItemsChanged(boolean animate) {
        updateTextViewFocus();
    }

    /**
     * Utility methods to iterate over items of the view
     */
    public void iterateOverItems(ItemOperator op) {
        mContent.iterateOverItems(op);
    }

    /**
     * Returns the sorted list of all the icons in the folder
     */
    public ArrayList<View> getIconsInReadingOrder() {
        if (mItemsInvalidated) {
            mItemsInReadingOrder.clear();
            mContent.iterateOverItems((i, v) -> !mItemsInReadingOrder.add(v));
            mItemsInvalidated = false;
        }
        return mItemsInReadingOrder;
    }

    public List<BubbleTextView> getItemsOnPage(int page) {
        ArrayList<View> allItems = getIconsInReadingOrder();
        int lastPage = mContent.getPageCount() - 1;
        int totalItemsInFolder = allItems.size();
        int itemsPerPage = mContent.itemsPerPage();
        int numItemsOnCurrentPage = page == lastPage
                ? totalItemsInFolder - (itemsPerPage * page)
                : itemsPerPage;

        int startIndex = page * itemsPerPage;
        int endIndex = Math.min(startIndex + numItemsOnCurrentPage, allItems.size());

        List<BubbleTextView> itemsOnCurrentPage = new ArrayList<>(numItemsOnCurrentPage);
        for (int i = startIndex; i < endIndex; ++i) {
            itemsOnCurrentPage.add((BubbleTextView) allItems.get(i));
        }
        return itemsOnCurrentPage;
    }

    @Override
    public void onFocusChange(View v, boolean hasFocus) {
        if (v == mFolderName) {
            if (hasFocus) {
                startEditingFolderName();
            } else {
                logEditFolderLabel();
                mFolderName.dispatchBackKey();
            }
        }
    }

    @Override
    public void getHitRectRelativeToDragLayer(Rect outRect) {
        getHitRect(outRect);
        outRect.left -= mScrollAreaOffset;
        outRect.right += mScrollAreaOffset;
    }

    @Override
    public void fillInLogContainerData(ItemInfo childInfo, LauncherLogProto.Target child,
            ArrayList<LauncherLogProto.Target> targets) {
        child.gridX = childInfo.cellX;
        child.gridY = childInfo.cellY;
        child.pageIndex = mContent.getCurrentPage();

        LauncherLogProto.Target target = newContainerTarget(LauncherLogProto.ContainerType.FOLDER);
        target.pageIndex = mInfo.screenId;
        target.gridX = mInfo.cellX;
        target.gridY = mInfo.cellY;
        targets.add(target);

        // continue to parent
        if (mInfo.container == CONTAINER_HOTSEAT) {
            mLauncher.getHotseat().fillInLogContainerData(mInfo, target, targets);
        } else {
            mLauncher.getWorkspace().fillInLogContainerData(mInfo, target, targets);
        }
    }

    private class OnScrollHintListener implements OnAlarmListener {

        private final DragObject mDragObject;

        OnScrollHintListener(DragObject object) {
            mDragObject = object;
        }

        /**
         * Scroll hint has been shown long enough. Now scroll to appropriate page.
         */
        @Override
        public void onAlarm(Alarm alarm) {
            if (mCurrentScrollDir == SCROLL_LEFT) {
                mContent.scrollLeft();
                mScrollHintDir = SCROLL_NONE;
            } else if (mCurrentScrollDir == SCROLL_RIGHT) {
                mContent.scrollRight();
                mScrollHintDir = SCROLL_NONE;
            } else {
                // This should not happen
                return;
            }
            mCurrentScrollDir = SCROLL_NONE;

            // Pause drag event until the scrolling is finished
            mScrollPauseAlarm.setOnAlarmListener(new OnScrollFinishedListener(mDragObject));
            mScrollPauseAlarm.setAlarm(RESCROLL_DELAY);
        }
    }

    private class OnScrollFinishedListener implements OnAlarmListener {

        private final DragObject mDragObject;

        OnScrollFinishedListener(DragObject object) {
            mDragObject = object;
        }

        /**
         * Page scroll is complete.
         */
        @Override
        public void onAlarm(Alarm alarm) {
            // Reorder immediately on page change.
            onDragOver(mDragObject);
        }
    }

    // Compares item position based on rank and position giving priority to the rank.
    public static final Comparator<ItemInfo> ITEM_POS_COMPARATOR = new Comparator<ItemInfo>() {

        @Override
        public int compare(ItemInfo lhs, ItemInfo rhs) {
            if (lhs.rank != rhs.rank) {
                return lhs.rank - rhs.rank;
            } else if (lhs.cellY != rhs.cellY) {
                return lhs.cellY - rhs.cellY;
            } else {
                return lhs.cellX - rhs.cellX;
            }
        }
    };

    /**
     * Temporary resource held while we don't want to handle info changes
     */
    private class SuppressInfoChanges implements AutoCloseable {

        SuppressInfoChanges() {
            mInfo.removeListener(Folder.this);
        }

        @Override
        public void close() {
            mInfo.addListener(Folder.this);
            updateTextViewFocus();
        }
    }

    /**
     * Returns a folder which is already open or null
     */
    public static Folder getOpen(Launcher launcher) {
        return getOpenView(launcher, TYPE_FOLDER);
    }

    @Override
    public void logActionCommand(int command) {
        mLauncher.getUserEventDispatcher().logActionCommand(
                command, getFolderIcon(), getLogContainerType());
    }

    @Override
    public int getLogContainerType() {
        return LauncherLogProto.ContainerType.FOLDER;
    }

    /**
     * Navigation bar back key or hardware input back key has been issued.
     */
    @Override
    public boolean onBackPressed() {
        if (isEditingName()) {
            mFolderName.dispatchBackKey();
        } else {
            super.onBackPressed();
        }
        return true;
    }

    @Override
    public boolean onControllerInterceptTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            DragLayer dl = mLauncher.getDragLayer();

            if (isEditingName()) {
                if (!dl.isEventOverView(mFolderName, ev)) {
                    mFolderName.dispatchBackKey();
                    return true;
                }
                return false;
            } else if (!dl.isEventOverView(this, ev)) {
                if (mLauncher.getAccessibilityDelegate().isInAccessibleDrag()) {
                    // Do not close the container if in drag and drop.
                    if (!dl.isEventOverView(mLauncher.getDropTargetBar(), ev)) {
                        return true;
                    }
                } else {
                    mLauncher.getUserEventDispatcher().logActionTapOutside(
                            newContainerTarget(LauncherLogProto.ContainerType.FOLDER));
                    close(true);
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Alternative to using {@link #getClipToOutline()} as it only works with derivatives of
     * rounded rect.
     */
    @Override
    public void setClipPath(Path clipPath) {
        mClipPath = clipPath;
        invalidate();
    }

    @Override
    public void draw(Canvas canvas) {
        if (mClipPath != null) {
            int count = canvas.save();
            canvas.clipPath(mClipPath);
            super.draw(canvas);
            canvas.restoreToCount(count);
        } else {
            super.draw(canvas);
        }
    }

    public FolderPagedView getContent() {
        return mContent;
    }

    private void logEditFolderLabel() {
        LauncherEvent launcherEvent = LauncherEvent.newBuilder()
                .setAction(Action.newBuilder().setType(Action.Type.SOFT_KEYBOARD))
                .addSrcTarget(newEditTextTargetBuilder()
                        .setFromFolderLabelState(getFromFolderLabelState())
                        .setToFolderLabelState(getToFolderLabelState()))
                .addSrcTarget(newFolderTargetBuilder())
                .addSrcTarget(newParentContainerTarget())
                .build();
        mLauncher.getUserEventDispatcher().logLauncherEvent(launcherEvent);
        mPreviousLabel = mFolderName.getText().toString();
        mIsPreviousLabelSuggested = !mInfo.hasOption(FLAG_MANUAL_FOLDER_NAME);
    }

    private Target.FromFolderLabelState getFromFolderLabelState() {
        return mPreviousLabel == null
                ? FROM_FOLDER_LABEL_STATE_UNSPECIFIED
                : mPreviousLabel.isEmpty()
                ? FROM_EMPTY
                : mIsPreviousLabelSuggested
                ? FROM_SUGGESTED
                : FROM_CUSTOM;
    }

    private Target.ToFolderLabelState getToFolderLabelState() {
        String newLabel =
                checkNotNull(mFolderName.getText().toString(),
                "Expected valid folder label, but found null");
        if (newLabel.equals(mPreviousLabel)) {
            return Target.ToFolderLabelState.UNCHANGED;
        }

        if (!FeatureFlags.FOLDER_NAME_SUGGEST.get()) {
            return newLabel.isEmpty()
                ? ToFolderLabelState.TO_EMPTY_WITH_SUGGESTIONS_DISABLED
                : ToFolderLabelState.TO_CUSTOM_WITH_SUGGESTIONS_DISABLED;
        }

        Optional<String[]> suggestedLabels = getSuggestedLabels();
        boolean isEmptySuggestions = suggestedLabels
                .map(labels -> stream(labels).allMatch(TextUtils::isEmpty))
                .orElse(true);
        if (isEmptySuggestions) {
            return newLabel.isEmpty()
                ? ToFolderLabelState.TO_EMPTY_WITH_EMPTY_SUGGESTIONS
                : ToFolderLabelState.TO_CUSTOM_WITH_EMPTY_SUGGESTIONS;
        }

        boolean hasValidPrimary = suggestedLabels
                .map(labels -> !isEmpty(labels[0]))
                .orElse(false);
        if (newLabel.isEmpty()) {
            return hasValidPrimary ? ToFolderLabelState.TO_EMPTY_WITH_VALID_PRIMARY
                : ToFolderLabelState.TO_EMPTY_WITH_VALID_SUGGESTIONS_AND_EMPTY_PRIMARY;
        }

        OptionalInt accepted_suggestion_index = getAcceptedSuggestionIndex();
        if (!accepted_suggestion_index.isPresent()) {
            return hasValidPrimary ? ToFolderLabelState.TO_CUSTOM_WITH_VALID_PRIMARY
                : ToFolderLabelState.TO_CUSTOM_WITH_VALID_SUGGESTIONS_AND_EMPTY_PRIMARY;
        }

        switch (accepted_suggestion_index.getAsInt()) {
            case 0:
                return ToFolderLabelState.TO_SUGGESTION0_WITH_VALID_PRIMARY;
            case 1:
                return hasValidPrimary ? ToFolderLabelState.TO_SUGGESTION1_WITH_VALID_PRIMARY
                    : ToFolderLabelState.TO_SUGGESTION1_WITH_EMPTY_PRIMARY;
            case 2:
                return hasValidPrimary ? ToFolderLabelState.TO_SUGGESTION2_WITH_VALID_PRIMARY
                    : ToFolderLabelState.TO_SUGGESTION2_WITH_EMPTY_PRIMARY;
            case 3:
                return hasValidPrimary ? ToFolderLabelState.TO_SUGGESTION3_WITH_VALID_PRIMARY
                    : ToFolderLabelState.TO_SUGGESTION3_WITH_EMPTY_PRIMARY;
            default:
                // fall through
        }
        return ToFolderLabelState.TO_FOLDER_LABEL_STATE_UNSPECIFIED;

    }

    private Optional<String[]> getSuggestedLabels() {
        return ofNullable(mInfo)
            .map(info -> info.suggestedFolderNames)
            .map(
                folderNames ->
                    (FolderNameInfo[])
                        folderNames.getParcelableArrayExtra(FolderInfo.EXTRA_FOLDER_SUGGESTIONS))
            .map(
                folderNameInfoArray ->
                    stream(folderNameInfoArray)
                        .filter(Objects::nonNull)
                        .map(FolderNameInfo::getLabel)
                        .filter(Objects::nonNull)
                        .map(CharSequence::toString)
                        .toArray(String[]::new));
    }

    private OptionalInt getAcceptedSuggestionIndex() {
        String newLabel = checkNotNull(mFolderName.getText().toString(),
                "Expected valid folder label, but found null");
        return getSuggestedLabels()
                .map(suggestionsArray ->
                    IntStream.range(0, suggestionsArray.length)
                        .filter(
                            index -> !isEmpty(suggestionsArray[index])
                                && newLabel.equalsIgnoreCase(suggestionsArray[index]))
                        .sequential()
                        .findFirst()
                ).orElse(OptionalInt.empty());

    }


    private Target.Builder newEditTextTargetBuilder() {
        return Target.newBuilder().setType(Target.Type.ITEM).setItemType(ItemType.EDITTEXT);
    }

    private Target.Builder newFolderTargetBuilder() {
        return Target.newBuilder()
                .setType(Target.Type.CONTAINER)
                .setContainerType(ContainerType.FOLDER)
                .setPageIndex(mInfo.screenId)
                .setGridX(mInfo.cellX)
                .setGridY(mInfo.cellY)
                .setCardinality(mInfo.contents.size());
    }

    private Target.Builder newParentContainerTarget() {
        Target.Builder builder = Target.newBuilder().setType(Target.Type.CONTAINER);
        switch (mInfo.container) {
            case CONTAINER_HOTSEAT:
                return builder.setContainerType(ContainerType.HOTSEAT);
            case CONTAINER_DESKTOP:
                return builder.setContainerType(ContainerType.WORKSPACE);
            default:
                throw new AssertionError(String
                        .format("Expected container to be either %s or %s but found %s.",
                                CONTAINER_HOTSEAT,
                                CONTAINER_DESKTOP,
                                mInfo.container));
        }
    }
}
