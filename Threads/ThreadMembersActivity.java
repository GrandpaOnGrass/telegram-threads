package org.telegram.ui.threads;

import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Cells.ManageChatUserCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.StickerEmptyView;
import org.telegram.ui.ProfileActivity;

import java.util.ArrayList;

// THREADS (stage 6): all participants of a thread, opened from the "Members" row of the thread info screen.
// A trimmed ChatUsersActivity (TYPE_USERS, the channel's "Subscribers" screen): the same simple glass header with the
// back button, the centered title and the search item, the same 64dp ManageChatUserCell rows (46dp avatar, name,
// status) in a section card, the same empty search view. No "Add" row and no ⋮ on the rows (needOption = false).
// The participants are local (ThreadsStore, from the replies): nothing is loaded from the server.
public class ThreadMembersActivity extends BaseFragment {

    // THREADS: ChatUsersActivity.search_button
    private static final int SEARCH_BUTTON = 0;

    private static final int VIEW_TYPE_USER = 0;
    private static final int VIEW_TYPE_INFO = 1;

    private final long dialogId;
    private final int parentMessageId;

    private final ArrayList<Long> participants = new ArrayList<>();
    // THREADS: while searching: the matching people and their names with the match highlighted (as ChatUsersActivity)
    private final ArrayList<Long> searchResult = new ArrayList<>();
    private final ArrayList<CharSequence> searchResultNames = new ArrayList<>();
    private boolean searching;
    private String query;

    private RecyclerListView listView;
    private Adapter adapter;
    private StickerEmptyView emptyView;

    public ThreadMembersActivity(long dialogId, int parentMessageId) {
        this.dialogId = dialogId;
        this.parentMessageId = parentMessageId;
    }

    @Override
    public boolean onFragmentCreate() {
        participants.clear();
        participants.addAll(ThreadsStore.getParticipants(currentAccount, dialogId, parentMessageId));
        return super.onFragmentCreate();
    }

    // THREADS: ChatUsersActivity: the simple glass header of the list screens
    @Override
    protected boolean useSimpleActionBar() {
        return true;
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    // THREADS: ChatUsersActivity: the list goes under the header's and the navigation bar's blur
    @Nullable
    @Override
    protected RecyclerListView getListViewForSimpleGlass() {
        return listView;
    }

    @Override
    public View createView(Context context) {
        setHasOwnBackground(true);

        simpleActionBar.setBackButtonImage(R.drawable.ic_ab_back);
        simpleActionBar.setTitle(LocaleController.getString(R.string.ChannelMembers));
        simpleActionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });
        // THREADS: ChatUsersActivity's search item
        final ActionBarMenuItem searchItem = simpleActionBar.createMenu().addItem(SEARCH_BUTTON, R.drawable.outline_header_search).setIsSearchField(true).setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener() {
            @Override
            public void onSearchExpand() {
                searching = true;
            }

            @Override
            public void onSearchCollapse() {
                searching = false;
                query = null;
                updateSearch();
            }

            @Override
            public void onTextChanged(EditText editText) {
                query = editText.getText().toString();
                updateSearch();
            }
        });
        searchItem.setSearchFieldHint(LocaleController.getString(R.string.Search));

        final FrameLayout frameLayout = new FrameLayout(context);
        fragmentView = frameLayout;

        // THREADS: ChatUsersActivity's empty search view
        emptyView = new StickerEmptyView(context, null, StickerEmptyView.STICKER_TYPE_SEARCH);
        emptyView.title.setText(LocaleController.getString(R.string.NoResult));
        emptyView.subtitle.setText(LocaleController.getString(R.string.SearchEmptyViewFilteredSubtitle2));
        emptyView.setVisibility(View.GONE);
        emptyView.showProgress(false, false);

        // THREADS: ChatUsersActivity's list: rows in a section card
        listView = new RecyclerListView(context);
        listView.setSections();
        listView.setSectionsDrawBackground(true);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setVerticalScrollBarEnabled(false);
        listView.setAdapter(adapter = new Adapter(context));
        listView.setOnItemClickListener((view, position) -> {
            final ArrayList<Long> list = currentList();
            if (position < 0 || position >= list.size()) {
                return;
            }
            final long userId = list.get(position);
            // THREADS: as the thread info screen: a tap opens the person's profile
            final Bundle args = new Bundle();
            args.putLong("user_id", userId);
            presentFragment(new ProfileActivity(args));
        });
        listView.setOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING && getParentActivity() != null) {
                    AndroidUtilities.hideKeyboard(getParentActivity().getCurrentFocus());
                }
            }
        });
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        frameLayout.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        listView.setEmptyView(emptyView);

        return fragmentView;
    }

    private boolean isFiltering() {
        return searching && !TextUtils.isEmpty(query) && query.trim().length() > 0;
    }

    private ArrayList<Long> currentList() {
        return isFiltering() ? searchResult : participants;
    }

    // THREADS: ChatUsersActivity.SearchAdapter's matching: the name (and its transliteration) starts with the query
    // or has a word starting with it, or the username starts with it; the matched part is highlighted.
    private void updateSearch() {
        searchResult.clear();
        searchResultNames.clear();
        if (isFiltering()) {
            final String search1 = query.trim().toLowerCase();
            String search2 = LocaleController.getInstance().getTranslitString(search1);
            if (search1.equals(search2) || search2.length() == 0) {
                search2 = null;
            }
            final String[] search = search2 != null ? new String[]{search1, search2} : new String[]{search1};
            for (int a = 0; a < participants.size(); a++) {
                final TLRPC.User user = getMessagesController().getUser(participants.get(a));
                if (user == null) {
                    continue;
                }
                final String name = UserObject.getUserName(user).toLowerCase();
                final String username = UserObject.getPublicUsername(user);
                String tName = LocaleController.getInstance().getTranslitString(name);
                if (name.equals(tName)) {
                    tName = null;
                }
                for (String q : search) {
                    int found = 0;
                    if (name.startsWith(q) || name.contains(" " + q) || tName != null && (tName.startsWith(q) || tName.contains(" " + q))) {
                        found = 1;
                    } else if (username != null && username.startsWith(q)) {
                        found = 2;
                    }
                    if (found != 0) {
                        searchResult.add(user.id);
                        searchResultNames.add(found == 1 ? AndroidUtilities.generateSearchName(user.first_name, user.last_name, q) : AndroidUtilities.generateSearchName("@" + username, null, "@" + q));
                        break;
                    }
                }
            }
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private class Adapter extends RecyclerListView.SelectionAdapter {

        private final Context context;

        Adapter(Context context) {
            this.context = context;
        }

        // THREADS: the rows, then ChatUsersActivity's participantsInfoRow (empty text for a group: the card's bottom)
        @Override
        public int getItemCount() {
            final int count = currentList().size();
            return count == 0 ? 0 : count + 1;
        }

        @Override
        public int getItemViewType(int position) {
            return position < currentList().size() ? VIEW_TYPE_USER : VIEW_TYPE_INFO;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() == VIEW_TYPE_USER;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            final View view;
            if (viewType == VIEW_TYPE_USER) {
                // THREADS: ChatUsersActivity's TYPE_USERS row (avatar padding 6, name padding 2), without the ⋮ button
                view = new ManageChatUserCell(context, 6, 2, false, getResourceProvider());
            } else {
                view = new TextInfoPrivacyCell(context, getResourceProvider());
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (holder.getItemViewType() == VIEW_TYPE_USER) {
                final ArrayList<Long> list = currentList();
                final TLRPC.User user = getMessagesController().getUser(list.get(position));
                final CharSequence name = isFiltering() ? searchResultNames.get(position) : null;
                // THREADS: status null: the cell shows the person's online / last seen (as ChatUsersActivity)
                ((ManageChatUserCell) holder.itemView).setData(user, name, null, position != list.size() - 1);
            } else {
                ((TextInfoPrivacyCell) holder.itemView).setText("");
            }
        }
    }
}
