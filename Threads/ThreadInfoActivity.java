package org.telegram.ui.threads;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Layout;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout; // THREADS (stage 10)

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationsController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.EdgeToEdgeSupportMode;
import org.telegram.ui.ActionBar.SimpleTextView;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeBind;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.UserCell;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ButtonBounce;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ProfileGooeyView;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.ScrollSlidingTextTabStrip;
import org.telegram.ui.Components.SharedMediaLayout;
import org.telegram.ui.Cells.GraySectionCell; // THREADS (stage 10)
import org.telegram.ui.Cells.SharedDocumentCell; // THREADS (stage 10)
import org.telegram.ui.Cells.SharedLinkCell; // THREADS (stage 10)
import org.telegram.ui.Components.Text;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl;
import org.telegram.ui.Components.blur3.utils.Blur3Utils;
import org.telegram.ui.ProfileActivity;
import org.telegram.utils.glass.BaseFragmentSimpleGlass;
import org.telegram.utils.glass.source.GlassSourceColor;

import java.util.ArrayList;
import java.util.List;

// THREADS (stage 5): thread info screen, modeled on the channel profile (ProfileActivity) at rest: the same hero block
// (96dp circle, title, status line), the action buttons (ProfileActionsView), a card of participants (UserCell in the
// list's sections) and the media tabs (SharedMediaLayout's tab strip and empty states). A new class: ProfileActivity
// itself is not touched. Static: no collapsing header, no photo gallery, no calls, no "Add members", no "Leave".
public class ThreadInfoActivity extends BaseFragment {

    // THREADS: ProfileActivity.getHeaderOnlyExtraHeight() with actions (152) + getActionsExtraHeight() (74)
    private static final int HEADER_ONLY_EXTRA_HEIGHT = 152;
    private static final int ACTIONS_EXTRA_HEIGHT = 74;
    // THREADS: ProfileActivity's avatar at rest: avatarScale 96 / 100 of its 100dp container
    private static final int AVATAR_SIZE = 96;
    // THREADS: ProfileActivity's avatarContainer is 100dp, scaled to the avatar's size
    private static final int AVATAR_CONTAINER_SIZE = 100;
    // THREADS: participants shown for now; the "Members N" row and the full list come later
    private static final int MAX_PARTICIPANTS = 5;

    // THREADS: SharedMediaLayout's tab ids and createEmptyStubView() types
    private static final int TAB_MEDIA = 0;
    private static final int TAB_FILES = 1;
    private static final int TAB_VOICE = 2;
    private static final int TAB_LINKS = 3;
    // THREADS: SharedMediaLayout.TABS_GLASS_INSET_DP
    private static final float TABS_GLASS_INSET_DP = 6.666f;

    private static final int VIEW_TYPE_HERO = 0;
    private static final int VIEW_TYPE_USER = 1;
    private static final int VIEW_TYPE_SHADOW = 2;
    private static final int VIEW_TYPE_MEDIA = 3;
    // THREADS (stage 6): the "Members" row (the channel profile's "Subscribers" row), with 6 people or more
    private static final int VIEW_TYPE_MEMBERS = 4;
    // THREADS: ProfileActivity's VIEW_TYPE_BOTTOM_PADDING, in place of the media when there are none
    private static final int VIEW_TYPE_BOTTOM_PADDING = 5;

    private final long dialogId;
    private final MessageObject parentMessage;
    private final int parentMessageId;
    private final ArrayList<Long> participants = new ArrayList<>();

    private FrameLayout root;
    private RecyclerListView listView;
    // THREADS: the profile's header (ProfileActivity: topView + avatarContainer2), fixed over the list; the list's
    // first row is an empty spacer of its height, and the header follows that row (extraHeight) as the list scrolls.
    private HeaderView headerView;
    // THREADS: ProfileActivity.headerBlurView: the progressive blur of the simple screens under the collapsed header
    private View headerBlurView;
    private float extraHeight;
    private float headerRowsProgress = 1f;

    private int rowCount;
    private int heroRow;
    private int usersStartRow;
    private int usersEndRow;
    private int shadowRow;
    private int membersRow = -1; // THREADS (stage 6)
    private int mediaRow;
    private int bottomPaddingRow = -1; // THREADS
    // THREADS: SharedMediaLayout.hasMedia, the count of each tab's content (by TAB_MEDIA .. TAB_LINKS)
    private final int[] hasMedia = new int[4];
    // THREADS (stage 10): the Files and Links tabs' content, newest first
    private final ArrayList<MessageObject> sharedFiles = new ArrayList<>();
    private final ArrayList<MessageObject> sharedLinks = new ArrayList<>();

    public ThreadInfoActivity(long dialogId, MessageObject parentMessage) {
        this.dialogId = dialogId;
        this.parentMessage = parentMessage;
        this.parentMessageId = parentMessage.getId();
    }

    @Override
    public boolean onFragmentCreate() {
        participants.addAll(ThreadsStore.getParticipants(currentAccount, dialogId, parentMessageId));
        countMedia(); // THREADS
        updateRows();
        return super.onFragmentCreate();
    }

    // THREADS: same edge-to-edge mode as the profile: the hero goes under the status bar.
    @Override
    public EdgeToEdgeSupportMode getEdgeToEdgeSupportMode() {
        return EdgeToEdgeSupportMode.FULL;
    }

    @Override
    public boolean drawEdgeNavigationBar() {
        return false;
    }

    private void updateRows() {
        rowCount = 0;
        heroRow = rowCount++;
        // THREADS (stage 6): up to 5 people in the card; with more, one "Members N" row opens the full list
        membersRow = -1;
        final int shown = participants.size() > MAX_PARTICIPANTS ? 0 : participants.size();
        if (participants.size() > MAX_PARTICIPANTS) {
            membersRow = rowCount++;
        }
        usersStartRow = rowCount;
        rowCount += shown;
        usersEndRow = rowCount;
        shadowRow = rowCount++;
        // THREADS: ProfileActivity.updateRowsIds(): the shared media only if something is in it, otherwise a bottom
        // padding row
        mediaRow = -1;
        bottomPaddingRow = -1;
        if (hasAnyMedia()) {
            mediaRow = rowCount++;
        } else {
            bottomPaddingRow = rowCount++;
        }
    }

    // THREADS: the content of each tab. Thread replies are local text only (ThreadsStore.Reply has no media, files,
    // voice; links in the text are not indexed), so for a real chat every count is 0 and the block is hidden.
    private void countMedia() {
        java.util.Arrays.fill(hasMedia, 0);
        sharedFiles.clear();
        sharedLinks.clear();
        hasMedia[TAB_FILES] = sharedFiles.size();
        hasMedia[TAB_LINKS] = sharedLinks.size();
    }

    // THREADS: ProfileActivity: hasMedia when any of the counts is above 0
    private boolean hasAnyMedia() {
        for (int count : hasMedia) {
            if (count > 0) {
                return true;
            }
        }
        return false;
    }

    private int getNewTop() {
        return (actionBar.getOccupyStatusBar() ? AndroidUtilities.statusBarHeight : 0) + ActionBar.getCurrentActionBarHeight();
    }

    @Override
    public View createView(Context context) {
        final Theme.ResourcesProvider resourcesProvider = getResourceProvider();
        root = new FrameLayout(context);
        fragmentView = root;
        ThemeBind.backgroundColor(root, Theme.key_windowBackgroundGray, resourcesProvider);

        final BaseFragmentSimpleGlass simpleGlass = getBaseSimpleGlass();

        listView = new RecyclerListView(context, resourcesProvider);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setItemAnimator(null);
        listView.setVerticalScrollBarEnabled(false);
        // THREADS: as the profile's list: rows between shadow rows are drawn as rounded cards
        listView.setLegacySections(false);
        listView.setSections();
        listView.applyPaddingToSections = false;
        ThemeBind.backgroundColor(listView, Theme.key_windowBackgroundGray, resourcesProvider);
        listView.setAdapter(new Adapter(context));
        listView.setOnItemClickListener((view, position) -> {
            // THREADS: as the profile's members: a tap opens the person's profile
            if (position == membersRow) {
                // THREADS (stage 6): as the profile's "Subscribers" row opens ChatUsersActivity
                presentFragment(new ThreadMembersActivity(dialogId, parentMessageId));
                return;
            }
            if (position >= usersStartRow && position < usersEndRow) {
                final Bundle args = new Bundle();
                args.putLong("user_id", participants.get(position - usersStartRow));
                presentFragment(new ProfileActivity(args));
            }
        });
        root.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        listView.setOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                checkListViewScroll();
            }

            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    settleHeader();
                }
            }
        });
        listView.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> checkListViewScroll());

        // THREADS: over the rows, under the name and the buttons (as in the profile)
        headerBlurView = new View(context);
        root.addView(headerBlurView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 0, Gravity.TOP));

        // THREADS: BaseFragment's navigation bar fade of the simple screens: the rows go under it at the bottom
        navigationBarFadeView = new View(context);
        root.addView(navigationBarFadeView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 0, Gravity.BOTTOM));

        headerView = new HeaderView(context);
        root.addView(headerView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP));

        // THREADS: glass of the header's back button: the capture is the list (as in ThreadActivity)
        simpleGlass.setViews(root, listView, null, resourcesProvider);
        simpleGlass.setCapture((canvas, position) -> Blur3Utils.captureRelativeParent(listView, canvas, position, listView, root));
        headerBlurView.setBackground(simpleGlass.createActionBarFadeDrawable(headerBlurView));
        headerBlurView.setAlpha(0);
        navigationBarFadeView.setBackground(simpleGlass.createNavigationBarFadeDrawable(navigationBarFadeView)); // THREADS
        applyNavigationBarInset(); // THREADS

        // THREADS: header: the round glass back button only, over the hero (as the profile's header buttons)
        actionBar.setAddToContainer(false);
        actionBar.setCastShadows(false);
        actionBar.setBackground(null);
        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });
        actionBar.setupGlass(simpleGlass.factory, BlurredBackgroundProviderImpl.simpleActionBar(resourcesProvider));
        // THREADS: no title in the bar (the profile's header has none at rest): no center glass pill
        actionBar.doNotDrawCenterGlass = true;
        root.addView(actionBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        // THREADS: ProfileActivity.applyPagesInsets(): the blur is the header row plus 44dp
        headerBlurView.getLayoutParams().height = getNewTop() + dp(44);
        extraHeight = dp(HEADER_ONLY_EXTRA_HEIGHT + ACTIONS_EXTRA_HEIGHT);
        return fragmentView;
    }

    // THREADS: BaseFragment.onInsetsInternal(): the fade is the navigation bar's height, hidden under 40dp (gestures)
    @Override
    public void onInsets(int left, int top, int right, int bottom) {
        super.onInsets(left, top, right, bottom);
        applyNavigationBarInset();
    }

    private void applyNavigationBarInset() {
        if (navigationBarFadeView == null || navigationBarFadeView.getLayoutParams() == null) {
            return;
        }
        navigationBarFadeView.getLayoutParams().height = mSystemInsets.bottom;
        navigationBarFadeView.setVisibility(mSystemInsets.bottom >= dp(40) ? View.VISIBLE : View.GONE);
        navigationBarFadeView.requestLayout();
    }

    // THREADS: ProfileActivity.checkListViewScroll(): extraHeight is how much of the header row is still open (the
    // spacer row's bottom below the header bar), headerRowsProgress how far the rows have gone under the bar.
    private void checkListViewScroll() {
        if (listView == null || headerView == null) {
            return;
        }
        View spacer = null;
        for (int i = 0; i < listView.getChildCount(); i++) {
            final View child = listView.getChildAt(i);
            if (listView.getChildAdapterPosition(child) == heroRow) {
                spacer = child;
                break;
            }
        }
        final float open = spacer == null ? 0 : spacer.getBottom() - getNewTop();
        // THREADS: no pull-down: the header never opens past its full height (the list's own overscroll stays)
        extraHeight = Math.min(getHeaderExtraHeight(), Math.max(0, open));
        listView.setTopGlowOffset((int) extraHeight); // THREADS: ProfileActivity: the glow starts under the header
        headerRowsProgress = spacer == null ? 1f : Utilities.clamp01(-open / (float) dp(12));
        headerView.update();
    }

    private float getHeaderExtraHeight() {
        return dp(HEADER_ONLY_EXTRA_HEIGHT + ACTIONS_EXTRA_HEIGHT);
    }

    // THREADS: ProfileActivity.calculateHeaderExtraDiff(): 1 with the whole header open, 0 with only the buttons
    private float headerDiff() {
        return Utilities.clamp01((extraHeight - dp(ACTIONS_EXTRA_HEIGHT)) / dp(HEADER_ONLY_EXTRA_HEIGHT));
    }

    // THREADS: ProfileActivity.getHeaderFadeProgress(): the header's color fades out as the buttons go under the bar
    private float headerFadeProgress() {
        return Utilities.clamp01(1f - extraHeight / dp(ACTIONS_EXTRA_HEIGHT));
    }

    // THREADS: ProfileActivity.settleHeader() without pulling down: from anywhere in between to all of the header,
    // the buttons only, or none of it.
    private void settleHeader() {
        final float actions = dp(ACTIONS_EXTRA_HEIGHT);
        if (extraHeight > 0 && extraHeight < getHeaderExtraHeight() * 0.6f && extraHeight > actions * .6f) {
            listView.smoothScrollBy(0, (int) (extraHeight - actions), CubicBezierInterpolator.EASE_OUT_QUINT);
        } else if (extraHeight > 0 && extraHeight < actions * .6f) {
            listView.smoothScrollBy(0, (int) extraHeight, CubicBezierInterpolator.EASE_OUT_QUINT);
        } else if (extraHeight > 0 && extraHeight < getHeaderExtraHeight()) {
            listView.smoothScrollBy(0, (int) (extraHeight - getHeaderExtraHeight()), CubicBezierInterpolator.EASE_OUT_QUINT);
        }
    }

    // THREADS: "Go to message": closes the thread and its info, and the chat scrolls to the parent message.
    private void goToMessage() {
        if (parentLayout == null) {
            return;
        }
        final List<BaseFragment> stack = parentLayout.getFragmentStack();
        final int self = stack.indexOf(this);
        ThreadActivity thread = null;
        ChatActivity chat = null;
        for (int i = self - 1; i >= 0; i--) {
            final BaseFragment fragment = stack.get(i);
            if (thread == null && fragment instanceof ThreadActivity) {
                thread = (ThreadActivity) fragment;
            } else if (thread != null && fragment instanceof ChatActivity && ((ChatActivity) fragment).getDialogId() == dialogId) {
                chat = (ChatActivity) fragment;
                break;
            }
        }
        if (chat != null) {
            chat.scrollToMessageId(parentMessageId, 0, true, 0, true, 0);
        }
        if (thread != null) {
            thread.removeSelfFromStack();
        }
        finishFragment();
    }

    // THREADS: "Mute": a local switch, stored on this device (ThreadsStore), nothing is sent. The same bulletin as
    // muting a chat.
    private void toggleMute() {
        final boolean muted = !ThreadsStore.isMuted(currentAccount, dialogId, parentMessageId);
        ThreadsStore.setMuted(currentAccount, dialogId, parentMessageId, muted);
        if (headerView != null) {
            headerView.actionsView.setMuted(muted);
        }
        BulletinFactory.createMuteBulletin(this, muted ? NotificationsController.SETTING_MUTE_FOREVER : NotificationsController.SETTING_MUTE_UNMUTE, 0, getResourceProvider()).show();
    }

    private CharSequence statusText() {
        return LocaleController.formatPluralString("Participants", participants.size());
    }

    // THREADS: the profile's header (ProfileActivity.needLayout() / refreshNameAndOnlineYBasedOnExpand(), without
    // pulling down): with diff = 1 (open) the 96dp avatar has its top at
    // newTop - 21dp, the name (17.5dp x 1.12) 8 + 1.3 + 7dp under it, the status 24 + 11dp under it, the buttons in the
    // last 74dp. As the list scrolls the avatar shrinks, blurs and goes into the camera cutout (gooey view), the name to 1.0 in the bar, then
    // the buttons go under the bar and the header's color fades into the simple screens' blur.
    private class HeaderView extends FrameLayout {

        private final AvatarDrawable avatarDrawable = new AvatarDrawable();
        private final SimpleTextView nameTextView;
        private final SimpleTextView onlineTextView;
        final ActionsView actionsView;
        private float avatarY;
        private float avatarSize;
        // THREADS: ProfileActivity's avatarGooey: the 100dp circle (pivot top left) inside ProfileGooeyView, which blurs
        // it progressively and pulls it into the camera cutout as it hides (setHideProgress(pullUpProgress)), as in a
        // group's profile. Real blur: RenderEffect on Android 12+ (average/high devices), a stack-blurred bitmap below.
        private final ProfileGooeyView avatarGooey;
        private final View avatarView;

        HeaderView(Context context) {
            super(context);
            setWillNotDraw(false);
            final Theme.ResourcesProvider resourcesProvider = getResourceProvider();

            // THREADS: the thread's circle: the gradient of the Replies avatar with the Thread icon (ThreadsConfig)
            avatarDrawable.setAvatarType(AvatarDrawable.AVATAR_TYPE_REPLIES);
            // THREADS (stage 9a): a flat fill in the Thread accent instead of the Replies gradient (blue)
            avatarDrawable.setColor(ThreadsConfig.THREAD_ACCENT_COLOR);
            final Drawable icon = ContextCompat.getDrawable(context, ThreadsConfig.THREAD_ICON_FILLED).mutate(); // THREADS: filled icon
            icon.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_avatar_text, resourcesProvider), PorterDuff.Mode.SRC_IN));
            avatarDrawable.setCustomIcon(icon);
            // THREADS: ProfileActivity: avatarDrawable.setScaleSize(100 / 42) in a 100dp container drawn at 0.96
            avatarDrawable.setScaleSize(AVATAR_CONTAINER_SIZE / 42f); // THREADS: drawn in the 100dp container

            // THREADS: ProfileActivity: avatarGooey.addView(avatarContainer, 100 x 100, TOP | LEFT), pivot 0, 0
            avatarGooey = new ProfileGooeyView(context);
            avatarView = new View(context) {
                @Override
                protected void onDraw(@NonNull Canvas canvas) {
                    avatarDrawable.setBounds(0, 0, getMeasuredWidth(), getMeasuredHeight());
                    avatarDrawable.draw(canvas);
                }
            };
            avatarView.setPivotX(0);
            avatarView.setPivotY(0);
            avatarGooey.addView(avatarView, LayoutHelper.createFrame(AVATAR_CONTAINER_SIZE, AVATAR_CONTAINER_SIZE, Gravity.TOP | Gravity.LEFT));
            addView(avatarGooey, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

            // THREADS: ProfileActivity's nameTextView (17.5dp bold, scaled 1.0 .. 1.12, centered)
            nameTextView = new SimpleTextView(context);
            nameTextView.setTextSizePx(dp(17.5f));
            nameTextView.setTypeface(AndroidUtilities.bold());
            nameTextView.setGravity(Gravity.CENTER_HORIZONTAL);
            nameTextView.setEllipsizeByGradient(true);
            nameTextView.setText(LocaleController.getString(R.string.ThreadsButton));
            addView(nameTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 18, 0, 18, 0));

            // THREADS: ProfileActivity's onlineTextView (13.5dp)
            onlineTextView = new SimpleTextView(context);
            onlineTextView.setTextSizePx(dp(13.5f));
            onlineTextView.setGravity(Gravity.CENTER_HORIZONTAL);
            onlineTextView.setEllipsizeByGradient(true);
            onlineTextView.setText(statusText());
            addView(onlineTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 18, 0, 18, 0));

            actionsView = new ActionsView(context);
            actionsView.setMuted(ThreadsStore.isMuted(currentAccount, dialogId, parentMessageId));
            addView(actionsView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, ACTIONS_EXTRA_HEIGHT, Gravity.TOP));
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            super.onLayout(changed, left, top, right, bottom);
            update();
        }

        void update() {
            final Theme.ResourcesProvider resourcesProvider = getResourceProvider();
            final float diff = headerDiff();
            final float fade = headerFadeProgress();
            final int statusBar = actionBar.getOccupyStatusBar() ? AndroidUtilities.statusBarHeight : 0;
            final int newTop = getNewTop();

            // THREADS: avatarScale = lerp(24, 96, diff) / 100 of the 100dp container; avatarY = lerp(-29dp, startY, diff)
            // THREADS: ProfileActivity.needLayout(): it shrinks into the camera cutout's size and goes to its center
            // (24dp and -29dp without a round cutout), and the gooey view blurs it with pullUpProgress = 1 - diff
            final float intoSize = avatarGooey.getHiddenAvatarSizeDp();
            avatarSize = dp(AndroidUtilities.lerp(intoSize, AVATAR_SIZE, diff));
            final float startY = newTop - dp(21);
            final float endY = avatarGooey.getHiddenAvatarCenterY(-dp(29));
            avatarY = (float) Math.ceil(AndroidUtilities.lerp(endY, startY, diff));
            final float avatarScale = AndroidUtilities.lerp(intoSize, AVATAR_SIZE, diff) / AVATAR_CONTAINER_SIZE;
            avatarView.setScaleX(avatarScale);
            avatarView.setScaleY(avatarScale);
            avatarView.setTranslationX((getMeasuredWidth() - dp(AVATAR_CONTAINER_SIZE) * avatarScale) / 2f);
            avatarView.setTranslationY(avatarY);
            avatarGooey.setHideProgress(1f - diff);

            final float endNameY = statusBar + ActionBar.getCurrentActionBarHeight() / 2f * (1f + diff) - 21 * AndroidUtilities.density;
            final float avatarBottom = (float) Math.floor(endNameY) + (avatarSize + dpf2(8)) * diff;
            final float nameScale = 1.0f + 0.12f * diff;
            nameTextView.setPivotX(nameTextView.getMeasuredWidth() / 2f);
            nameTextView.setPivotY(0);
            nameTextView.setScaleX(nameScale);
            nameTextView.setScaleY(nameScale);
            nameTextView.setTranslationY(avatarBottom + dp(1.3f) + dp(7) * diff);
            onlineTextView.setTranslationY(avatarBottom + dp(24) + (float) Math.floor(11 * AndroidUtilities.density) * diff);
            // THREADS: ProfileActivity: name and status go over to the simple screens' colors with the header's fade
            nameTextView.setTextColor(ColorUtils.blendARGB(Theme.getColor(Theme.key_profile_title, resourcesProvider), Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider), fade));
            onlineTextView.setTextColor(ColorUtils.blendARGB(Theme.getColor(Theme.key_actionBarDefaultSubtitle, resourcesProvider), Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider), fade));

            // THREADS: ProfileActivity.updateActionsPosition(): the buttons in the last 74dp of the open header
            final float bottom = extraHeight + newTop;
            final float height = Math.min(dp(ACTIONS_EXTRA_HEIGHT), bottom - newTop);
            actionsView.setTranslationY(bottom - height);
            actionsView.setCurrentHeight(height);

            // THREADS: ProfileActivity.updateHeaderBlurViews()
            if (headerBlurView != null) {
                final float alpha = Math.min(fade, headerRowsProgress);
                headerBlurView.setAlpha(alpha);
                headerBlurView.setVisibility(alpha > 0 ? View.VISIBLE : View.GONE);
            }
            invalidate();
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            // THREADS: ProfileActivity.TopView.onDraw(): the header's color down to the rows, fading with the buttons
            final float fade = headerFadeProgress();
            if (fade < 1f) {
                final int color = Theme.getColor(Theme.key_windowBackgroundGray, getResourceProvider());
                final Paint paint = Theme.dialogs_onlineCirclePaint;
                final int was = paint.getColor();
                paint.setColor(Theme.multAlpha(color, 1f - fade));
                canvas.drawRect(0, 0, getMeasuredWidth(), getNewTop() + extraHeight, paint);
                paint.setColor(was);
            }
            // THREADS: the circle is drawn by avatarGooey (a child), over this background
        }

        // THREADS: the gooey view learns the camera cutout when it gets its size: lay the circle out again then
        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            post(this::update);
        }
    }

    // THREADS: the profile's action buttons (ProfileActionsView), two of them: "Go to message" and "Mute"/"Unmute".
    // A copy of its drawing, because its buttons are a fixed set: 14dp sides, 7dp between, 8dp top, 12dp bottom,
    // 16dp radius, 24dp icon, 11dp bold text; filled with the button color of a header without a peer color
    // (ProfileActivity.TopView.setBackgroundColorId), white filled icons over a dark button.
    private class ActionsView extends View {

        private final float xpadding = dpf2(14);
        private final float ypadding = dpf2(12);
        private final float top = dpf2(8);
        private final int targetHeight = (int) (dp(ACTIONS_EXTRA_HEIGHT) - ypadding - top);
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Button[] buttons = new Button[2];
        private Button pressed;
        private float currentHeight = dp(ACTIONS_EXTRA_HEIGHT);

        // THREADS: ProfileActionsView.updatePosition(): how much of the 74dp is open
        void setCurrentHeight(float height) {
            if (currentHeight != height) {
                currentHeight = height;
                invalidate();
            }
        }

        private class Button {
            final RectF rect = new RectF();
            final ButtonBounce bounce = new ButtonBounce(ActionsView.this);
            Drawable filled;
            Drawable outline;
            Text text;
            Runnable onClick;

            void set(int titleRes, int filledRes, int outlineRes) {
                filled = ContextCompat.getDrawable(getContext(), filledRes).mutate();
                outline = ContextCompat.getDrawable(getContext(), outlineRes).mutate();
                text = new Text(LocaleController.getString(titleRes), 11, AndroidUtilities.bold())
                    .multiline(3)
                    .align(Layout.Alignment.ALIGN_CENTER);
                invalidate();
            }
        }

        ActionsView(Context context) {
            super(context);
            buttons[0] = new Button();
            // THREADS: the profile's "Message" icons
            buttons[0].set(R.string.ThreadsGoToMessage, R.drawable.filled_profile_message_24, R.drawable.outline_profile_message_24);
            buttons[0].onClick = ThreadInfoActivity.this::goToMessage;
            buttons[1] = new Button();
            buttons[1].onClick = ThreadInfoActivity.this::toggleMute;
        }

        // THREADS: ProfileActionsView.ActionButton.NOTIFICATION_MUTE / NOTIFICATION_UNMUTE
        void setMuted(boolean muted) {
            if (muted) {
                buttons[1].set(R.string.ProfileButtonUnmute, R.drawable.filled_profile_unmute_24, R.drawable.outline_profile_unmute_24);
            } else {
                buttons[1].set(R.string.ProfileButtonMute, R.drawable.filled_profile_mute_24, R.drawable.outline_profile_mute_24);
            }
        }

        // THREADS: ProfileActivity.TopView.setBackgroundColorId(null): the button color by the header's brightness
        private int buttonColor() {
            final int actionBar = Theme.getColor(Theme.key_actionBarDefault, getResourceProvider());
            final double brightness = AndroidUtilities.computePerceivedBrightness(actionBar);
            if (brightness > .8f) {
                return Color.WHITE;
            } else if (brightness < .2f) {
                return Theme.multAlpha(Theme.adaptHSV(actionBar, +0.02f, +0.25f), .35f);
            }
            return Theme.multAlpha(org.telegram.ui.PeerColorActivity.adaptProfileEmojiColor(actionBar), .15f);
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            final int color = buttonColor();
            paint.setColor(color);
            // THREADS: ProfileActionsView.drawAction(): black icons and text on a light button, white on a dark one
            final boolean light = AndroidUtilities.computePerceivedBrightness(color) > 0.72f;
            final int contentColor = light ? Color.BLACK : Color.WHITE;
            final PorterDuffColorFilter filter = new PorterDuffColorFilter(contentColor, PorterDuff.Mode.SRC_IN);

            // THREADS: ProfileActionsView.onDraw(): the buttons get shorter with the open height, fade out below 20%,
            // their content scales with it and fades out below 40%
            final float height = Math.max(0f, currentHeight - ypadding - top);
            final float fraction = Utilities.clamp01(height / targetHeight);
            final float alphaFraction1 = Utilities.clamp01((fraction - 0.2f) / 0.8f);
            final float alphaFraction2 = Utilities.clamp01((fraction - 0.4f) / 0.6f);
            for (Button button : buttons) {
                button.rect.setEmpty();
            }
            if (height <= 0f || alphaFraction1 <= 0f) {
                return;
            }
            final int wasAlpha = paint.getAlpha();
            paint.setAlpha((int) (wasAlpha * alphaFraction1));

            final float between = xpadding / 2f;
            final float width = (getMeasuredWidth() - between * (buttons.length - 1) - xpadding * 2f) / buttons.length;
            final float r = dp(16);
            float left = xpadding;
            for (Button button : buttons) {
                button.rect.set(left, top, left + width, top + height);
                left += width + between;

                canvas.save();
                final float scale = button.bounce.getScale(0.04f);
                canvas.scale(scale, scale, button.rect.centerX(), button.rect.centerY());
                canvas.drawRoundRect(button.rect, r, r, paint);
                if (alphaFraction2 <= 0f) {
                    canvas.restore();
                    continue;
                }
                canvas.scale(fraction, fraction, button.rect.centerX(), button.rect.centerY());
                canvas.clipRect(button.rect);

                // THREADS: ProfileActionsView.updateBounds() and drawAction()
                final float cx = button.rect.centerX();
                final int drawableSize = dp(24);
                button.text.setMaxWidth(button.rect.width() - dp(2));
                final float textScale = button.text.getLineCount() >= 3 ? 0.75f : button.text.getLineCount() >= 2 ? 0.85f : 1.0f;
                final float drawableTop = Math.max(0, (targetHeight - button.text.getHeight() * textScale) / 3f + dpf2(1.33f));
                final float textY = drawableTop + drawableTop + drawableSize - button.text.getHeight() * textScale / 2.0f - dp(4.66f);
                canvas.save();
                canvas.scale(textScale, textScale, cx, textY + button.text.getHeight() * textScale / 2.0f);
                button.text.draw(canvas, cx - button.text.getWidth() / 2f, textY, contentColor, alphaFraction2);
                canvas.restore();
                final Drawable icon = light ? button.outline : button.filled;
                icon.setColorFilter(filter);
                icon.setAlpha((int) (0xFF * alphaFraction2));
                icon.setBounds((int) (cx - drawableSize / 2f), (int) drawableTop, (int) (cx + drawableSize / 2f), (int) drawableTop + drawableSize);
                icon.draw(canvas);
                canvas.restore();
            }
            paint.setAlpha(wasAlpha);
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent event) {
            final float x = event.getX(), y = event.getY();
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    pressed = null;
                    for (Button button : buttons) {
                        if (button.rect.contains(x, y)) {
                            pressed = button;
                            button.bounce.setPressed(true);
                        }
                    }
                    return pressed != null;
                case MotionEvent.ACTION_MOVE:
                    if (pressed != null && !pressed.rect.contains(x, y)) {
                        pressed.bounce.setPressed(false);
                        pressed = null;
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (pressed != null) {
                        pressed.bounce.setPressed(false);
                        if (event.getAction() == MotionEvent.ACTION_UP && pressed.rect.contains(x, y)) {
                            pressed.onClick.run();
                        }
                        pressed = null;
                    }
                    return true;
            }
            return super.onTouchEvent(event);
        }
    }

    // THREADS: the profile's media section: SharedMediaLayout's centered pill tab strip (minimal width, glass of
    // radius 18 over the white of its pages) and its empty states. Local replies have no media, so every tab is empty.
    private class MediaView extends FrameLayout {

        private final ScrollSlidingTextTabStrip tabs;
        private final FrameLayout pages;

        MediaView(Context context) {
            super(context);
            setTag(RecyclerListView.TAG_NOT_SECTION);
            final Theme.ResourcesProvider resourcesProvider = getResourceProvider();
            ThemeBind.backgroundColor(this, Theme.key_windowBackgroundWhite, resourcesProvider);

            pages = new FrameLayout(context);
            addView(pages, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP, 0, 50, 0, 0));

            tabs = new ScrollSlidingTextTabStrip(context, resourcesProvider);
            tabs.animationDuration = 320;
            tabs.setColors(Theme.key_profile_tabSelectedLine, Theme.key_profile_tabSelectedText, Theme.key_profile_tabText, Theme.key_profile_tabSelector);
            tabs.setUseMinimalWidth(true);
            // THREADS: SharedMediaLayout without a custom glass: a color source of windowBackgroundWhite
            final GlassSourceColor source = new GlassSourceColor();
            source.setColor(Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider));
            final BlurredBackgroundDrawable glass = new BlurredBackgroundDrawableViewFactory(source)
                .create(tabs, BlurredBackgroundProviderImpl.simpleActionBar(resourcesProvider));
            glass.setRadius(dp(18));
            glass.setPadding(dp(TABS_GLASS_INSET_DP));
            tabs.setPadding(0, dp(7), 0, dp(7));
            tabs.setClipToPadding(false);
            tabs.setBackground(null);
            tabs.setBlurredBackground(glass);
            tabs.setOpen(false);
            // THREADS: SharedMediaLayout.updateTabs(): only the tabs with something in them, in its order; "Media"
            // gets its full name when it is the only one
            int firstTab = -1;
            if (hasMedia[TAB_MEDIA] > 0) {
                final boolean only = hasMedia[TAB_FILES] == 0 && hasMedia[TAB_VOICE] == 0 && hasMedia[TAB_LINKS] == 0;
                tabs.addTextTab(TAB_MEDIA, LocaleController.getString(only ? R.string.SharedMediaTabFull2 : R.string.SharedMediaTab2));
                firstTab = TAB_MEDIA;
            }
            if (hasMedia[TAB_FILES] > 0) {
                tabs.addTextTab(TAB_FILES, LocaleController.getString(R.string.SharedFilesTab2));
                if (firstTab == -1) firstTab = TAB_FILES;
            }
            if (hasMedia[TAB_LINKS] > 0) {
                tabs.addTextTab(TAB_LINKS, LocaleController.getString(R.string.SharedLinksTab2));
                if (firstTab == -1) firstTab = TAB_LINKS;
            }
            if (hasMedia[TAB_VOICE] > 0) {
                tabs.addTextTab(TAB_VOICE, LocaleController.getString(R.string.SharedVoiceTab2));
                if (firstTab == -1) firstTab = TAB_VOICE;
            }
            tabs.finishAddingTabs();
            tabs.setDelegate(new ScrollSlidingTextTabStrip.ScrollSlidingTabStripDelegate() {
                @Override
                public void onPageSelected(int page, boolean forward) {
                    showPage(page);
                }

                @Override
                public void onPageScrolled(float progress) {
                }
            });
            addView(tabs, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 50, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 6 - TABS_GLASS_INSET_DP, 0, 6 - TABS_GLASS_INSET_DP, 0));
            if (firstTab != -1) {
                showPage(firstTab); // THREADS: the first tab shown
            }
        }

        private void showPage(int type) {
            pages.removeAllViews();
            // THREADS (stage 10): files and links in SharedMediaLayout's own cells, as its Files / Links pages bind them
            final ArrayList<MessageObject> items = type == TAB_FILES ? sharedFiles : type == TAB_LINKS ? sharedLinks : null;
            if (items != null && !items.isEmpty()) {
                pages.addView(type == TAB_FILES ? filesPage(items) : linksPage(items),
                    LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
                return;
            }
            pages.addView(SharedMediaLayout.createEmptyStubView(getContext(), type, dialogId, getResourceProvider()),
                LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        }

        // THREADS (stage 10): SharedMediaLayout's Files page: one SharedDocumentCell (VIEW_TYPE_DEFAULT) per file, a
        // divider under every one but the last
        private View filesPage(ArrayList<MessageObject> items) {
            final LinearLayout page = new LinearLayout(getContext());
            page.setOrientation(LinearLayout.VERTICAL);
            for (int i = 0; i < items.size(); i++) {
                final SharedDocumentCell cell = new SharedDocumentCell(getContext(), SharedDocumentCell.VIEW_TYPE_DEFAULT, getResourceProvider());
                cell.setDocument(items.get(i), i != items.size() - 1);
                page.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            }
            return page;
        }

        // THREADS (stage 10): SharedMediaLayout's Links page: sections by month (formatSectionDate); the first one has
        // no header, every later one starts with a GraySectionCell(28); one SharedLinkCell (VIEW_TYPE_DEFAULT) per link,
        // a divider under every one but the last of its section
        private View linksPage(ArrayList<MessageObject> items) {
            final LinearLayout page = new LinearLayout(getContext());
            page.setOrientation(LinearLayout.VERTICAL);
            String section = null;
            for (int i = 0; i < items.size(); i++) {
                final MessageObject item = items.get(i);
                final String name = LocaleController.formatSectionDate(item.messageOwner.date);
                if (section != null && !section.equals(name)) {
                    final GraySectionCell header = new GraySectionCell(getContext(), 28, getResourceProvider());
                    header.setText(name);
                    page.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
                }
                section = name;
                final boolean lastInSection = i == items.size() - 1 || !name.equals(LocaleController.formatSectionDate(items.get(i + 1).messageOwner.date));
                final SharedLinkCell cell = new SharedLinkCell(getContext(), SharedLinkCell.VIEW_TYPE_DEFAULT, getResourceProvider());
                cell.setLink(item, !lastInSection);
                page.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            }
            return page;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            // THREADS: as the profile's media: one screen high under the header
            final int height = listView != null ? listView.getMeasuredHeight() - getNewTop() : 0;
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(Math.max(height, dp(300)), MeasureSpec.EXACTLY));
        }
    }

    private class Adapter extends RecyclerListView.SelectionAdapter {

        private final Context context;

        Adapter(Context context) {
            this.context = context;
        }

        @Override
        public int getItemCount() {
            return rowCount;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == heroRow) {
                return VIEW_TYPE_HERO;
            } else if (position >= usersStartRow && position < usersEndRow) {
                return VIEW_TYPE_USER;
            } else if (position == membersRow) {
                return VIEW_TYPE_MEMBERS; // THREADS (stage 6)
            } else if (position == shadowRow) {
                return VIEW_TYPE_SHADOW;
            } else if (position == bottomPaddingRow) {
                return VIEW_TYPE_BOTTOM_PADDING; // THREADS
            }
            return VIEW_TYPE_MEDIA;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() == VIEW_TYPE_USER || holder.getItemViewType() == VIEW_TYPE_MEMBERS; // THREADS
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            final View view;
            switch (viewType) {
                case VIEW_TYPE_HERO:
                    // THREADS: empty, the height of the open header (ProfileActivity's list padding): the header itself
                    // is HeaderView, over the list
                    view = new View(context) {
                        @Override
                        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), getNewTop() + (int) getHeaderExtraHeight());
                        }
                    };
                    view.setTag(RecyclerListView.TAG_NOT_SECTION);
                    break;
                case VIEW_TYPE_USER:
                    // THREADS: the profile's member row (no "Add members" row: padding 9)
                    view = new UserCell(context, 9, 0, true, getResourceProvider());
                    break;
                case VIEW_TYPE_SHADOW:
                    view = new ShadowSectionCell(context, getResourceProvider());
                    break;
                case VIEW_TYPE_MEMBERS:
                    // THREADS (stage 6): ProfileActivity's VIEW_TYPE_TEXT row
                    view = new TextCell(context, 18, false, false, getResourceProvider());
                    break;
                case VIEW_TYPE_BOTTOM_PADDING:
                    // THREADS: ProfileActivity's bottom padding: fills the rest of the screen so the header can fold all
                    // the way, but not when that is more than the header's height (then the list does not scroll)
                    view = new View(context) {
                        private int lastPaddingHeight = 0;
                        private int lastListViewHeight = 0;

                        @Override
                        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                            if (lastListViewHeight != listView.getMeasuredHeight()) {
                                lastPaddingHeight = 0;
                            }
                            lastListViewHeight = listView.getMeasuredHeight();
                            final int n = listView.getChildCount();
                            if (n == getItemCount()) {
                                int totalHeight = 0;
                                for (int i = 0; i < n; i++) {
                                    final View child = listView.getChildAt(i);
                                    final int p = listView.getChildAdapterPosition(child);
                                    if (p >= 0 && p != bottomPaddingRow) {
                                        totalHeight += child.getMeasuredHeight();
                                    }
                                }
                                int paddingHeight = (fragmentView == null ? 0 : fragmentView.getMeasuredHeight()) - ActionBar.getCurrentActionBarHeight() - AndroidUtilities.statusBarHeight - totalHeight;
                                if (paddingHeight > getHeaderExtraHeight()) {
                                    paddingHeight = 0;
                                }
                                if (paddingHeight <= 0) {
                                    paddingHeight = 0;
                                }
                                setMeasuredDimension(listView.getMeasuredWidth(), lastPaddingHeight = paddingHeight);
                            } else {
                                setMeasuredDimension(listView.getMeasuredWidth(), lastPaddingHeight);
                            }
                        }
                    };
                    view.setTag(RecyclerListView.TAG_NOT_SECTION);
                    break;
                default:
                    view = new MediaView(context);
                    break;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (holder.getItemViewType() == VIEW_TYPE_USER) {
                final TLRPC.User user = getMessagesController().getUser(participants.get(position - usersStartRow));
                // THREADS: as the profile's members: name and online status from UserCell itself
                ((UserCell) holder.itemView).setData(user, null, null, 0, position != usersEndRow - 1);
            } else if (holder.getItemViewType() == VIEW_TYPE_MEMBERS) {
                // THREADS (stage 6): ProfileActivity's subscribersRow of a group: "Members", the count, msg_groups
                ((TextCell) holder.itemView).setTextAndValueAndIcon(LocaleController.getString(R.string.ChannelMembers), LocaleController.formatNumber(participants.size(), ','), R.drawable.msg_groups, false);
            }
        }
    }
}
