package org.telegram.ui.threads;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.lerp;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.ImageReceiver; // THREADS
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.EdgeToEdgeSupportMode;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeBind;
import org.telegram.ui.Cells.ChatActionCell;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ChatActivityEnterView;
import org.telegram.ui.Components.ChatActivityEnterViewAnimatedIconView;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.EditTextCaption;
import org.telegram.ui.Components.EditTextEmoji;
import org.telegram.ui.Components.EmojiView;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.SizeNotifierFrameLayout;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.chat.WallpaperBitmapProvider;
import org.telegram.ui.Components.chat.layouts.ChatActivityFadeView;
import org.telegram.utils.glass.source.GlassSourceWrapped;
import org.telegram.utils.glass.source.GlassSourceBitmap;
import org.telegram.utils.glass.source.GlassSource;
import android.graphics.drawable.Drawable;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProviderThemed;
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl;
import org.telegram.ui.Components.blur3.utils.Blur3Utils;
import org.telegram.ui.Components.chat.ChatInputViewsContainer;
import org.telegram.ui.Components.inset.WindowInsetsStateHolder;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.recyclerview.ChatListItemAnimator;
import org.telegram.utils.glass.BaseFragmentSimpleGlass;

import me.vkryl.android.animator.FactorAnimator;

import java.util.ArrayList;

// THREADS: thread screen. Glass header "Thread · N replies" + chat name, parent message, "N replies" divider,
// local replies, and an input island built like the chat's (ChatActivityEnterView) around EditTextEmoji.
// Local storage only (ThreadsStore).
public class ThreadActivity extends BaseFragment {

    private static final int TYPE_MESSAGE = 0;
    private static final int TYPE_DIVIDER = 1;

    // THREADS: ChatActivityEnterView.DEFAULT_HEIGHT
    private static final int DEFAULT_HEIGHT = 44;
    // THREADS: id of the (visual only) ⋮ header item
    private static final int MENU_OPTIONS = 1;

    private final long dialogId;
    private final MessageObject parentMessage;
    private final int parentMessageId;

    private final ArrayList<MessageObject> replies = new ArrayList<>();
    private MessageObject parentCopy;

    private SizeNotifierFrameLayout root;
    private RecyclerListView listView;
    // THREADS: top and bottom fades over the messages (under the status bar / header and above the input),
    // the same view and source as ChatActivity.chatActivityFadeView: blurred wallpaper only.
    private ChatActivityFadeView fadeView;
    private final GlassSourceWrapped wallpaperSource = new GlassSourceWrapped();
    private final WallpaperBitmapProvider wallpaperBitmapProvider = new WallpaperBitmapProvider();
    private LinearLayoutManager layoutManager;
    private Adapter adapter;
    private ThreadHeaderView headerView;
    private int localIdCounter = 1;

    // THREADS: input island (same building blocks as the chat screen)
    private final WindowInsetsStateHolder windowInsetsStateHolder = new WindowInsetsStateHolder(this::checkInsets);
    private ChatInputViewsContainer chatInputViewsContainer;
    private EditTextEmoji editTextEmoji;
    private View emojiPanelView; // THREADS: EditTextEmoji's EmojiView, hosted in the in-app keyboard container
    private ChatActivityEnterViewAnimatedIconView emojiButton;
    private ChatActivityEnterViewAnimatedIconView audioVideoSendButton;
    private FrameLayout audioVideoButtonContainer;
    private ChatActivityEnterView.SendButton sendButton;
    private AnimatorSet runningAnimation;
    private boolean sendButtonShown;
    private float inputIslandHeight = dp(DEFAULT_HEIGHT);
    // THREADS: same animator, interpolator and duration as ChatActivityEnterView.animatorInputFieldHeight
    private final FactorAnimator animatorInputFieldHeight = new FactorAnimator(0, new FactorAnimator.Target() {
        @Override
        public void onFactorChanged(int id, float factor, float fraction, FactorAnimator callee) {
            setInputIslandHeight(factor);
        }
    }, ChatListItemAnimator.DEFAULT_INTERPOLATOR, ChatListItemAnimator.DEFAULT_DURATION);
    private boolean lastImeVisible;

    public ThreadActivity(long dialogId, MessageObject parentMessage) {
        this.dialogId = dialogId;
        this.parentMessage = parentMessage;
        this.parentMessageId = parentMessage.getId();
    }

    @Override
    public boolean onFragmentCreate() {
        parentCopy = new MessageObject(currentAccount, parentMessage.messageOwner, true, false);
        for (ThreadsStore.Reply reply : ThreadsStore.getReplies(currentAccount, dialogId, parentMessageId)) {
            replies.add(createReplyObject(reply));
        }
        return super.onFragmentCreate();
    }

    // THREADS: same edge-to-edge mode as ChatActivity: content goes under the status and navigation bars.
    @Override
    public EdgeToEdgeSupportMode getEdgeToEdgeSupportMode() {
        return EdgeToEdgeSupportMode.FULL;
    }

    @Override
    public boolean drawEdgeNavigationBar() {
        return false;
    }

    private MessageObject createReplyObject(ThreadsStore.Reply reply) {
        final TLRPC.TL_message message = new TLRPC.TL_message();
        message.message = reply.text;
        message.date = reply.date;
        message.dialog_id = dialogId;
        message.flags = 259;
        message.from_id = new TLRPC.TL_peerUser();
        // THREADS: the stored sender (stage 3); old replies have the current user.
        message.from_id.user_id = reply.senderId;
        message.id = localIdCounter++;
        message.media = new TLRPC.TL_messageMediaEmpty();
        message.out = reply.senderId == getUserConfig().getClientUserId();
        if (!message.out) {
            message.flags &= ~2; // THREADS: TL_message out flag, an incoming message
        }
        message.unread = false;
        message.peer_id = getMessagesController().getPeer(dialogId);
        final MessageObject messageObject = new MessageObject(currentAccount, message, true, false);
        // THREADS (stage 4): other people's replies are incoming messages. A group shows avatar and name by itself
        // (cell.isChat); a private chat never shows names, so there only the avatar is forced (see the adapter).
        if (!message.out && !DialogObject.isChatDialog(dialogId)) {
            messageObject.forceAvatar = true;
        }
        return messageObject;
    }

    private CharSequence chatName() {
        if (dialogId > 0) {
            final TLRPC.User user = getMessagesController().getUser(dialogId);
            if (UserObject.isUserSelf(user)) {
                return LocaleController.getString(R.string.SavedMessages);
            }
            return user == null ? "" : UserObject.getUserName(user);
        }
        final TLRPC.Chat chat = getMessagesController().getChat(-dialogId);
        return chat == null ? "" : chat.title;
    }

    private String repliesText() {
        return LocaleController.formatPluralString("ThreadsReplies", replies.size());
    }

    // THREADS (stage 5): Slack-style header: "Thread", then the participants' avatars and the chat name. The reply
    // count is only in the "N replies" divider of the list.
    private void updateTitle() {
        if (headerView != null) {
            headerView.setTitle(LocaleController.getString(R.string.ThreadsButton));
            headerView.setParticipants(currentAccount, ThreadsStore.getParticipants(currentAccount, dialogId, parentMessageId));
        }
    }

    @Override
    public View createView(Context context) {
        // This view fills the whole screen (edge-to-edge), the wallpaper must not reserve space for the action bar.
        root = new SizeNotifierFrameLayout(context) {
            @Override
            protected boolean isActionBarVisible() {
                return false;
            }

            @Override
            protected boolean isStatusBarVisible() {
                return false;
            }

            @Override
            protected boolean useRootView() {
                return false;
            }

            // THREADS: as ChatActivity's content view onMeasure(): the wallpaper bitmap is center-cropped to the screen.
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                if (wallpaperSource.getSource() instanceof GlassSourceBitmap) {
                    ((GlassSourceBitmap) wallpaperSource.getSource()).setParentSize(
                        MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec), 0);
                }
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            }

            // THREADS: as ChatActivity's content view: the wallpaper is the source of the fades.
            @Override
            public void onUpdateBackgroundDrawable(Drawable drawable) {
                super.onUpdateBackgroundDrawable(drawable);
                final GlassSource source = wallpaperBitmapProvider.updateSourceFromBackgroundViewDrawable(drawable);
                if (source instanceof GlassSourceBitmap && getMeasuredWidth() > 0) {
                    ((GlassSourceBitmap) source).setParentSize(getMeasuredWidth(), getMeasuredHeight(), 0);
                }
                wallpaperSource.setSource(source);
                if (fadeView != null) {
                    fadeView.setOverlayColor(Theme.isCompactMode(getResourceProvider()) ? getThemedColor(Theme.key_chat_compact_backgroundGradient) : 0);
                    fadeView.invalidate();
                }
            }

            // THREADS: EditTextEmoji adds its emoji panel to this layout; put it into the in-app keyboard
            // container of the input island instead, where the chat screen keeps its emoji panel.
            @Override
            public void addView(View child) {
                if (editTextEmoji != null && editTextEmoji.isPopupView(child) && chatInputViewsContainer != null) {
                    emojiPanelView = child;
                    // THREADS: as ChatActivityEnterView does with an in-app keyboard: the panel draws no fill of
                    // its own, the frosted glass of the input island's keyboard area shows through.
                    if (child instanceof EmojiView) {
                        final EmojiView emojiView = (EmojiView) child;
                        emojiView.shouldLightenBackground = false;
                        emojiView.setShouldDrawBackground(false);
                        emojiView.isNewHeightControl = true;
                    }
                    chatInputViewsContainer.getInAppKeyboardBubbleContainer().addView(child,
                        LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP));
                    return;
                }
                super.addView(child);
            }
        };
        fragmentView = root;

        final BaseFragmentSimpleGlass simpleGlass = getBaseSimpleGlass();

        // List: full screen under the glass header and the input island, like the chat list.
        listView = new RecyclerListView(context) {
            // THREADS (stage 4): the side avatar of incoming messages is drawn by the list, not by the cell.
            // Same as ChannelAdminLogActivity's chatListView.drawChild(): avatar at the bottom of a sender's run,
            // sticking to the bottom edge while the run scrolls.
            @Override
            public boolean drawChild(Canvas canvas, View child, long drawingTime) {
                final boolean result = super.drawChild(canvas, child, drawingTime);
                if (child instanceof ChatMessageCell) {
                    drawThreadsAvatar(canvas, (ChatMessageCell) child);
                }
                return result;
            }
        };
        layoutManager = new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false);
        layoutManager.setStackFromEnd(true);
        listView.setLayoutManager(layoutManager);
        listView.setItemAnimator(null);
        listView.setClipToPadding(false);
        listView.setAdapter(adapter = new Adapter(context));
        listView.setSelectorDrawableColor(0);
        // Long-press a reply: Copy / Delete (local only).
        listView.setOnItemLongClickListener((view, position) -> {
            final int index = position - 2;
            if (index < 0 || index >= replies.size()) {
                return false;
            }
            final String text = replies.get(index).messageOwner.message;
            ItemOptions.makeOptions(this, view)
                .add(R.drawable.msg_copy, LocaleController.getString(R.string.Copy), () -> {
                    AndroidUtilities.addToClipboard(text);
                    BulletinFactory.of(this).createCopyBulletin(LocaleController.getString(R.string.ThreadsCopied)).show();
                })
                .add(R.drawable.msg_delete, LocaleController.getString(R.string.Delete), true, () -> deleteReply(index))
                .show();
            return true;
        });
        root.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        // THREADS: same fade view, right above the list, as ChatActivity (48dp fades, wallpaper source).
        fadeView = new ChatActivityFadeView(context);
        fadeView.setResourcesProvider(getResourceProvider());
        fadeView.setup(new BlurredBackgroundDrawableViewFactory(wallpaperSource));
        fadeView.setFadeHeightTop(dp(48));
        fadeView.setFadeHeightBottom(dp(48));
        root.addView(fadeView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        // THREADS: glass under the header and the input: the capture is the wallpaper plus the list.
        simpleGlass.setViews(root, listView, null, getResourceProvider());
        simpleGlass.setCapture((canvas, position) -> {
            if (root.backgroundView != null) {
                root.backgroundView.draw(canvas);
            }
            Blur3Utils.captureRelativeParent(listView, canvas, position, listView, root);
        });

        createHeader(context, simpleGlass);
        createInput(context, simpleGlass);
        // THREADS: top fade zone follows the header height (ChatActivity.checkUi_topFade()).
        actionBar.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> updateFadeZones());

        // Edge-to-edge insets go to the same holder type the chat uses (keyboard + in-app emoji keyboard).
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            windowInsetsStateHolder.setInsets(insets);
            final boolean imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime());
            if (lastImeVisible != imeVisible) {
                lastImeVisible = imeVisible;
                root.notifyHeightChanged();
            }
            return WindowInsetsCompat.CONSUMED;
        });
        if (context instanceof LaunchActivity) {
            windowInsetsStateHolder.setupAnimatedInsetsProvider(((LaunchActivity) context).getRootAnimatedInsetsListener(), root);
        }

        // Same chat wallpaper as the chat screen.
        root.setBackgroundImage(Theme.getCachedWallpaper(), Theme.isWallpaperMotion());

        updateListPaddings();
        scrollToBottom();
        return fragmentView;
    }

    // THREADS: header built like ChatActivity's: ActionBar on glass with the round back button,
    // and a title block (ThreadHeaderView) in the center glass pill.
    private void createHeader(Context context, BaseFragmentSimpleGlass simpleGlass) {
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
        // THREADS: ⋮ button, as the chat's header item (glass round button on the right). Visual only: tap does nothing.
        // Created before setupGlass(), which puts the menu on glass (as in ChatActivity).
        final ActionBarMenu menu = actionBar.createMenu();
        final ActionBarMenuItem optionsItem = menu.addItem(MENU_OPTIONS, R.drawable.ic_ab_other);
        actionBar.setupGlass(simpleGlass.factory, BlurredBackgroundProviderImpl.topPanelChatActivity(getResourceProvider()));
        // The title pill is drawn by ThreadHeaderView (sized to the text, like the chat's avatar container pill).
        actionBar.doNotDrawCenterGlass = true;

        headerView = new ThreadHeaderView(context, simpleGlass.factory,
            BlurredBackgroundProviderImpl.topPanelChatActivity(getResourceProvider()), getResourceProvider());
        headerView.setOccupyStatusBar(actionBar.getOccupyStatusBar());
        // Same margins as the chat's avatar container inside the ActionBar.
        actionBar.addView(headerView, 0, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP | Gravity.LEFT, 52, 0, 52, 0));
        updateTitle();
        headerView.setSubtitle(chatName());
        // THREADS (stage 5): tapping the title opens the thread info, as the chat's title opens the profile
        headerView.setOnClickListener(v -> presentFragment(new ThreadInfoActivity(dialogId, parentMessage)));

        root.addView(actionBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
    }

    // THREADS: input island rebuilt from ChatActivity + ChatActivityEnterView (same views, drawables, colors,
    // sizes, margins). Paperclip and mic are visual only.
    private void createInput(Context context, BaseFragmentSimpleGlass simpleGlass) {
        final Theme.ResourcesProvider resourcesProvider = getResourceProvider();

        // Same color provider as ChatActivity.blurredBackgroundColorProvider.
        final BlurredBackgroundColorProviderThemed inputColorProvider = new BlurredBackgroundColorProviderThemed(resourcesProvider, Theme.key_chat_messagePanelBackground) {
            @Override
            public int getBackgroundColor() {
                if (!BlurredBackgroundProviderImpl.checkBlurEnabled(currentAccount, resourcesProvider)) {
                    return ColorUtils.setAlphaComponent(getThemedColor(Theme.key_chat_messagePanelBackground), 255);
                }
                if (!Theme.isCurrentThemeDark()) {
                    return ColorUtils.setAlphaComponent(super.getBackgroundColor(), 216);
                }
                return super.getBackgroundColor();
            }
        };
        inputColorProvider.setCompactOutlineKey(Theme.key_chat_compact_inputGlassOutline);

        chatInputViewsContainer = new ChatInputViewsContainer(context);
        chatInputViewsContainer.setClipChildren(false);
        chatInputViewsContainer.setWindowInsetsProvider(windowInsetsStateHolder);
        chatInputViewsContainer.setInputIslandBubbleDrawable(simpleGlass.factory.create(chatInputViewsContainer, inputColorProvider));
        chatInputViewsContainer.setUnderKeyboardBackgroundDrawable(simpleGlass.frostedFactory.create(chatInputViewsContainer, inputColorProvider));
        chatInputViewsContainer.setInputBubbleHeight(inputIslandHeight);

        final FrameLayout bubbleContainer = chatInputViewsContainer.getInputIslandBubbleContainer();
        bubbleContainer.setClipChildren(false);

        // ChatActivityEnterView: the enter view sits in the bubble with 7dp sides, textFieldContainer 1dp below its top.
        final FrameLayout textFieldContainer = new FrameLayout(context);
        textFieldContainer.setClipChildren(false);
        textFieldContainer.setClipToPadding(false);
        textFieldContainer.setPadding(0, dp(1), 0, 0);
        bubbleContainer.addView(textFieldContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.BOTTOM, 7, 1, 7, 0));

        final FrameLayout messageEditTextContainer = new FrameLayout(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
                // THREADS: animated like ChatActivityEnterView.animatorInputFieldHeight
                final int height = Math.max(dp(44), getMeasuredHeight());
                if (animatorInputFieldHeight.getFactor() > 0) {
                    animatorInputFieldHeight.animateTo(height);
                } else {
                    animatorInputFieldHeight.forceFactor(height);
                }
            }
        };
        messageEditTextContainer.setClipChildren(false);
        textFieldContainer.addView(messageEditTextContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM, 0, 0, DEFAULT_HEIGHT, 0));

        // Emoji button (ChatActivityEnterView.emojiButton)
        emojiButton = new ChatActivityEnterViewAnimatedIconView(context);
        emojiButton.setContentDescription(LocaleController.getString(R.string.AccDescrEmojiButton));
        emojiButton.setFocusable(true);
        final int emojiPadding = dp(7.5f);
        emojiButton.setPadding(emojiPadding, emojiPadding, emojiPadding, emojiPadding);
        ThemeBind.colorFilter(emojiButton, Theme.key_glass_defaultIcon, resourcesProvider, PorterDuff.Mode.SRC_IN);
        emojiButton.setBackground(Theme.createInsetRoundRectDrawable(Theme.getColor(Theme.key_listSelector, resourcesProvider), dp(19), dp(1), dp(3)));
        messageEditTextContainer.addView(emojiButton, LayoutHelper.createFrame(DEFAULT_HEIGHT, DEFAULT_HEIGHT, Gravity.BOTTOM | Gravity.LEFT, 2, 0, 0, 0));

        // Text field: EditTextEmoji (field + emoji panel), styled as ChatActivityEnterView.messageEditText.
        editTextEmoji = new EditTextEmoji(context, root, this, EditTextEmoji.STYLE_DIALOG, true, resourcesProvider) {
            @Override
            protected void showPopup(int show) {
                super.showPopup(show);
                fixEmojiPanelHeight();
                // THREADS: the chat's emoji panel opens as an in-app keyboard of the input island.
                if (show == 1) {
                    windowInsetsStateHolder.requestInAppKeyboardHeightIncludeNavbar(getKeyboardHeight());
                } else {
                    windowInsetsStateHolder.resetInAppKeyboardHeight(show == 2);
                }
            }

            @Override
            public void hidePopup(boolean byBackButton) {
                // THREADS: the in-app keyboard container animates the panel away (as in the chat).
                super.hidePopup(false);
            }

            @Override
            public void onSizeChanged(int height, boolean isWidthGreater) {
                super.onSizeChanged(height, isWidthGreater);
                fixEmojiPanelHeight();
            }

            // THREADS: the chat's emoji panel has the search row
            @Override
            protected boolean allowSearch() {
                return true;
            }

            @Override
            protected void onEmojiKeyboardUpdate() {
                updateEmojiButton(true);
            }
        };
        editTextEmoji.glassDesignForEmojiView = true;
        // THREADS: Stickers and GIF tabs like the chat's panel; picking a sticker/GIF does nothing (local thread, text only).
        editTextEmoji.threadsNeedStickersAndGifs = true;
        editTextEmoji.getEmojiButton().setVisibility(View.GONE);
        final EditTextCaption editText = editTextEmoji.getEditText();
        editText.setLayoutParams(LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM));
        editText.setIncludeFontPadding(false);
        editText.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        editText.setInputType(editText.getInputType() | EditorInfo.TYPE_TEXT_FLAG_CAP_SENTENCES | EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE);
        editText.setSingleLine(false);
        editText.setMaxLines(6);
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        editText.setGravity(Gravity.BOTTOM);
        editText.setPadding(0, dp(9), 0, dp(10));
        editText.setBackground(null);
        editText.setCursorSize(dp(20));
        editText.setCursorWidth(1.5f);
        ThemeBind.textColor(editText, Theme.key_chat_messagePanelText, resourcesProvider);
        ThemeBind.linkTextColor(editText, Theme.key_chat_messageLinkOut, resourcesProvider);
        ThemeBind.highlightColor(editText, Theme.key_chat_inTextSelectionHighlight, resourcesProvider);
        ThemeBind.editHintColor(editText, Theme.key_chat_messagePanelHint, resourcesProvider);
        ThemeBind.hintTextColor(editText, Theme.key_chat_messagePanelHint, resourcesProvider);
        ThemeBind.cursorColor(editText, Theme.key_chat_messagePanelCursor, resourcesProvider);
        ThemeBind.editHandlesColor(editText, Theme.key_chat_TextSelectionCursor, resourcesProvider);
        editText.setHint(LocaleController.getString(R.string.ThreadsInputHint));
        editText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                checkSendButton(true);
                updateEmojiButton(true); // THREADS: sticker/GIF icon turns into the smile while typing
            }
        });
        // Same margins as messageEditText: 52 left (emoji button), 50 right (paperclip), 1.5 bottom.
        updateEmojiButton(false); // THREADS
        messageEditTextContainer.addView(editTextEmoji, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM, 52, 0, 50, 1.5f));

        emojiButton.setOnClickListener(v -> {
            // THREADS: same toggle as EditTextEmoji's own (hidden) button: emoji panel <-> keyboard.
            editTextEmoji.getEmojiButton().performClick();
        });

        // Paperclip (ChatActivityEnterView.attachButton) — visual only.
        final ImageView attachButton = new ImageView(context);
        attachButton.setScaleType(ImageView.ScaleType.CENTER);
        ThemeBind.colorFilter(attachButton, Theme.key_glass_defaultIcon, resourcesProvider, PorterDuff.Mode.MULTIPLY);
        attachButton.setImageResource(R.drawable.msg_input_attach2);
        ThemeBind.selectorBackground(attachButton, Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector, resourcesProvider)), null, ThemeBind.of(Theme.key_listSelector), resourcesProvider);
        attachButton.setOnClickListener(v -> { });
        messageEditTextContainer.addView(attachButton, LayoutHelper.createFrame(DEFAULT_HEIGHT, DEFAULT_HEIGHT, Gravity.BOTTOM | Gravity.RIGHT));

        // Mic / send (ChatActivityEnterView.sendButtonContainer)
        final FrameLayout sendButtonContainer = new FrameLayout(context) {
            @Override
            protected void onSizeChanged(int w, int h, int oldw, int oldh) {
                super.onSizeChanged(w, h, oldw, oldh);
                setPivotX(w - dp(22));
                setPivotY(h - dp(22));
            }
        };
        sendButtonContainer.setClipChildren(false);
        sendButtonContainer.setClipToPadding(false);
        textFieldContainer.addView(sendButtonContainer, LayoutHelper.createFrame(100, DEFAULT_HEIGHT, Gravity.BOTTOM | Gravity.RIGHT));

        audioVideoButtonContainer = new FrameLayout(context) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final RectF backgroundRect = new RectF();

            @Override
            protected void dispatchDraw(@NonNull Canvas canvas) {
                // Same blue circle as the chat's mic: 38dp, radius 19, 3dp from the corner.
                final float r = AndroidUtilities.dpf2(19);
                paint.setColor(Theme.getColor(Theme.key_chat_messagePanelSend, resourcesProvider));
                final float margin = AndroidUtilities.dpf2(3);
                final float size = AndroidUtilities.dpf2(38);
                backgroundRect.set(getMeasuredWidth() - size - margin, getMeasuredHeight() - size - margin, getMeasuredWidth() - margin, getMeasuredHeight() - margin);
                canvas.drawRoundRect(backgroundRect, r, r, paint);
                super.dispatchDraw(canvas);
            }
        };
        audioVideoButtonContainer.setSoundEffectsEnabled(false);
        audioVideoButtonContainer.setOnClickListener(v -> { }); // visual only: no recording
        sendButtonContainer.addView(audioVideoButtonContainer, LayoutHelper.createFrame(DEFAULT_HEIGHT, DEFAULT_HEIGHT, Gravity.RIGHT | Gravity.BOTTOM));

        audioVideoSendButton = new ChatActivityEnterViewAnimatedIconView(context, 24);
        audioVideoSendButton.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        final int micPadding = dp(10f);
        audioVideoSendButton.setPadding(micPadding, micPadding, micPadding, micPadding);
        audioVideoSendButton.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
        audioVideoSendButton.setState(ChatActivityEnterViewAnimatedIconView.State.VOICE, false);
        audioVideoButtonContainer.addView(audioVideoSendButton, LayoutHelper.createFrame(DEFAULT_HEIGHT, DEFAULT_HEIGHT));

        sendButton = new ChatActivityEnterView.SendButton(context, R.drawable.send_plane_24, resourcesProvider, true) {
            @Override
            public boolean dispatchTouchEvent(MotionEvent event) {
                // the button is 100dp wide but only its right part is the circle: keep the paperclip tappable
                if (event.getAction() == MotionEvent.ACTION_DOWN && event.getX() < getWidth() - width()) {
                    return false;
                }
                return super.dispatchTouchEvent(event);
            }
        };
        sendButton.setVisibility(View.INVISIBLE);
        sendButton.setContentDescription(LocaleController.getString(R.string.Send));
        sendButton.setSoundEffectsEnabled(false);
        sendButton.setScaleX(0.1f);
        sendButton.setScaleY(0.1f);
        sendButton.setAlpha(0.0f);
        sendButton.setOnClickListener(v -> send());
        sendButtonContainer.addView(sendButton, LayoutHelper.createFrame(100, DEFAULT_HEIGHT, Gravity.RIGHT | Gravity.BOTTOM));

        root.addView(chatInputViewsContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
    }

    // THREADS: as in ChatActivityEnterView.attachEmojiView() with an in-app keyboard: the panel fills the
    // container (EditTextEmoji sets a fixed height, which pushed the panel's bottom bar off screen).
    private void fixEmojiPanelHeight() {
        if (emojiPanelView == null) {
            return;
        }
        final ViewGroup.LayoutParams lp = emojiPanelView.getLayoutParams();
        if (lp != null && lp.height != ViewGroup.LayoutParams.MATCH_PARENT) {
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
            emojiPanelView.setLayoutParams(lp);
        }
    }

    private void setInputIslandHeight(float height) {
        if (inputIslandHeight != height) {
            inputIslandHeight = height;
            if (chatInputViewsContainer != null) {
                chatInputViewsContainer.setInputBubbleHeight(height);
            }
            updateListPaddings();
        }
    }

    private void checkInsets() {
        if (chatInputViewsContainer != null) {
            chatInputViewsContainer.checkInsets();
        }
        updateListPaddings();
    }

    // THREADS: same paddings as ChatActivity.checkUi_chatListViewPaddings() / chatListViewPaddingTop.
    private void updateListPaddings() {
        if (listView == null) {
            return;
        }
        final int top = dp(4) + ActionBar.getCurrentActionBarHeight() + (actionBar != null && actionBar.getOccupyStatusBar() ? AndroidUtilities.statusBarHeight : 0);
        final int bottom = (int) (dp(9 + 7) + inputIslandHeight + windowInsetsStateHolder.getAnimatedMaxBottomInset());
        if (listView.getPaddingTop() != top || listView.getPaddingBottom() != bottom) {
            listView.setPadding(0, top, 0, bottom);
        }
        updateFadeZones();
    }

    // THREADS: ChatActivity.checkUi_topFade() (no pinned / topic / search panels here) and checkUi_BlurHeight().
    private void updateFadeZones() {
        if (fadeView == null) {
            return;
        }
        if (actionBar != null) {
            fadeView.setFadeZoneTop(actionBar.getMeasuredHeight() + dp(7 - 6));
        }
        fadeView.setFadeZoneBottom((int) (windowInsetsStateHolder.getAnimatedMaxBottomInset() + dp(9) + inputIslandHeight + dp(7)));
    }

    private void updateEmojiButton(boolean animated) {
        if (emojiButton == null || editTextEmoji == null) {
            return;
        }
        // THREADS: ChatActivityEnterView.setEmojiButtonImage(): keyboard while open, else the last tab used
        // (smile / sticker / GIF), smile when there is text.
        final ChatActivityEnterViewAnimatedIconView.State state;
        if (editTextEmoji.isPopupShowing()) {
            state = ChatActivityEnterViewAnimatedIconView.State.KEYBOARD;
        } else {
            final int currentPage = emojiPanelView instanceof EmojiView ?
                ((EmojiView) emojiPanelView).getCurrentPage() :
                MessagesController.getGlobalEmojiSettings().getInt("selected_page", 0);
            if (currentPage == 0 || !TextUtils.isEmpty(editTextEmoji.getEditText().getText())) {
                state = ChatActivityEnterViewAnimatedIconView.State.SMILE;
            } else if (currentPage == 1) {
                state = ChatActivityEnterViewAnimatedIconView.State.STICKER;
            } else {
                state = ChatActivityEnterViewAnimatedIconView.State.GIF;
            }
        }
        emojiButton.setState(state, animated);
    }

    // THREADS: ChatActivityEnterView.checkSendButton() for the plain case: mic <-> send.
    private void checkSendButton(boolean animated) {
        if (editTextEmoji == null || sendButton == null) {
            return;
        }
        final boolean hasText = AndroidUtilities.getTrimmedString(editTextEmoji.getEditText().getText()).length() > 0;
        if (hasText == sendButtonShown) {
            return;
        }
        sendButtonShown = hasText;
        if (runningAnimation != null) {
            runningAnimation.cancel();
            runningAnimation = null;
        }
        if (hasText) {
            sendButton.setVisibility(View.VISIBLE);
            if (!animated) {
                audioVideoSendButton.setScaleX(0.1f);
                audioVideoSendButton.setScaleY(0.1f);
                audioVideoSendButton.setAlpha(0.0f);
                audioVideoButtonContainer.setVisibility(View.GONE);
                sendButton.setScaleX(1f);
                sendButton.setScaleY(1f);
                sendButton.setAlpha(1f);
                return;
            }
            final ArrayList<Animator> animators = new ArrayList<>();
            if (audioVideoButtonContainer.getVisibility() == View.VISIBLE) {
                animators.add(ObjectAnimator.ofFloat(audioVideoSendButton, View.SCALE_X, 0.1f));
                animators.add(ObjectAnimator.ofFloat(audioVideoSendButton, View.SCALE_Y, 0.1f));
                animators.add(ObjectAnimator.ofFloat(audioVideoSendButton, View.ALPHA, 0.0f));
            }
            animators.add(animateSendButton(true));
            runningAnimation = new AnimatorSet();
            runningAnimation.playTogether(animators);
            runningAnimation.setDuration(220);
            runningAnimation.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
            runningAnimation.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (animation == runningAnimation) {
                        audioVideoButtonContainer.setVisibility(View.GONE);
                        runningAnimation = null;
                    }
                }
            });
            runningAnimation.start();
        } else {
            audioVideoButtonContainer.setVisibility(View.VISIBLE);
            if (!animated) {
                audioVideoSendButton.setScaleX(1f);
                audioVideoSendButton.setScaleY(1f);
                audioVideoSendButton.setAlpha(1f);
                sendButton.setScaleX(0.1f);
                sendButton.setScaleY(0.1f);
                sendButton.setAlpha(0f);
                sendButton.setVisibility(View.INVISIBLE);
                return;
            }
            final ArrayList<Animator> animators = new ArrayList<>();
            animators.add(ObjectAnimator.ofFloat(audioVideoSendButton, View.SCALE_X, 1f));
            animators.add(ObjectAnimator.ofFloat(audioVideoSendButton, View.SCALE_Y, 1f));
            animators.add(ObjectAnimator.ofFloat(audioVideoSendButton, View.ALPHA, 1f));
            animators.add(ObjectAnimator.ofFloat(sendButton, View.SCALE_X, 0.1f));
            animators.add(ObjectAnimator.ofFloat(sendButton, View.SCALE_Y, 0.1f));
            animators.add(ObjectAnimator.ofFloat(sendButton, View.ALPHA, 0.0f));
            runningAnimation = new AnimatorSet();
            runningAnimation.playTogether(animators);
            runningAnimation.setDuration(150);
            runningAnimation.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (animation == runningAnimation) {
                        sendButton.setVisibility(View.INVISIBLE);
                        runningAnimation = null;
                    }
                }
            });
            runningAnimation.start();
        }
    }

    // THREADS: ChatActivityEnterView.animateSendButton()
    private ValueAnimator animateSendButton(boolean show) {
        final float fromAlpha = sendButton.getAlpha();
        final float toAlpha = show ? 1.0f : 0.0f;
        final float fromScaleX = sendButton.getScaleX();
        final float toScaleX = show ? 1.0f : 0.1f;
        final float fromScaleY = sendButton.getScaleY();
        final float toScaleY = show ? 1.0f : 0.1f;
        if (show && fromAlpha < 0.25f) {
            sendButton.appear();
        }
        final ValueAnimator a = ValueAnimator.ofFloat(0, 1);
        a.addUpdateListener(anm -> {
            final float t = (float) anm.getAnimatedValue();
            sendButton.setAlpha(lerp(fromAlpha, toAlpha, t));
            sendButton.setScaleX(lerp(fromScaleX, toScaleX, t));
            sendButton.setScaleY(lerp(fromScaleY, toScaleY, t));
        });
        return a;
    }

    private void send() {
        if (editTextEmoji == null) {
            return;
        }
        final String text = editTextEmoji.getEditText().getText() == null ? "" : editTextEmoji.getEditText().getText().toString().trim();
        if (TextUtils.isEmpty(text)) {
            return;
        }
        addReply(text);
        editTextEmoji.getEditText().setText("");
    }

    // THREADS: called from ChatActivity when a reply was sent from the reply panel in thread mode.
    public void addReply(String text) {
        final ThreadsStore.Reply reply = ThreadsStore.addReply(currentAccount, dialogId, parentMessageId, text);
        replies.add(createReplyObject(reply));
        updateTitle();
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        scrollToBottom();
    }

    private void deleteReply(int index) {
        if (index < 0 || index >= replies.size()) {
            return;
        }
        ThreadsStore.deleteReply(currentAccount, dialogId, parentMessageId, index);
        replies.remove(index);
        updateTitle();
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private void scrollToBottom() {
        if (listView != null && adapter != null) {
            listView.post(() -> layoutManager.scrollToPositionWithOffset(adapter.getItemCount() - 1, -100000));
        }
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (editTextEmoji != null && editTextEmoji.isPopupShowing()) {
            if (invoked) {
                editTextEmoji.hidePopup(true);
            }
            return false;
        }
        return super.onBackPressed(invoked);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (editTextEmoji != null) {
            editTextEmoji.onResume();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (editTextEmoji != null) {
            editTextEmoji.onPause();
            AndroidUtilities.hideKeyboard(editTextEmoji.getEditText());
        }
    }

    @Override
    public void onFragmentDestroy() {
        if (editTextEmoji != null) {
            editTextEmoji.onDestroy();
        }
        super.onFragmentDestroy();
    }

    // THREADS (stage 4): ChannelAdminLogActivity's avatar drawing (chatListView.drawChild), trimmed to plain
    // text messages: no albums, sliding or alpha layers here.
    private void drawThreadsAvatar(Canvas canvas, ChatMessageCell cell) {
        final ImageReceiver imageReceiver = cell.getAvatarImage();
        if (imageReceiver == null || cell.getMessageObject() == null) {
            return;
        }
        final boolean updateVisibility = listView.getChildAdapterPosition(cell) != RecyclerView.NO_POSITION;
        int top = (int) cell.getY();
        if (cell.drawPinnedBottom()) {
            final RecyclerView.ViewHolder holder = listView.getChildViewHolder(cell);
            final int p = holder.getAdapterPosition();
            if (p >= 0 && listView.findViewHolderForAdapterPosition(p + 1) != null) {
                imageReceiver.setVisible(false, false);
                return;
            }
        }
        int y = (int) cell.getY() + cell.getLayoutHeight();
        final int maxY = listView.getMeasuredHeight() - listView.getPaddingBottom();
        if (y > maxY) {
            y = maxY;
        }
        if (cell.drawPinnedTop()) {
            int p = listView.getChildViewHolder(cell).getAdapterPosition();
            if (p >= 0) {
                for (int tries = 0; tries < 20; tries++) {
                    final RecyclerView.ViewHolder holder = listView.findViewHolderForAdapterPosition(p - 1);
                    if (holder == null) {
                        break;
                    }
                    top = holder.itemView.getTop();
                    if (holder.itemView instanceof ChatMessageCell && ((ChatMessageCell) holder.itemView).drawPinnedTop()) {
                        p--;
                    } else {
                        break;
                    }
                }
            }
        }
        if (y - dp(48) < top) {
            y = top + dp(48);
        }
        if (!cell.drawPinnedBottom()) {
            final int cellBottom = (int) (cell.getY() + cell.getMeasuredHeight());
            if (y > cellBottom) {
                y = cellBottom;
            }
        }
        if (updateVisibility) {
            imageReceiver.setImageY(y - dp(44));
            imageReceiver.setVisible(true, false);
        }
        imageReceiver.setAlpha(1f);
        imageReceiver.draw(canvas);
    }

    private class Adapter extends RecyclerListView.SelectionAdapter {

        private final Context context;

        Adapter(Context context) {
            this.context = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getAdapterPosition() >= 2; // replies can be long-pressed
        }

        @Override
        public int getItemCount() {
            return 2 + replies.size();
        }

        @Override
        public int getItemViewType(int position) {
            return position == 1 ? TYPE_DIVIDER : TYPE_MESSAGE;
        }

        // THREADS
        private boolean sameSender(MessageObject a, MessageObject b) {
            return a.getFromChatId() == b.getFromChatId();
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            final View view;
            if (viewType == TYPE_DIVIDER) {
                view = new ChatActionCell(context, false, getResourceProvider());
            } else {
                final ChatMessageCell cell = new ChatMessageCell(context, currentAccount, false, null, getResourceProvider());
                cell.setDelegate(new ChatMessageCell.ChatMessageCellDelegate() {
                });
                cell.isChat = DialogObject.isChatDialog(dialogId);
                cell.threadsHideName = !cell.isChat; // THREADS: private chat: avatar on other people's replies, no name
                view = cell;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (holder.getItemViewType() == TYPE_DIVIDER) {
                ((ChatActionCell) holder.itemView).setCustomText(repliesText());
                return;
            }
            final MessageObject messageObject = position == 0 ? parentCopy : replies.get(position - 2);
            // THREADS (stage 4): replies stick together only with the same sender's neighbours, as in the chat
            // (the avatar sits on the last one of a run).
            final boolean topNear = position > 2 && sameSender(replies.get(position - 3), messageObject);
            final boolean bottomNear = position >= 2 && position < getItemCount() - 1 && sameSender(messageObject, replies.get(position - 1));
            final ChatMessageCell cell = (ChatMessageCell) holder.itemView;
            cell.setFullyDraw(true);
            cell.setMessageObject(messageObject, null, bottomNear, topNear, false);
        }
    }
}
