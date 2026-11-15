package org.telegram.ui.threads;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.Collections;

// THREADS: every thread of a chat, opened from the chat's ⋮ menu ("Threads", shown only when the chat has one).
// The screen is a simple list screen like ThreadMembersActivity (glass header with back button, title, the chat's name
// as subtitle). Each row is drawn like a chat-list row (DialogCell, two-line layout): same height, avatar, text paints,
// positions and unread-style badge, built from the same pieces, because DialogCell itself only takes a real dialog (its
// "custom dialog" mode has letter avatars only). Data from ThreadsStore (local).
public class ThreadsListActivity extends BaseFragment {

    private final long dialogId;
    private final CharSequence chatName;
    // THREADS: the parent messages the chat already has loaded; any other parent is read from the local database
    private final ArrayList<MessageObject> knownParents;

    private final ArrayList<Row> rows = new ArrayList<>();
    private RecyclerListView listView;
    private Adapter adapter;

    private static class Row {
        int parentId;
        MessageObject parent;
        ThreadsStore.Reply lastReply;
        int count;
    }

    public ThreadsListActivity(long dialogId, CharSequence chatName, ArrayList<MessageObject> knownParents) {
        this.dialogId = dialogId;
        this.chatName = chatName;
        this.knownParents = knownParents;
    }

    @Override
    public boolean onFragmentCreate() {
        loadRows();
        return super.onFragmentCreate();
    }

    @Override
    public void onResume() {
        super.onResume();
        // THREADS: back from a thread: its last reply and count may have changed
        loadRows();
    }

    // THREADS: newest activity (last reply) first
    private void loadRows() {
        rows.clear();
        final ArrayList<Integer> missing = new ArrayList<>();
        for (int id : ThreadsStore.getThreadIds(currentAccount, dialogId)) {
            final ArrayList<ThreadsStore.Reply> replies = ThreadsStore.getReplies(currentAccount, dialogId, id);
            if (replies.isEmpty()) {
                continue;
            }
            final Row row = new Row();
            row.parentId = id;
            row.lastReply = replies.get(replies.size() - 1);
            row.count = replies.size();
            row.parent = findKnown(id);
            if (row.parent == null) {
                missing.add(id);
            }
            rows.add(row);
        }
        sortRows();
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        if (!missing.isEmpty()) {
            // THREADS: parents not loaded in the chat: read them from the local database (read only, off the UI thread)
            final int account = currentAccount;
            Utilities.globalQueue.postRunnable(() -> {
                final ArrayList<MessageObject> found = new ArrayList<>();
                for (int id : missing) {
                    final TLRPC.Message message = getMessagesStorage().getMessage(dialogId, id);
                    if (message != null) {
                        found.add(new MessageObject(account, message, false, false));
                    }
                }
                AndroidUtilities.runOnUIThread(() -> {
                    knownParents.addAll(found);
                    for (Row row : rows) {
                        if (row.parent == null) {
                            row.parent = findKnown(row.parentId);
                        }
                    }
                    if (adapter != null) {
                        adapter.notifyDataSetChanged();
                    }
                });
            });
        }
    }

    private void sortRows() {
        Collections.sort(rows, (a, b) -> Integer.compare(b.lastReply.date, a.lastReply.date));
    }

    @Nullable
    private MessageObject findKnown(int id) {
        for (MessageObject m : knownParents) {
            if (m != null && m.getId() == id) {
                return m;
            }
        }
        return null;
    }

    // THREADS: ThreadMembersActivity / ChatUsersActivity: the simple glass header of the list screens
    @Override
    protected boolean useSimpleActionBar() {
        return true;
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    @Nullable
    @Override
    protected RecyclerListView getListViewForSimpleGlass() {
        return listView;
    }

    @Override
    public View createView(Context context) {
        setHasOwnBackground(true);
        Theme.createDialogsResources(context); // THREADS: the chat-list paints the rows use (as DialogsActivity)

        simpleActionBar.setBackButtonImage(R.drawable.ic_ab_back);
        simpleActionBar.setTitle(LocaleController.getString(R.string.ThreadsList));
        if (!TextUtils.isEmpty(chatName)) {
            simpleActionBar.setSubtitle(chatName);
        }
        simpleActionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        final FrameLayout frameLayout = new FrameLayout(context);
        fragmentView = frameLayout;

        // THREADS: the rows in a section card, as the Members list
        listView = new RecyclerListView(context);
        listView.setSections();
        listView.setSectionsDrawBackground(true);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setVerticalScrollBarEnabled(false);
        listView.setAdapter(adapter = new Adapter(context));
        listView.setOnItemClickListener((view, position) -> {
            if (position < 0 || position >= rows.size()) {
                return;
            }
            final Row row = rows.get(position);
            if (row.parent != null) {
                presentFragment(new ThreadActivity(dialogId, row.parent));
            }
        });
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        return fragmentView;
    }

    private class Adapter extends RecyclerListView.SelectionAdapter {

        private final Context context;

        Adapter(Context context) {
            this.context = context;
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            final int position = holder.getAdapterPosition();
            return position >= 0 && position < rows.size() && rows.get(position).parent != null;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            final ThreadRowCell cell = new ThreadRowCell(context);
            cell.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(cell);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            ((ThreadRowCell) holder.itemView).set(rows.get(position), position != rows.size() - 1);
        }
    }

    // THREADS: a chat-list row (DialogCell, two-line layout, left to right): 70dp high + 1px divider, the 52dp avatar at
    // 11dp / 9dp, the name (dialogs_namePaint) at 76dp / 14dp, the message (dialogs_messagePaint, "Name:" part in
    // key_chats_nameMessage) at 39dp, the time (dialogs_timePaint) 15dp from the right at 16dp, the badge (20.666dp
    // high, 11.5dp corners, 6.333dp text padding, dialogs_countTextPaint2) 15.666dp from the right at 38dp, here in the
    // Thread accent; the divider (Theme.dividerPaint) from 72dp.
    private class ThreadRowCell extends View {

        private final ImageReceiver avatarImage = new ImageReceiver(this);
        private final AvatarDrawable avatarDrawable = new AvatarDrawable();
        private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        private Row row;
        private boolean divider;
        private StaticLayout nameLayout, messageLayout, timeLayout, countLayout;
        private int timeWidth, countWidth;

        ThreadRowCell(Context context) {
            super(context);
            avatarImage.setRoundRadius(dp(26));
        }

        void set(Row row, boolean divider) {
            this.row = row;
            this.divider = divider;
            final TLRPC.User starter = row.parent != null && row.parent.messageOwner != null && row.parent.messageOwner.from_id != null
                ? getMessagesController().getUser(row.parent.messageOwner.from_id.user_id) : null;
            if (starter != null) {
                avatarDrawable.setInfo(currentAccount, starter);
                avatarImage.setForUserOrChat(starter, avatarDrawable);
            } else {
                avatarDrawable.setInfo(row.parentId, "", null);
                avatarImage.setImageBitmap(avatarDrawable);
            }
            nameLayout = messageLayout = timeLayout = countLayout = null;
            requestLayout();
            invalidate();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(70) + (divider ? 1 : 0));
            buildLayouts(getMeasuredWidth());
        }

        private void buildLayouts(int width) {
            if (row == null || width <= 0) {
                return;
            }
            final String time = LocaleController.stringForMessageListDate(row.lastReply.date);
            timeWidth = (int) Math.ceil(Theme.dialogs_timePaint.measureText(time));
            timeLayout = new StaticLayout(time, Theme.dialogs_timePaint, timeWidth, Layout.Alignment.ALIGN_NORMAL, 1.0f, 0.0f, false);

            final String count = String.format("%d", row.count);
            countWidth = Math.max(dp(8), (int) Math.ceil(Theme.dialogs_countTextPaint2.measureText(count)));
            countLayout = new StaticLayout(count, Theme.dialogs_countTextPaint2, countWidth, Layout.Alignment.ALIGN_CENTER, 1.0f, 0.0f, false);

            final int nameLeft = dp(76);
            final int nameWidth = Math.max(0, width - nameLeft - dp(15) - timeWidth - dp(6));
            CharSequence title = "";
            if (row.parent != null) {
                final CharSequence text = row.parent.messageText != null ? row.parent.messageText : "";
                final String s = text.toString().trim();
                final int nl = s.indexOf('\n');
                title = nl >= 0 ? s.substring(0, nl) : s;
            }
            title = Emoji.replaceEmoji(title, Theme.dialogs_namePaint[0].getFontMetricsInt(), false);
            nameLayout = new StaticLayout(TextUtils.ellipsize(title, Theme.dialogs_namePaint[0], nameWidth, TextUtils.TruncateAt.END), Theme.dialogs_namePaint[0], Math.max(1, nameWidth), Layout.Alignment.ALIGN_NORMAL, 1.0f, 0.0f, false);

            final int messageWidth = Math.max(0, width - nameLeft - dp(15.666f) - countWidth - dp(6.333f * 2) - dp(6));
            final long self = UserConfig.getInstance(currentAccount).getClientUserId();
            final String sender;
            if (row.lastReply.senderId == self) {
                sender = LocaleController.getString(R.string.FromYou);
            } else {
                final TLRPC.User user = getMessagesController().getUser(row.lastReply.senderId);
                sender = user != null ? UserObject.getFirstName(user) : "";
            }
            String replyText = row.lastReply.text == null ? "" : row.lastReply.text.replace('\n', ' ');
            final SpannableStringBuilder message = new SpannableStringBuilder();
            message.append(sender).append(": ");
            message.setSpan(new ForegroundColorSpan(Theme.getColor(Theme.key_chats_nameMessage, getResourceProvider())), 0, message.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            message.append(replyText);
            final CharSequence messageText = Emoji.replaceEmoji(message, Theme.dialogs_messagePaint[0].getFontMetricsInt(), false);
            messageLayout = new StaticLayout(TextUtils.ellipsize(messageText, Theme.dialogs_messagePaint[0], messageWidth, TextUtils.TruncateAt.END), Theme.dialogs_messagePaint[0], Math.max(1, messageWidth), Layout.Alignment.ALIGN_NORMAL, 1.0f, 0.0f, false);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            avatarImage.onAttachedToWindow();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            avatarImage.onDetachedFromWindow();
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            if (row == null) {
                return;
            }
            final int width = getMeasuredWidth();
            avatarImage.setImageCoords(dp(11), dp(9), dp(52), dp(52));
            avatarImage.draw(canvas);

            if (nameLayout != null) {
                canvas.save();
                canvas.translate(dp(76), dp(14));
                nameLayout.draw(canvas);
                canvas.restore();
            }
            if (timeLayout != null) {
                canvas.save();
                canvas.translate(width - dp(15) - timeWidth, dp(16));
                timeLayout.draw(canvas);
                canvas.restore();
            }
            if (messageLayout != null) {
                canvas.save();
                canvas.translate(dp(76), dp(39));
                messageLayout.draw(canvas);
                canvas.restore();
            }
            if (countLayout != null) {
                final float countLeft = width - dp(15.666f) - (countWidth + dp(6.333f * 2));
                rect.set(countLeft, dp(38), countLeft + countWidth + dp(6.333f * 2), dp(38) + dp(20.666f));
                badgePaint.setColor(ThreadsConfig.THREAD_ACCENT_COLOR);
                canvas.drawRoundRect(rect, dp(11.5f), dp(11.5f), badgePaint);
                canvas.save();
                canvas.translate(countLeft + dp(6.333f), dp(38) + AndroidUtilities.dpf2(3));
                countLayout.draw(canvas);
                canvas.restore();
            }
            if (divider) {
                canvas.drawLine(dp(72), getMeasuredHeight() - 1, width, getMeasuredHeight() - 1, Theme.dividerPaint);
            }
        }
    }
}
